package dev.wallupus.ui

import android.net.VpnService
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
import dev.wallupus.filter.UpdateWorker
import dev.wallupus.vpn.WallupusVpnService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    // System "Connection request" dialog. Registered at construction time, as the
    // Activity Result API requires (registering later throws once the activity is STARTED).
    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Denied, or another VPN holds the slot: drop the spinner at once rather than on timeout.
        if (it.resultCode == RESULT_OK) startVpn() else connecting = false
    }

    // Tap-to-running gap (consent dialog + establish()). Snapshot state on the Activity, not in
    // composition, because toggle() and the consent callback live out here.
    private var connecting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Explicit dark bar style: the default (auto) follows the *system* theme, which would put
        // dark icons on our always-dark UI whenever the phone is in light mode.
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        setContent {
            WallupusTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val running by WallupusVpnService.running.collectAsStateWithLifecycle()
                // Service came up (or went down): the pending state is over either way.
                LaunchedEffect(running) { connecting = false }
                // Safety net: establish() can fail and stopSelf() without running ever changing.
                LaunchedEffect(connecting) {
                    if (connecting) {
                        delay(CONNECT_TIMEOUT_MS)
                        connecting = false
                    }
                }
                val aura by animateFloatAsState(if (running) 1f else 0f, tween(900), label = "aura")
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
                        .systemBarsPadding(),
                ) {
                    // Scaffold's Surface used to provide this. Without it, any Text with no explicit
                    // colour falls back to LocalContentColor's default: black on our black.
                    CompositionLocalProvider(LocalContentColor provides Palette.TextHi) {
                        TopBar(tab, onTab = { tab = it })
                        when (tab) {
                            0 -> HomeScreen(connecting, onToggle = ::toggle, onUpdateNow = { UpdateWorker.runNow(this@MainActivity) })
                            else -> LogScreen()
                        }
                    }
                }
            }
        }
    }

    private fun toggle(on: Boolean) {
        if (!on) {
            WallupusVpnService.stop(this)
            return
        }
        connecting = true
        // prepare() returns null if consent was already granted; otherwise an Intent for the
        // system dialog. It also returns non-null if another VPN app is currently active.
        val consent = VpnService.prepare(this)
        if (consent == null) startVpn() else vpnConsent.launch(consent)
    }

    private fun startVpn() {
        // PacketLoop.run() triggers an immediate blocklist update on first run or when stale,
        // which also covers Always-On VPN starts that never pass through MainActivity.
        WallupusVpnService.start(this)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}
