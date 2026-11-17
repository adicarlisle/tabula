package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Examples: cost is computed from the token counts each API response reports. */
class UsageMeterTest {
    // Prices in US dollars per million tokens. Editable by the user; these are examples.
    private val prices = PriceTable(
        mapOf(
            "claude-haiku-5-5" to ModelPrice(input = 0.10, output = 0.50, cacheRead = 0.01, cacheWrite = 0.125),
            "claude-sonnet-5-5" to ModelPrice(input = 2.00, output = 10.00, cacheRead = 0.10, cacheWrite = 2.50),
        ),
    )
    private val nov17 = LocalDate.of(2026, 11, 17)

    @Test
    fun `one call costs input plus output plus cache reads and writes`() {
        val usage = TokenUsage(input = 1_000, output = 200, cacheRead = 3_000, cacheWrite = 0)
        // 1000*0.10 + 200*0.50 + 3000*0.01 = 100 + 100 + 30 = 230 per million = $0.00023
        assertEquals(0.00023, prices.cost("claude-haiku-5-5", usage), 1e-12)
    }

    @Test
    fun `unknown model is priced as unknown, never as free`() {
        assertEquals(null, prices.costOrNull("some-new-model", TokenUsage(1, 1, 0, 0)))
    }

    @Test
    fun `month to date only counts this month`() {
        val meter = UsageMeter(prices)
        meter.record(LocalDate.of(2026, 10, 31), "claude-sonnet-5-5", Feature.SEARCH, TokenUsage(1_000_000, 0, 0, 0))
        meter.record(LocalDate.of(2026, 11, 1), "claude-haiku-5-5", Feature.SEARCH, TokenUsage(1_000_000, 0, 0, 0))
        meter.record(nov17, "claude-haiku-5-5", Feature.CAPTURE, TokenUsage(0, 1_000_000, 0, 0))
        assertEquals(0.60, meter.monthToDate(nov17), 1e-9)
        assertEquals(mapOf(Feature.SEARCH to 0.10, Feature.CAPTURE to 0.50), meter.byFeature(nov17))
    }

    @Test
    fun `prediction extends the last 14 days average to month end`() {
        val meter = UsageMeter(prices)
        // 14 days, Nov 4..17, each spending $0.01 (100k haiku input tokens)
        for (d in 4..17) meter.record(LocalDate.of(2026, 11, d), "claude-haiku-5-5", Feature.SEARCH, TokenUsage(100_000, 0, 0, 0))
        // spent so far 0.14, 13 days left in November at 0.01/day
        assertEquals(0.27, meter.predictMonthEnd(nov17), 1e-9)
    }

    @Test
    fun `prediction with a short history averages over the days that exist`() {
        val meter = UsageMeter(prices)
        meter.record(LocalDate.of(2026, 11, 16), "claude-haiku-5-5", Feature.SEARCH, TokenUsage(200_000, 0, 0, 0))
        meter.record(nov17, "claude-haiku-5-5", Feature.SEARCH, TokenUsage(0, 0, 0, 0))
        // 0.02 over Nov 1..17 counted from the first recorded day: 2 days, 0.01/day, 13 days left
        assertEquals(0.15, meter.predictMonthEnd(nov17), 1e-9)
    }
}
