package dev.wallupus.dns

import dev.wallupus.TestPackets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsMessageTest {

    @Test fun parsesQuestionAndLowercases() {
        val q = DnsMessage.parseQuery(TestPackets.dnsQuery("Ads.Example.COM", qtype = 28))!!
        assertEquals("ads.example.com", q.name)
        assertEquals(28, q.qtype)
        assertEquals(0x1234, q.id)
    }

    @Test fun rejectsResponsesPointersAndJunk() {
        val resp = TestPackets.dnsQuery("a.com").also { it[2] = 0x81.toByte() } // QR=1
        assertNull(DnsMessage.parseQuery(resp))

        // Question name replaced by a compression pointer to itself: the classic loop.
        val loop = TestPackets.dnsQuery("a.com").copyOf(12) + TestPackets.hex("c00c 0001 0001")
        assertNull(DnsMessage.parseQuery(loop))

        // Label length that runs past the end of the packet.
        assertNull(DnsMessage.parseQuery(TestPackets.dnsQuery("a.com").copyOf(14)))

        // Literal dot inside a label.
        val dotted = TestPackets.dnsQuery("ab.com")
        dotted[13] = '.'.code.toByte()
        assertNull(DnsMessage.parseQuery(dotted))
    }

    @Test fun blockedResponseIsNxdomainEchoingQuestion() {
        val query = TestPackets.dnsQuery("tracker.example")
        val q = DnsMessage.parseQuery(query)!!
        val r = DnsMessage.blockedResponse(query, q)

        assertEquals(q.questionEnd, r.size) // OPT record stripped
        assertEquals(0x12, r[0].toInt()); assertEquals(0x34, r[1].toInt())
        val flags = ((r[2].toInt() and 0xFF) shl 8) or (r[3].toInt() and 0xFF)
        assertEquals(0x8183, flags) // QR, RD, RA, NXDOMAIN
        assertTrue(DnsMessage.isResponseFor(r, q))
        assertEquals(60L, DnsMessage.cacheTtlSeconds(r)) // negative answer → short TTL
    }

    @Test fun cacheTtlUsesMinimumAndHandlesCompression() {
        // Response for example.com A with two answers (TTL 300 and 120), names compressed.
        val r = TestPackets.hex(
            "1234 8180 0001 0002 0000 0000" +
                "07 6578616d706c65 03 636f6d 00 0001 0001" +
                "c00c 0001 0001 0000012c 0004 5db8d822" +
                "c00c 0001 0001 00000078 0004 5db8d823",
        )
        assertEquals(120L, DnsMessage.cacheTtlSeconds(r))

        val truncated = r.copyOf(r.size - 3)
        assertNull(DnsMessage.cacheTtlSeconds(truncated))

        val servfail = r.copyOf().also { it[3] = 0x82.toByte() }
        assertNull(DnsMessage.cacheTtlSeconds(servfail))
    }

    @Test fun isResponseForRejectsMismatchedName() {
        val q = DnsMessage.parseQuery(TestPackets.dnsQuery("a.com"))!!
        val other = DnsMessage.blockedResponse(
            TestPackets.dnsQuery("b.com"),
            DnsMessage.parseQuery(TestPackets.dnsQuery("b.com"))!!,
        )
        assertFalse(DnsMessage.isResponseFor(other, q))
    }

    @Test fun cacheExpiresWithClock() {
        var now = 0L
        val cache = DnsCache(capacity = 2, clock = { now })
        val query = TestPackets.dnsQuery("x.com")
        val q = DnsMessage.parseQuery(query)!!
        cache.put(q, DnsMessage.blockedResponse(query, q)) // 60 s negative TTL
        assertTrue(cache.get(q) != null)
        now += 61_000_000_000L
        assertNull(cache.get(q))
    }
}
