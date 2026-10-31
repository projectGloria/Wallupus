package dev.wallupus.dns

import dev.wallupus.Upstream
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * RFC 8484 DNS-over-HTTPS. Blocking API on purpose: it's called from a bounded IO dispatcher,
 * and OkHttp's sync calls are simpler to reason about than callback plumbing.
 *
 * This app is excluded from its own VPN (addDisallowedApplication), so these sockets go straight
 * out the real network — no loop back into the tunnel.
 *
 * The upstream list is swappable at runtime ([use]) for the Settings screen, with the same
 * pattern as the filter matcher: build an immutable [Route], publish it with one volatile write.
 * The packet loop never takes a lock to read it.
 */
class DohClient(upstreams: List<Upstream>, base: OkHttpClient) {
    private class Route(val endpoints: List<HttpUrl>) {
        // Sticky failover: once the primary fails we stay on the one that works instead of paying
        // a timeout on every query. Racy by design — the worst case is one extra attempt.
        @Volatile var preferred = 0
    }

    // Literal IPs: InetAddress.getByName does no DNS lookup for these. This is what keeps the
    // resolver hostname itself out of your ISP's plain-text DNS logs. Only ever grows (an entry is
    // replaced, never dropped), so a request still in flight to the previous resolver
    // when you switch keeps its bootstrap instead of falling through to Dns.SYSTEM.
    private val bootstrap = ConcurrentHashMap<String, List<InetAddress>>()

    @Volatile private var route = route(upstreams)

    /** Bumped on every [use]; the packet loop watches it to drop answers cached from the old resolver. */
    @Volatile var generation = 0
        private set

    private val client = base.newBuilder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                // No bootstrap (a custom URL without IPs): one lookup on the underlying network's
                // resolver. This app is outside the VPN, so it can't recurse into ourselves.
                bootstrap[hostname] ?: Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS) // the client's own DNS timeout is ~5 s; answer before it gives up
        .build()

    private fun route(upstreams: List<Upstream>): Route {
        require(upstreams.isNotEmpty())
        upstreams.forEach { u ->
            // An upstream without IPs keeps any already known for its host (e.g. a custom URL
            // that matches a preset), which beats falling back to a plain-text lookup.
            if (u.bootstrap.isNotEmpty()) {
                bootstrap[u.url.toHttpUrl().host] = u.bootstrap.map { InetAddress.getByName(it) } // literals only, see isIpLiteral
            }
        }
        return Route(upstreams.map { it.url.toHttpUrl() })
    }

    /** Switches every query from now on to [upstreams]; in-flight ones finish on the old route. */
    fun use(upstreams: List<Upstream>) {
        route = route(upstreams)
        generation++ // single writer (the Settings screen, main thread): no need for an atomic
        // Idle keep-alive connections to the old resolver would otherwise linger for ~5 minutes.
        client.connectionPool.evictAll()
    }

    /** Returns the raw DNS response, or null if every upstream failed. */
    fun resolve(query: ByteArray): ByteArray? = resolve(route, query)

    private fun resolve(r: Route, query: ByteArray): ByteArray? {
        for (k in r.endpoints.indices) {
            val i = (r.preferred + k) % r.endpoints.size
            val resp = post(r.endpoints[i], query)
            if (resp != null) {
                r.preferred = i
                return resp
            }
        }
        return null
    }

    /**
     * Round-trip time in ms of a test query through [upstreams], or null if it didn't get a valid
     * answer. Never touches the live route. Blocking, call off main. The first query pays for
     * TLS setup; the second (over the pooled connection) is what every real query will cost.
     */
    fun probe(upstreams: List<Upstream>): Long? {
        val r = route(upstreams)
        val query = probeQuery()
        val q = DnsMessage.parseQuery(query) ?: return null
        fun ok() = resolve(r, query)?.let { DnsMessage.isResponseFor(it, q) } == true
        if (!ok()) return null
        val start = System.nanoTime()
        if (!ok()) return null
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun post(url: HttpUrl, query: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", MEDIA)
            .post(query.toRequestBody(MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return null
                val bytes = r.body?.bytes() ?: return null
                bytes.takeIf { it.size in 12..65_535 } // DNS header minimum .. UDP payload maximum
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val MEDIA = "application/dns-message"
        val MEDIA_TYPE = MEDIA.toMediaType()
    }
}
