package app.tabula.core

data class Provider(val id: String)

enum class ModelTier { QUICK, DEEP }

/** What the user chose to happen once their own cap is reached. */
enum class CapAction { QUICK_ONLY, PHONE_ONLY, ASK }

enum class PauseReason { OFFLINE, NO_KEYS, NO_CREDIT, CAP_REACHED }

enum class ProviderFailure { OUT_OF_CREDIT, INVALID_KEY, TEMPORARY }

sealed interface Route {
    data class Ai(val provider: Provider, val tier: ModelTier) : Route
    /** AI paused: the launcher keeps working with on-phone search only. */
    data class PhoneOnly(val reason: PauseReason) : Route
    data object AskUser : Route
}

/**
 * Picks where an AI request goes. The cap is null (off) until the user sets one.
 * Providers that report billing or key problems are skipped until they succeed again.
 */
class AiRouter(
    private val chain: List<Provider>,
    private val cap: Double?,
    private val onCap: CapAction,
) {
    private val unavailable = mutableSetOf<Provider>()

    fun route(wanted: ModelTier, spentThisMonth: Double, online: Boolean = true): Route {
        if (!online) return Route.PhoneOnly(PauseReason.OFFLINE)
        if (chain.isEmpty()) return Route.PhoneOnly(PauseReason.NO_KEYS)
        val provider = chain.firstOrNull { it !in unavailable } ?: return Route.PhoneOnly(PauseReason.NO_CREDIT)
        if (cap != null && spentThisMonth >= cap) {
            return when (onCap) {
                CapAction.QUICK_ONLY -> Route.Ai(provider, ModelTier.QUICK)
                CapAction.PHONE_ONLY -> Route.PhoneOnly(PauseReason.CAP_REACHED)
                CapAction.ASK -> Route.AskUser
            }
        }
        return Route.Ai(provider, wanted)
    }

    fun reportFailure(provider: Provider, failure: ProviderFailure) {
        if (failure != ProviderFailure.TEMPORARY) unavailable += provider
    }

    fun reportSuccess(provider: Provider) {
        unavailable -= provider
    }
}

/**
 * Every pause (offline, no keys, no credit, cap) looks the same on Home: one quiet line,
 * phone search keeps working. The reason is shown only on the usage screen.
 */
object AiPause {
    const val LINE = "AI PAUSED · PHONE SEARCH ONLY"

    fun line(route: Route): String? = if (route is Route.PhoneOnly) LINE else null
}
