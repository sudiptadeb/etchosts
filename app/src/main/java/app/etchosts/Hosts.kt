package app.etchosts

import java.net.Inet4Address
import java.net.InetAddress

/** Addresses for one name. Raw bytes: 4 for IPv4, 16 for IPv6. */
class HostEntry {
    val v4 = mutableListOf<ByteArray>()
    val v6 = mutableListOf<ByteArray>()

    internal fun add(ip: ByteArray) {
        val list = if (ip.size == 4) v4 else v6
        if (list.none { it.contentEquals(ip) }) list.add(ip)
    }
}

/** An immutable lookup table; swap the whole thing to apply edits. */
class HostsTable(
    private val exact: Map<String, HostEntry>,
    private val wildcard: Map<String, HostEntry>,
    val badLines: List<Int>,
) {
    val size get() = exact.size + wildcard.size

    /** Exact match first, then the most specific `*.suffix` (which does not match the apex). */
    fun lookup(name: String): HostEntry? {
        val n = name.lowercase().trimEnd('.')
        exact[n]?.let { return it }
        var i = n.indexOf('.')
        while (i >= 0) {
            wildcard[n.substring(i + 1)]?.let { return it }
            i = n.indexOf('.', i + 1)
        }
        return null
    }

    companion object {
        val EMPTY = HostsTable(emptyMap(), emptyMap(), emptyList())

        fun parse(text: String): HostsTable {
            val exact = HashMap<String, HostEntry>()
            val wild = HashMap<String, HostEntry>()
            val bad = mutableListOf<Int>()
            text.lineSequence().forEachIndexed { idx, raw ->
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                val parts = line.split(Regex("\\s+"))
                val ip = parseIp(parts[0])
                if (ip == null || parts.size < 2) {
                    bad.add(idx + 1)
                    return@forEachIndexed
                }
                for (h in parts.drop(1)) {
                    val host = h.lowercase().trimEnd('.')
                    if (host.startsWith("*.") && host.length > 2) {
                        wild.getOrPut(host.substring(2)) { HostEntry() }.add(ip)
                    } else if (host.isNotEmpty() && !host.contains('*')) {
                        exact.getOrPut(host) { HostEntry() }.add(ip)
                    }
                }
            }
            return HostsTable(exact, wild, bad)
        }

        /** Parses an IP literal without ever touching DNS. */
        fun parseIp(s: String): ByteArray? {
            if (s.contains(':')) {
                val lit = s.substringBefore('%')
                if (lit.any { !(it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.') }) return null
                return try {
                    val a = InetAddress.getByName(lit) // a literal: no lookup happens
                    if (a is Inet4Address) a.address else a.address.takeIf { it.size == 16 }
                } catch (e: Exception) {
                    null
                }
            }
            val p = s.split('.')
            if (p.size != 4) return null
            val out = ByteArray(4)
            for (i in 0 until 4) {
                if (p[i].isEmpty() || p[i].length > 3 || p[i].any { !it.isDigit() }) return null
                val v = p[i].toInt()
                if (v > 255) return null
                out[i] = v.toByte()
            }
            return out
        }
    }
}
