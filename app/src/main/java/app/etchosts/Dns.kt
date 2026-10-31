package app.etchosts

/** Just enough of RFC 1035 to read a question and write an answer. */
object Dns {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val RCODE_SERVFAIL = 2
    const val TTL = 60

    class Question(val id: Int, val name: String, val type: Int, val end: Int)

    fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte()
    }

    /** Returns the first question of a standard query, or null if it is not one. */
    fun parseQuery(m: ByteArray): Question? {
        if (m.size < 12) return null
        val flags = u16(m, 2)
        if (flags and 0x8000 != 0 || (flags ushr 11) and 0xf != 0 || u16(m, 4) < 1) return null
        val sb = StringBuilder()
        var p = 12
        while (true) {
            if (p >= m.size) return null
            val len = m[p].toInt() and 0xff
            p++
            if (len == 0) break
            if (len > 63 || p + len > m.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in p until p + len) sb.append((m[i].toInt() and 0xff).toChar())
            p += len
            if (sb.length > 253) return null
        }
        if (p + 4 > m.size) return null
        return Question(u16(m, 0), sb.toString().lowercase(), u16(m, p), p + 4)
    }

    /** NOERROR response carrying [addrs] as records of [q].type; empty [addrs] = NODATA. */
    fun answer(query: ByteArray, q: Question, addrs: List<ByteArray>): ByteArray {
        val qLen = q.end - 12
        val out = ByteArray(12 + qLen + addrs.sumOf { 12 + it.size })
        header(out, query, q, 0, addrs.size)
        System.arraycopy(query, 12, out, 12, qLen)
        var p = 12 + qLen
        for (a in addrs) {
            put16(out, p, 0xC00C)                       // pointer to the question name
            put16(out, p + 2, if (a.size == 4) TYPE_A else TYPE_AAAA)
            put16(out, p + 4, 1)                        // class IN
            put16(out, p + 6, 0); put16(out, p + 8, TTL)
            put16(out, p + 10, a.size)
            System.arraycopy(a, 0, out, p + 12, a.size)
            p += 12 + a.size
        }
        return out
    }

    fun error(query: ByteArray, q: Question, rcode: Int): ByteArray {
        val qLen = q.end - 12
        val out = ByteArray(12 + qLen)
        header(out, query, q, rcode, 0)
        System.arraycopy(query, 12, out, 12, qLen)
        return out
    }

    private fun header(out: ByteArray, query: ByteArray, q: Question, rcode: Int, ancount: Int) {
        put16(out, 0, q.id)
        // QR=1, keep RD from the query, RA=1
        put16(out, 2, 0x8000 or (u16(query, 2) and 0x0100) or 0x0080 or rcode)
        put16(out, 4, 1)
        put16(out, 6, ancount)
    }

    /** Local answer for a hosts hit: A gets the IPv4s, AAAA the IPv6s, anything else NODATA. */
    fun fromHosts(query: ByteArray, q: Question, e: HostEntry): ByteArray = answer(
        query, q,
        when (q.type) {
            TYPE_A -> e.v4
            TYPE_AAAA -> e.v6
            else -> emptyList()
        }
    )
}
