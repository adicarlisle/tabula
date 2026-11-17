package app.tabula

import app.tabula.core.Gateway
import app.tabula.core.Pairing
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.json.JSONObject

/** Talks to the user's Ollama gateway over HTTPS, trusting only the gateway's own pinned certificate authority. */
class GatewayClient(private val host: String, private val key: String, private val ca: X509Certificate) {
    private val ssl: SSLContext = SSLContext.getInstance("TLS").apply {
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("tabula-gateway", ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        init(null, tmf.trustManagers, null)
    }

    private fun open(path: String): HttpsURLConnection =
        (URL("https://$host:${Gateway.PORT}$path").openConnection() as HttpsURLConnection).apply {
            sslSocketFactory = ssl.socketFactory
            connectTimeout = 10_000; readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $key")
        }

    private fun check(c: HttpsURLConnection) {
        if (c.responseCode == 401) throw IllegalStateException("The gateway refused this device's key. It may have been revoked.")
        if (c.responseCode !in 200..299) throw IllegalStateException("Gateway replied ${c.responseCode}")
    }

    /** Model names on the server. */
    fun models(): List<String> {
        val c = open("/api/tags")
        check(c)
        val arr = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
    }

    fun ask(model: String, prompt: String): String {
        val c = open("/api/generate")
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("model", model).put("prompt", prompt).put("stream", false).toString().toByteArray()) }
        check(c)
        return JSONObject(c.inputStream.bufferedReader().readText()).optString("response").trim()
    }

    companion object {
        fun parsePem(pem: String): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.trim().toByteArray())) as X509Certificate

        fun sha256(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

        /** For a device that scanned a QR code: trust exactly the certificate the QR code carried. */
        fun fromPairing(p: Pairing): GatewayClient {
            val der = java.util.Base64.getUrlDecoder().decode(p.caBase64)
            val ca = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return GatewayClient(p.host, p.key, ca)
        }

        fun caBase64(ca: X509Certificate): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ca.encoded)
    }
}
