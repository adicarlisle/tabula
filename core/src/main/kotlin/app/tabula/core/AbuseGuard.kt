package app.tabula.core

/** Pro only works with servers on a private home or office network (never public internet addresses). */
object TargetPolicy {
    fun allowed(ip: String): Boolean {
        val p = ip.trim().split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }
}

/** Stops the app being used to guess SSH passwords: 3 failures lock that server for 10 minutes. */
class LoginGuard(private val maxFailures: Int = 3, private val lockMillis: Long = 10 * 60_000L) {
    private val failures = mutableMapOf<String, MutableList<Long>>()

    fun mayTry(host: String, now: Long): Boolean = secondsLocked(host, now) == 0

    fun secondsLocked(host: String, now: Long): Int {
        val recent = failures[host].orEmpty()
        if (recent.size < maxFailures) return 0
        val until = recent.last() + lockMillis
        return if (now >= until) 0 else ((until - now) / 1000).toInt()
    }

    fun failed(host: String, now: Long) {
        failures.getOrPut(host) { mutableListOf() }.add(now)
    }

    fun succeeded(host: String) { failures.remove(host) }
}
