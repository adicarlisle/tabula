package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Examples: when setup fails, say what happened and what to do, in plain words. */
class SetupFailureTest {
    private fun explain(vararg lines: String) = SetupFailure.explain(lines.joinToString("\n") + "\nTABULA_EXIT 1")

    @Test
    fun `wrong sudo password`() {
        val f = explain("TABULA_STEP 1/5 Checking Docker", "sudo: Authentication required but not attempted")
        assertEquals("The password didn't work for sudo", f.title)
        assertEquals("Run setup again and check the password for this user on the server.", f.advice)
    }

    @Test
    fun `user without admin rights`() =
        assertEquals("This user can't use sudo", explain("adi is not in the sudoers file.").title)

    @Test
    fun `another install already running`() =
        assertEquals("Another install is running on the server", explain("E: Could not get lock /var/lib/dpkg/lock-frontend").title)

    @Test
    fun `server offline`() {
        listOf("curl: (6) Could not resolve host: ollama.com", "Error response from daemon: Get \"https://registry-1.docker.io/v2/\": dial tcp: lookup registry-1.docker.io: Temporary failure in name resolution", "net/http: TLS handshake timeout").forEach {
            assertEquals("The server couldn't reach the internet", explain(it).title, it)
        }
    }

    @Test
    fun `disk full`() =
        assertEquals("The server is out of disk space", explain("write /var/lib/docker/tmp/x: no space left on device").title)

    @Test
    fun `port taken`() =
        assertEquals("Port 11435 is already in use on the server", explain("Bind for 0.0.0.0:11435 failed: port is already allocated").title)

    @Test
    fun `unknown errors name the step that failed`() {
        val f = explain("TABULA_STEP 4/5 Downloading Ollama and the gateway", "something odd")
        assertEquals("Setup stopped at step 4 of 5: Downloading Ollama and the gateway", f.title)
        assertEquals("Open the details to see why, then try again. Nothing else was changed.", f.advice)
    }

    @Test
    fun `setup checks disk space first and puts the old ollama back if it fails`() {
        val script = ServerScripts.install(ServerKind.OLLAMA)
        assertTrue("df -BG" in script, "disk check")
        assertTrue("Putting your old Ollama back" in script, "rollback")
        assertTrue(script.indexOf("df -BG") < script.indexOf("systemctl disable --now ollama"), "check before changing anything")
    }
}
