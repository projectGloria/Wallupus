package dev.wallupus.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import dev.wallupus.dns.DnsCache
import dev.wallupus.dns.DnsMessage
import dev.wallupus.dns.DohClient
import dev.wallupus.filter.Filters
import dev.wallupus.log.QueryLog
import dev.wallupus.net.IpPacket
import dev.wallupus.net.UdpPacket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore

/**
 * Reads DNS packets off the tun interface, answers blocked names instantly, forwards the rest
 * over DoH, writes replies back into the tunnel.
 *
 * Threading: one dedicated reader thread (blocking poll), DoH calls on a bounded IO pool, and a
 * single synchronized writer. The reader never blocks on the network, so a slow upstream can't
 * stall blocked-domain answers.
 */
class PacketLoop(tun: ParcelFileDescriptor, private val doh: DohClient) {
    private val tunFd: FileDescriptor = tun.fileDescriptor
    private val output = FileOutputStream(tunFd)

    // Self-pipe: stop() writes a byte here to wake poll(). Closing the tun fd from another
    // thread does NOT reliably unblock a read on Linux, so we never rely on that.
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor

    init {
        val pipe = Os.pipe()
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(MAX_PARALLEL))

    // Back-pressure: beyond this many outstanding upstream queries we answer SERVFAIL instead of
    // queueing. Bounds memory when the network is dead and every app is retrying in a loop.
    private val inflight = Semaphore(MAX_INFLIGHT)
    private val cache = DnsCache()
    private val thread = Thread(::run, "wallupus-tun")

    fun start() = thread.start()

    fun stop() {
        try {
            Os.write(wakeWrite, byteArrayOf(1), 0, 1)
        } catch (_: ErrnoException) {
        }
        thread.join(2_000)
        scope.cancel()
        try { Os.close(wakeRead) } catch (_: ErrnoException) {}
        try { Os.close(wakeWrite) } catch (_: ErrnoException) {}
    }

    private fun run() {
        Filters.ensureLoaded() // never start answering with an empty matcher if a list exists
        val buf = ByteArray(WallupusVpnService.MTU)
        val pollIn = OsConstants.POLLIN.toShort()
        val fds = arrayOf(
            StructPollfd().apply { fd = tunFd; events = pollIn },
            StructPollfd().apply { fd = wakeRead; events = pollIn },
        )
        val fatal = OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL

        while (true) {
            try {
                Os.poll(fds, -1)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                Log.w(TAG, "poll failed", e)
                return
            }
            if (fds[1].revents.toInt() != 0) return // stop() requested
            val rev = fds[0].revents.toInt()
            if (rev and fatal != 0) return // tun torn down underneath us (e.g. VPN revoked)
            if (rev and OsConstants.POLLIN == 0) continue

            val n = try {
                Os.read(tunFd, buf, 0, buf.size)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                Log.w(TAG, "tun read failed", e)
                return
            }
            if (n <= 0) continue
            try {
                handle(buf, n)
            } catch (e: RuntimeException) {
                // One malformed packet must never take down DNS for the whole phone.
                Log.w(TAG, "dropping packet", e)
            }
        }
    }

    private fun handle(buf: ByteArray, len: Int) {
        val udp = IpPacket.parseUdp(buf, len) ?: return
        if (udp.dstPort != 53) return
        val q = DnsMessage.parseQuery(udp.payload) ?: return

        // Matcher before cache: a domain blocked by a fresh list update is blocked immediately,
        // even if an older allowed answer is still cached.
        val blocked = Filters.matcher.get().isBlocked(q.name)
        QueryLog.record(q.name, q.qtype, blocked)
        if (blocked) {
            reply(udp, DnsMessage.blockedResponse(udp.payload, q))
            return
        }

        cache.get(q)?.let {
            reply(udp, DnsMessage.forQuery(it, udp.payload, q))
            return
        }

        if (!inflight.tryAcquire()) {
            reply(udp, DnsMessage.servfail(udp.payload, q))
            return
        }
        scope.launch {
            try {
                // RFC 8484 §4.1: send ID 0 so HTTP caches can share answers; restore it after.
                val resp = doh.resolve(DnsMessage.withId(udp.payload, 0))
                val answer = if (resp != null && DnsMessage.isResponseFor(resp, q)) {
                    cache.put(q, resp)
                    DnsMessage.withId(resp, q.id)
                } else {
                    DnsMessage.servfail(udp.payload, q)
                }
                reply(udp, answer)
            } finally {
                inflight.release()
            }
        }
    }

    private fun reply(req: UdpPacket, dnsPayload: ByteArray) {
        val packet = IpPacket.buildUdpReply(req, dnsPayload)
        try {
            // One write() per packet on a tun fd = one packet; must not interleave across threads.
            synchronized(output) { output.write(packet) }
        } catch (_: IOException) {
            // Tunnel closed mid-flight during shutdown; the reply has nowhere to go anyway.
        }
    }

    private companion object {
        const val TAG = "Wallupus"
        const val MAX_PARALLEL = 16
        const val MAX_INFLIGHT = 64
    }
}
