package app.tabula.core

import java.security.SecureRandom
import java.util.Base64

/** One device's access key to the user's Ollama gateway. */
data class DeviceKey(val name: String, val key: String) {
    companion object {
        private val keyPattern = Regex("^tk_[A-Za-z0-9_-]{43}$")
        private val random = SecureRandom()

        fun generate(name: String): DeviceKey {
            val bytes = ByteArray(32).also(random::nextBytes)
            return DeviceKey(cleanName(name), "tk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }

        /** Names end up in a config file, so only plain characters are kept. */
        fun cleanName(name: String): String =
            name.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' || it == '.' }
                .replace(Regex(" +"), " ").trim().take(32).ifEmpty { "Device" }

        fun isValidKey(key: String) = keyPattern.matches(key)

        fun encodeAll(keys: List<DeviceKey>): String = keys.joinToString("\n") { "${it.key}\t${cleanName(it.name)}" }

        fun decodeAll(text: String): List<DeviceKey> = text.lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2 && isValidKey(parts[0])) DeviceKey(cleanName(parts[1]), parts[0]) else null
        }
    }
}

/** Ollama in Docker with no open port, behind a Caddy HTTPS gateway that checks a per-device key. */
object Gateway {
    const val PORT = 11435

    private val USE_PATHS = listOf(
        "/api/generate", "/api/chat", "/api/tags", "/api/show", "/api/version",
        "/v1/chat/completions", "/v1/completions", "/v1/models",
    )

    fun compose(gpu: Boolean): String = buildString {
        appendLine("services:")
        appendLine("  ollama:")
        appendLine("    image: ollama/ollama:latest")
        appendLine("    restart: unless-stopped")
        appendLine("    volumes:")
        appendLine("      - /usr/share/ollama/.ollama:/root/.ollama")
        if (gpu) appendLine("    gpus: all")
        appendLine("  gateway:")
        appendLine("    image: caddy:2")
        appendLine("    restart: unless-stopped")
        appendLine("    environment:")
        appendLine("      TABULA_HOST: \${TABULA_HOST}")
        appendLine("    ports:")
        appendLine("      - \"$PORT:$PORT\"")
        appendLine("    volumes:")
        appendLine("      - ./Caddyfile:/etc/caddy/Caddyfile:ro")
        appendLine("      - caddy_data:/data")
        appendLine("volumes:")
        appendLine("  caddy_data: {}")
    }

    fun caddyfile(keys: List<DeviceKey>): String = buildString {
        // Devices connect by IP address and send no SNI, so tell Caddy which certificate to use.
        appendLine("{")
        appendLine("\tdefault_sni {\$TABULA_HOST}")
        appendLine("}")
        appendLine("https://{\$TABULA_HOST}:$PORT {")
        appendLine("\ttls internal")
        appendLine("\t# The gateway's own certificate authority, so devices can check they reached this server.")
        appendLine("\thandle /tabula-ca.crt {")
        appendLine("\t\troot * /data/caddy/pki/authorities/local")
        appendLine("\t\trewrite * /root.crt")
        appendLine("\t\tfile_server")
        appendLine("\t}")
        if (keys.isNotEmpty()) {
            // Needs a listed key AND an allowed path. Devices can use models, not download, delete or change them;
            // that stays with the owner's phone over SSH. Anything else falls through to 401.
            appendLine("\t@use {")
            appendLine("\t\theader Authorization " + keys.joinToString(" ") { "\"Bearer ${it.key}\"" })
            appendLine("\t\tpath " + USE_PATHS.joinToString(" "))
            appendLine("\t}")
            appendLine("\thandle @use {")
            appendLine("\t\treverse_proxy ollama:11434")
            appendLine("\t}")
        }
        appendLine("\trespond 401")
        appendLine("}")
    }
}

/**
 * What a device scans to get access: the gateway's address, this device's key, and the gateway's own CA
 * certificate (DER, base64url). The scanning device trusts exactly that certificate and nothing else.
 */
data class Pairing(val host: String, val port: Int, val key: String, val caBase64: String) {
    fun encode() = "tabula://pair?h=$host&p=$port&k=$key&ca=$caBase64"

    companion object {
        private val ca = Regex("^[A-Za-z0-9_-]{200,3000}={0,2}$")

        fun decode(text: String): Pairing? {
            if (!text.startsWith("tabula://pair?")) return null
            val q = text.removePrefix("tabula://pair?").split('&').mapNotNull {
                it.split('=', limit = 2).takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to kv[1] }
            }.toMap()
            val host = q["h"] ?: return null
            val port = q["p"]?.toIntOrNull() ?: return null
            val key = q["k"] ?: return null
            val cert = q["ca"] ?: return null
            if (!TargetPolicy.allowed(host) || port !in 1..65535 || !DeviceKey.isValidKey(key) || !ca.matches(cert)) return null
            return Pairing(host, port, key, cert)
        }
    }
}
