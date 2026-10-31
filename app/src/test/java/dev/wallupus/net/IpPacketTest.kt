package dev.wallupus.net

import dev.wallupus.TestPackets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpPacketTest {

    /** RFC 1071 property: re-summing a packet that includes its checksum yields 0 after fold. */
    private fun verifies(b: ByteArray, off: Int, len: Int, initial: Long = 0) =
        IpPacket.fold(IpPacket.onesSum(b, off, len, initial)) == 0

    @Test fun ipv4HeaderChecksumKnownVector() {
        // Classic textbook header (Wikipedia "IPv4 header checksum" example), checksum = 0xB861.
        val h = TestPackets.hex("4500 0073 0000 4000 4011 0000 c0a8 0001 c0a8 00c7")
        assertEquals(0xB861, IpPacket.fold(IpPacket.onesSum(h, 0, 20, 0)))
    }

    @Test fun v4RoundTrip() {
        val payload = TestPackets.dnsQuery("example.com")
        val pkt = TestPackets.ipv4Udp("10.111.222.2", "10.111.222.1", 40000, 53, payload)
        assert(verifies(pkt, 0, 20)) { "IPv4 header checksum invalid" }

        val udp = IpPacket.parseUdp(pkt, pkt.size)!!
        assertEquals(4, udp.ipVersion)
        assertEquals(40000, udp.srcPort)
        assertEquals(53, udp.dstPort)
        assertArrayEquals(payload, udp.payload)
        assertArrayEquals(TestPackets.ip4("10.111.222.1"), udp.dstAddr)

        val reply = IpPacket.buildUdpReply(udp, byteArrayOf(1, 2, 3)) // odd length on purpose
        val r = IpPacket.parseUdp(reply, reply.size)!!
        assertEquals(53, r.srcPort)
        assertEquals(40000, r.dstPort)
        assertArrayEquals(udp.dstAddr, r.srcAddr)
        // UDP checksum must verify over pseudo-header + segment.
        assertEquals(0, IpPacket.udpChecksum(reply, 20, reply.size - 20, r.srcAddr, r.dstAddr))
    }

    @Test fun v6RoundTrip() {
        val src = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 2 }
        val dst = ByteArray(16).also { it[0] = 0xfd.toByte(); it[15] = 1 }
        val payload = TestPackets.dnsQuery("example.org", qtype = 28)
        val pkt = TestPackets.ipv6Udp(src, dst, 5353, 53, payload)
        val udp = IpPacket.parseUdp(pkt, pkt.size)!!
        assertEquals(6, udp.ipVersion)
        assertArrayEquals(payload, udp.payload)
        assertEquals(0, IpPacket.udpChecksum(pkt, 40, pkt.size - 40, src, dst))
    }

    @Test fun rejectsFragmentsTcpAndTruncation() {
        val pkt = TestPackets.ipv4Udp("10.0.0.2", "10.0.0.1", 1, 53, TestPackets.dnsQuery("a.com"))

        val frag = pkt.copyOf().also { it[6] = 0x20 } // MF set
        assertNull(IpPacket.parseUdp(frag, frag.size))

        val tcp = pkt.copyOf().also { it[9] = 6 }
        assertNull(IpPacket.parseUdp(tcp, tcp.size))

        assertNull(IpPacket.parseUdp(pkt, 25)) // shorter than the declared total length
        assertNull(IpPacket.parseUdp(ByteArray(0), 0))
    }

    @Test fun buildsTcpResetForSynAndNeverRepliesToRst() {
        // IPv4 TCP SYN from 10.111.222.2:54321 to 8.8.8.8:853, seq = 0x1000.
        val syn = ByteArray(40).also {
            it[0] = 0x45
            put16(it, 2, 40)
            it[8] = 64
            it[9] = 6 // TCP
            TestPackets.ip4("10.111.222.2").copyInto(it, 12)
            TestPackets.ip4("8.8.8.8").copyInto(it, 16)
            put16(it, 20, 54321)
            put16(it, 22, 853)
            put32(it, 24, 0x1000L)
            it[32] = 0x50 // 20-byte TCP header
            it[33] = 0x02 // SYN
        }
        val rst = IpPacket.buildTcpReset(syn, syn.size)!!
        assertEquals(40, rst.size)
        assert(verifies(rst, 0, 20)) { "IPv4 header checksum invalid on TCP RST" }
        assertEquals(853, u16(rst, 20))
        assertEquals(54321, u16(rst, 22))
        assertEquals(0L, u32(rst, 24))
        assertEquals(0x1001L, u32(rst, 28)) // ack = seq + 1 for SYN
        assertEquals(0x14, rst[33].u8())     // RST | ACK
        assertEquals(0, IpPacket.l4Checksum(6, rst, 20, 20, TestPackets.ip4("8.8.8.8"), TestPackets.ip4("10.111.222.2")))

        // Never reply RST to an incoming RST.
        assertNull(IpPacket.buildTcpReset(rst, rst.size))
    }
}
