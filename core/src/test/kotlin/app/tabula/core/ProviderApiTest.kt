package app.tabula.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Examples: one small HTTPS layer for every AI provider: what is sent, and how replies and errors are read. */
class ProviderApiTest {
    private val ask = AiAsk(system = "Answer briefly.", prompt = "will i need a coat", maxTokens = 400)

    private fun body(call: HttpCall) = Json.parseToJsonElement(call.body).jsonObject

    @Test
    fun `anthropic request`() {
        val call = ProviderApi.build(ProviderKind.ANTHROPIC, "claude-haiku-5-5", "sk-ant-KEY", ask)
        assertEquals("https://api.anthropic.com/v1/messages", call.url)
        assertEquals("sk-ant-KEY", call.headers["x-api-key"])
        assertEquals("2023-06-01", call.headers["anthropic-version"])
        val b = body(call)
        assertEquals("claude-haiku-5-5", b["model"]!!.jsonPrimitive.content)
        assertEquals("Answer briefly.", b["system"]!!.jsonPrimitive.content)
        assertEquals("will i need a coat", b["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("low", b["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content, "quick answers use low effort to save tokens")
        assertFalse("temperature" in b)
    }

    @Test
    fun `openai, openrouter and the local gateway share the chat completions shape`() {
        val openai = ProviderApi.build(ProviderKind.OPENAI, "gpt-5-mini", "sk-proj-KEY", ask)
        assertEquals("https://api.openai.com/v1/chat/completions", openai.url)
        assertEquals("Bearer sk-proj-KEY", openai.headers["Authorization"])
        assertEquals(400, body(openai)["max_completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals("system", body(openai)["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)

        assertEquals("https://openrouter.ai/api/v1/chat/completions", ProviderApi.build(ProviderKind.OPENROUTER, "m", "sk-or-v1-KEY", ask).url)
        val local = ProviderApi.build(ProviderKind.LOCAL, "llama3", "tk_KEY", ask, localHost = "192.168.1.65")
        assertEquals("https://192.168.1.65:11435/v1/chat/completions", local.url)
        assertEquals(400, body(local)["max_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `gemini request keeps the key in a header, not the url`() {
        val call = ProviderApi.build(ProviderKind.GEMINI, "gemini-2.5-flash", "AIzaKEY", ask)
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent", call.url)
        assertEquals("AIzaKEY", call.headers["x-goog-api-key"])
        assertFalse("AIza" in call.url)
    }

    @Test
    fun `replies are read with their token counts`() {
        val anthropic = ProviderApi.parse(
            ProviderKind.ANTHROPIC, 200,
            """{"content":[{"type":"thinking","thinking":""},{"type":"text","text":"Yes, "},{"type":"text","text":"bring one."}],"stop_reason":"end_turn",
               "usage":{"input_tokens":120,"output_tokens":9,"cache_read_input_tokens":30,"cache_creation_input_tokens":0}}""",
        )
        assertEquals(AiReply.Answer("Yes, bring one.", TokenUsage(120, 9, 30, 0)), anthropic)

        val openai = ProviderApi.parse(
            ProviderKind.OPENAI, 200,
            """{"choices":[{"message":{"content":"Yes."}}],"usage":{"prompt_tokens":50,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":10}}}""",
        )
        assertEquals(AiReply.Answer("Yes.", TokenUsage(40, 2, 10, 0)), openai)

        val gemini = ProviderApi.parse(
            ProviderKind.GEMINI, 200,
            """{"candidates":[{"content":{"parts":[{"text":"Yes."}]}}],"usageMetadata":{"promptTokenCount":30,"candidatesTokenCount":3}}""",
        )
        assertEquals(AiReply.Answer("Yes.", TokenUsage(30, 3, 0, 0)), gemini)
    }

    @Test
    fun `errors are sorted into bad key, out of credit and try later`() {
        fun failure(kind: ProviderKind, status: Int, body: String) = (ProviderApi.parse(kind, status, body) as AiReply.Failed).failure
        assertEquals(ProviderFailure.INVALID_KEY, failure(ProviderKind.ANTHROPIC, 401, """{"type":"error","error":{"type":"authentication_error"}}"""))
        assertEquals(ProviderFailure.OUT_OF_CREDIT, failure(ProviderKind.ANTHROPIC, 400, """{"error":{"message":"Your credit balance is too low to access the Anthropic API."}}"""))
        assertEquals(ProviderFailure.TEMPORARY, failure(ProviderKind.ANTHROPIC, 529, """{"error":{"type":"overloaded_error"}}"""))
        assertEquals(ProviderFailure.OUT_OF_CREDIT, failure(ProviderKind.OPENAI, 429, """{"error":{"code":"insufficient_quota"}}"""))
        assertEquals(ProviderFailure.TEMPORARY, failure(ProviderKind.OPENAI, 429, """{"error":{"code":"rate_limit_exceeded"}}"""))
        assertEquals(ProviderFailure.OUT_OF_CREDIT, failure(ProviderKind.OPENROUTER, 402, """{"error":{"message":"Insufficient credits"}}"""))
        assertEquals(ProviderFailure.INVALID_KEY, failure(ProviderKind.GEMINI, 400, """{"error":{"status":"INVALID_ARGUMENT","message":"API key not valid."}}"""))
        assertEquals(ProviderFailure.TEMPORARY, failure(ProviderKind.OPENAI, 200, "not json"))
    }

    @Test
    fun `a refusal is an answer saying so, not an error`() {
        val reply = ProviderApi.parse(ProviderKind.ANTHROPIC, 200, """{"content":[],"stop_reason":"refusal","usage":{"input_tokens":5,"output_tokens":0}}""")
        assertEquals(AiReply.Answer("The model declined to answer that.", TokenUsage(5, 0, 0, 0)), reply)
    }

    @Test
    fun `key shapes are checked before saving so a pasted wrong key is caught`() {
        assertTrue(ProviderKind.ANTHROPIC.keyLooksRight("sk-ant-api03-" + "x".repeat(80)))
        assertFalse(ProviderKind.ANTHROPIC.keyLooksRight("AIza" + "x".repeat(35)))
        assertTrue(ProviderKind.GEMINI.keyLooksRight("AIza" + "x".repeat(35)))
        assertTrue(ProviderKind.OPENROUTER.keyLooksRight("sk-or-v1-" + "a".repeat(64)))
        assertFalse(ProviderKind.OPENAI.keyLooksRight("sk-or-v1-" + "a".repeat(64)), "an OpenRouter key pasted into OpenAI")
        assertTrue(ProviderKind.OPENAI.keyLooksRight("sk-proj-" + "a".repeat(100)))
    }

    @Test
    fun `saved keys are shown only by their last four characters`() =
        assertEquals("•••• 9xQz", ProviderKind.masked("fake-test-value-9xQz"))
}
