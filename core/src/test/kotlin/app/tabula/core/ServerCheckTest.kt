package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Examples: before changing anything, say what is already on the server. */
class ServerCheckTest {
    @Test
    fun `existing open ollama with models is described plainly`() {
        val found = ServerCheck.parse(
            """
            ollama_version=0.40.1
            ollama_service=active
            ollama_host=0.0.0.0
            ollama_models=5
            docker=yes
            gpu=no
            gateway=none
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "Ollama 0.40.1 is already installed and running.",
                "It is open to your whole network with no key. Setup switches it off and runs Ollama in Docker behind your keys instead.",
                "5 models found. They will be kept.",
                "Docker is installed.",
                "No NVIDIA GPU found, so models run on the CPU.",
            ),
            found.summary(),
        )
    }

    @Test
    fun `fresh server`() {
        val found = ServerCheck.parse("ollama_service=none\ndocker=no\ngpu=yes\ngateway=none")
        assertEquals(
            listOf("No Ollama found. It will be installed in Docker.", "Docker will be installed.", "NVIDIA GPU found. Ollama will use it."),
            found.summary(),
        )
    }

    @Test
    fun `tabula gateway already running`() {
        val found = ServerCheck.parse("ollama_service=none\ndocker=yes\ngpu=no\ngateway=running")
        assertEquals("Tabula's gateway is already running here. Setup will update it.", found.summary().first())
    }

    @Test
    fun `junk lines are ignored`() = assertEquals(ServerCheck.parse(""), ServerCheck.parse("rm -rf /\n=\nfoo"))

    @Test
    fun `setup stages are read from the log`() {
        assertEquals(SetupProgress(0, 5, "Starting"), SetupProgress.of(""))
        assertEquals(
            SetupProgress(4, 5, "Downloading Ollama and the gateway"),
            SetupProgress.of("TABULA_STEP 1/5 Checking Docker\nfoo\nTABULA_STEP 4/5 Downloading Ollama and the gateway\nbar"),
        )
    }
}
