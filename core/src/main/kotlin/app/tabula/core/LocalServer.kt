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
        ServerKind.OLLAMA -> ollamaInDocker()

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

    /**
     * Ollama in Docker with no open port, behind the HTTPS key gateway. The Caddyfile with the device keys
     * is uploaded separately by the phone (chmod 600), so keys never appear in this script.
     */
    private fun ollamaInDocker(): String = """
        set -euo pipefail
        sudo -S -p '' -v
        # Keep sudo's permission fresh during long downloads.
        ( while true; do sudo -n -v 2>/dev/null; sleep 60; done ) & KEEP=$D!
        # If anything fails: stop the half-made gateway and switch the old Ollama back on.
        OLD_OLLAMA=0
        cleanup() {
          rc=${D}?
          kill ${D}KEEP 2>/dev/null || true
          if [ "${D}rc" -ne 0 ]; then
            if [ -f ~/.tabula/gateway/compose.yaml ]; then (cd ~/.tabula/gateway && sudo -n docker compose down >/dev/null 2>&1) || true; fi
            if [ "${D}OLD_OLLAMA" = 1 ]; then echo "TABULA_INFO Putting your old Ollama back"; sudo -n systemctl enable --now ollama || true; fi
          fi
        }
        trap cleanup EXIT
        sudo -n sh -c 'printf "Managed by the Tabula app: %s set up by user %s on %s\n" "${D}1" "${D}2" "${D}(date -Is)" >> /etc/tabula-managed' _ "Ollama in Docker" "${D}USER"
        logger -t tabula "Tabula phone app set up Ollama in Docker for user ${D}USER"
        echo "TABULA_STEP 1/5 Checking Docker"
        AVAIL=${D}(df -BG --output=avail /var/lib 2>/dev/null | tail -1 | tr -dc 0-9)
        if [ "${D}{AVAIL:-0}" -lt 10 ]; then echo "no space left on device: ${D}{AVAIL}G free, at least 10G needed"; exit 1; fi
        if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
          sudo -n apt-get update -qq
          sudo -n apt-get install -y -qq docker.io docker-compose-v2
        fi
        sudo -n systemctl enable --now docker
        sudo -n usermod -aG docker "${D}USER"
        echo "TABULA_STEP 2/5 Switching off the open Ollama service"
        if systemctl cat ollama >/dev/null 2>&1; then sudo -n systemctl disable --now ollama; OLD_OLLAMA=1; fi
        echo "TABULA_STEP 3/5 Writing the gateway"
        cd ~/.tabula/gateway
        if command -v nvidia-smi >/dev/null 2>&1 && sudo -n docker info 2>/dev/null | grep -qi nvidia; then
        cat > compose.yaml <<'COMPOSE'
        @@COMPOSE_GPU@@
        COMPOSE
        else
        cat > compose.yaml <<'COMPOSE'
        @@COMPOSE_CPU@@
        COMPOSE
        fi
        echo "TABULA_HOST=$D(ip route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if(${D}i=="src") print ${D}(i+1)}')" > .env
        echo "TABULA_STEP 4/5 Downloading Ollama and the gateway"
        # Report how much has downloaded every few seconds (from the network counter; needs no sudo).
        NETIF=${D}(ip route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if(${D}i=="dev") print ${D}(i+1)}')
        rx() { grep "${D}NETIF:" /proc/net/dev | sed 's/.*://' | awk '{print ${D}1}'; }
        RX0=${D}(rx)
        ( while sleep 5; do awk -v b=${D}(( ${D}(rx) - RX0 )) 'BEGIN { if (b > 1073741824) printf "TABULA_INFO %.1f GB downloaded\n", b / 1073741824; else printf "TABULA_INFO %d MB downloaded\n", b / 1048576 }'; done ) & WATCH=${D}!
        sudo -n docker compose pull -q
        kill ${D}WATCH 2>/dev/null || true
        echo "TABULA_STEP 5/5 Starting"
        sudo -n docker compose up -d
        for i in ${D}(seq 1 60); do sudo -n docker compose exec -T gateway test -f /data/caddy/pki/authorities/local/root.crt 2>/dev/null && break; sleep 1; done
        sudo -n docker compose exec -T gateway test -f /data/caddy/pki/authorities/local/root.crt || { echo "The gateway didn't start in time"; exit 1; }
        OLD_OLLAMA=0
        echo TABULA_OK
    """.trimIndent()
        .replace("@@COMPOSE_GPU@@", Gateway.compose(gpu = true).trimEnd())
        .replace("@@COMPOSE_CPU@@", Gateway.compose(gpu = false).trimEnd())

    fun installModel(kind: ServerKind, model: HfModelId): String = when (kind) {
        ServerKind.OLLAMA -> "docker compose -f ~/.tabula/gateway/compose.yaml exec -T ollama ollama pull 'hf.co/${model.value}'"
        ServerKind.VLLM ->
            "~/.tabula/venv/bin/hf download '${model.value}' --include '*.safetensors' '*.json' 'tokenizer*' && ~/.tabula/serve.sh '${model.value}'"
    }

    /**
     * Starts the uploaded setup in its own session on the server, so it finishes even if the phone
     * disconnects. The sudo password is read from the inherited stdin by `sudo -S -v` straight away.
     */
    fun launchDetached(): String =
        "read -r TABULA_PW; printf '%s\\n' \"${D}TABULA_PW\" | " +
            "setsid -f bash -c 'bash ~/.tabula/install.sh > ~/.tabula/install.log 2>&1; echo \"TABULA_EXIT ${D}?\" >> ~/.tabula/install.log'" +
            "; unset TABULA_PW"

    fun progress(): String = "tail -n 60 ~/.tabula/install.log 2>/dev/null"

    private val modelName = Regex("""^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$""")

    /** Deletes one model from the Ollama container. Only the owner's phone can do this (over SSH). */
    fun removeModel(name: String): String {
        require(modelName.matches(name) && ".." !in name) { "Not a model name: $name" }
        return "docker compose -f ~/.tabula/gateway/compose.yaml exec -T ollama ollama rm '$name'"
    }

    /** Stops any model download in the container and removes its half-downloaded files. */
    fun cancelDownloads(): String =
        "docker compose -f ~/.tabula/gateway/compose.yaml exec -T ollama sh -c '" +
            "pkill -f \"^ollama pull\"; " +
            "for i in 1 2 3 4 5 6 7 8 9 10; do sleep 1; " +
            "[ -z \"$D(find /root/.ollama/models/blobs -name \"*-partial*\" -newermt \"-2 seconds\" 2>/dev/null)\" ] && break; done; " +
            "rm -f /root/.ollama/models/blobs/*-partial*'"

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

enum class SetupState {
    RUNNING, DONE, FAILED;

    companion object {
        fun of(log: String): SetupState = when {
            "TABULA_OK" in log && "TABULA_EXIT 0" in log -> DONE
            Regex("""TABULA_EXIT \d+""").containsMatchIn(log) -> FAILED
            else -> RUNNING
        }
    }
}
