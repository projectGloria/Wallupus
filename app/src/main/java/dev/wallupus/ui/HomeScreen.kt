package dev.wallupus.ui

import android.content.Context
import android.net.ConnectivityManager
import android.text.format.DateUtils
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wallupus.WallupusApp
import dev.wallupus.filter.Filters
import dev.wallupus.log.QueryLog
import dev.wallupus.update.AppUpdater
import dev.wallupus.vpn.WallupusVpnService
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample

@OptIn(FlowPreview::class)
@Composable
fun HomeScreen(connecting: Boolean, onToggle: (Boolean) -> Unit, onUpdateNow: () -> Unit) {
    val ctx = LocalContext.current
    // collectAsStateWithLifecycle stops collecting when the app is backgrounded — no point
    // recomposing a screen nobody can see.
    val running by WallupusVpnService.running.collectAsStateWithLifecycle()
    val status by Filters.status.collectAsStateWithLifecycle()

    // Sample the log's version counter at 2 Hz: at 50 queries/s, recomposing per query would
    // burn more CPU drawing numbers than resolving DNS.
    var counts by remember { mutableStateOf(readCounts()) }
    // Keyed on running too: stopping moves the session counts into prefs and zeroes them
    // without bumping version, so without a restart here the card would show stale numbers.
    LaunchedEffect(running) {
        counts = readCounts()
        QueryLog.version.sample(500).collect {
            counts = readCounts()
        }
    }

    // Re-checked whenever the toggle changes; cheap binder calls, no need for a callback.
    val strictPrivateDns = remember(running) { strictPrivateDnsHost(ctx) }

    // Derived, not stored: `running` is the service's truth, `connecting` only fills the gap
    // before it flips. Once running is true, any stale `connecting` is simply irrelevant.
    val state = when {
        running -> BlockState.On
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
                StatusChip(state)
                BlockButton(state, counts.sessionBlocked, onToggle)
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

        item { BlocklistPanel(status, counts, onUpdateNow) }

        val rules = status.userAllow.map { it to true } + status.userBlock.map { it to false }
        if (rules.isNotEmpty()) {
            item { RulesPanel(rules.sortedBy { it.first }) }
        }

        item { AppPanel() }
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

@Composable
private fun BlocklistPanel(status: Filters.Status, counts: Counts, onUpdateNow: () -> Unit) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BLOCKLIST", Modifier.weight(1f), style = Overline, color = Palette.TextLo)
            Text(
                if (status.lastUpdateMillis == 0L) "Never updated"
                else "Updated " + DateUtils.getRelativeTimeSpanString(status.lastUpdateMillis),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextLo,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "%,d".format(status.listDomains),
                style = MaterialTheme.typography.headlineMedium.merge(Tabular),
                fontWeight = FontWeight.SemiBold,
                color = Palette.TextHi,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "domains",
                Modifier.padding(bottom = 5.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextLo,
            )
        }
        status.lastError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Ember)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "All-time: %,d of %,d blocked".format(counts.lifetimeBlocked, counts.lifetimeTotal),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextLo,
            )
            BusyButton("Update now", "Updating", busy = status.updating, onClick = onUpdateNow)
        }
    }
}

/**
 * Version line plus manual update buttons. Nothing here touches the network until tapped; the
 * state lives in [AppUpdater] (app scope), so a download survives leaving the screen.
 */
@Composable
private fun AppPanel() {
    val ctx = LocalContext.current
    // Package info doesn't change while we're running (an update restarts the process).
    val version = remember { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" }
    val supported = remember { AppUpdater.supported(ctx) }
    val state by AppUpdater.state.collectAsStateWithLifecycle()

    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("APP", Modifier.weight(1f), style = Overline, color = Palette.TextLo)
            Text("Version $version", style = MaterialTheme.typography.bodySmall.merge(Tabular), color = Palette.TextLo)
        }
        if (!supported) return@Panel
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (line, color) = when (val s = state) {
                AppUpdater.State.Idle, AppUpdater.State.Checking -> "" to Palette.TextLo
                AppUpdater.State.UpToDate -> "You're on the latest version" to Palette.TextLo
                is AppUpdater.State.Available -> "Version ${s.release.versionName} is available" to Palette.TextHi
                is AppUpdater.State.Downloading -> "Downloading ${s.release.versionName}" to Palette.TextLo
                is AppUpdater.State.Ready -> "Downloaded ${s.release.versionName}" to Palette.TextHi
                is AppUpdater.State.Failed -> s.message to Palette.Ember
            }
            Text(line, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodySmall, color = color)

            // A known release (available, downloaded, or a failed install) gets "Install update";
            // everything else checks again.
            val release = when (val s = state) {
                is AppUpdater.State.Available -> s.release
                is AppUpdater.State.Downloading -> s.release
                is AppUpdater.State.Ready -> s.release
                is AppUpdater.State.Failed -> s.release
                else -> null
            }
            if (release != null) {
                BusyButton(
                    "Install update",
                    "Downloading",
                    busy = state is AppUpdater.State.Downloading,
                    onClick = { AppUpdater.install(release) },
                )
            } else {
                BusyButton(
                    "Check for update",
                    "Checking",
                    busy = state is AppUpdater.State.Checking,
                    onClick = AppUpdater::check,
                )
            }
        }
    }
}

/** Tonal pill that swaps its label for a spinner while [busy], and ignores taps meanwhile. */
@Composable
private fun BusyButton(label: String, busyLabel: String, busy: Boolean, onClick: () -> Unit) {
    val tonal = MaterialTheme.colorScheme.primaryContainer
    val onTonal = MaterialTheme.colorScheme.onPrimaryContainer
    FilledTonalButton(
        onClick = onClick,
        enabled = !busy,
        // Keep the button's colour while busy: a greyed-out pill with a spinner in it reads as
        // "broken", not "working".
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = tonal,
            contentColor = onTonal,
            disabledContainerColor = tonal.copy(alpha = 0.7f),
            disabledContentColor = onTonal,
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
    ) {
        AnimatedContent(busy, label = label) { working ->
            if (working) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), color = onTonal, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(busyLabel)
                }
            } else {
                Text(label)
            }
        }
    }
}

@Composable
private fun RulesPanel(rules: List<Pair<String, Boolean>>) {
    Panel {
        Text("YOUR RULES", style = Overline, color = Palette.TextLo)
        Spacer(Modifier.height(4.dp))
        rules.forEach { (domain, allow) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(if (allow) Palette.Bone else Palette.Crimson, CircleShape))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(domain, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = Palette.TextHi)
                    Text(if (allow) "Always allow" else "Always block", style = MaterialTheme.typography.bodySmall, color = Palette.TextLo)
                }
                TextButton(onClick = { Filters.setUserRule(domain, null) }) { Text("Remove", color = Palette.TextLo) }
            }
        }
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
