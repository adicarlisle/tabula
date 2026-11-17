package app.tabula.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Examples: finding SSH servers on the user's own wifi, only when they ask. */
class DiscoveryTest {
    @Test
    fun `scans the phone's own home network, skipping itself`() {
        val hosts = LanScan.hosts("192.168.1.37", prefixLength = 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertEquals(false, "192.168.1.37" in hosts)
    }

    @Test
    fun `big networks are narrowed to the phone's own 254 addresses`() {
        val hosts = LanScan.hosts("10.20.30.40", prefixLength = 16)
        assertEquals("10.20.30.1", hosts.first())
        assertEquals(253, hosts.size)
    }

    @Test
    fun `small networks scan only what exists`() =
        assertEquals(listOf("192.168.1.1", "192.168.1.2"), LanScan.hosts("192.168.1.3", prefixLength = 30))

    @Test
    fun `never scans public addresses or mobile data`() {
        assertEquals(emptyList<String>(), LanScan.hosts("8.8.8.8", 24))
        assertEquals(emptyList<String>(), LanScan.hosts("100.64.1.1", 24))
        assertEquals(emptyList<String>(), LanScan.hosts("not an ip", 24))
    }

    @Test
    fun `reads the ssh greeting`() {
        assertEquals(SshBanner("OpenSSH 9.6p1", "Ubuntu"), SshBanner.parse("SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.5\r\n"))
        assertEquals(SshBanner("dropbear 2022.83", null), SshBanner.parse("SSH-2.0-dropbear_2022.83"))
        assertNull(SshBanner.parse("HTTP/1.1 400 Bad Request"))
    }

    @Test
    fun `address from separate fields`() {
        assertEquals(ServerAddress("192.168.1.20", 22, "adi"), ServerAddress.of("192.168.1.20", 22, " adi "))
        assertNull(ServerAddress.of("192.168.1.20", 22, "bad user"))
    }
}
