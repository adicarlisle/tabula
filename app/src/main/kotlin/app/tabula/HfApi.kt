package app.tabula

import app.tabula.core.HfModel
import app.tabula.core.HfModelId
import app.tabula.core.HfPolicy
import app.tabula.core.ServerKind
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Plain Hugging Face Hub API requests made by the app itself: search, model details and access checks.
 * The AI never makes, chooses or triggers these, and they don't count as AI usage.
 */
class HfApi(private val token: String?) {
    private fun open(url: String, method: String = "GET"): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", HfPolicy.userAgent(BuildConfig.VERSION_NAME))
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun getText(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("Hugging Face replied ${c.responseCode}")
            return c.inputStream.bufferedReader().readText()
        } finally { c.disconnect() }
    }

    data class Hit(val id: String, val downloads: Long)

    fun search(query: String, kind: ServerKind): List<Hit> {
        val filter = if (kind == ServerKind.OLLAMA) "gguf" else "safetensors"
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val arr = JSONArray(getText("https://huggingface.co/api/models?search=$q&filter=$filter&sort=downloads&direction=-1&limit=20"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { Hit(it.getString("id"), it.optLong("downloads")) }
    }

    fun details(id: HfModelId): HfModel {
        val o = JSONObject(getText("https://huggingface.co/api/models/${id.value}"))
        val gated = when (val g = o.opt("gated")) { is String -> g; true -> "auto"; else -> "false" }
        val licence = o.optJSONObject("cardData")?.optString("license")?.takeIf { it.isNotBlank() }
            ?: o.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.getString(it) }.firstOrNull { it.startsWith("license:") }?.removePrefix("license:") }
        val siblings = o.optJSONArray("siblings") ?: JSONArray()
        val files = (0 until siblings.length()).map { siblings.getJSONObject(it).getString("rfilename") }
        return HfModel(id, gated, licence, o.optBoolean("disabled"), o.optBoolean("private"), files)
    }

    /** Whether this user's token can download the repo (true for open models). */
    fun hasAccess(model: HfModel): Boolean {
        if (model.gated == "false") return true
        val file = model.files.firstOrNull { it.endsWith(".json") } ?: model.files.firstOrNull() ?: return false
        val c = open("https://huggingface.co/${model.id.value}/resolve/main/$file", "HEAD")
        return try { c.responseCode in 200..399 } finally { c.disconnect() }
    }
}
