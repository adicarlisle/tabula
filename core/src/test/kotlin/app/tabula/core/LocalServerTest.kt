package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Examples for the Pro "local AI server": what runs on the user's Linux machine, and the safety checks around it. */
class LocalServerTest {
    @Test
    fun `ollama is installed in docker behind the key gateway, and the old open service is switched off`() {
        val script = ServerScripts.install(ServerKind.OLLAMA)
        assertTrue("docker compose up -d" in script)
        assertTrue("systemctl disable --now ollama" in script)
        assertTrue("usermod -aG docker" in script)
        assertFalse("0.0.0.0" in script)
        assertFalse("tk_" in script, "device keys are uploaded separately, never shown in the script")
    }

    @Test
    fun `vllm install uses its own virtualenv, localhost and an api key file only the user can read`() {
        val script = ServerScripts.install(ServerKind.VLLM)
        assertTrue("python3 -m venv" in script)
        assertTrue("--host 127.0.0.1" in script)
        assertTrue("chmod 600" in script)
        assertFalse("0.0.0.0" in script)
    }

    @Test
    fun `scripts never contain the password, sudo reads it from stdin`() {
        ServerKind.entries.forEach { kind ->
            val script = ServerScripts.install(kind)
            assertTrue("sudo -S" in script || "sudo" !in script, "$kind")
            assertFalse("PASSWORD" in script.uppercase(), "$kind")
        }
    }

    @Test
    fun `model install commands per server`() {
        assertEquals(
            "docker compose -f ~/.tabula/gateway/compose.yaml exec -T ollama ollama pull 'hf.co/bartowski/Llama-3.2-3B-Instruct-GGUF'",
            ServerScripts.installModel(ServerKind.OLLAMA, HfModelId.parse("bartowski/Llama-3.2-3B-Instruct-GGUF")),
        )
        assertEquals(
            "~/.tabula/venv/bin/hf download 'Qwen/Qwen3-4B' --include '*.safetensors' '*.json' 'tokenizer*' && ~/.tabula/serve.sh 'Qwen/Qwen3-4B'",
            ServerScripts.installModel(ServerKind.VLLM, HfModelId.parse("Qwen/Qwen3-4B")),
        )
    }

    @Test
    fun `model names are checked strictly so they can't run shell commands`() {
        listOf("x; rm -rf ~", "a/b c", "a/\$(whoami)", "../etc/passwd", "a/b'c", "noslash", "a/b/c", "").forEach {
            assertThrows<IllegalArgumentException>(it) { HfModelId.parse(it) }
        }
        assertEquals("Qwen/Qwen3-4B", HfModelId.parse(" Qwen/Qwen3-4B ").value)
    }

    @Test
    fun `server address and username are validated`() {
        assertEquals(ServerAddress("192.168.1.20", 22, "adi"), ServerAddress.parse("adi@192.168.1.20"))
        assertEquals(ServerAddress("gpu-box.local", 2222, "ml_user"), ServerAddress.parse("ml_user@gpu-box.local:2222"))
        listOf("192.168.1.20", "a b@host", "u@host:99999", "u@", "u@host;ls", "-oProxy@host").forEach {
            assertNull(ServerAddress.parse(it), it)
        }
    }

    @Test
    fun `host key is trusted on first use and refused if it changes`() {
        val hosts = KnownHosts()
        assertEquals(HostKeyDecision.ASK_USER, hosts.check("box:22", "SHA256:aaa"))
        hosts.trust("box:22", "SHA256:aaa")
        assertEquals(HostKeyDecision.TRUSTED, hosts.check("box:22", "SHA256:aaa"))
        assertEquals(HostKeyDecision.CHANGED_REFUSE, hosts.check("box:22", "SHA256:bbb"))
        assertEquals(KnownHosts.decode(hosts.encode()).check("box:22", "SHA256:aaa"), HostKeyDecision.TRUSTED)
    }

    @Test
    fun `setup keeps running on the server if the phone disconnects`() {
        val launch = ServerScripts.launchDetached()
        // The shell reads the password line itself before detaching, so a fast-closing SSH channel can't drop it.
        assertTrue(launch.startsWith("read -r TABULA_PW; printf '%s\\n' \"${'$'}TABULA_PW\" | setsid -f "), launch)
        assertTrue(launch.endsWith("; unset TABULA_PW"), launch)
        assertTrue("~/.tabula/install.log" in launch)
        assertTrue("TABULA_EXIT" in launch)
        assertEquals("tail -n 60 ~/.tabula/install.log 2>/dev/null", ServerScripts.progress())
    }

    @Test
    fun `progress log says when setup is finished`() {
        assertEquals(SetupState.RUNNING, SetupState.of("downloading...\n>>> Installing ollama"))
        assertEquals(SetupState.DONE, SetupState.of("...\nTABULA_OK\nTABULA_EXIT 0"))
        assertEquals(SetupState.FAILED, SetupState.of("curl: (6) Could not resolve host\nTABULA_EXIT 6"))
    }

    @Test
    fun `every script is valid bash`() {
        val scripts = ServerKind.entries.map { ServerScripts.install(it) } + ServerCheck.SCRIPT + ServerScripts.launchDetached() +
            ServerScripts.cancelDownloads() + ServerScripts.removeModel("devstral:latest")
        java.io.File("build/cancel-downloads.sh").writeText(ServerScripts.cancelDownloads())
        scripts.forEach { script ->
            val p = ProcessBuilder("bash", "-n").redirectErrorStream(true).start()
            p.outputStream.use { it.write(script.toByteArray()) }
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), out + "\n" + script)
        }
    }
}
