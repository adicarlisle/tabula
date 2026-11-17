package app.tabula.core

/** Which addresses to check for an SSH server. Private home/office ranges only, at most 254 addresses. */
object LanScan {
    fun hosts(phoneIp: String, prefixLength: Int): List<String> {
        val parts = phoneIp.trim().split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return emptyList()
        val (a, b) = parts
        val private = a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
        if (!private) return emptyList()
        val ip = parts.fold(0L) { acc, p -> (acc shl 8) or p.toLong() }
        val prefix = prefixLength.coerceIn(24, 30)
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network = ip and mask
        val broadcast = network or (mask.inv() and 0xFFFFFFFFL)
        return ((network + 1) until broadcast).filter { it != ip }.map { n ->
            "${(n shr 24) and 255}.${(n shr 16) and 255}.${(n shr 8) and 255}.${n and 255}"
        }
    }
}

/** The greeting an SSH server sends first, e.g. "SSH-2.0-OpenSSH_9.6p1 Ubuntu-3ubuntu13.5". */
data class SshBanner(val software: String, val system: String?) {
    companion object {
        fun parse(line: String): SshBanner? {
            val text = line.trim()
            val rest = when {
                text.startsWith("SSH-2.0-") -> text.removePrefix("SSH-2.0-")
                text.startsWith("SSH-1.99-") -> text.removePrefix("SSH-1.99-")
                else -> return null
            }
            val software = rest.substringBefore(' ').replaceFirst('_', ' ')
            val system = rest.substringAfter(' ', "").substringBefore('-').takeIf { it.isNotBlank() }
            return SshBanner(software, system)
        }
    }
}
