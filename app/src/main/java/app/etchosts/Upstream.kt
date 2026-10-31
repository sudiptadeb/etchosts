package app.etchosts

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

interface Upstream {
    /** Sends a raw DNS query, returns the raw response. Throws on failure. */
    fun resolve(query: ByteArray): ByteArray

    companion object {
        const val TIMEOUT_MS = 4000

        /** Preference key -> label. The first one is the default. */
        val CHOICES = linkedMapOf(
            "cf_doh" to "Cloudflare DoH (cloudflare-dns.com)",
            "google_doh" to "Google DoH (dns.google)",
            "cf_udp" to "Cloudflare plain (1.1.1.1 / 1.0.0.1)",
            "google_udp" to "Google plain (8.8.8.8 / 8.8.4.4)",
        )

        fun of(key: String?, protect: (DatagramSocket) -> Boolean): Upstream = when (key) {
            "google_doh" -> Doh("https://dns.google/dns-query")
            "cf_udp" -> Udp(listOf("1.1.1.1", "1.0.0.1"), protect)
            "google_udp" -> Udp(listOf("8.8.8.8", "8.8.4.4"), protect)
            else -> Doh("https://cloudflare-dns.com/dns-query")
        }
    }
}

/** RFC 8484 POST. Keep-alive is pooled by HttpURLConnection itself. */
class Doh(private val url: String) : Upstream {
    override fun resolve(query: ByteArray): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = Upstream.TIMEOUT_MS
            c.readTimeout = Upstream.TIMEOUT_MS
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.setFixedLengthStreamingMode(query.size)
            c.outputStream.use { it.write(query) }
            if (c.responseCode != 200) throw IOException("DoH HTTP ${c.responseCode}")
            val body = c.inputStream.use { it.readBytes() }
            if (body.size < 12 || body.size > 65535) throw IOException("bad DoH body")
            return body
        } finally {
            c.disconnect()
        }
    }
}

/** Plain DNS over UDP/53, trying each server in turn. */
class Udp(servers: List<String>, private val protect: (DatagramSocket) -> Boolean) : Upstream {
    private val addrs = servers.map { InetAddress.getByAddress(HostsTable.parseIp(it)) }

    override fun resolve(query: ByteArray): ByteArray {
        var last: IOException = IOException("no servers")
        for (a in addrs) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    s.soTimeout = Upstream.TIMEOUT_MS / addrs.size
                    s.send(DatagramPacket(query, query.size, a, 53))
                    val buf = ByteArray(4096)
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        s.receive(p)
                        if (p.length >= 12 && buf[0] == query[0] && buf[1] == query[1]) return buf.copyOf(p.length)
                    }
                }
            } catch (e: IOException) {
                last = e
            }
        }
        throw last
    }
}
