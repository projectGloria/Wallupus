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
    private const val PROTO_UDP = 17
    private const val IPV4_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
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

    /** Checksum field must be zero in [b] when this is called. */
    fun udpChecksum(b: ByteArray, off: Int, len: Int, src: ByteArray, dst: ByteArray): Int {
        var sum = onesSum(src, 0, src.size, 0)
        sum = onesSum(dst, 0, dst.size, sum)
        // Pseudo-header tail: protocol + UDP length. For IPv6 these are 32-bit fields, but the
        // high bytes are zero, so adding them as 16-bit words gives the same one's-complement sum.
        sum += PROTO_UDP + len
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
