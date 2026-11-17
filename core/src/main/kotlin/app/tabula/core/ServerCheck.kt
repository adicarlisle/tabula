package app.tabula.core

/** What is already on the server, found before anything changes, and said in plain words. */
data class ServerCheck(
    val ollamaVersion: String? = null,
    val ollamaService: String = "none",
    val ollamaHost: String? = null,
    val models: Int = 0,
    val docker: Boolean = false,
    val gpu: Boolean = false,
    val gatewayRunning: Boolean = false,
) {
    fun summary(): List<String> = buildList {
        if (gatewayRunning) add("Tabula's gateway is already running here. Setup will update it.")
        if (ollamaService == "none") {
            add("No Ollama found. It will be installed in Docker.")
        } else {
            val name = if (ollamaVersion != null) "Ollama $ollamaVersion" else "Ollama"
            add(if (ollamaService == "active") "$name is already installed and running." else "$name is already installed but not running.")
            if (ollamaHost?.startsWith("0.0.0.0") == true) {
                add("It is open to your whole network with no key. Setup switches it off and runs Ollama in Docker behind your keys instead.")
            } else {
                add("Setup switches it off and runs Ollama in Docker behind your keys instead.")
            }
        }
        when {
            models == 1 -> add("1 model found. It will be kept.")
            models > 1 -> add("$models models found. They will be kept.")
        }
        add(if (docker) "Docker is installed." else "Docker will be installed.")
        add(if (gpu) "NVIDIA GPU found. Ollama will use it." else "No NVIDIA GPU found, so models run on the CPU.")
    }

    companion object {
        /** Read-only checks. Nothing here changes the server. */
        const val SCRIPT = """
v=${'$'}(ollama --version 2>/dev/null | grep -o '[0-9][0-9.]*' | head -1); [ -n "${'$'}v" ] && echo "ollama_version=${'$'}v"
if systemctl cat ollama >/dev/null 2>&1; then echo "ollama_service=${'$'}(systemctl is-active ollama 2>/dev/null)"; else echo ollama_service=none; fi
h=${'$'}(systemctl show ollama -p Environment 2>/dev/null | grep -o 'OLLAMA_HOST=[^ ]*' | cut -d= -f2); [ -n "${'$'}h" ] && echo "ollama_host=${'$'}h"
echo "ollama_models=${'$'}(ollama list 2>/dev/null | tail -n +2 | grep -c . || true)"
command -v docker >/dev/null 2>&1 && echo docker=yes || echo docker=no
command -v nvidia-smi >/dev/null 2>&1 && nvidia-smi -L >/dev/null 2>&1 && echo gpu=yes || echo gpu=no
docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^gateway-gateway' && echo gateway=running || echo gateway=none
true
"""

        fun parse(text: String): ServerCheck {
            val kv = text.lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            return ServerCheck(
                ollamaVersion = kv["ollama_version"]?.takeIf { Regex("^[0-9][0-9.]{0,20}$").matches(it) },
                ollamaService = kv["ollama_service"]?.takeIf { it in setOf("active", "inactive", "failed", "none") } ?: "none",
                ollamaHost = kv["ollama_host"]?.takeIf { Regex("^[0-9a-zA-Z.:\\[\\]-]{1,64}$").matches(it) },
                models = kv["ollama_models"]?.toIntOrNull()?.coerceIn(0, 10_000) ?: 0,
                docker = kv["docker"] == "yes",
                gpu = kv["gpu"] == "yes",
                gatewayRunning = kv["gateway"] == "running",
            )
        }
    }
}

/** Setup prints "TABULA_STEP n/N label" lines; the phone turns the latest into a progress bar. */
data class SetupProgress(val step: Int, val total: Int, val label: String) {
    companion object {
        private val line = Regex("""^TABULA_STEP (\d+)/(\d+) (.+)$""")

        private val info = Regex("""^TABULA_INFO (.+)$""")

        /** The latest progress note for the current stage, e.g. "1.2 GB downloaded". */
        fun detail(log: String): String? =
            log.lines().map { it.trim() }.takeLastWhile { !line.matches(it) }
                .mapNotNull { info.matchEntire(it)?.groupValues?.get(1) }.lastOrNull()

        fun of(log: String): SetupProgress =
            log.lines().mapNotNull { line.matchEntire(it.trim()) }.lastOrNull()
                ?.destructured?.let { (n, total, label) -> SetupProgress(n.toInt(), total.toInt(), label) }
                ?: SetupProgress(0, 5, "Starting")
    }
}
