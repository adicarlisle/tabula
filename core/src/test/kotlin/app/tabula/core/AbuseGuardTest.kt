package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Examples: Tabula can't be turned into a tool for attacking other people's servers. */
class AbuseGuardTest {
    @Test
    fun `only servers on a private home or office network are allowed`() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.4.2", "172.31.255.1").forEach { assertTrue(TargetPolicy.allowed(it), it) }
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "127.0.0.1", "0.0.0.0", "169.254.1.1", "203.0.113.5", "example.com", "").forEach {
            assertFalse(TargetPolicy.allowed(it), it)
        }
    }

    @Test
    fun `three wrong passwords lock that server for ten minutes`() {
        val guard = LoginGuard()
        val t0 = 1_000_000L
        assertTrue(guard.mayTry("box", t0))
        repeat(3) { guard.failed("box", t0) }
        assertFalse(guard.mayTry("box", t0 + 60_000))
        assertTrue(guard.mayTry("other", t0))
        assertTrue(guard.mayTry("box", t0 + 10 * 60_000))
    }

    @Test
    fun `a successful login clears the count`() {
        val guard = LoginGuard()
        repeat(2) { guard.failed("box", 0) }
        guard.succeeded("box")
        repeat(2) { guard.failed("box", 0) }
        assertTrue(guard.mayTry("box", 0))
    }

    @Test
    fun `setup leaves a visible trace for the server's owner`() {
        ServerKind.entries.forEach { kind ->
            val script = ServerScripts.install(kind)
            assertTrue("logger -t tabula" in script, "$kind")
            assertTrue("/etc/tabula-managed" in script, "$kind")
        }
    }

    @Test
    fun `seconds left on a lock are shown`() {
        val guard = LoginGuard()
        repeat(3) { guard.failed("box", 0) }
        assertEquals(540, guard.secondsLocked("box", 60_000))
    }
}
