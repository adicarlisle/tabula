package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Examples: the cap is off until the user sets one; when credit runs out we fall back, never break. */
class AiRouterTest {
    private val anthropic = Provider("anthropic")
    private val openRouter = Provider("openrouter")
    private val local = Provider("ollama")

    @Test
    fun `no cap means every request is allowed on the main provider`() {
        val router = AiRouter(chain = listOf(anthropic, openRouter), cap = null, onCap = CapAction.QUICK_ONLY)
        assertEquals(Route.Ai(anthropic, ModelTier.DEEP), router.route(ModelTier.DEEP, spentThisMonth = 99.0))
    }

    @Test
    fun `under the cap nothing changes`() {
        val router = AiRouter(chain = listOf(anthropic), cap = 2.0, onCap = CapAction.QUICK_ONLY)
        assertEquals(Route.Ai(anthropic, ModelTier.DEEP), router.route(ModelTier.DEEP, spentThisMonth = 1.99))
    }

    @Test
    fun `cap reached applies the user's choice`() {
        fun at(action: CapAction) = AiRouter(listOf(anthropic), cap = 2.0, onCap = action).route(ModelTier.DEEP, 2.0)
        assertEquals(Route.Ai(anthropic, ModelTier.QUICK), at(CapAction.QUICK_ONLY))
        assertEquals(Route.PhoneOnly(PauseReason.CAP_REACHED), at(CapAction.PHONE_ONLY))
        assertEquals(Route.AskUser, at(CapAction.ASK))
    }

    @Test
    fun `a provider out of credit is skipped until it recovers`() {
        val router = AiRouter(chain = listOf(anthropic, openRouter, local), cap = null, onCap = CapAction.QUICK_ONLY)
        router.reportFailure(anthropic, ProviderFailure.OUT_OF_CREDIT)
        assertEquals(Route.Ai(openRouter, ModelTier.QUICK), router.route(ModelTier.QUICK, 0.0))
        router.reportFailure(openRouter, ProviderFailure.OUT_OF_CREDIT)
        assertEquals(Route.Ai(local, ModelTier.QUICK), router.route(ModelTier.QUICK, 0.0))
        router.reportSuccess(anthropic)
        assertEquals(Route.Ai(anthropic, ModelTier.QUICK), router.route(ModelTier.QUICK, 0.0))
    }

    @Test
    fun `everything out of credit leaves the phone-only launcher`() {
        val router = AiRouter(chain = listOf(anthropic), cap = null, onCap = CapAction.QUICK_ONLY)
        router.reportFailure(anthropic, ProviderFailure.OUT_OF_CREDIT)
        assertEquals(Route.PhoneOnly(PauseReason.NO_CREDIT), router.route(ModelTier.QUICK, 0.0))
    }

    @Test
    fun `no keys at all is phone-only, not an error`() {
        assertEquals(Route.PhoneOnly(PauseReason.NO_KEYS), AiRouter(emptyList(), null, CapAction.QUICK_ONLY).route(ModelTier.QUICK, 0.0))
    }

    @Test
    fun `a temporary error does not mark the provider as out of credit`() {
        val router = AiRouter(chain = listOf(anthropic, openRouter), cap = null, onCap = CapAction.QUICK_ONLY)
        router.reportFailure(anthropic, ProviderFailure.TEMPORARY)
        assertEquals(Route.Ai(anthropic, ModelTier.QUICK), router.route(ModelTier.QUICK, 0.0))
    }

    @Test
    fun `no signal falls back exactly like running out of credit`() {
        val router = AiRouter(chain = listOf(anthropic), cap = 2.0, onCap = CapAction.ASK)
        val offline = router.route(ModelTier.QUICK, spentThisMonth = 5.0, online = false)
        assertEquals(Route.PhoneOnly(PauseReason.OFFLINE), offline)

        router.reportFailure(anthropic, ProviderFailure.OUT_OF_CREDIT)
        val noCredit = router.route(ModelTier.QUICK, spentThisMonth = 0.0)

        // Same experience for the user: one quiet line, phone search keeps working.
        assertEquals(AiPause.line(offline), AiPause.line(noCredit))
        assertEquals("AI PAUSED · PHONE SEARCH ONLY", AiPause.line(noCredit))
        assertEquals(null, AiPause.line(Route.Ai(anthropic, ModelTier.QUICK)))
    }
}
