package app.tabula.core

/** What the Hugging Face API reports about a model repo. [gated] is "false", "auto" or "manual". */
data class HfModel(
    val id: HfModelId,
    val gated: String,
    val licence: String?,
    val disabled: Boolean,
    val private: Boolean,
    val files: List<String>,
)

sealed interface HfDecision {
    data class Allowed(val licence: String) : HfDecision
    /** Gated: the user accepts the authors' terms on huggingface.co with their own account. Never automated. */
    data class AcceptTermsOnHuggingFace(val url: String) : HfDecision
    data class ReviewModelCard(val url: String) : HfDecision
    data class Blocked(val reason: String) : HfDecision
}

/**
 * Hugging Face rules, followed and never worked around: licences shown before install, gated models
 * only after the user accepts the terms themselves, the user's own token, and an honest user agent.
 * Weights go straight from Hugging Face to the user's server; Tabula never hosts or redistributes them.
 */
object HfPolicy {
    fun decide(model: HfModel, server: ServerKind, accessGranted: Boolean): HfDecision {
        val page = "https://huggingface.co/${model.id.value}"
        if (model.disabled) return HfDecision.Blocked("This model is disabled on Hugging Face")
        if (model.private) return HfDecision.Blocked("This model is private")
        if (model.gated != "false" && !accessGranted) return HfDecision.AcceptTermsOnHuggingFace(page)
        when (server) {
            ServerKind.VLLM -> if (model.files.none { it.endsWith(".safetensors") }) return HfDecision.Blocked("No safetensors weights for vLLM")
            ServerKind.OLLAMA -> if (model.files.none { it.endsWith(".gguf") }) return HfDecision.Blocked("No GGUF file for Ollama")
        }
        val licence = model.licence?.takeIf { it.isNotBlank() } ?: return HfDecision.ReviewModelCard(page)
        return HfDecision.Allowed(licence)
    }

    fun userAgent(version: String) = "Tabula/$version (Android launcher)"
}
