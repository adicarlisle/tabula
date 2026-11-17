package app.tabula

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import app.tabula.core.HfDecision
import app.tabula.core.HfModelId
import app.tabula.core.HfPolicy
import app.tabula.core.KnownHosts
import app.tabula.core.LoginGuard
import app.tabula.core.TargetPolicy
import app.tabula.core.ServerAddress
import app.tabula.core.ServerKind
import app.tabula.core.ServerScripts
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private enum class Step { SERVER, TRUST, SCRIPT, RUNNING, MODELS }

/**
 * Pro: set up a model server (Ollama or vLLM) on the user's own Linux machine over SSH, then install
 * Hugging Face models onto it. Every command is shown before it runs.
 */
@Composable
internal fun ProScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val look = LocalLook.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("pro", Context.MODE_PRIVATE) }
    val secrets = remember { Secrets(context) }
    val known = remember { KnownHosts.decode(prefs.getString("known_hosts", "") ?: "") }

    var addressText by remember { mutableStateOf(prefs.getString("address", "") ?: "") }
    val saved = remember { ServerAddress.parse(addressText) }
    var host by remember { mutableStateOf(saved?.host ?: "") }
    var user by remember { mutableStateOf(saved?.user ?: "") }
    val found = remember { mutableStateListOf<FoundServer>() }
    var permission by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(runCatching { ServerKind.valueOf(prefs.getString("kind", "OLLAMA")!!) }.getOrDefault(ServerKind.OLLAMA)) }
    var step by remember { mutableStateOf(if (prefs.getBoolean("ready", false)) Step.MODELS else Step.SERVER) }
    var fingerprint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<String>() }
    var session by remember { mutableStateOf<Session?>(null) }

    var hfToken by remember { mutableStateOf(secrets.get("hf-token")?.decodeToString() ?: "") }
    var query by remember { mutableStateOf("") }
    val hits = remember { mutableStateListOf<HfApi.Hit>() }
    var picked by remember { mutableStateOf<Pair<HfModelId, HfDecision>?>(null) }
    var installed by remember { mutableStateOf(prefs.getString("model", null)) }

    fun saveKnown() = prefs.edit { putString("known_hosts", known.encode()) }
    fun address() = ServerAddress.of(host, saved?.takeIf { it.host == host }?.port ?: 22, user)
    fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true; message = null
        scope.launch {
            try { block() } catch (e: Exception) { message = e.message ?: e.javaClass.simpleName } finally { busy = false }
        }
    }

    /** Connect with the password, check the host key, then install this phone's own SSH key. */
    fun firstConnect() = work {
        val addr = address() ?: throw IllegalArgumentException("Pick a server and enter your username on it")
        addressText = "${addr.user}@${addr.host}:${addr.port}"
        prefs.edit { putString("address", addressText); putString("kind", kind.name) }
        // Resolve once, check it is on a private network, and connect to exactly that address.
        val ip = withContext(Dispatchers.IO) { java.net.InetAddress.getByName(addr.host).hostAddress ?: "" }
        if (!TargetPolicy.allowed(ip)) throw IllegalArgumentException("Tabula only sets up computers on your own home or office network.")
        val target = addr.copy(host = ip)
        val guard = loginGuard(prefs)
        val now = System.currentTimeMillis()
        if (!guard.mayTry(ip, now)) throw IllegalStateException("Too many wrong passwords. Try again in ${guard.secondsLocked(ip, now) / 60 + 1} min.")
        val ssh = Ssh(known)
        try {
            val s = withContext(Dispatchers.IO) {
                try {
                    ssh.connect(target, password = password.toCharArray())
                } catch (e: com.jcraft.jsch.JSchException) {
                    if (e.message?.contains("Auth", ignoreCase = true) == true) {
                        recordFailure(prefs, ip)
                        throw IllegalStateException("Wrong username or password.")
                    }
                    throw e
                }
            }
            clearFailures(prefs, ip)
            val (prv, pub) = withContext(Dispatchers.IO) { ssh.newKeyPair() }
            withContext(Dispatchers.IO) {
                ssh.run(s, "umask 077; mkdir -p ~/.ssh && cat >> ~/.ssh/authorized_keys", (pub + "\n").toByteArray())
            }
            secrets.put("ssh-key", prv)
            session = s
            step = Step.SCRIPT
        } catch (e: UnknownHostKey) {
            fingerprint = e.fingerprint; step = Step.TRUST
        }
    }

    /** Upload the shown script, run it with the sudo password on stdin, then forget the password. */
    fun runScript() = work {
        val s = session ?: throw IllegalStateException("Not connected")
        step = Step.RUNNING; log.clear()
        val ssh = Ssh(known)
        val script = ServerScripts.install(kind)
        val ok = withContext(Dispatchers.IO) {
            ssh.run(s, "mkdir -p ~/.tabula && chmod 700 ~/.tabula && cat > ~/.tabula/install.sh", script.toByteArray())
            var ok = false
            ssh.run(s, "bash ~/.tabula/install.sh", (password + "\n").toByteArray()) { line ->
                if (line.contains("TABULA_OK")) ok = true
                scope.launch { log.add(line); if (log.size > 200) log.removeAt(0) }
            }
            ok
        }
        password = ""
        s.disconnect(); session = null
        if (ok) { prefs.edit { putBoolean("ready", true) }; step = Step.MODELS } else message = "Setup didn't finish. Read the log above."
    }

    suspend fun keySession(): Session {
        val addr = address() ?: throw IllegalStateException("No server saved")
        val key = secrets.get("ssh-key") ?: throw IllegalStateException("No phone key saved. Set the server up again.")
        return withContext(Dispatchers.IO) { Ssh(known).connect(addr, privateKey = key) }
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Spacer(Modifier.height(16.dp))
            TapLabel("← SETTINGS", onTap = onClose)
            Title("LOCAL AI")
            Label("PRO · YOUR OWN MODEL SERVER ON YOUR NETWORK")
        }

        when (step) {
            Step.SERVER -> item {
                Section("SERVER")
                Button(if (busy) "LOOKING…" else "FIND SERVERS ON THIS WIFI", enabled = !busy) {
                    work {
                        val list = LanFinder.find(context)
                        found.clear(); found.addAll(list)
                        if (list.isEmpty()) message = "No SSH servers found. Is SSH on? (sudo apt install openssh-server)"
                    }
                }
                found.forEach { f ->
                    val on = f.host == host
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { host = f.host }).padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        BasicText((if (on) "● " else "") + f.host, style = TextStyle(fontFamily = Mono, fontSize = 14.sp, color = if (on) look.accent else look.text))
                        Label(listOfNotNull(f.banner.system, f.banner.software).joinToString(" · ").uppercase())
                    }
                }
                Spacer(Modifier.height(8.dp))
                Field(host, "server address, e.g. 192.168.1.20") { host = it }
                Spacer(Modifier.height(8.dp))
                Field(user, "username on the server") { user = it }
                Spacer(Modifier.height(8.dp))
                Field(password, "password", secret = true) { password = it }
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { permission = !permission }).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.padding(end = 12.dp).border(1.dp, look.text, RoundedCornerShape(2.dp)).padding(3.dp)) {
                        Box(Modifier.background(if (permission) look.accent else androidx.compose.ui.graphics.Color.Transparent).padding(6.dp))
                    }
                    BasicText(
                        "This is my computer, or I have permission to install software on it.",
                        style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = look.text),
                    )
                }
                Note("Used once to add this phone's own key and to run setup with sudo. It is never saved.")
                Section("INSTALL")
                Pick(ServerKind.entries, kind) { kind = it }
                Note(if (kind == ServerKind.OLLAMA) "Ollama: easiest, runs GGUF models." else "vLLM: faster on an NVIDIA GPU, runs safetensors models.")
                Spacer(Modifier.height(16.dp))
                Button(if (busy) "CONNECTING…" else "CONNECT", enabled = !busy && permission && password.isNotEmpty() && host.isNotBlank() && user.isNotBlank()) { firstConnect() }
            }

            Step.TRUST -> item {
                Section("NEW SERVER")
                Note("Check this fingerprint matches your server (run: ssh-keygen -lf /etc/ssh/ssh_host_ecdsa_key.pub).")
                SelectionContainer { BasicText(fingerprint, style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = look.text)) }
                Spacer(Modifier.height(16.dp))
                Button("TRUST AND CONNECT") {
                    address()?.let { known.trust(Ssh.hostId(it), fingerprint); saveKnown() }
                    firstConnect()
                }
                TapLabel("CANCEL") { step = Step.SERVER }
            }

            Step.SCRIPT -> item {
                Section("THIS WILL RUN ON ${address()?.host ?: "YOUR SERVER"}")
                Code(ServerScripts.install(kind))
                Note("The model server listens only on the server itself. This phone reaches it through the encrypted SSH connection.")
                Spacer(Modifier.height(16.dp))
                Button(if (busy) "RUNNING…" else "RUN SETUP", enabled = !busy) { runScript() }
                TapLabel("CANCEL") { session?.disconnect(); session = null; password = ""; step = Step.SERVER }
            }

            Step.RUNNING -> item {
                Section(if (busy) "SETTING UP… THIS CAN TAKE A FEW MINUTES" else "SETUP LOG")
                Code(log.joinToString("\n").ifEmpty { "…" })
                if (!busy) TapLabel("BACK") { step = Step.SERVER }
            }

            Step.MODELS -> {
                item {
                    Section("SERVER · ${addressText.uppercase()} · $kind")
                    installed?.let { Note("Installed: $it") }
                    Section("HUGGING FACE TOKEN (OPTIONAL, FOR GATED MODELS)")
                    Field(hfToken, "hf_…", secret = true) { hfToken = it }
                    Note("Stored encrypted on this phone. Requests are plain Hugging Face API calls, never AI.")
                    TapLabel("SAVE TOKEN") { if (hfToken.isBlank()) secrets.remove("hf-token") else secrets.put("hf-token", hfToken.trim().toByteArray()) }
                    Section("FIND A MODEL")
                    Field(query, if (kind == ServerKind.OLLAMA) "e.g. llama gguf" else "e.g. qwen3") { query = it }
                    Spacer(Modifier.height(8.dp))
                    Button(if (busy) "SEARCHING…" else "SEARCH", enabled = !busy && query.isNotBlank()) {
                        work {
                            val found = withContext(Dispatchers.IO) { HfApi(hfToken.ifBlank { null }).search(query, kind) }
                            hits.clear(); hits.addAll(found); picked = null
                        }
                    }
                }
                picked?.let { (id, decision) ->
                    item {
                        Section(id.value.uppercase())
                        when (decision) {
                            is HfDecision.Allowed -> {
                                Note("Licence: ${decision.licence}. By installing you agree to the model's licence.")
                                Button(if (busy) "INSTALLING…" else "INSTALL ON SERVER", enabled = !busy) {
                                    work {
                                        log.clear()
                                        val s = keySession()
                                        val cmd = ServerScripts.installModel(kind, id)
                                        val token = hfToken.trim()
                                        val code = withContext(Dispatchers.IO) {
                                            // The token goes over stdin, never on the command line.
                                            if (token.isNotEmpty()) Ssh(known).run(s, "read -r HF_TOKEN; export HF_TOKEN; $cmd", (token + "\n").toByteArray()) { scope.launch { log.add(it) } }
                                            else Ssh(known).run(s, cmd) { scope.launch { log.add(it) } }
                                        }
                                        s.disconnect()
                                        if (code == 0) {
                                            installed = id.value; prefs.edit { putString("model", id.value) }
                                            message = if (kind == ServerKind.VLLM) "Installed. vLLM takes a minute or two to load it." else "Installed."
                                        } else message = "Install failed (exit $code). See the log."
                                    }
                                }
                            }
                            is HfDecision.AcceptTermsOnHuggingFace -> {
                                Note("This model is gated. Accept its terms on Hugging Face with your own account, then search again.")
                                Button("OPEN ON HUGGING FACE") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(decision.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            is HfDecision.ReviewModelCard -> {
                                Note("No licence is listed. Read the model card before using it.")
                                Button("OPEN MODEL CARD") { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(decision.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                            is HfDecision.Blocked -> Note(decision.reason)
                        }
                        if (log.isNotEmpty()) Code(log.takeLast(12).joinToString("\n"))
                    }
                }
                items(hits, key = { it.id }) { hit ->
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = {
                            work {
                                val id = HfModelId.parse(hit.id)
                                val api = HfApi(hfToken.ifBlank { null })
                                val decision = withContext(Dispatchers.IO) {
                                    val m = api.details(id)
                                    HfPolicy.decide(m, kind, accessGranted = api.hasAccess(m))
                                }
                                picked = id to decision
                            }
                        }).padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        BasicText(hit.id, Modifier.weight(1f), style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = look.text))
                        Label("${hit.downloads / 1000}K")
                    }
                }
                item {
                    installed?.let { model ->
                        Section("TRY IT")
                        Button(if (busy) "ASKING…" else "ASK THE MODEL A TEST QUESTION", enabled = !busy) {
                            work {
                                val s = keySession()
                                val reply = withContext(Dispatchers.IO) { testModel(s, kind, model) }
                                s.disconnect()
                                message = "Model says: $reply"
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    TapLabel("SET UP A DIFFERENT SERVER", color = look.faint) {
                        prefs.edit { putBoolean("ready", false); remove("model") }
                        secrets.remove("ssh-key"); installed = null; step = Step.SERVER
                    }
                }
            }
        }

        message?.let { item { Spacer(Modifier.height(12.dp)); SelectionContainer { BasicText(it, style = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = look.accent)) } } }
        item { Spacer(Modifier.height(48.dp)) }
    }
}

/** Opens an SSH tunnel to the model server's localhost port and asks one short question. */
private fun testModel(s: Session, kind: ServerKind, model: String): String {
    val local = s.setPortForwardingL(0, "127.0.0.1", ServerScripts.port(kind))
    try {
        val (path, body, apiKey) = when (kind) {
            ServerKind.OLLAMA -> Triple(
                "/api/generate",
                JSONObject().put("model", "hf.co/$model").put("prompt", "Reply in five words: are you running?").put("stream", false),
                null,
            )
            ServerKind.VLLM -> {
                val key = StringBuilder()
                Ssh.readText(s, "cat ~/.tabula/api-key", key)
                Triple(
                    "/v1/chat/completions",
                    JSONObject().put("model", model).put("max_tokens", 30)
                        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Reply in five words: are you running?"))),
                    key.toString().trim(),
                )
            }
        }
        val c = URL("http://127.0.0.1:$local$path").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 120_000
        c.setRequestProperty("Content-Type", "application/json")
        if (apiKey != null) c.setRequestProperty("Authorization", "Bearer $apiKey")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) return "server replied ${c.responseCode} (vLLM may still be loading)"
        val o = JSONObject(c.inputStream.bufferedReader().readText())
        return if (kind == ServerKind.OLLAMA) o.optString("response").trim()
        else o.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
    } finally {
        s.delPortForwardingL(local)
    }
}

