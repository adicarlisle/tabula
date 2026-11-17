package app.tabula.core

/** What the search bar can do on the phone itself: free, instant, works with no AI. */
sealed interface LocalCommand {
    data class Timer(val seconds: Int) : LocalCommand
    data class Alarm(val hour: Int, val minute: Int) : LocalCommand
    data class Calculate(val result: Double) : LocalCommand
    data class Setting(val kind: SettingKind, val on: Boolean) : LocalCommand
    /** Not something the phone can answer: hand the original text to the AI (if available). */
    data class AskAi(val text: String) : LocalCommand
}

enum class SettingKind { WIFI, BLUETOOTH, TORCH }

object LocalCommandParser {
    private val timer = Regex(
        """^(?:set )?(?:a )?timer(?: for)? (\d{1,5}) ?(s|secs?|seconds?|m|mins?|minutes?|h|hrs?|hours?)$""",
    )
    private val alarm = Regex(
        """^(?:alarm(?: for| at)?|wake me(?: up)?(?: at)?|set (?:an )?alarm(?: for| at)?) (\d{1,2})(?:[:.](\d{2}))? ?(am|pm)?$""",
    )
    private val settingFirst = Regex("""^(?:turn )?(wi-?fi|bluetooth|torch|flashlight) (on|off)$""")
    private val settingLast = Regex("""^turn (on|off) (?:the )?(wi-?fi|bluetooth|torch|flashlight)$""")
    private val mathChars = Regex("""^[0-9.+\-*/^() ]+$""")
    private val mathOperator = Regex("""[0-9)] *[+\-*/^]""")

    fun parse(input: String): LocalCommand {
        val original = input.trim()
        val text = original.lowercase().replace(Regex("""\s+"""), " ")
        if (text.isEmpty()) return LocalCommand.AskAi("")
        return parseTimer(text) ?: parseAlarm(text) ?: parseSetting(text) ?: parseMaths(text)
            ?: LocalCommand.AskAi(original)
    }

    private fun parseTimer(text: String): LocalCommand? {
        val (amount, unit) = timer.matchEntire(text)?.destructured ?: return null
        val multiplier = when (unit.first()) { 's' -> 1; 'm' -> 60; else -> 3600 }
        val seconds = amount.toInt() * multiplier
        return if (seconds in 1..(24 * 3600)) LocalCommand.Timer(seconds) else null
    }

    private fun parseAlarm(text: String): LocalCommand? {
        val (h, m, meridiem) = alarm.matchEntire(text)?.destructured ?: return null
        var hour = h.toInt()
        val minute = m.ifEmpty { "0" }.toInt()
        if (minute > 59) return null
        when (meridiem) {
            "" -> if (hour > 23) return null
            else -> {
                if (hour !in 1..12) return null
                hour = (hour % 12) + if (meridiem == "pm") 12 else 0
            }
        }
        return LocalCommand.Alarm(hour, minute)
    }

    private fun parseSetting(text: String): LocalCommand? {
        settingFirst.matchEntire(text)?.destructured?.let { (name, state) -> return setting(name, state) }
        settingLast.matchEntire(text)?.destructured?.let { (state, name) -> return setting(name, state) }
        return null
    }

    private fun setting(name: String, state: String): LocalCommand {
        val kind = when (name) {
            "bluetooth" -> SettingKind.BLUETOOTH
            "torch", "flashlight" -> SettingKind.TORCH
            else -> SettingKind.WIFI
        }
        return LocalCommand.Setting(kind, on = state == "on")
    }

    private fun parseMaths(text: String): LocalCommand? {
        if (!mathChars.matches(text) || !mathOperator.containsMatchIn(text)) return null
        val value = Arithmetic(text.replace(" ", "")).evaluate() ?: return null
        return LocalCommand.Calculate(value)
    }
}

/**
 * Small recursive-descent evaluator: + - * / ^ and brackets. Never uses a scripting engine,
 * so typed text can't run code. Returns null for anything malformed or non-finite.
 */
private class Arithmetic(private val s: String) {
    private var i = 0

    fun evaluate(): Double? = runCatching {
        val v = expression()
        if (i != s.length || !v.isFinite()) null else v
    }.getOrNull()

    private fun expression(): Double {
        var v = term()
        while (i < s.length && (s[i] == '+' || s[i] == '-')) {
            v = if (s[i++] == '+') v + term() else v - term()
        }
        return v
    }

    private fun term(): Double {
        var v = power()
        while (i < s.length && (s[i] == '*' || s[i] == '/')) {
            if (s[i++] == '*') v *= power() else {
                val d = power()
                require(d != 0.0)
                v /= d
            }
        }
        return v
    }

    private fun power(): Double {
        val base = unary()
        if (i < s.length && s[i] == '^') { i++; return Math.pow(base, power()) }
        return base
    }

    private fun unary(): Double = if (i < s.length && s[i] == '-') { i++; -unary() } else atom()

    private fun atom(): Double {
        require(depth++ < 64)
        try {
            if (i < s.length && s[i] == '(') {
                i++
                val v = expression()
                require(i < s.length && s[i] == ')'); i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        } finally { depth-- }
    }

    private var depth = 0
}
