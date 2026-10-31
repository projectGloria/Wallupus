package dev.wallupus.dns

import android.net.ConnectivityManager
import dev.wallupus.net.u16
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom

/**
 * Plain UDP/53 to the DNS servers of the real network (Wi-Fi/cellular): what the phone would use
 * without us. Used when the DoH switch is off but blocking is on.
 *
 * This app is excluded from its own VPN, so [ConnectivityManager.getActiveNetwork] is the
 * underlying network here, not the tunnel, and nothing loops back into ourselves.
 *
 * Known limit: a truncated (TC) answer can't be retried over TCP, because TCP to the fake
 * resolver gets a RST. Clients advertise large EDNS buffers, so that's rare in practice.
 */
class PlainDnsClient(private val connectivity: ConnectivityManager) {
    private val random = SecureRandom()

    /** Raw response for [query], or null if every server failed. Blocking, call off main. */
    fun resolve(query: ByteArray): ByteArray? {
        // Re-read per query (one binder call on the IO pool): Wi-Fi ↔ cellular hand-overs change
        // the servers, and there's no state to keep in sync.
        val network = connectivity.activeNetwork ?: return null
        val servers = connectivity.getLinkProperties(network)?.dnsServers.orEmpty()
        if (servers.isEmpty()) return null
        // Fresh random ID per upstream query: we're on plain UDP now, so off-path spoofing has to
        // guess it. The caller restores the client's own ID on the reply.
        val id = random.nextInt(0x10000)
        val out = DnsMessage.withId(query, id)
        for (server in servers) {
            try {
                DatagramSocket().use { socket ->
                    network.bindSocket(socket)
                    socket.soTimeout = TIMEOUT_MS
                    val to = InetSocketAddress(server, 53)
                    socket.send(DatagramPacket(out, out.size, to))
                    val buf = ByteArray(MAX_UDP)
                    while (true) {
                        val p = DatagramPacket(buf, buf.size)
                        socket.receive(p) // SocketTimeoutException ends this server's turn
                        // Drop stray packets from anyone but the server, or with the wrong ID.
                        if (p.address != server || p.length < 12 || u16(buf, 0) != id) continue
                        return buf.copyOf(p.length)
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            }
        }
        return null
    }

    private companion object {
        const val TIMEOUT_MS = 2_000
        const val MAX_UDP = 65_535
    }
}
