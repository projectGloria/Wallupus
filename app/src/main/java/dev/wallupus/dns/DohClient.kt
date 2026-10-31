package dev.wallupus.dns

import dev.wallupus.Upstream
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * RFC 8484 DNS-over-HTTPS. Blocking API on purpose: it's called from a bounded IO dispatcher,
 * and OkHttp's sync calls are simpler to reason about than callback plumbing.
 *
 * This app is excluded from its own VPN (addDisallowedApplication), so these sockets go straight
 * out the real network — no loop back into the tunnel.
 */
class DohClient(upstreams: List<Upstream>, base: OkHttpClient) {
    private val endpoints = upstreams.map { it.url.toHttpUrl() }

    // Literal IPs: InetAddress.getByName does no DNS lookup for these. This is what keeps the
    // resolver hostname itself out of your ISP's plain-text DNS logs.
    private val bootstrap: Map<String, List<InetAddress>> = upstreams.associate { u ->
        u.url.toHttpUrl().host to u.bootstrap.map { InetAddress.getByName(it) }
    }

    private val client = base.newBuilder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                bootstrap[hostname] ?: Dns.SYSTEM.lookup(hostname)
        })
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS) // the client's own DNS timeout is ~5 s; answer before it gives up
        .build()

    // Sticky failover: once the primary fails we stay on the one that works instead of paying a
    // timeout on every query. Racy by design — the worst case is one extra attempt.
    @Volatile private var preferred = 0

    /** Returns the raw DNS response, or null if every upstream failed. */
    fun resolve(query: ByteArray): ByteArray? {
        for (k in endpoints.indices) {
            val i = (preferred + k) % endpoints.size
            val resp = post(i, query)
            if (resp != null) {
                preferred = i
                return resp
            }
        }
        return null
    }

    private fun post(index: Int, query: ByteArray): ByteArray? {
        val request = Request.Builder()
            .url(endpoints[index])
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
