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

    @Test fun isResponseForRejectsMismatchedType() {
        val aaaa = DnsMessage.parseQuery(TestPackets.dnsQuery("a.com", qtype = 28))!!
        val aQuery = TestPackets.dnsQuery("a.com", qtype = 1)
        val aAnswer = DnsMessage.blockedResponse(aQuery, DnsMessage.parseQuery(aQuery)!!)
        assertFalse(DnsMessage.isResponseFor(aAnswer, aaaa))
    }

    @Test fun ttlWithTopBitSetMeansDoNotCache() {
        // RFC 2181 §8: TTL 0x80000000 is treated as 0, not clamped to the one-hour maximum.
        val r = TestPackets.hex(
            "1234 8180 0001 0001 0000 0000" +
                "07 6578616d706c65 03 636f6d 00 0001 0001" +
                "c00c 0001 0001 80000000 0004 5db8d822",
        )
        assertNull(DnsMessage.cacheTtlSeconds(r))
    }

    @Test fun cacheHitEchoesTheNewQuestionCasing() {
        val cache = DnsCache(clock = { 0L })
        val first = TestPackets.dnsQuery("Example.COM", id = 0x1111)
        val q1 = DnsMessage.parseQuery(first)!!
        cache.put(q1, DnsMessage.blockedResponse(first, q1))

        // A 0x20-randomizing client asks again with different casing and checks the echo.
        val second = TestPackets.dnsQuery("eXaMpLe.cOm", id = 0x2222)
        val q2 = DnsMessage.parseQuery(second)!!
        val hit = DnsMessage.forQuery(cache.get(q2)!!, second, q2)
        assertTrue(hit.copyOfRange(12, q2.questionEnd).contentEquals(second.copyOfRange(12, q2.questionEnd)))
        assertEquals(0x22, hit[0].toInt()); assertEquals(0x22, hit[1].toInt())
        assertTrue(DnsMessage.isResponseFor(hit, q2))
    }

    @Test fun dnssecQueriesBypassTheCache() {
        val plain = TestPackets.dnsQuery("x.com")
        val withDo = TestPackets.dnsQuery("x.com", dnssecOk = true)
        val withCd = TestPackets.dnsQuery("x.com").also { it[3] = (it[3].toInt() or 0x10).toByte() }
        val qPlain = DnsMessage.parseQuery(plain)!!
        val qDo = DnsMessage.parseQuery(withDo)!!
        val qCd = DnsMessage.parseQuery(withCd)!!
        assertFalse(qPlain.dnssec)
        assertTrue(qDo.dnssec)
        assertTrue(qCd.dnssec)

        val cache = DnsCache(clock = { 0L })
        cache.put(qDo, DnsMessage.blockedResponse(withDo, qDo))
        assertNull(cache.get(qPlain)) // a DO answer is never stored, so it can't leak to plain clients
        cache.put(qPlain, DnsMessage.blockedResponse(plain, qPlain))
        assertNull(cache.get(qDo)) // plain answer has no RRSIGs: never serve it to a DO client
        assertTrue(cache.get(qPlain) != null)
    }

    @Test fun answerInFlightAcrossClearIsNotCached() {
        val cache = DnsCache(clock = { 0L })
        val query = TestPackets.dnsQuery("x.com")
        val q = DnsMessage.parseQuery(query)!!
        val sent = cache.epoch // query goes upstream to the old resolver…
        cache.clear()          // …the resolver switches before it answers
        cache.put(q, DnsMessage.blockedResponse(query, q), sent)
        assertNull(cache.get(q))
        cache.put(q, DnsMessage.blockedResponse(query, q), cache.epoch)
        assertTrue(cache.get(q) != null)
    }

    @Test fun probeNeedsAUsableAnswerAndResponsesNeedOneQuestion() {
        val answer = TestPackets.hex(
            "0000 8180 0001 0001 0000 0000" +
                "07 6578616d706c65 03 636f6d 00 0001 0001" +
                "c00c 0001 0001 0000012c 0004 5db8d822",
        )
        val q = DnsMessage.parseQuery(probeQuery())!!
        assertTrue(DnsMessage.isResponseFor(answer, q))
        assertTrue(DnsMessage.isUsableAnswer(answer))

        val servfail = answer.copyOf().also { it[3] = 0x82.toByte() }
        assertTrue(DnsMessage.isResponseFor(servfail, q)) // matches the question…
        assertFalse(DnsMessage.isUsableAnswer(servfail))   // …but must not pass a resolver test
        assertFalse(DnsMessage.isUsableAnswer(answer.copyOf().also { it[2] = 0x83.toByte() })) // TC
        assertFalse(DnsMessage.isUsableAnswer(answer.copyOf().also { it[7] = 0 }))             // no answers

        assertFalse(DnsMessage.isResponseFor(answer.copyOf().also { it[5] = 2 }, q))                // QDCOUNT=2
        assertFalse(DnsMessage.isResponseFor(answer.copyOf().also { it[2] = 0x89.toByte() }, q))    // opcode 1
    }

    @Test fun nameLengthLimitCountsTheRootLabel() {
        // RFC 1035 §2.3.4: 255 wire octets including the root byte = 253 presentation chars.
        fun name(last: Int) = listOf("a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(last)).joinToString(".")
        assertEquals(253, name(61).length)
        assertTrue(DnsMessage.parseQuery(TestPackets.dnsQuery(name(61))) != null)
        assertNull(DnsMessage.parseQuery(TestPackets.dnsQuery(name(62))))
    }
}
