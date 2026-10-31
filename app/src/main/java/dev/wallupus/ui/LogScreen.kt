package dev.wallupus.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.wallupus.filter.Filters
import dev.wallupus.log.QueryLog
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: String) { ALL("All"), BLOCKED("Blocked"), ALLOWED("Allowed") }

@OptIn(FlowPreview::class)
@Composable
fun LogScreen() {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var entries by remember { mutableStateOf(QueryLog.snapshot()) }
    var selected by remember { mutableStateOf<QueryLog.Entry?>(null) }

    // Snapshot at most twice a second; see QueryLog for why we don't stream every entry.
    LaunchedEffect(Unit) {
        QueryLog.version.sample(500).collect { entries = QueryLog.snapshot() }
    }

    val shown = when (filter) {
        LogFilter.ALL -> entries
        LogFilter.BLOCKED -> entries.filter { it.blocked }
        LogFilter.ALLOWED -> entries.filter { !it.blocked }
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogFilter.entries.forEach {
                FilterChip(selected = filter == it, onClick = { filter = it }, label = { Text(it.label) })
            }
        }
        if (shown.isEmpty()) {
            Text("Nothing yet. Turn blocking on and use your phone.", Modifier.padding(16.dp))
        }
        LazyColumn {
            // No stable key: entries aren't unique (same domain, same second) and the whole list
            // is replaced on each snapshot anyway.
            items(shown) { e ->
                Row(
                    Modifier.fillMaxWidth().clickable { selected = e }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (e.blocked) "✕" else "✓",
                        color = if (e.blocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(e.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${timeFmt.format(Date(e.timeMillis))} · ${QueryLog.typeName(e.qtype)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }

    selected?.let { RuleDialog(it) { selected = null } }
}

/**
 * Lets you pick how broad the rule is: `cdn.ads.example.com`, `ads.example.com` or `example.com`.
 * Every rule also covers subdomains, so picking a parent is the "whole site" option.
 */
@Composable
private fun RuleDialog(entry: QueryLog.Entry, onDismiss: () -> Unit) {
    val suffixes = remember(entry.name) {
        val labels = entry.name.split('.')
        // Single-label names (wpad, search-domain lookups) have no parents; fall back to the
        // name itself rather than crashing on an empty list. setUserRule ignores invalid names.
        (0 until labels.size - 1).map { labels.drop(it).joinToString(".") }.ifEmpty { listOf(entry.name) }
    }
    var target by remember { mutableStateOf(suffixes.first()) }
    val existing = remember(entry.name) { Filters.matcher.get().userRuleFor(entry.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (entry.blocked) "Blocked" else "Allowed") },
        text = {
            Column {
                existing?.let { (domain, allow) ->
                    Text("Your rule: ${if (allow) "allow" else "block"} $domain", Modifier.padding(bottom = 8.dp))
                }
                Text("Apply to:", style = MaterialTheme.typography.labelLarge)
                suffixes.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = target == s, onClick = { target = s }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == s, onClick = { target = s })
                        Text(s, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                existing?.let { (domain, _) ->
                    TextButton(onClick = { Filters.setUserRule(domain, null); onDismiss() }) { Text("Remove rule") }
                }
                TextButton(onClick = { Filters.setUserRule(target, false); onDismiss() }) { Text("Block") }
                TextButton(onClick = { Filters.setUserRule(target, true); onDismiss() }) { Text("Allow") }
            }
        },
    )
}
