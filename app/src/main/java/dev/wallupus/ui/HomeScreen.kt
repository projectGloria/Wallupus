package dev.wallupus.ui

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wallupus.WallupusApp
import dev.wallupus.dns.Resolvers
import dev.wallupus.vpn.Modes
import dev.wallupus.log.QueryLog
import dev.wallupus.vpn.WallupusVpnService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

@OptIn(FlowPreview::class)
@Composable
fun HomeScreen(connecting: Boolean, onToggle: (Boolean) -> Unit, onDnsToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    // collectAsStateWithLifecycle stops collecting when the app is backgrounded — no point
    // recomposing a screen nobody can see.
    val running by WallupusVpnService.running.collectAsStateWithLifecycle()
    val mode by Modes.state.collectAsStateWithLifecycle()
    val resolverId by Resolvers.selected.collectAsStateWithLifecycle()

    // Sample the log's version counter at 2 Hz: at 50 queries/s, recomposing per query would
    // burn more CPU drawing numbers than resolving DNS. WithLifecycle: a plain LaunchedEffect
    // keeps collecting after ON_STOP (Compose only pauses recomposition, not coroutines).
    val tick by remember { QueryLog.version.sample(500) }.collectAsStateWithLifecycle(QueryLog.version.value)
    // Derived from the tick, not pushed into state. Keyed on running too: stopping moves the
    // session counts into prefs and zeroes them without bumping version.
    val counts = remember(tick, running) { readCounts() }

    // Re-checked whenever the toggle changes; cheap binder calls, no need for a callback.
    val strictPrivateDns = remember(running) { strictPrivateDnsHost(ctx) }

    // Derived, not stored: `running` is the service's truth, `connecting` only fills the gap
    // before it flips. The tunnel can be up for DNS alone, so "on" needs the blocking switch too.
    val state = when {
        running && mode.blocking -> BlockState.On
        connecting -> BlockState.Connecting
        else -> BlockState.Off
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            // fillParentMaxHeight: the button sits centred in the first screenful, and the
            // panels below are a scroll away instead of squeezing it.
            Column(
                Modifier.fillParentMaxHeight(0.5f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                StatusChip(state, dnsOnly = running && mode.dns && !mode.blocking)
                BlockButton(state, counts.sessionBlocked, onToggle)
                DnsLine(resolverId, active = running && mode.dns, onDnsToggle)
            }
        }

        item { StatsPanel(counts) }

        if (strictPrivateDns != null) {
            item {
                Panel(brush = SolidColor(Palette.EmberWash), border = Palette.Ember.copy(alpha = 0.35f)) {
                    Text("PRIVATE DNS BYPASSES WALLUPUS", style = Overline, color = Palette.Ember)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "It's set to \"$strictPrivateDns\", so Android sends lookups there directly. " +
                            "Set Settings → Network → Private DNS to Off or Automatic.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextHi,
                    )
                }
            }
        }

    }
}

@Composable
private fun StatsPanel(counts: Counts) {
    // Per-mille so the rate rolls like the other counters; -1 = nothing to divide yet.
    val rate = if (counts.sessionTotal == 0L) -1L else counts.sessionBlocked * 1000 / counts.sessionTotal
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat("BLOCKED", counts.sessionBlocked, Palette.Crimson, Modifier.weight(1f)) { "%,d".format(it) }
            Hairline()
            Stat("QUERIES", counts.sessionTotal, Palette.TextHi, Modifier.weight(1f)) { "%,d".format(it) }
            Hairline()
            Stat("BLOCK RATE", rate, Palette.TextHi, Modifier.weight(1f)) {
                if (it < 0) "—" else "%.1f%%".format(it / 10f)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "All-time: %,d of %,d blocked".format(counts.lifetimeBlocked, counts.lifetimeTotal),
            Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.TextLo,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Which resolver answers, and a switch for it. Independent of the block button: DNS can run
 * with blocking off, and turning it off sends lookups to the network's own resolver.
 */
@Composable
private fun DnsLine(resolverId: String, active: Boolean, onToggle: (Boolean) -> Unit) {
    val preset = remember(resolverId) { Resolvers.preset(resolverId) }
    Row(
        Modifier
            .padding(top = 14.dp)
            .clip(CircleShape)
            .toggleable(active, role = Role.Switch, onValueChange = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ResolverLogo(preset?.icon, size = 18.dp, muted = !active)
        Spacer(Modifier.width(8.dp))
        Text(
            (preset?.name ?: "Custom DNS") + if (active) " · encrypted" else " · off",
            style = MaterialTheme.typography.bodySmall,
            color = if (active) Palette.TextHi else Palette.TextLo,
        )
    }
}

@Composable
private fun Hairline() = Box(Modifier.width(1.dp).height(40.dp).background(Palette.Line))

/** Session counter that rolls up like an odometer when it grows, and just fades on reset. */
@Composable
private fun Stat(label: String, value: Long, color: Color, modifier: Modifier, format: (Long) -> String) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            value,
            transitionSpec = {
                if (targetState > initialState) {
                    (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                } else {
                    fadeIn() togetherWith fadeOut()
                }
            },
            label = label,
        ) { v ->
            Text(
                format(v),
                style = MaterialTheme.typography.titleLarge.merge(Tabular),
                fontWeight = FontWeight.SemiBold,
                color = color,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = Overline, color = Palette.TextLo)
    }
}

private data class Counts(val sessionBlocked: Long, val sessionTotal: Long, val lifetimeBlocked: Long, val lifetimeTotal: Long)

// Prefs hold only what has been flushed; adding the unflushed part of the session keeps the
// lifetime line moving with the sampled counts instead of jumping once a minute.
private fun readCounts(): Counts {
    val prefs = WallupusApp.prefs
    return Counts(
        QueryLog.sessionBlocked.get(),
        QueryLog.sessionTotal.get(),
        prefs.lifetimeBlocked + QueryLog.unsavedBlocked(),
        prefs.lifetimeTotal + QueryLog.unsavedTotal(),
    )
}

/**
 * "Automatic" Private DNS is fine (it only upgrades to DoT if our fake server speaks it, which it
 * doesn't, then falls back to us). "Strict" mode with a hostname skips the VPN's DNS entirely.
 */
@Suppress("DEPRECATION") // allNetworks: the callback-based replacement is overkill for one check
private fun strictPrivateDnsHost(ctx: Context): String? {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
    return cm.allNetworks.firstNotNullOfOrNull { n ->
        cm.getLinkProperties(n)?.takeIf { it.isPrivateDnsActive }?.privateDnsServerName
    }
}
