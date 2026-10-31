package dev.wallupus

import dev.wallupus.net.IpPacket

/** Hand-built packets so tests don't depend on a network stack. */
object TestPackets {
    fun hex(s: String): ByteArray =
        s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Standard query, RD=1, one question, with an EDNS OPT record like real stubs send. */
    fun dnsQuery(name: String, qtype: Int = 1, id: Int = 0x1234, withEdns: Boolean = true): ByteArray {
        val out = ArrayList<Byte>()
        fun u16(v: Int) { out += (v ushr 8).toByte(); out += v.toByte() }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(if (withEdns) 1 else 0)
        for (label in name.split('.')) {
            out += label.length.toByte()
            label.forEach { out += it.code.toByte() }
        }
        out += 0
        u16(qtype); u16(1)
        if (withEdns) { out += 0; u16(41); u16(1232); u16(0); u16(0); u16(0) }
        return out.toByteArray()
    }

    /** Wraps a UDP payload in IPv4 with valid checksums (built via our own reply builder, then
     *  verified independently in IpPacketTest). */
    fun ipv4Udp(src: String, dst: String, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val fake = dev.wallupus.net.UdpPacket(
            4, ip4(dst), ip4(src), dport, sport, ByteArray(0),
        )
        return IpPacket.buildUdpReply(fake, payload)
    }

    fun ipv6Udp(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
        val fake = dev.wallupus.net.UdpPacket(6, dst, src, dport, sport, ByteArray(0))
        return IpPacket.buildUdpReply(fake, payload)
    }

    fun ip4(s: String) = s.split('.').map { it.toInt().toByte() }.toByteArray()
}
