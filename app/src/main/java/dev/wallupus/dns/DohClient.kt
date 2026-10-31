package dev.wallupus.dns

import dev.wallupus.Upstream
import dev.wallupus.WallupusApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private class Route(val endpoints: List<HttpUrl>, val client: OkHttpClient) {
        // Sticky failover: once the primary fails we stay on the one that works instead of paying
        // a timeout on every query. Racy by design — the worst case is one extra attempt.
        @Volatile var preferred = 0
    }

    // Literal IPs: InetAddress.getByName does no DNS lookup for these. This is what keeps the
    // resolver hostname itself out of your ISP's plain-text DNS logs. Only ever grows (an entry is
    // replaced, never dropped), so a request still in flight to the previous resolver
    // when you switch keeps its bootstrap instead of falling through to Dns.SYSTEM.
    // Written only by [use]: a probe never touches it (see [probe]).
    private val bootstrap = ConcurrentHashMap<String, List<InetAddress>>()

    private val client = base.newBuilder()
        .dns(lookup(emptyMap()))
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS) // the client's own DNS timeout is ~5 s; answer before it gives up
        // A DoH endpoint has no business redirecting: following one would resolve the new host
        // via Dns.SYSTEM in plain text, and turn a 301/302'd POST into a body-less GET.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile private var route = install(upstreams)

    /** Bumped on every [use]; the packet loop watches it to drop answers cached from the old resolver. */
    @Volatile var generation = 0
        private set

    /** [extra] first (a probe's own IPs), then the live map, then one plain lookup as a last resort. */
    private fun lookup(extra: Map<String, List<InetAddress>>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            // No bootstrap (a custom URL without IPs): one lookup on the underlying network's
            // resolver. This app is outside the VPN, so it can't recurse into ourselves.
            extra[hostname] ?: bootstrap[hostname] ?: Dns.SYSTEM.lookup(hostname)
    }

    private fun ips(upstreams: List<Upstream>): Map<String, List<InetAddress>> =
        // An upstream without IPs keeps any already known for its host (e.g. a custom URL that
        // matches a preset), which beats falling back to a plain-text lookup.
        upstreams.filter { it.bootstrap.isNotEmpty() }.associate { u ->
            u.url.toHttpUrl().host to u.bootstrap.map { InetAddress.getByName(it) } // literals only, see isIpLiteral
        }

    private fun install(upstreams: List<Upstream>): Route {
        require(upstreams.isNotEmpty())
        bootstrap.putAll(ips(upstreams))
        return Route(upstreams.map { it.url.toHttpUrl() }, client)
    }

    /** Switches every query from now on to [upstreams]; in-flight ones finish on the old route. */
    fun use(upstreams: List<Upstream>) {
        route = install(upstreams)
        generation++ // single writer (the Settings screen, main thread): no need for an atomic
        // Idle keep-alive connections to the old resolver would otherwise linger for ~5 minutes.
        // Off main: closing a pooled TLS socket writes close_notify, which is network I/O, and
        // StrictMode turns that into NetworkOnMainThreadException (OkHttp's closeQuietly
        // rethrows RuntimeExceptions). The probe that preceded every switch always leaves one.
        WallupusApp.appScope.launch(Dispatchers.IO) { client.connectionPool.evictAll() }
    }

    /** Returns the raw DNS response, or null if every upstream failed. */
    fun resolve(query: ByteArray): ByteArray? = resolve(route, query)

    private fun resolve(r: Route, query: ByteArray): ByteArray? {
        for (k in r.endpoints.indices) {
            val i = (r.preferred + k) % r.endpoints.size
            val resp = post(r.client, r.endpoints[i], query)
            if (resp != null) {
                r.preferred = i
                return resp
            }
        }
        return null
    }

    /**
     * Round-trip time in ms of a test query through [upstreams], or null if it didn't get a valid
     * answer. Never touches the live route or its bootstrap IPs: a typo'd IP for the active
     * resolver's host must not break it, and a failed test must leave nothing behind. Blocking,
     * call off main. The first query pays for TLS setup; the second (over the pooled connection)
     * is what every real query will cost.
     */
    fun probe(upstreams: List<Upstream>): Long? {
        require(upstreams.isNotEmpty())
        // Own Dns = different OkHttp Address, so the live client can't pick up a probe connection
        // (and vice versa). Shares the pool and dispatcher; use() evicts what the probe leaves.
        val c = client.newBuilder().dns(lookup(ips(upstreams))).build()
        val r = Route(upstreams.map { it.url.toHttpUrl() }, c)
        val query = probeQuery()
        val q = DnsMessage.parseQuery(query) ?: return null
        // A useful answer, not just a matching one: a SERVFAIL means the server is up but can't
        // resolve, and switching to it would take every app's DNS down with it.
        fun ok() = resolve(r, query)?.let { DnsMessage.isResponseFor(it, q) && DnsMessage.isUsableAnswer(it) } == true
        if (!ok()) return null
        val start = System.nanoTime()
        if (!ok()) return null
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun post(client: OkHttpClient, url: HttpUrl, query: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", MEDIA)
            .post(query.toRequestBody(MEDIA_TYPE))
            .build()
        return try {
            client.newCall(request).execute().use { r ->
                if (r.code != 200) return null
                val body = r.body ?: return null
                // Bounded read: ResponseBody.bytes() buffers whatever the server streams, and 16
                // workers doing that against a URL serving a big file is an OOM. request(n) pulls
                // at most n bytes and is true only if that many exist, i.e. the body is too big.
                if (body.contentLength() > MAX_DNS) return null
                val source = body.source()
                if (source.request(MAX_DNS + 1L)) return null
                source.readByteArray().takeIf { it.size >= 12 } // at least a DNS header
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val MEDIA = "application/dns-message"
        val MEDIA_TYPE = MEDIA.toMediaType()
        // What fits in one IPv4 packet (65,535 − 20 IP − 8 UDP) on the way back through the tun;
        // anything bigger would wrap the 16-bit length fields in IpPacket.buildUdpReply.
        const val MAX_DNS = 65_507
    }
}
