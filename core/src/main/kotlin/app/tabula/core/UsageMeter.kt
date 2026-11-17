package app.tabula.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Token counts as reported in each API response's usage block. */
data class TokenUsage(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long)

/** US dollars per million tokens. */
data class ModelPrice(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

class PriceTable(private val prices: Map<String, ModelPrice>) {
    fun costOrNull(model: String, u: TokenUsage): Double? {
        val p = prices[model] ?: return null
        return (u.input * p.input + u.output * p.output + u.cacheRead * p.cacheRead + u.cacheWrite * p.cacheWrite) / 1_000_000.0
    }

    fun cost(model: String, u: TokenUsage): Double =
        costOrNull(model, u) ?: throw IllegalArgumentException("No price for $model")
}

enum class Feature { SEARCH, ACTIONS, CAPTURE, MORNING_LINE }

class UsageMeter(private val prices: PriceTable, private val predictionWindowDays: Long = 14) {
    private data class Entry(val day: LocalDate, val model: String, val feature: Feature, val cost: Double?)

    private val entries = mutableListOf<Entry>()

    /** Calls whose model has no price yet; shown to the user instead of being counted as free. */
    val unpricedCalls: Int get() = entries.count { it.cost == null }

    fun record(day: LocalDate, model: String, feature: Feature, usage: TokenUsage) {
        entries += Entry(day, model, feature, prices.costOrNull(model, usage))
    }

    private fun thisMonth(today: LocalDate) =
        entries.filter { it.day.year == today.year && it.day.month == today.month && !it.day.isAfter(today) }

    fun monthToDate(today: LocalDate): Double = thisMonth(today).sumOf { it.cost ?: 0.0 }

    fun byFeature(today: LocalDate): Map<Feature, Double> =
        thisMonth(today).groupBy { it.feature }.mapValues { (_, list) -> list.sumOf { it.cost ?: 0.0 } }

    /** Month-to-date plus the recent daily average carried to the last day of the month. */
    fun predictMonthEnd(today: LocalDate): Double {
        val spent = monthToDate(today)
        val first = entries.minOfOrNull { it.day } ?: return spent
        val windowStart = maxOf(today.minusDays(predictionWindowDays - 1), first)
        val days = ChronoUnit.DAYS.between(windowStart, today) + 1
        val windowSpend = entries.filter { !it.day.isBefore(windowStart) && !it.day.isAfter(today) }.sumOf { it.cost ?: 0.0 }
        val daysLeft = today.lengthOfMonth() - today.dayOfMonth
        return spent + (windowSpend / days) * daysLeft
    }
}
