package dev.wallupus.filter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterTest {

    // The hand-written check replaced this regex for memory reasons; it must accept exactly the same names.
    @Test fun nameCheckMatchesOriginalRegex() {
        val spec = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?)+$")
        val l63 = "a".repeat(63)
        val cases = listOf(
            "", ".", "a", "a.", ".a", "a..b", "a.b", "a.b.", "_dmarc.x.y", "a-b.c", "-a.b", "a-.b", "a.-b", "a.b-",
            "a.b_", "xn--bcher-kva.example", "A.b", "a b.c", "a*b.c", "1.2.3.4", "$l63.com", "${l63}a.com",
            "a.$l63", "a.${l63}a", "a-.-b", "ab.c-d-e.f", "a.b.c.d.e.f", "é.com", "a/b.c",
        )
        for (c in cases) assertEquals("\"$c\"", spec.matches(c), ListParser.isValidName(c))
    }

    @Test fun parsesAllThreeFormats() {
        assertEquals(Rule.Block("ads.example.com"), ListParser.parseLine("0.0.0.0 ads.example.com"))
        assertEquals(Rule.Block("ads.example.com"), ListParser.parseLine("127.0.0.1\tADS.example.com # comment"))
        assertEquals(Rule.Block("ads.example.com"), ListParser.parseLine("ads.example.com."))
        assertEquals(Rule.Block("ads.example.com"), ListParser.parseLine("||ads.example.com^"))
        assertEquals(Rule.Block("ads.example.com"), ListParser.parseLine("||ads.example.com^\$important"))
        assertEquals(Rule.Allow("ok.example.com"), ListParser.parseLine("@@||ok.example.com^"))
    }

    @Test fun skipsNoiseAndUnsupportedSyntax() {
        listOf(
            "", "# comment", "! adblock comment", "[Adblock Plus 2.0]",
            "127.0.0.1 localhost", "0.0.0.0 0.0.0.0", "::1 ip6-loopback",
            "192.168.1.5 router.lan",      // redirect, not a block
            "||example.com/ads^",          // path rule
            "||ads*.example.com^",         // wildcard
            "example.com##.banner",        // cosmetic
            "com",                         // single label: would block an entire TLD
            "10.0.0.1",                    // IP literal
        ).forEach { assertNull("should skip: '$it'", ListParser.parseLine(it)) }
    }

    @Test fun normalizesUnicodeToPunycode() {
        assertEquals("xn--bcher-kva.example", ListParser.normalize("bücher.example"))
    }

    private fun matcher(
        block: List<String>, allow: List<String> = emptyList(),
        userAllow: Set<String> = emptySet(), userBlock: Set<String> = emptySet(),
    ) = DomainMatcher(
        block.map { DomainHash.of(it) }.sorted().toLongArray(),
        allow.map { DomainHash.of(it) }.sorted().toLongArray(),
        userAllow, userBlock,
    )

    @Test fun blocksSubdomainsButNotParentsOrLookalikes() {
        val m = matcher(listOf("doubleclick.net"))
        assertTrue(m.isBlocked("doubleclick.net"))
        assertTrue(m.isBlocked("ad.g.doubleclick.net"))
        assertFalse(m.isBlocked("net"))
        assertFalse(m.isBlocked("notdoubleclick.net"))
        assertFalse(m.isBlocked("doubleclick.net.evil.com"))
    }

    @Test fun precedenceUserThenListExceptions() {
        val m = matcher(
            block = listOf("example.com", "tracker.io"),
            allow = listOf("cdn.example.com"),
            userAllow = setOf("tracker.io", "parent.org"),
            userBlock = setOf("cdn.example.com", "ads.parent.org"),
        )
        assertFalse("list exception beats list block", matcher(listOf("example.com"), listOf("cdn.example.com")).isBlocked("x.cdn.example.com"))
        assertTrue("user block beats list exception", m.isBlocked("cdn.example.com"))
        assertFalse("user allow beats list block", m.isBlocked("a.tracker.io"))
        assertEquals("tracker.io" to true, m.userRuleFor("a.tracker.io"))

        // More-specific subdomain user rule beats a broader parent user rule, and matches userRuleFor.
        assertFalse(m.isBlocked("parent.org"))
        assertTrue("subdomain user block beats parent user allow", m.isBlocked("ads.parent.org"))
        assertEquals("ads.parent.org" to false, m.userRuleFor("ads.parent.org"))
    }

    @Test fun compileDedupesAndRoundTripsThroughDisk() {
        val text = """
            0.0.0.0 a.com
            0.0.0.0 a.com
            ||b.com^
            @@||ok.b.com^
        """.trimIndent()
        val lists = CompiledLists.build(sequenceOf(text.reader().buffered()))
        assertEquals(2, lists.block.size)
        assertEquals(1, lists.allow.size)

        val f = File.createTempFile("wallupus", ".bin")
        try {
            lists.writeTo(f)
            val back = CompiledLists.readOrEmpty(f)
            assertTrue(back.block.contentEquals(lists.block))
            assertTrue(back.allow.contentEquals(lists.allow))

            f.writeBytes(byteArrayOf(1, 2, 3)) // corrupt → empty, never a crash
            assertEquals(0, CompiledLists.readOrEmpty(f).block.size)
        } finally {
            f.delete()
        }
    }

    @Test fun compiledReaderRejectsLyingHeadersAndUnsortedArrays() {
        val f = File.createTempFile("wallupus", ".bin")
        try {
            CompiledLists(longArrayOf(1, 2, 3), longArrayOf()).writeTo(f)
            val good = f.readBytes()
            assertEquals(3, CompiledLists.readOrEmpty(f).block.size)

            // Header claims 5M block entries in a 40-byte file: rejected before allocating.
            f.writeBytes(good.copyOf().also { it[8] = 0x00; it[9] = 0x4C; it[10] = 0x4B; it[11] = 0x40 })
            assertEquals(0, CompiledLists.readOrEmpty(f).block.size)

            f.writeBytes(good + byteArrayOf(0)) // trailing junk
            assertEquals(0, CompiledLists.readOrEmpty(f).block.size)

            // Out of order: binary search would silently miss, so it counts as corrupt.
            CompiledLists(longArrayOf(3, 1, 2), longArrayOf()).writeTo(f)
            assertEquals(0, CompiledLists.readOrEmpty(f).block.size)
        } finally {
            f.delete()
        }
    }

    // A captive portal / CDN error page served with HTTP 200 must never replace a cached list.
    @Test fun sourceCheckRejectsErrorPagesAndCollapses() {
        val html = """
            <!DOCTYPE html>
            <html><head><title>Sign in to Free_WiFi</title></head>
            <body><p>Please accept the terms to continue.</p></body></html>
        """.trimIndent()
        assertEquals(0, SourceCheck.countRules(html.reader()))
        assertFalse(SourceCheck.accept(0, null))
        assertFalse(SourceCheck.accept(0, 130_000))

        val list = """
            # header
            0.0.0.0 a.com
            ||b.com^
            @@||ok.b.com^
            example.com##.banner
        """.trimIndent()
        assertEquals(3, SourceCheck.countRules(list.reader()))

        assertTrue(SourceCheck.accept(3, null))           // first download: anything real goes
        assertTrue(SourceCheck.accept(120_000, 130_000))  // normal weekly churn
        assertTrue(SourceCheck.accept(32_500, 130_000))   // exactly a quarter is still accepted
        assertFalse(SourceCheck.accept(32_499, 130_000))  // collapsed: keep the cached copy
    }

    // The log's rule dialog must never offer a public suffix like co.uk as a target.
    @Test fun ruleTargetsStopAtRegistrableDomain() {
        assertEquals(listOf("metrics.bbc.co.uk", "bbc.co.uk"), ruleTargets("metrics.bbc.co.uk"))
        assertEquals(listOf("cdn.ads.example.com", "ads.example.com", "example.com"), ruleTargets("cdn.ads.example.com"))
        assertEquals(listOf("example.com"), ruleTargets("example.com"))
        assertEquals(listOf("user.github.io"), ruleTargets("user.github.io"))
        assertEquals(listOf("_dmarc.example.com", "example.com"), ruleTargets("_dmarc.example.com"))
        assertEquals(emptyList<String>(), ruleTargets("co.uk"))
        assertEquals(emptyList<String>(), ruleTargets("wpad"))
        assertEquals(emptyList<String>(), ruleTargets("10.0.0.1"))
    }
}
