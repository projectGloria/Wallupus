package dev.wallupus.vpn

import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import dev.wallupus.WallupusApp
import dev.wallupus.dns.DnsCache
import dev.wallupus.dns.DnsMessage
import dev.wallupus.dns.DohClient
import dev.wallupus.dns.PlainDnsClient
import dev.wallupus.filter.Filters
import dev.wallupus.filter.UpdateWorker
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Semaphore

/**
 * Reads DNS packets off the tun interface, answers blocked names instantly, forwards the rest
 * over DoH, writes replies back into the tunnel.
 *
 * Threading: one dedicated reader thread (blocking poll), DoH calls on a bounded IO pool, and a
 * single synchronized writer. The reader never blocks on the network, so a slow upstream can't
 * stall blocked-domain answers.
 */
class PacketLoop(
    tun: ParcelFileDescriptor,
    private val doh: DohClient,
    private val connectivity: ConnectivityManager,
    // Called on the reader thread if it exits without stop() asking it to. The tun is still
    // established then, so every DNS query on the phone would vanish into it: the owner must
    // tear the tunnel down.
    private val onDied: (PacketLoop) -> Unit,
) {
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
    // Network's own DNS, for when the DoH switch is off. Cheap to hold: no sockets until used.
    private val plain = PlainDnsClient(connectivity)
    // Reader thread only. The DoH flag is part of the cache identity: answers from the network's
    // resolver and from DoH must not be mixed after a switch.
    private var cacheGeneration = doh.generation
    private var cacheDns = Modes.state.value.dns
    private val thread = Thread(::run, "wallupus-tun")
    @Volatile private var stopping = false

    fun start() = thread.start()

    fun stop() {
        stopping = true
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
        try {
            loop()
        } finally {
            if (!stopping) onDied(this)
        }
    }

    private fun loop() {
        Filters.ensureLoaded() // never start answering with an empty matcher if a list exists
        // First run (including via Always-On VPN) or stale (>24h): trigger a background refresh
        // so daily-rotating DGA ad domains don't slip through. DNS-only mode doesn't need a list.
        if (Modes.state.value.blocking) UpdateWorker.refreshIfStale(WallupusApp.instance)
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
        val udp = IpPacket.parseUdp(buf, len)
        if (udp == null) {
            // Reject TCP probes (:853 DoT, :443 hardcoded-IP DoH) with an immediate RST so the
            // OS/browser falls back to UDP/53 at once instead of waiting for SYN timeouts.
            IpPacket.buildTcpReset(buf, len)?.let(::writePacket)
            return
        }
        if (udp.dstPort != 53) return
        val q = DnsMessage.parseQuery(udp.payload) ?: return

        // One volatile read per packet: both switches are seen consistently for this query.
        val mode = Modes.state.value

        // Resolver switched in Settings (or DoH turned on/off): cached answers came from the old
        // one. Only this thread clears; answers still in flight carry the epoch they were sent
        // under, and the cache drops them if a clear() happened in between.
        val gen = doh.generation
        if (gen != cacheGeneration || mode.dns != cacheDns) {
            cache.clear()
            cacheGeneration = gen
            cacheDns = mode.dns
        }

        // Matcher before cache: a domain blocked by a fresh list update is blocked immediately,
        // even if an older allowed answer is still cached. Blocking off = DNS-only mode, where
        // everything is forwarded (and still logged, as allowed).
        val blocked = mode.blocking && Filters.matcher.get().isBlocked(q.name)
        QueryLog.record(q.name, q.qtype, blocked, ownerUid(udp))
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
        val epoch = cache.epoch
        scope.launch {
            try {
                // RFC 8484 §4.1: send ID 0 so HTTP caches can share answers; restore it after.
                // (PlainDnsClient picks its own random ID; the restore below covers both.)
                val zeroed = DnsMessage.withId(udp.payload, 0)
                val resp = if (mode.dns) doh.resolve(zeroed) else plain.resolve(zeroed)
                val answer = if (resp != null && DnsMessage.isResponseFor(resp, q)) {
                    cache.put(q, resp, epoch)
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

    /**
     * Which app sent this query (Android 10+, active VPN app only). It has to run *before* we
     * reply: the asking socket is still open and waiting, so the kernel can still find its owner
     * by 4-tuple. Apps resolving through libc go via netd, which chowns its socket to the asking
     * app, so this is the app's UID rather than the resolver's. One binder call (sub-ms) on the
     * reader thread; DNS rates are tens per second, well inside budget.
     */
    private fun ownerUid(p: UdpPacket): Int = try {
        connectivity.getConnectionOwnerUid(
            OsConstants.IPPROTO_UDP,
            InetSocketAddress(InetAddress.getByAddress(p.srcAddr), p.srcPort),
            InetSocketAddress(InetAddress.getByAddress(p.dstAddr), p.dstPort),
        )
    } catch (_: Exception) {
        // SecurityException if another VPN took over mid-flight; UnknownHostException can't
        // happen for raw 4/16-byte addresses. Neither is worth dropping the query over.
        Process.INVALID_UID
    }

    private fun reply(req: UdpPacket, dnsPayload: ByteArray) {
        writePacket(IpPacket.buildUdpReply(req, dnsPayload))
    }

    private fun writePacket(packet: ByteArray) {
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
