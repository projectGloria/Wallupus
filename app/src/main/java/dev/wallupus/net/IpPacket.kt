package dev.wallupus.net

// Pure JVM: no android.* imports, so everything here is unit-testable on a desktop JVM.

/** The only slice of an IP packet we care about: one UDP datagram and its endpoints. */
class UdpPacket(
    val ipVersion: Int,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
)

/**
 * Minimal IPv4/IPv6 + UDP codec for packets arriving on the tun interface.
 *
 * We only ever route two fake DNS addresses into the tunnel, so anything that isn't a plain,
 * unfragmented UDP datagram is dropped rather than handled. Being strict here is the security
 * boundary: every byte comes from another app on the phone and must be treated as hostile.
 */
object IpPacket {
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
    private const val TCP_HEADER = 20
    private const val TCP_FIN = 0x01
    private const val TCP_SYN = 0x02
    private const val TCP_RST = 0x04
    private const val TCP_ACK = 0x10
    private const val TTL = 64

    fun parseUdp(buf: ByteArray, len: Int): UdpPacket? {
        if (len < 1 || len > buf.size) return null
        return when (buf[0].toInt() ushr 4) {
            4 -> parseV4(buf, len)
            6 -> parseV6(buf, len)
            else -> null
        }
    }

    private fun parseV4(b: ByteArray, len: Int): UdpPacket? {
        if (len < IPV4_HEADER) return null
        val ihl = (b[0].toInt() and 0x0F) * 4 // header length incl. options, in bytes
        if (ihl < IPV4_HEADER || len < ihl + UDP_HEADER) return null
        if (b[9].u8() != PROTO_UDP) return null
        // MF flag (0x2000) or a non-zero fragment offset → fragment. A DNS query that needs
        // fragmenting is either broken or an attack; reassembly is not worth the code.
        if (u16(b, 6) and 0x3FFF != 0) return null
        val totalLen = u16(b, 2)
        if (totalLen > len || totalLen < ihl + UDP_HEADER) return null
        return parseUdpHeader(4, b, ihl, totalLen, b.copyOfRange(12, 16), b.copyOfRange(16, 20))
    }

    private fun parseV6(b: ByteArray, len: Int): UdpPacket? {
        if (len < IPV6_HEADER + UDP_HEADER) return null
        // Next header must be UDP directly. The kernel's own resolver traffic never carries
        // extension headers, so refusing them costs nothing and avoids a chain-walking parser.
        if (b[6].u8() != PROTO_UDP) return null
        val end = IPV6_HEADER + u16(b, 4)
        if (end > len) return null
        return parseUdpHeader(6, b, IPV6_HEADER, end, b.copyOfRange(8, 24), b.copyOfRange(24, 40))
    }

    private fun parseUdpHeader(
        version: Int, b: ByteArray, off: Int, ipEnd: Int, src: ByteArray, dst: ByteArray,
    ): UdpPacket? {
        val udpLen = u16(b, off + 4)
        if (udpLen < UDP_HEADER || off + udpLen > ipEnd) return null
        return UdpPacket(
            ipVersion = version,
            srcAddr = src,
            dstAddr = dst,
            srcPort = u16(b, off),
            dstPort = u16(b, off + 2),
            // Copy out: the caller reuses its read buffer for the next packet.
            payload = b.copyOfRange(off + UDP_HEADER, off + udpLen),
        )
    }

    /**
     * Wraps [payload] in a reply to [req]: addresses and ports swapped, fresh headers,
     * correct checksums. The kernel silently drops packets with bad checksums, which would look
     * like "DNS randomly times out" — the most annoying class of bug to chase on a phone.
     */
    fun buildUdpReply(req: UdpPacket, payload: ByteArray): ByteArray {
        val udpLen = UDP_HEADER + payload.size
        val ipHeader = if (req.ipVersion == 4) IPV4_HEADER else IPV6_HEADER
        val out = ByteArray(ipHeader + udpLen)

        if (req.ipVersion == 4) {
            out[0] = 0x45 // version 4, IHL 5 (no options)
            put16(out, 2, out.size)
            out[6] = 0x40 // DF: we never fragment, the tun MTU is large enough
            out[8] = TTL.toByte()
            out[9] = PROTO_UDP.toByte()
            req.dstAddr.copyInto(out, 12) // reply comes FROM the fake DNS server
            req.srcAddr.copyInto(out, 16)
            put16(out, 10, fold(onesSum(out, 0, IPV4_HEADER, 0)))
        } else {
            out[0] = 0x60 // version 6, traffic class/flow label zero
            put16(out, 4, udpLen)
            out[6] = PROTO_UDP.toByte()
            out[7] = TTL.toByte() // hop limit
            req.dstAddr.copyInto(out, 8)
            req.srcAddr.copyInto(out, 24)
        }

        // UDP header
        put16(out, ipHeader, req.dstPort)
        put16(out, ipHeader + 2, req.srcPort)
        put16(out, ipHeader + 4, udpLen)
        payload.copyInto(out, ipHeader + UDP_HEADER)

        // Checksum over pseudo-header + segment. Optional on IPv4, mandatory on IPv6; we always
        // set it. A computed 0 must be sent as 0xFFFF because 0 means "no checksum".
        val sum = udpChecksum(out, ipHeader, udpLen, req.dstAddr, req.srcAddr)
        put16(out, ipHeader + 6, if (sum == 0) 0xFFFF else sum)
        return out
    }

