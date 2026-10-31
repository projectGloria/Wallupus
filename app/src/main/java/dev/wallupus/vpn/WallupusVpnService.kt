package dev.wallupus.vpn

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.wallupus.WallupusApp
import dev.wallupus.log.QueryLog
import dev.wallupus.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A "VPN" that only captures DNS.
 *
 * We advertise a fake resolver address and route *only that address* into the tunnel. Every
 * other packet on the phone uses the real network as if we didn't exist — so there's no
 * userspace TCP stack, no throughput cost, and nothing to break for non-DNS traffic.
 */
class WallupusVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var loop: PacketLoop? = null
    private var flusher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        // Covers: our own START, the system's always-on start (action android.net.VpnService),
        // and a STICKY restart after process death (null intent).
        startTunnel()
        return START_STICKY
    }

    @Synchronized
    private fun startTunnel() {
        if (tun != null) return
        val pfd = try {
            Builder()
                .setSession("Wallupus") // shown in the VPN dialog and Settings → VPN
                .addAddress(TUN_V4, 32)
                .addAddress(TUN_V6, 128)
                .addDnsServer(DNS_V4)
                .addDnsServer(DNS_V6)
                // The whole trick: only the fake resolver addresses are routed to us.
                .addRoute(DNS_V4, 32)
                .addRoute(DNS_V6, 128)
                // Our own DoH/list traffic must bypass the tunnel, or we'd resolve via ourselves.
                .addDisallowedApplication(packageName)
                .setMtu(MTU)
                // Don't make Android think the network is metered just because a VPN is up.
                .setMetered(false)
                .setBlocking(true)
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .establish() // null = user revoked consent (VpnService.prepare() needed again)
        } catch (e: Exception) {
            // IllegalStateException (not prepared) / SecurityException / bad config.
            Log.w(TAG, "establish failed", e)
            null
        }
        if (pfd == null) {
            stopSelf()
            return
        }
        tun = pfd
        loop = PacketLoop(pfd, WallupusApp.doh).also { it.start() }
        flusher = WallupusApp.appScope.launch {
            while (true) {
                delay(LIFETIME_FLUSH_MS)
                flushLifetime()
            }
        }
        _running.value = true
    }

    // Synchronized with shutdown(): addLifetime is a read-modify-write on prefs.
    @Synchronized
    private fun flushLifetime() {
        val (blocked, total) = QueryLog.takeUnsaved()
        if (total > 0) WallupusApp.prefs.addLifetime(blocked, total)
    }

    @Synchronized
    private fun shutdown() {
        flusher?.cancel()
        flusher = null
        loop?.stop()
        loop = null
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        if (_running.value) {
            flushLifetime()
            QueryLog.resetSession()
        }
        _running.value = false
    }

    /** Another VPN app took over, or the user disconnected from Settings. */
    override fun onRevoke() {
        shutdown()
        super.onRevoke() // calls stopSelf()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Wallupus"
        private const val ACTION_STOP = "dev.wallupus.STOP"

        // Unique Local / RFC 1918 space that's very unlikely to collide with a real LAN.
        private const val TUN_V4 = "10.111.222.2"
        private const val DNS_V4 = "10.111.222.1"
        private const val TUN_V6 = "fd00:5e:1::2"
        private const val DNS_V6 = "fd00:5e:1::1"

        // The tun is local-only, so a large MTU costs nothing and means big DNS answers
        // (DNSSEC, long TXT/HTTPS records) never get truncated → no TCP fallback to implement.
        const val MTU = 16_384

        // At most a minute of counts lost if the process is killed; one small prefs write a minute.
        private const val LIFETIME_FLUSH_MS = 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Caller must have already obtained consent via VpnService.prepare(). */
        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, WallupusVpnService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, WallupusVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
