package dev.wallupus.ui

import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wallupus.WallupusApp
import dev.wallupus.dns.Resolvers
import dev.wallupus.filter.UpdateWorker
import dev.wallupus.vpn.Modes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.wallupus.vpn.WallupusVpnService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // System "Connection request" dialog. Registered at construction time, as the
    // Activity Result API requires (registering later throws once the activity is STARTED).
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Denied, or another VPN holds the slot: drop the spinner at once rather than on timeout,
        // and persist nothing: the switches only change once the tunnel can actually come up.
        val next = pending
        pending = null
        if (it.resultCode == RESULT_OK) {
            next?.let(Modes::set)
            startVpn()
        } else {
            connecting = false
        }
    }

    // The switch state waiting on the consent dialog. Plain field: it only bridges request() and
    // the result callback, nothing renders it. Saved across recreation (two booleans, nothing
    // sensitive): rotating under the dialog would otherwise start the tunnel in the old mode.
    private var pending: Modes.Mode? = null

    // Tap-to-running gap (consent dialog + establish()). Snapshot state on the Activity, not in
    // composition, because toggle() and the consent callback live out here.
    private var connecting by mutableStateOf(false)

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pending?.let { outState.putBooleanArray(PENDING, booleanArrayOf(it.blocking, it.dns)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restored before the consent result is delivered (that waits for onStart).
        // (The spinner isn't restored: LaunchedEffect(running) clears it on first composition.)
        savedInstanceState?.getBooleanArray(PENDING)?.let { pending = Modes.Mode(it[0], it[1]) }
        // Explicit dark bar style: the default (auto) follows the *system* theme, which would put
        // dark icons on our always-dark UI whenever the phone is in light mode.
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        // The Recents thumbnail is a screenshot written to disk by the system; from the Log tab
        // that's a picture of your query history. Blank card instead; your own screenshots still work.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        setContent {
            WallupusTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val running by WallupusVpnService.running.collectAsStateWithLifecycle()
                val mode by Modes.state.collectAsStateWithLifecycle()
                val resolverId by Resolvers.selected.collectAsStateWithLifecycle()
                // Service came up (or went down): the pending state is over either way.
                LaunchedEffect(running) { connecting = false }
                // Safety net: establish() can fail and stopSelf() without running ever changing.
                LaunchedEffect(connecting) {
                    if (connecting) {
                        delay(CONNECT_TIMEOUT_MS)
                        connecting = false
                    }
                }
                // Same condition as BlockState.On: the tunnel also runs for DNS alone, and a crimson
                // glow behind a grey "not blocking" button would contradict it.
                val aura by animateFloatAsState(if (running && mode.blocking) 1f else 0f, tween(900), label = "aura")
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Ink)
                        // Soft light pooled behind the button: graphite when idle, crimson while
                        // blocking. Read in draw, so the 900 ms fade never recomposes the screen.
                        .drawBehind {
                            val c = Offset(size.width / 2, size.height * 0.34f)
                            val r = size.width * 1.05f
                            val tint = lerp(Palette.GraphiteHigh, Palette.CrimsonDeep, aura)
                            drawCircle(
                                Brush.radialGradient(listOf(tint.copy(alpha = 0.55f - 0.3f * aura), Color.Transparent), c, r),
                                radius = r,
                                center = c,
                            )
                        }
                        .systemBarsPadding()
                        // Edge-to-edge windows don't resize for the keyboard: without this it
                        // covers the lower fields of Settings → Custom.
                        .imePadding(),
                ) {
                    // Scaffold's Surface used to provide this. Without it, any Text with no explicit
                    // colour falls back to LocalContentColor's default: black on our black.
                    CompositionLocalProvider(LocalContentColor provides Palette.TextHi) {
                        TopBar(
                            tab,
                            onTab = { tab = it },
                            dnsIcon = Resolvers.preset(resolverId)?.icon,
                            dnsActive = running && mode.dns,
                            onDnsToggle = { request(dns = it) },
                        )
                        when (tab) {
                            0 -> HomeScreen(connecting, onToggle = { request(blocking = it) }, onDnsToggle = { request(dns = it) })
                            1 -> LogScreen()
                            2 -> ListsScreen(onUpdateNow = { UpdateWorker.runNow(this@MainActivity) })
                            else -> SettingsScreen(onDnsToggle = { request(dns = it) })
                        }
                    }
                }
            }
        }
    }

    /**
     * Flips one switch (null = leave as is). Blocking and DNS are independent; the tunnel runs
     * while either is on, so this decides whether it has to start, keep running, or stop.
     */
    private fun request(blocking: Boolean? = null, dns: Boolean? = null) {
        val current = Modes.state.value
        val next = Modes.Mode(blocking ?: current.blocking, dns ?: current.dns)
        if (!next.any) {
            Modes.set(next)
            WallupusVpnService.stop(this)
            return
        }
        if (WallupusVpnService.running.value) {
            // Tunnel already up: the packet loop picks the new mode up on its next query.
            Modes.set(next)
            if (next.blocking && !current.blocking) {
                // DNS-only until now, so the list may never have been fetched or be days old.
                WallupusApp.appScope.launch(Dispatchers.IO) { UpdateWorker.refreshIfStale(applicationContext) }
            }
            return
        }
        // Only the block button has a "connecting" state; a DNS-only start shouldn't spin it.
        connecting = next.blocking
        pending = next
        // prepare() returns null if consent was already granted; otherwise an Intent for the
        // system dialog. It also returns non-null if another VPN app is currently active.
        val consent = VpnService.prepare(this)
        if (consent == null) {
            Modes.set(next)
            pending = null
            startVpn()
        } else {
            vpnConsent.launch(consent)
        }
    }

    private fun startVpn() {
        // PacketLoop.run() triggers an immediate blocklist update on first run or when stale,
        // which also covers Always-On VPN starts that never pass through MainActivity.
        WallupusVpnService.start(this)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000L
        const val PENDING = "pendingMode"
    }
}
