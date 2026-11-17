package app.tabula

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.tabula.core.LanScan
import app.tabula.core.SshBanner
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class FoundServer(val host: String, val banner: SshBanner)

/** Finds SSH servers on the wifi the phone is on. Runs only when the user taps "find". */
object LanFinder {
    /** The phone's IPv4 address and prefix on wifi or ethernet, or null on mobile data. */
    private fun localNetwork(context: Context): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val link = cm.getLinkProperties(net)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        return link.address.hostAddress!! to link.prefixLength
    }

    suspend fun find(context: Context, port: Int = 22): List<FoundServer> = withContext(Dispatchers.IO) {
        val (ip, prefix) = localNetwork(context) ?: throw IllegalStateException("Connect to wifi to find servers")
        val gate = Semaphore(32)
        coroutineScope {
            LanScan.hosts(ip, prefix).map { host ->
                async { gate.withPermit { probe(host, port) } }
            }.awaitAll().filterNotNull()
        }
    }

    private fun probe(host: String, port: Int): FoundServer? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 400)
            s.soTimeout = 1500
            val line = s.getInputStream().bufferedReader().readLine() ?: return null
            SshBanner.parse(line)?.let { FoundServer(host, it) }
        }
    } catch (_: Exception) { null }
}
