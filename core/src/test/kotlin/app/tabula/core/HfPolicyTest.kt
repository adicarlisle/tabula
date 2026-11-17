package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Examples: Hugging Face rules are followed, never worked around. */
class HfPolicyTest {
    private fun model(gated: String = "false", licence: String? = "apache-2.0", disabled: Boolean = false, private: Boolean = false, files: List<String> = listOf("model.safetensors", "config.json")) =
        HfModel(HfModelId.parse("org/model"), gated, licence, disabled, private, files)

    @Test
    fun `open model with a licence can be installed, licence shown first`() =
        assertEquals(HfDecision.Allowed("apache-2.0"), HfPolicy.decide(model(), ServerKind.VLLM, accessGranted = true))

    @Test
    fun `gated model you have not accepted sends you to its page to accept the terms yourself`() {
        val expected = HfDecision.AcceptTermsOnHuggingFace("https://huggingface.co/org/model")
        assertEquals(expected, HfPolicy.decide(model(gated = "manual"), ServerKind.VLLM, accessGranted = false))
        assertEquals(expected, HfPolicy.decide(model(gated = "auto"), ServerKind.VLLM, accessGranted = false))
    }

    @Test
    fun `gated model you were granted is allowed`() =
        assertEquals(HfDecision.Allowed("apache-2.0"), HfPolicy.decide(model(gated = "manual"), ServerKind.VLLM, accessGranted = true))

    @Test
    fun `disabled or private repos are blocked`() {
        assertEquals(HfDecision.Blocked("This model is disabled on Hugging Face"), HfPolicy.decide(model(disabled = true), ServerKind.VLLM, true))
        assertEquals(HfDecision.Blocked("This model is private"), HfPolicy.decide(model(private = true), ServerKind.VLLM, true))
    }

    @Test
    fun `no licence listed means you must read the model card before installing`() =
        assertEquals(HfDecision.ReviewModelCard("https://huggingface.co/org/model"), HfPolicy.decide(model(licence = null), ServerKind.VLLM, true))

    @Test
    fun `the files must suit the server`() {
        assertEquals(HfDecision.Blocked("No safetensors weights for vLLM"), HfPolicy.decide(model(files = listOf("model.gguf")), ServerKind.VLLM, true))
        assertEquals(HfDecision.Blocked("No GGUF file for Ollama"), HfPolicy.decide(model(), ServerKind.OLLAMA, true))
        assertEquals(HfDecision.Allowed("apache-2.0"), HfPolicy.decide(model(files = listOf("q4.gguf")), ServerKind.OLLAMA, true))
    }

    @Test
    fun `requests identify the app honestly`() = assertEquals("Tabula/0.1.0 (Android launcher)", HfPolicy.userAgent("0.1.0"))
}
