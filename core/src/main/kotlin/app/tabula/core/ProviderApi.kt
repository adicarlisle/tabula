package app.tabula.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** The AI providers a user can bring a key for. LOCAL is their own Ollama behind Tabula's gateway. */
enum class ProviderKind(val label: String, val quick: String, val deep: String, private val keyPattern: Regex) {
    ANTHROPIC("Anthropic", "claude-haiku-5-5", "claude-sonnet-5-5", Regex("^sk-ant-[A-Za-z0-9_-]{20,}$")),
    OPENAI("OpenAI", "gpt-5-mini", "gpt-5", Regex("^sk-(?!or-|ant-)[A-Za-z0-9_-]{20,}$")),
    GEMINI("Gemini", "gemini-2.5-flash", "gemini-2.5-pro", Regex("^AIza[A-Za-z0-9_-]{30,}$")),
    OPENROUTER("OpenRouter", "anthropic/claude-haiku-5-5", "anthropic/claude-sonnet-5-5", Regex("^sk-or-[A-Za-z0-9_-]{20,}$")),
    LOCAL("My local AI", "", "", Regex("^tk_[A-Za-z0-9_-]{43}$")),
    ;

    fun keyLooksRight(key: String) = keyPattern.matches(key.trim())

    companion object {
        fun masked(key: String) = "•••• " + key.trim().takeLast(4)
    }
}

data class AiAsk(val system: String, val prompt: String, val maxTokens: Int)

data class HttpCall(val url: String, val headers: Map<String, String>, val body: String)

sealed interface AiReply {
    data class Answer(val text: String, val usage: TokenUsage) : AiReply
    data class Failed(val failure: ProviderFailure, val message: String) : AiReply
}

/** Builds each provider's HTTPS request and reads its reply. Plain JSON, no SDKs, nothing logged. */
object ProviderApi {
    private val json = Json { ignoreUnknownKeys = true }

    fun build(kind: ProviderKind, model: String, key: String, ask: AiAsk, localHost: String? = null): HttpCall = when (kind) {
        ProviderKind.ANTHROPIC -> HttpCall(
            "https://api.anthropic.com/v1/messages",
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json"),
            buildJsonObject {
                put("model", model)
                put("max_tokens", ask.maxTokens)
                put("system", ask.system)
                put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", ask.prompt) }) })
                put("output_config", buildJsonObject { put("effort", "low") })
            }.toString(),
        )
        ProviderKind.GEMINI -> HttpCall(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
            mapOf("x-goog-api-key" to key, "content-type" to "application/json"),
            buildJsonObject {
                put("systemInstruction", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", ask.system) }) }) })
                put("contents", buildJsonArray {
                    add(buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", ask.prompt) }) }) })
                })
                put("generationConfig", buildJsonObject { put("maxOutputTokens", ask.maxTokens) })
            }.toString(),
        )
        ProviderKind.OPENAI, ProviderKind.OPENROUTER, ProviderKind.LOCAL -> {
            val url = when (kind) {
                ProviderKind.OPENAI -> "https://api.openai.com/v1/chat/completions"
                ProviderKind.OPENROUTER -> "https://openrouter.ai/api/v1/chat/completions"
                else -> "https://${requireNotNull(localHost) { "local host" }}:${Gateway.PORT}/v1/chat/completions"
            }
            HttpCall(
                url,
                mapOf("Authorization" to "Bearer $key", "content-type" to "application/json"),
                buildJsonObject {
                    put("model", model)
                    put(if (kind == ProviderKind.OPENAI) "max_completion_tokens" else "max_tokens", ask.maxTokens)
                    put("messages", buildJsonArray {
                        add(buildJsonObject { put("role", "system"); put("content", ask.system) })
                        add(buildJsonObject { put("role", "user"); put("content", ask.prompt) })
                    })
                }.toString(),
            )
        }
    }

    fun parse(kind: ProviderKind, status: Int, body: String): AiReply {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return AiReply.Failed(ProviderFailure.TEMPORARY, "Unreadable reply ($status)")
        if (status !in 200..299) return AiReply.Failed(classify(status, body), "HTTP $status")
        return when (kind) {
            ProviderKind.ANTHROPIC -> {
                val u = o["usage"]?.jsonObject
                val usage = TokenUsage(u.long("input_tokens"), u.long("output_tokens"), u.long("cache_read_input_tokens"), u.long("cache_creation_input_tokens"))
                if (o["stop_reason"]?.jsonPrimitive?.contentOrNull == "refusal") return AiReply.Answer("The model declined to answer that.", usage)
                val text = (o["content"] as? JsonArray).orEmpty().map { it.jsonObject }
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
                AiReply.Answer(text.trim(), usage)
            }
            ProviderKind.GEMINI -> {
                val parts = o["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
                val u = o["usageMetadata"]?.jsonObject
                val cached = u.long("cachedContentTokenCount")
                AiReply.Answer(
                    parts.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }.trim(),
                    TokenUsage(u.long("promptTokenCount") - cached, u.long("candidatesTokenCount"), cached, 0),
                )
            }
            else -> {
                val text = o["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
                val u = o["usage"]?.jsonObject
                val cached = u?.get("prompt_tokens_details")?.jsonObject.long("cached_tokens")
                AiReply.Answer(text.trim(), TokenUsage(u.long("prompt_tokens") - cached, u.long("completion_tokens"), cached, 0))
            }
        }
    }

    private fun classify(status: Int, body: String): ProviderFailure {
        val b = body.lowercase()
        return when {
            "credit balance" in b || "insufficient_quota" in b || "insufficient credits" in b || status == 402 -> ProviderFailure.OUT_OF_CREDIT
            status == 401 || status == 403 || "api key not valid" in b || "invalid_api_key" in b || "authentication_error" in b -> ProviderFailure.INVALID_KEY
            else -> ProviderFailure.TEMPORARY
        }
    }

    private fun JsonObject?.long(name: String): Long = this?.get(name)?.jsonPrimitive?.longOrNull ?: 0
}
