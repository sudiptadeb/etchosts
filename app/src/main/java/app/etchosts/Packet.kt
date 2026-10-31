package app.etchosts

/** A UDP datagram read from the tun, IPv4 or IPv6 (no extension headers). */
class UdpPacket(
    val src: ByteArray, val dst: ByteArray,
    val srcPort: Int, val dstPort: Int,
    val payload: ByteArray,
) {
    /** Builds the reply datagram: addresses and ports swapped, checksums computed. */
    fun reply(data: ByteArray): ByteArray = Packet.build(dst, src, dstPort, srcPort, data)
}

object Packet {
    private fun u16(b: ByteArray, o: Int) = Dns.u16(b, o)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    fun parse(b: ByteArray, len: Int): UdpPacket? {
        if (len < 1) return null
        val udp: Int
        val end: Int
        val src: ByteArray
        val dst: ByteArray
        when (b[0].toInt() ushr 4 and 0xf) {
            4 -> {
                if (len < 20) return null
                val ihl = (b[0].toInt() and 0xf) * 4
                if (b[9].toInt() != 17 || u16(b, 6) and 0x3fff != 0) return null // not UDP, or a fragment
                udp = ihl; end = minOf(len, u16(b, 2))
                src = b.copyOfRange(12, 16); dst = b.copyOfRange(16, 20)
            }
            6 -> {
                if (len < 40 || b[6].toInt() != 17) return null
                udp = 40; end = minOf(len, 40 + u16(b, 4))
                src = b.copyOfRange(8, 24); dst = b.copyOfRange(24, 40)
            }
            else -> return null
        }
        if (udp + 8 > end) return null
        val ulen = u16(b, udp + 4)
        if (ulen < 8 || udp + ulen > end) return null
        return UdpPacket(src, dst, u16(b, udp), u16(b, udp + 2), b.copyOfRange(udp + 8, udp + ulen))
    }

    fun build(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, data: ByteArray): ByteArray {
        val v6 = src.size == 16
        val ip = if (v6) 40 else 20
        val ulen = 8 + data.size
        val out = ByteArray(ip + ulen)
        if (v6) {
            out[0] = 0x60
            put16(out, 4, ulen)
            out[6] = 17; out[7] = 64
            System.arraycopy(src, 0, out, 8, 16); System.arraycopy(dst, 0, out, 24, 16)
        } else {
            out[0] = 0x45
            put16(out, 2, out.size)
            out[8] = 64; out[9] = 17
            System.arraycopy(src, 0, out, 12, 4); System.arraycopy(dst, 0, out, 16, 4)
            put16(out, 10, fold(sum(out, 0, 20, 0)))
        }
        put16(out, ip, sport); put16(out, ip + 2, dport); put16(out, ip + 4, ulen)
        System.arraycopy(data, 0, out, ip + 8, data.size)
        // pseudo-header: addresses, protocol, UDP length
        var s = sum(src, 0, src.size, 0)
        s = sum(dst, 0, dst.size, s) + 17 + ulen
        var c = fold(sum(out, ip, ulen, s))
        if (c == 0) c = 0xffff
        put16(out, ip + 6, c)
        return out
    }

    /** One's-complement sum of [len] bytes at [off], added to [seed]. */
    fun sum(b: ByteArray, off: Int, len: Int, seed: Long): Long {
        var s = seed
        var i = off
        while (i + 1 < off + len) { s += u16(b, i); i += 2 }
        if (i < off + len) s += (b[i].toInt() and 0xff) shl 8
        return s
    }

    fun fold(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xffff) + (s ushr 16)
        return s.toInt().inv() and 0xffff
    }
}