@Composable
private fun Field(value: String, hint: String, secret: Boolean = false, onChange: (String) -> Unit) {
    val look = LocalLook.current
    val context = LocalContext.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = look.text),
        cursorBrush = SolidColor(look.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri, autoCorrectEnabled = false),
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxWidth().height(52.dp).border(1.dp, look.faint, RoundedCornerShape(look.radius / 2)).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) BasicText(hint, style = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = look.faint))
                    inner()
                }
                BasicText(
                    "PASTE",
                    Modifier.combinedClickable(onClick = { clipboardText(context)?.let { onChange(it.trim()) } }).padding(horizontal = 14.dp, vertical = 16.dp),
                    style = TextStyle(fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp, color = look.muted),
                )
            }
        },
    )
}

@Composable
private fun Button(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val look = LocalLook.current
    Box(
        Modifier.fillMaxWidth().height(52.dp)
            .background(if (enabled) look.text else look.faint, RoundedCornerShape(look.radius))
            .then(if (enabled) Modifier.combinedClickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, style = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.sp, color = look.bg))
    }
}

@Composable
private fun <E : Enum<E>> Pick(options: List<E>, selected: E, onPick: (E) -> Unit) {
    val look = LocalLook.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).height(48.dp)
                    .background(if (on) look.text else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(look.radius / 4))
                    .border(1.dp, if (on) look.text else look.faint, RoundedCornerShape(look.radius / 4))
                    .combinedClickable(onClick = { onPick(o) }),
                contentAlignment = Alignment.Center,
            ) { BasicText(o.name, style = TextStyle(fontFamily = Mono, fontSize = 12.sp, letterSpacing = 1.sp, color = if (on) look.bg else look.muted)) }
        }
    }
}