    /**
     * Synthesizes a TCP RST (RFC 793 §3.4) for a non-RST TCP segment arriving on the tun.
     *
     * Android's opportunistic DoT probes port 853, and browsers with hardcoded-IP DoH probe port
     * 443 on public resolver IPs. Answering with an immediate RST fails the connect() in <1 ms
     * (ECONNREFUSED) so they fall back to UDP/53 without waiting for SYN retransmission timeouts.
     */
    fun buildTcpReset(buf: ByteArray, len: Int): ByteArray? {
        if (len < 1 || len > buf.size) return null
        val version = buf[0].toInt() ushr 4
        val off: Int
        val totalLen: Int
        val src: ByteArray
        val dst: ByteArray
        when (version) {
            4 -> {
                if (len < IPV4_HEADER) return null
                off = (buf[0].toInt() and 0x0F) * 4
                if (off < IPV4_HEADER || len < off + TCP_HEADER) return null
                if (buf[9].u8() != PROTO_TCP) return null
                if (u16(buf, 6) and 0x3FFF != 0) return null
                totalLen = u16(buf, 2)
                if (totalLen > len || totalLen < off + TCP_HEADER) return null
                src = buf.copyOfRange(12, 16)
                dst = buf.copyOfRange(16, 20)
            }
            6 -> {
                if (len < IPV6_HEADER + TCP_HEADER) return null
                if (buf[6].u8() != PROTO_TCP) return null
                off = IPV6_HEADER
                totalLen = IPV6_HEADER + u16(buf, 4)
                if (totalLen > len || totalLen < IPV6_HEADER + TCP_HEADER) return null
                src = buf.copyOfRange(8, 24)
                dst = buf.copyOfRange(24, 40)
            }
            else -> return null
        }

        val dataOff = (buf[off + 12].u8() ushr 4) * 4
        if (dataOff < TCP_HEADER || off + dataOff > totalLen) return null
        val flags = buf[off + 13].u8()
        if (flags and TCP_RST != 0) return null // never reply RST to an RST

        val srcPort = u16(buf, off)
        val dstPort = u16(buf, off + 2)
        val inSeq = u32(buf, off + 4)
        val inAck = u32(buf, off + 8)
        val segLen = (totalLen - off - dataOff) +
            (if (flags and TCP_SYN != 0) 1 else 0) +
            (if (flags and TCP_FIN != 0) 1 else 0)

        val outSeq: Long
        val outAck: Long
        val outFlags: Int
        if (flags and TCP_ACK != 0) {
            outSeq = inAck
            outAck = 0L
            outFlags = TCP_RST
        } else {
            outSeq = 0L
            outAck = (inSeq + segLen) and 0xFFFF_FFFFL
            outFlags = TCP_RST or TCP_ACK
        }

        val ipHeader = if (version == 4) IPV4_HEADER else IPV6_HEADER
        val out = ByteArray(ipHeader + TCP_HEADER)
        if (version == 4) {
            out[0] = 0x45
            put16(out, 2, out.size)
            out[6] = 0x40
            out[8] = TTL.toByte()
            out[9] = PROTO_TCP.toByte()
            dst.copyInto(out, 12)
            src.copyInto(out, 16)
            put16(out, 10, fold(onesSum(out, 0, IPV4_HEADER, 0)))
        } else {
            out[0] = 0x60
            put16(out, 4, TCP_HEADER)
            out[6] = PROTO_TCP.toByte()
            out[7] = TTL.toByte()
            dst.copyInto(out, 8)
            src.copyInto(out, 24)
        }

        put16(out, ipHeader, dstPort)
        put16(out, ipHeader + 2, srcPort)
        put32(out, ipHeader + 4, outSeq)
        put32(out, ipHeader + 8, outAck)
        out[ipHeader + 12] = 0x50 // 5 words (20 bytes), no TCP options
        out[ipHeader + 13] = outFlags.toByte()
        put16(out, ipHeader + 16, l4Checksum(PROTO_TCP, out, ipHeader, TCP_HEADER, dst, src))
        return out
    }

    /** Checksum field must be zero in [b] when this is called. */
    fun udpChecksum(b: ByteArray, off: Int, len: Int, src: ByteArray, dst: ByteArray): Int =
        l4Checksum(PROTO_UDP, b, off, len, src, dst)

    internal fun l4Checksum(proto: Int, b: ByteArray, off: Int, len: Int, src: ByteArray, dst: ByteArray): Int {
        var sum = onesSum(src, 0, src.size, 0)
        sum = onesSum(dst, 0, dst.size, sum)
        // Pseudo-header tail: protocol + segment length. For IPv6 these are 32-bit fields, but
        // the high bytes are zero, so adding them as 16-bit words gives the same one's-complement sum.
        sum += proto + len
        sum = onesSum(b, off, len, sum)
        return fold(sum)
    }

    internal fun onesSum(b: ByteArray, off: Int, len: Int, initial: Long): Long {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += (b[i].u8() shl 8) or b[i + 1].u8()
            i += 2
        }
        if (i < end) sum += b[i].u8() shl 8 // odd length: pad with a zero byte
        return sum
    }

    internal fun fold(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }
}

internal fun Byte.u8(): Int = toInt() and 0xFF

internal fun u16(b: ByteArray, off: Int): Int = (b[off].u8() shl 8) or b[off + 1].u8()

internal fun put16(b: ByteArray, off: Int, v: Int) {
    b[off] = (v ushr 8).toByte()
    b[off + 1] = v.toByte()
}

internal fun u32(b: ByteArray, off: Int): Long =
    (u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()

internal fun put32(b: ByteArray, off: Int, v: Long) {
    put16(b, off, (v ushr 16).toInt())
    put16(b, off + 2, v.toInt())
}
