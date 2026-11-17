package app.tabula

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.tabula.core.LanScan
import app.tabula.core.SshBanner
import java.net.Inet4Address
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class FoundServer(val host: String, val banner: SshBanner)

data class FindResult(val servers: List<FoundServer>, val searched: String)

/** The home wifi (or ethernet) network itself, never a VPN, so local traffic goes straight to the LAN. */
object HomeNetwork {
    fun get(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // allNetworks is the simple way to find the wifi network underneath a VPN.
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    fun address(context: Context, network: Network): Pair<String, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val link = cm.getLinkProperties(network)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: return null
        return link.address.hostAddress!! to link.prefixLength
    }
}

/** Finds SSH servers on the wifi the phone is on. Runs only when the user taps "find". */
object LanFinder {
    suspend fun find(context: Context, port: Int = 22): FindResult = withContext(Dispatchers.IO) {
        val network = HomeNetwork.get(context) ?: throw IllegalStateException("Connect to wifi to find servers.")
        val (ip, prefix) = HomeNetwork.address(context, network) ?: throw IllegalStateException("This wifi has no IPv4 address.")
        val hosts = LanScan.hosts(ip, prefix)
        if (hosts.isEmpty()) throw IllegalStateException("This wifi ($ip) isn't a home or office network, so Tabula won't search it.")
        val gate = Semaphore(32)
        val servers = coroutineScope {
            hosts.map { host -> async { gate.withPermit { probe(network, host, port) } } }.awaitAll().filterNotNull()
        }
        FindResult(servers, "${hosts.first()} – ${hosts.last()} on wifi (this phone is $ip)")
    }

    private fun probe(network: Network, host: String, port: Int): FoundServer? = try {
        network.socketFactory.createSocket().use { s ->
            s.connect(InetSocketAddress(host, port), 400)
            s.soTimeout = 1500
            val line = s.getInputStream().bufferedReader().readLine() ?: return null
            SshBanner.parse(line)?.let { FoundServer(host, it) }
        }
    } catch (_: Exception) { null }
}
