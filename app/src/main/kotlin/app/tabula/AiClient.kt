package app.tabula

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import app.tabula.core.AiAsk
import app.tabula.core.AiReply
import app.tabula.core.AiRouter
import app.tabula.core.CapAction
import app.tabula.core.ModelPrice
import app.tabula.core.ModelTier
import app.tabula.core.Pairing
import app.tabula.core.PriceTable
import app.tabula.core.Provider
import app.tabula.core.ProviderApi
import app.tabula.core.ProviderKind
import app.tabula.core.Route
import java.net.HttpURLConnection
import java.net.URL
import java.time.YearMonth

/** The user's AI providers: their keys (encrypted), chosen models, and the order they're tried in. */
class AiKeys(private val context: Context) {
    private val secrets = Secrets(context)
    private val prefs = context.getSharedPreferences("ai", Context.MODE_PRIVATE)
    private val pro = context.getSharedPreferences("pro", Context.MODE_PRIVATE)

    fun key(kind: ProviderKind): String? =
        if (kind == ProviderKind.LOCAL) secrets.get("gw-key")?.decodeToString()?.takeIf { localEnabled() }
        else secrets.get("key:${kind.name}")?.decodeToString()

    fun saveKey(kind: ProviderKind, key: String) = secrets.put("key:${kind.name}", key.trim().toByteArray())
    fun removeKey(kind: ProviderKind) = secrets.remove("key:${kind.name}")

    fun localAvailable() = pro.getString("gw_host", null) != null && secrets.get("gw-key") != null
    fun localEnabled() = localAvailable() && prefs.getBoolean("local_on", true)
    fun setLocal(on: Boolean) = prefs.edit { putBoolean("local_on", on) }
    fun localHost() = pro.getString("gw_host", null)
    fun localCa() = pro.getString("gw_ca", null)

    fun model(kind: ProviderKind, tier: ModelTier): String =
        prefs.getString("model:${kind.name}:${tier.name}", null)?.takeIf { it.isNotBlank() }
            ?: if (kind == ProviderKind.LOCAL) pro.getString("model", "") ?: "" else if (tier == ModelTier.QUICK) kind.quick else kind.deep

    fun setModel(kind: ProviderKind, tier: ModelTier, model: String) = prefs.edit { putString("model:${kind.name}:${tier.name}", model.trim()) }

    /** Tried in this order: your own local AI first (free), then the cloud providers you have keys for. */
    fun chain(): List<ProviderKind> =
        listOf(ProviderKind.LOCAL, ProviderKind.ANTHROPIC, ProviderKind.OPENAI, ProviderKind.GEMINI, ProviderKind.OPENROUTER).filter { key(it) != null }

    /** Spend this month in US dollars, from the token counts each reply reports. */
    fun monthSpend(): Double = prefs.getFloat("spend:${YearMonth.now()}", 0f).toDouble()
    fun addSpend(dollars: Double) = prefs.edit { putFloat("spend:${YearMonth.now()}", (monthSpend() + dollars).toFloat()) }
}

data class AiAnswer(val text: String, val by: String, val cost: String)

/** Sends one question to the first working provider. HTTPS only; keys are never logged or shown. */
class AiClient(private val context: Context) {
    private val keys = AiKeys(context)

    // US dollars per million tokens, for providers whose prices Tabula knows. Others show "cost unknown", never "free".
    private val prices = PriceTable(
        mapOf(
            "claude-haiku-5-5" to ModelPrice(input = 0.10, output = 0.50, cacheRead = 0.01, cacheWrite = 0.125),
            "claude-sonnet-5-5" to ModelPrice(input = 2.00, output = 10.00, cacheRead = 0.10, cacheWrite = 2.50),
        ),
    )

    private val system = "You are the search bar of Tabula, a calm Android launcher. Answer in one to three short sentences. " +
        "Plain text only, no markdown. Never write, draft or send messages for the user."

    fun online(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Returns an answer, or null when AI is paused (offline, no keys, or every provider failed): phone search only. */
    fun ask(question: String, tier: ModelTier = ModelTier.QUICK): AiAnswer? {
        val chain = keys.chain()
        val router = AiRouter(chain.map { Provider(it.name) }, cap = null, onCap = CapAction.QUICK_ONLY)
        // The local AI on your own network works without internet; cloud providers need it.
        val online = online() || chain.firstOrNull() == ProviderKind.LOCAL
        repeat(chain.size) {
            val route = router.route(tier, keys.monthSpend(), online)
            if (route !is Route.Ai) return null
            val kind = ProviderKind.valueOf(route.provider.id)
            val model = keys.model(kind, route.tier)
            when (val reply = call(kind, model, keys.key(kind)!!, AiAsk(system, question, maxTokens = 400))) {
                is AiReply.Answer -> {
                    router.reportSuccess(route.provider)
                    val cost = if (kind == ProviderKind.LOCAL) 0.0 else prices.costOrNull(model, reply.usage)
                    if (cost != null) keys.addSpend(cost)
                    val costText = when {
                        kind == ProviderKind.LOCAL -> "FREE"
                        cost == null -> "COST UNKNOWN"
                        else -> "≈" + "%.3f".format(cost * 100) + "¢"
                    }
                    return AiAnswer(reply.text.ifBlank { "(no answer)" }, "${kind.label} · ${model.substringAfterLast('/')}".uppercase(), costText)
                }
                is AiReply.Failed -> router.reportFailure(route.provider, reply.failure)
            }
        }
        return null
    }

    /** A tiny request to check a key works. Returns null if it worked, or a short reason. */
    fun test(kind: ProviderKind, key: String, model: String): String? =
        when (val r = runCatching { call(kind, model, key, AiAsk("Reply with OK.", "OK?", maxTokens = 16)) }.getOrElse { AiReply.Failed(app.tabula.core.ProviderFailure.TEMPORARY, it.message ?: "network error") }) {
            is AiReply.Answer -> null
            is AiReply.Failed -> when (r.failure) {
                app.tabula.core.ProviderFailure.INVALID_KEY -> "The key was rejected."
                app.tabula.core.ProviderFailure.OUT_OF_CREDIT -> "The key works but the account is out of credit."
                app.tabula.core.ProviderFailure.TEMPORARY -> "Couldn't check right now (${r.message}). Check the model name and try again."
            }
        }

    private fun call(kind: ProviderKind, model: String, key: String, ask: AiAsk): AiReply {
        if (kind == ProviderKind.LOCAL) {
            val host = keys.localHost() ?: return AiReply.Failed(app.tabula.core.ProviderFailure.INVALID_KEY, "no gateway")
            val req = ProviderApi.build(kind, model, key, ask, localHost = host)
            val gw = GatewayClient.fromPairing(Pairing(host, app.tabula.core.Gateway.PORT, key, keys.localCa()!!), HomeNetwork.get(context))
            val (code, body) = gw.post("/v1/chat/completions", req.headers, req.body)
            return ProviderApi.parse(kind, code, body)
        }
        val req = ProviderApi.build(kind, model, key, ask)
        val c = URL(req.url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15_000; c.readTimeout = 60_000
        req.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(req.body.toByteArray()) }
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
        c.disconnect()
        return ProviderApi.parse(kind, code, body)
    }
}
