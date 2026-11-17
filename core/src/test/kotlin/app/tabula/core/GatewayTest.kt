package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Examples: Ollama in Docker behind an HTTPS gateway that needs a per-device API key. */
class GatewayTest {
    private val keys = listOf(DeviceKey("Adi's Pixel", "tk_" + "a".repeat(43)), DeviceKey("Laptop", "tk_" + "b".repeat(43)))

    @Test
    fun `ollama has no open port, only the gateway does`() {
        val compose = Gateway.compose(gpu = false)
        assertTrue("image: ollama/ollama" in compose)
        assertTrue("image: caddy:2" in compose)
        assertTrue("\"11435:11435\"" in compose)
        assertFalse("11434:11434" in compose)
        assertFalse("gpus" in compose)
        assertTrue("gpus: all" in Gateway.compose(gpu = true))
    }

    @Test
    fun `existing models are kept`() =
        assertTrue("/usr/share/ollama/.ollama:/root/.ollama" in Gateway.compose(gpu = false))

    @Test
    fun `gateway serves https, lets only listed keys through and refuses the rest`() {
        val caddy = Gateway.caddyfile(keys)
        assertTrue("tls internal" in caddy)
        // Devices connect by IP address, which sends no SNI; without this the TLS handshake fails.
        assertTrue(caddy.startsWith("{\n\tdefault_sni {${'$'}TABULA_HOST}\n}\n"), caddy)
        assertTrue("\"Bearer tk_${"a".repeat(43)}\"" in caddy)
        assertTrue("\"Bearer tk_${"b".repeat(43)}\"" in caddy)
        assertTrue("reverse_proxy ollama:11434" in caddy)
        assertTrue("respond 401" in caddy)
        assertTrue("handle /tabula-ca.crt" in caddy)
    }

    @Test
    fun `with no keys nothing gets through`() {
        val caddy = Gateway.caddyfile(emptyList())
        assertFalse("reverse_proxy" in caddy)
        assertTrue("respond 401" in caddy)
    }

    @Test
    fun `keys are long, random and url-safe`() {
        val a = DeviceKey.generate("Phone")
        val b = DeviceKey.generate("Phone")
        assertTrue(Regex("^tk_[A-Za-z0-9_-]{43}$").matches(a.key), a.key)
        assertNotEquals(a.key, b.key)
    }

    @Test
    fun `device names can't break the gateway config`() {
        assertEquals("Adis Pixel 10", DeviceKey.cleanName("Adi's \"Pixel\" 10\n{}"))
        assertEquals("Device", DeviceKey.cleanName("   "))
    }

    @Test
    fun `keys file round trip`() = assertEquals(keys.map { it.copy(name = DeviceKey.cleanName(it.name)) }, DeviceKey.decodeAll(DeviceKey.encodeAll(keys)))

    private val ca = "MIIB" + "A".repeat(500)

    @Test
    fun `pairing qr holds address, key and the gateway's own certificate`() {
        val pair = Pairing("192.168.1.65", 11435, "tk_" + "a".repeat(43), ca)
        val text = pair.encode()
        assertTrue(text.startsWith("tabula://pair?"))
        assertEquals(pair, Pairing.decode(text))
    }

    @Test
    fun `pairing codes are checked strictly`() {
        val ok = Pairing("192.168.1.65", 11435, "tk_" + "a".repeat(43), ca).encode()
        listOf(
            ok.replace("192.168.1.65", "8.8.8.8"),
            ok.replace("tk_", "xx_"),
            ok.replace(ca, "short"),
            ok.replace(ca, ca.dropLast(3) + "!!!"),
            ok.replace("tabula://", "https://"),
            "tabula://pair?h=192.168.1.65",
            "",
        ).forEach { assertNull(Pairing.decode(it), it) }
    }

    @Test
    fun `devices with a key can use models but not download, delete or change them`() {
        val caddy = Gateway.caddyfile(keys)
        // One rule needs both a listed key and an allowed path; anything else falls through to 401.
        assertTrue("\t@use {\n\t\theader Authorization " in caddy, caddy)
        assertTrue("\t\tpath /api/generate /api/chat /api/tags /api/show /api/version /v1/chat/completions /v1/completions /v1/models\n\t}" in caddy, caddy)
        assertTrue("\thandle @use {\n\t\treverse_proxy ollama:11434\n\t}\n\trespond 401" in caddy, caddy)
        listOf("/api/pull", "/api/delete", "/api/create", "/api/push", "/api/copy").forEach { assertFalse(it in caddy, it) }
    }

    @Test
    fun `the owner's phone manages models over ssh`() {
        assertEquals(
            "docker compose -f ~/.tabula/gateway/compose.yaml exec -T ollama ollama rm 'devstral:latest'",
            ServerScripts.removeModel("devstral:latest"),
        )
        assertTrue("pkill -f 'ollama pull'" in ServerScripts.cancelDownloads())
        assertTrue("-partial" in ServerScripts.cancelDownloads())
        listOf("x'; rm -rf /", "a b", "", "\$(id)").forEach { assertThrows<IllegalArgumentException>(it) { ServerScripts.removeModel(it) } }
    }
}
