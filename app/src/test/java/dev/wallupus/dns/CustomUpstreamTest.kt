package dev.wallupus.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomUpstreamTest {
    @Test fun ipLiteralsAcceptedHostnamesRejected() {
        listOf("9.9.9.9", "0.0.0.0", "255.255.255.255", "2620:fe::fe", "::1", "2a07:e340::2").forEach {
            assertTrue(it, isIpLiteral(it))
        }
        listOf("", "dns.quad9.net", "1.2.3", "1.2.3.4.5", "256.1.1.1", "010.0.0.1", "1.2.3.-4",
            "fe80::1%wlan0", "2620:fe::fe::1", "gggg::1", "localhost",
            "١.١.١.١", "۹.۹.۹.۹", "١::1").forEach { // Arabic/Persian digits
            assertFalse(it, isIpLiteral(it))
        }
    }

    @Test fun customUpstreamParsesUrlAndBootstrap() {
        val u = customUpstream("  https://dns.example/dns-query ", "9.9.9.9, 2620:fe::fe 9.9.9.9")
        assertEquals("https://dns.example/dns-query", u.url)
        assertEquals(listOf("9.9.9.9", "2620:fe::fe"), u.bootstrap)
        assertEquals(emptyList<String>(), customUpstream("https://dns.example/q", "").bootstrap)
    }

    @Test fun customUpstreamRejectsUnsafeInput() {
        listOf(
            "http://dns.example/dns-query" to "",
            "dns.example/dns-query" to "",
            "https://user:pw@dns.example/q" to "",
            "https://dns.example/q" to "dns.example",
        ).forEach { (url, boot) ->
            val e = runCatching { customUpstream(url, boot) }.exceptionOrNull()
            assertTrue("$url / $boot", e is IllegalArgumentException && !e.message.isNullOrEmpty())
        }
    }

    @Test fun probeQueryIsAValidQuestion() {
        val q = DnsMessage.parseQuery(probeQuery())
        assertNotNull(q)
        assertEquals(PROBE_NAME, q!!.name)
        assertEquals(1, q.qtype)
        assertEquals(0, q.id)
    }
}
