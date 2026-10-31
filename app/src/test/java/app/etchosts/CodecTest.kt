package app.etchosts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HostsTest {
    private val t = HostsTable.parse(
        """
        # comment
        192.0.2.10   app.example.test  API.example.test.  # trailing comment
        2001:db8::10 app.example.test
        192.0.2.11   app.example.test
        192.0.2.20   *.dev.example.test
        192.0.2.21   *.example.test
        not-an-ip    broken.example.test
        999.1.1.1    broken2.example.test
        192.0.2.30
        """.trimIndent()
    )

    @Test fun exactMatchIsCaseInsensitiveAndMerges() {
        val e = t.lookup("APP.Example.Test.")!!
        assertEquals(2, e.v4.size)
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 10), e.v4[0])
        assertEquals(1, e.v6.size)
        assertEquals(16, e.v6[0].size)
        assertNotNull(t.lookup("api.example.test"))
    }

    @Test fun wildcardMostSpecificWinsAndSkipsApex() {
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 20), t.lookup("a.b.dev.example.test")!!.v4[0])
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 21), t.lookup("other.example.test")!!.v4[0])
        assertNull(t.lookup("example.test"))
        assertNull(t.lookup("example.org"))
    }

    @Test fun badLinesAreReported() {
        assertEquals(listOf(7, 8, 9), t.badLines)
    }

    @Test fun ipParsing() {
        assertNull(HostsTable.parseIp("1.2.3"))
        assertNull(HostsTable.parseIp("01.2.3.4444"))
        assertNull(HostsTable.parseIp("host:name"))
        assertEquals(16, HostsTable.parseIp("fe80::1%wlan0")!!.size)
        assertEquals(4, HostsTable.parseIp("::ffff:192.0.2.1")!!.size)
    }
}

class DnsTest {
    fun query(name: String, type: Int, id: Int = 0x1234): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf((id ushr 8).toByte(), id.toByte(), 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0))
        for (l in name.split('.')) { out.write(l.length); out.write(l.toByteArray()) }
        out.write(byteArrayOf(0, 0, type.toByte(), 0, 1))
        return out.toByteArray()
    }

    @Test fun parseAndAnswerA() {
        val q = query("App.Example.Test", Dns.TYPE_A)
        val parsed = Dns.parseQuery(q)!!
        assertEquals("app.example.test", parsed.name)
        assertEquals(Dns.TYPE_A, parsed.type)
        val e = HostsTable.parse("192.0.2.10 app.example.test\n192.0.2.11 app.example.test").lookup(parsed.name)!!
        val r = Dns.fromHosts(q, parsed, e)
        assertEquals(0x1234, Dns.u16(r, 0))
        assertEquals(0x8180, Dns.u16(r, 2)) // QR, RD, RA, NOERROR
        assertEquals(1, Dns.u16(r, 4))
        assertEquals(2, Dns.u16(r, 6))
        val a = parsed.end
        assertEquals(0xC00C, Dns.u16(r, a))
        assertEquals(Dns.TYPE_A, Dns.u16(r, a + 2))
        assertEquals(60, Dns.u16(r, a + 8))
        assertEquals(4, Dns.u16(r, a + 10))
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 10), r.copyOfRange(a + 12, a + 16))
        assertEquals(r.size, a + 2 * 16)
    }

    @Test fun aaaaWithoutV6IsEmptyNoError() {
        val q = query("app.example.test", Dns.TYPE_AAAA)
        val parsed = Dns.parseQuery(q)!!
        val r = Dns.fromHosts(q, parsed, HostsTable.parse("192.0.2.10 app.example.test").lookup("app.example.test")!!)
        assertEquals(0x8180, Dns.u16(r, 2))
        assertEquals(0, Dns.u16(r, 6))
        assertEquals(q.size, r.size)
    }

    @Test fun servfail() {
        val q = query("x.example", Dns.TYPE_A)
        val r = Dns.error(q, Dns.parseQuery(q)!!, Dns.RCODE_SERVFAIL)
        assertEquals(0x8182, Dns.u16(r, 2))
    }

    @Test fun rejectsResponsesAndGarbage() {
        val q = query("x.example", Dns.TYPE_A)
        q[2] = 0x81.toByte()
        assertNull(Dns.parseQuery(q))
        assertNull(Dns.parseQuery(ByteArray(5)))
        assertNull(Dns.parseQuery(query("x.example", 1).copyOf(16)))
    }
}

class PacketTest {
    private val v4a = byteArrayOf(10, -1, -1, 1)
    private val v4b = byteArrayOf(10, -1, -1, 2)

    @Test fun ipv4RoundTripAndChecksums() {
        val data = DnsTest().query("example.test", 1)
        val p = Packet.build(v4a, v4b, 40000, 53, data)
        assertEquals(0, Packet.fold(Packet.sum(p, 0, 20, 0))) // header checksum verifies
        val u = Packet.parse(p, p.size)!!
        assertEquals(40000, u.srcPort); assertEquals(53, u.dstPort)
        assertArrayEquals(data, u.payload)
        val r = u.reply(byteArrayOf(1, 2, 3))
        val back = Packet.parse(r, r.size)!!
        assertArrayEquals(v4a, back.dst); assertEquals(40000, back.dstPort); assertEquals(53, back.srcPort)
        assertEquals(0, udpCheck(r, 20, v4b, v4a))
    }

    @Test fun ipv6RoundTripAndChecksum() {
        val a = HostsTable.parseIp("fd00:e7c:4057::1")!!
        val b = HostsTable.parseIp("fd00:e7c:4057::2")!!
        val p = Packet.build(a, b, 5353, 53, byteArrayOf(9, 8, 7, 6, 5))
        assertEquals(0, udpCheck(p, 40, a, b))
        val u = Packet.parse(p, p.size)!!
        assertArrayEquals(a, u.src); assertArrayEquals(byteArrayOf(9, 8, 7, 6, 5), u.payload)
    }

    @Test fun ignoresNonUdpAndFragments() {
        val p = Packet.build(v4a, v4b, 1, 53, byteArrayOf(1))
        p[9] = 6
        assertNull(Packet.parse(p, p.size))
        val f = Packet.build(v4a, v4b, 1, 53, byteArrayOf(1))
        f[6] = 0x20 // MF
        assertNull(Packet.parse(f, f.size))
    }

    /** Verifies the UDP checksum: the full sum including the pseudo-header folds to 0. */
    private fun udpCheck(p: ByteArray, ip: Int, src: ByteArray, dst: ByteArray): Int {
        val ulen = p.size - ip
        var s = Packet.sum(src, 0, src.size, 0)
        s = Packet.sum(dst, 0, dst.size, s) + 17 + ulen
        return Packet.fold(Packet.sum(p, ip, ulen, s))
    }
}
