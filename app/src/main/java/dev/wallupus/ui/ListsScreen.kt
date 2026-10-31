package dev.wallupus.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wallupus.filter.Filters
import dev.wallupus.update.AppUpdater

/**
 * Everything you manage rather than glance at: the blocklist, your own rules, and app updates.
 * Lives in its own tab so Home stays the switch and the numbers.
 */
@Composable
fun ListsScreen(onUpdateNow: () -> Unit) {
    val status by Filters.status.collectAsStateWithLifecycle()
    // Derived from status on each change, never stored separately.
    val rules = remember(status) {
        (status.userAllow.map { it to true } + status.userBlock.map { it to false }).sortedBy { it.first }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { BlocklistPanel(status, onUpdateNow) }
        item { RulesPanel(rules) }
        item { AppPanel() }
    }
}

@Composable
private fun BlocklistPanel(status: Filters.Status, onUpdateNow: () -> Unit) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
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
            BusyButton("Update now", "Updating", busy = status.updating, onClick = onUpdateNow)
        }
        status.lastError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.Ember)
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
        if (rules.isEmpty()) {
            Text(
                "None yet. Tap any lookup in the Log to always block or always allow it.",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextLo,
            )
        }
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
