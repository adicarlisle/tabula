package app.tabula.core

enum class ServerKind { OLLAMA, VLLM }

/** user@host[:port] on the local network. Strict, so nothing typed here can become an SSH option. */
data class ServerAddress(val host: String, val port: Int, val user: String) {
    companion object {
        private val pattern = Regex("""^([a-z_][a-z0-9_-]{0,31})@([A-Za-z0-9][A-Za-z0-9.-]{0,252})(?::(\d{1,5}))?$""")

        fun parse(text: String): ServerAddress? {
            val (user, host, port) = pattern.matchEntire(text.trim())?.destructured ?: return null
            val p = if (port.isEmpty()) 22 else port.toInt()
            return if (p in 1..65535) ServerAddress(host, p, user) else null
        }

        fun of(host: String, port: Int, user: String): ServerAddress? = parse("${user.trim()}@${host.trim()}:$port")
    }
}

/** A Hugging Face repo id such as Qwen/Qwen3-4B. Only safe characters, so it can be quoted into a shell command. */
@JvmInline
value class HfModelId private constructor(val value: String) {
    companion object {
        private val pattern = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}$""")

        fun parse(text: String): HfModelId {
            val id = text.trim()
            require(pattern.matches(id) && ".." !in id) { "Not a Hugging Face model id: $text" }
            return HfModelId(id)
        }
    }
}

/**
 * The exact scripts Tabula runs on the user's machine. They are shown in full before anything runs.
 * The sudo password is sent once on stdin to `sudo -S -v`; later steps use the cached `sudo -n`.
 * Model servers listen on 127.0.0.1 only; the phone reaches them through the SSH tunnel.
 */
object ServerScripts {
    private const val D = "$"

    fun install(kind: ServerKind): String = when (kind) {
        ServerKind.OLLAMA -> """
            set -euo pipefail
            sudo -S -p '' -v
            sudo -n sh -c 'printf "Managed by the Tabula app: %s set up by user %s on %s\n" "$1" "$2" "$(date -Is)" >> /etc/tabula-managed' _ Ollama "${D}USER"
            logger -t tabula "Tabula phone app set up Ollama for user ${D}USER"
            curl -fsSL https://ollama.com/install.sh -o /tmp/ollama-install.sh
            sudo -n sh /tmp/ollama-install.sh
            sudo -n mkdir -p /etc/systemd/system/ollama.service.d
            printf '[Service]\nEnvironment="OLLAMA_HOST=127.0.0.1:11434"\n' | sudo -n tee /etc/systemd/system/ollama.service.d/tabula.conf >/dev/null
            sudo -n systemctl daemon-reload
            sudo -n systemctl enable --now ollama
            sudo -n systemctl restart ollama
            echo TABULA_OK
        """.trimIndent()

        ServerKind.VLLM -> """
            set -euo pipefail
            sudo -S -p '' -v
            sudo -n sh -c 'printf "Managed by the Tabula app: %s set up by user %s on %s\n" "$1" "$2" "$(date -Is)" >> /etc/tabula-managed' _ vLLM "${D}USER"
            logger -t tabula "Tabula phone app set up vLLM for user ${D}USER"
            sudo -n apt-get update -qq
            sudo -n apt-get install -y -qq python3-venv python3-pip
            mkdir -p ~/.tabula && chmod 700 ~/.tabula
            python3 -m venv ~/.tabula/venv
            ~/.tabula/venv/bin/pip install -q --upgrade pip vllm huggingface_hub
            [ -f ~/.tabula/api-key ] || (head -c 32 /dev/urandom | base64 | tr -dc A-Za-z0-9 > ~/.tabula/api-key)
            chmod 600 ~/.tabula/api-key
            cat > ~/.tabula/serve.sh <<'SERVE'
            #!/usr/bin/env bash
            set -euo pipefail
            pkill -f 'vllm serve' || true
            VLLM_API_KEY="$D(cat ~/.tabula/api-key)" nohup ~/.tabula/venv/bin/vllm serve "${D}1" --host 127.0.0.1 --port 8000 > ~/.tabula/vllm.log 2>&1 &
            SERVE
            chmod 700 ~/.tabula/serve.sh
            echo TABULA_OK
        """.trimIndent()
    }

    fun installModel(kind: ServerKind, model: HfModelId): String = when (kind) {
        ServerKind.OLLAMA -> "ollama pull 'hf.co/${model.value}'"
        ServerKind.VLLM ->
            "~/.tabula/venv/bin/hf download '${model.value}' --include '*.safetensors' '*.json' 'tokenizer*' && ~/.tabula/serve.sh '${model.value}'"
    }

    /** Port the model server listens on, on the server's own localhost. */
    fun port(kind: ServerKind): Int = if (kind == ServerKind.OLLAMA) 11434 else 8000
}

enum class HostKeyDecision { TRUSTED, ASK_USER, CHANGED_REFUSE }

/** Trust on first use: the user confirms a new server's fingerprint once; a changed key is refused. */
class KnownHosts(private val keys: MutableMap<String, String> = mutableMapOf()) {
    fun check(host: String, fingerprint: String): HostKeyDecision = when (keys[host]) {
        null -> HostKeyDecision.ASK_USER
        fingerprint -> HostKeyDecision.TRUSTED
        else -> HostKeyDecision.CHANGED_REFUSE
    }

    fun trust(host: String, fingerprint: String) { keys[host] = fingerprint }

    fun forget(host: String) { keys.remove(host) }

    fun encode(): String = keys.entries.joinToString("\n") { "${it.key} ${it.value}" }

    companion object {
        fun decode(text: String) = KnownHosts(
            text.lines().mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap().toMutableMap(),
        )
    }
}