@Composable
private fun Note(text: String) {
    BasicText(text, Modifier.padding(vertical = 8.dp), style = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp, color = LocalLook.current.muted))
}

@Composable
private fun Code(text: String) {
    val look = LocalLook.current
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).border(1.dp, look.faint, RoundedCornerShape(4.dp)).padding(12.dp)) {
        SelectionContainer {
            BasicText(text, style = TextStyle(fontFamily = Mono, fontSize = 10.sp, lineHeight = 14.sp, color = look.text))
        }
    }
}

/** Current clipboard text, read only when the user taps PASTE. */
private fun clipboardText(context: Context): String? {
    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** Failed logins survive app restarts, so the 3-tries lock can't be reset by reopening Tabula. */
private fun loginGuard(prefs: android.content.SharedPreferences): LoginGuard {
    val guard = LoginGuard()
    prefs.all.filterKeys { it.startsWith("fail:") }.forEach { (k, v) ->
        (v as? String)?.split(',')?.mapNotNull { it.toLongOrNull() }?.forEach { guard.failed(k.removePrefix("fail:"), it) }
    }
    return guard
}

private fun recordFailure(prefs: android.content.SharedPreferences, host: String) {
    val previous = prefs.getString("fail:$host", "")!!.split(',').filter { it.isNotBlank() }
    prefs.edit { putString("fail:$host", (previous + System.currentTimeMillis().toString()).takeLast(3).joinToString(",")) }
}

private fun clearFailures(prefs: android.content.SharedPreferences, host: String) = prefs.edit { remove("fail:$host") }
