package dev.wallupus.dns

import dev.wallupus.net.put16
import dev.wallupus.net.u16
import dev.wallupus.net.u8

/** The single question of a standard query. [questionEnd] = offset just past QTYPE/QCLASS. */
data class DnsQuestion(val id: Int, val name: String, val qtype: Int, val questionEnd: Int)

/**
 * Just enough DNS wire format (RFC 1035) to read a question, synthesize a refusal, and read TTLs
 * from upstream answers for caching. We never rewrite upstream answers — they're forwarded as-is.
 */
object DnsMessage {
    private const val HEADER = 12
    private const val RCODE_NOERROR = 0
    private const val RCODE_SERVFAIL = 2
    private const val RCODE_NXDOMAIN = 3
    private const val MAX_CACHE_TTL = 3600L
    private const val NEGATIVE_CACHE_TTL = 60L

    /**
     * Parses a client query. Returns null for anything we shouldn't answer; the caller then drops
     * the packet and the client retries/times out, which is the correct outcome for garbage.
     */
    fun parseQuery(msg: ByteArray): DnsQuestion? {
        if (msg.size < HEADER) return null
        val flags = u16(msg, 2)
        if (flags and 0x8000 != 0) return null          // QR=1: that's a response, not a query
        if ((flags ushr 11) and 0x0F != 0) return null  // opcode != QUERY (NOTIFY, UPDATE…)
        if (u16(msg, 4) != 1) return null               // QDCOUNT must be 1 (every real stub)

        val name = StringBuilder()
        var i = HEADER
        var wireLen = 0
        while (true) {
            if (i >= msg.size) return null
            val labelLen = msg[i].u8()
            if (labelLen == 0) { i++; break }
            // Compression pointers are legal in theory but no stub resolver puts one in a
            // question. Refusing them outright removes the classic pointer-loop DoS entirely.
            if (labelLen and 0xC0 != 0) return null
            if (i + 1 + labelLen > msg.size) return null
            wireLen += labelLen + 1
            if (wireLen > 255) return null // RFC 1035 name length limit
            if (name.isNotEmpty()) name.append('.')
            for (k in i + 1..i + labelLen) {
                val c = msg[k].u8()
                // A literal '.' inside a label would make "a.b" (one label) look like two labels
                // to our matcher. Not a real-world name — refuse it.
                if (c == '.'.code || c < 0x21 || c > 0x7E) return null
                name.append(c.toChar().lowercaseChar())
            }
            i += 1 + labelLen
        }
        if (i + 4 > msg.size) return null
        return DnsQuestion(id = u16(msg, 0), name = name.toString(), qtype = u16(msg, i), questionEnd = i + 4)
    }

    /**
     * NXDOMAIN for a blocked name, any query type.
     *
     * Why not 0.0.0.0 like Pi-hole's default? Two reasons: Firefox only honours its "disable DoH"
     * canary (use-application-dns.net) on NXDOMAIN/empty answers, and NXDOMAIN needs no per-type
     * answer records, so this function can't produce a malformed RR. No SOA is attached, so per
     * RFC 2308 clients won't negative-cache it; unblocking a domain takes effect immediately.
     */
    fun blockedResponse(query: ByteArray, q: DnsQuestion): ByteArray = refusal(query, q, RCODE_NXDOMAIN)

    /** Upstream failed or we're overloaded: tell the client to try again instead of hanging. */
    fun servfail(query: ByteArray, q: DnsQuestion): ByteArray = refusal(query, q, RCODE_SERVFAIL)

    private fun refusal(query: ByteArray, q: DnsQuestion, rcode: Int): ByteArray {
        // Header + the original question; everything after it (e.g. the EDNS OPT record in the
        // additional section) is dropped, so ARCOUNT goes to 0.
        val out = query.copyOf(q.questionEnd)
        val rd = u16(query, 2) and 0x0100 // echo the client's "recursion desired" bit
        // QR=1 | opcode 0 | RD as asked | RA=1 | rcode
        put16(out, 2, 0x8000 or rd or 0x0080 or rcode)
        put16(out, 6, 0)  // ANCOUNT
        put16(out, 8, 0)  // NSCOUNT
        put16(out, 10, 0) // ARCOUNT
        return out
    }

    /** Copy of [msg] with its transaction ID replaced. */
    fun withId(msg: ByteArray, id: Int): ByteArray = msg.copyOf().also { put16(it, 0, id) }

    /** Cheap sanity check on an upstream answer before we hand it to a client. */
    fun isResponseFor(msg: ByteArray, q: DnsQuestion): Boolean {
        if (msg.size < HEADER) return false
        if (u16(msg, 2) and 0x8000 == 0) return false
        val name = readQuestionName(msg) ?: return false
        return name.equals(q.name, ignoreCase = true)
    }

    private fun readQuestionName(msg: ByteArray): String? {
        if (u16(msg, 4) < 1) return null
        val sb = StringBuilder()
        var i = HEADER
        while (i < msg.size) {
            val l = msg[i].u8()
            if (l == 0) return sb.toString()
            if (l and 0xC0 != 0 || i + 1 + l > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (k in i + 1..i + l) sb.append(msg[k].u8().toChar())
            i += 1 + l
        }
        return null
    }

    /**
     * How long an upstream answer may be cached, in seconds, or null = don't cache.
     * Uses the minimum TTL across the answer section; negative answers get a short fixed TTL.
     */
    fun cacheTtlSeconds(msg: ByteArray): Long? {
        if (msg.size < HEADER) return null
        val flags = u16(msg, 2)
        if (flags and 0x0200 != 0) return null // TC: truncated, never cache a partial answer
        val rcode = flags and 0x0F
        if (rcode != RCODE_NOERROR && rcode != RCODE_NXDOMAIN) return null

        var i = HEADER
        repeat(u16(msg, 4)) {
            i = skipName(msg, i) ?: return null
            i += 4 // QTYPE + QCLASS
        }
        val answers = u16(msg, 6)
        if (answers == 0) return NEGATIVE_CACHE_TTL

        var minTtl = MAX_CACHE_TTL
        repeat(answers) {
            i = skipName(msg, i) ?: return null
            if (i + 10 > msg.size) return null
            // TYPE(2) CLASS(2) TTL(4) RDLENGTH(2)
            val ttl = (u16(msg, i + 4).toLong() shl 16) or u16(msg, i + 6).toLong()
            val rdLen = u16(msg, i + 8)
            i += 10 + rdLen
            if (i > msg.size) return null
            if (ttl < minTtl) minTtl = ttl
        }
        return minTtl.takeIf { it > 0 }
    }

    /**
     * Returns the offset just past a (possibly compressed) name. A pointer always ends a name in
     * the stream, so we never need to follow it — which also means no pointer-loop risk.
     */
    private fun skipName(msg: ByteArray, start: Int): Int? {
        var i = start
        while (i < msg.size) {
            val l = msg[i].u8()
            when {
                l == 0 -> return i + 1
                l and 0xC0 == 0xC0 -> return if (i + 2 <= msg.size) i + 2 else null
                l and 0xC0 != 0 -> return null // 0x40/0x80 prefixes are reserved
                else -> i += 1 + l
            }
        }
        return null
    }
}
