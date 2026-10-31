package dev.wallupus.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.wallupus.filter.Filters
import dev.wallupus.filter.ruleTargets
import dev.wallupus.log.QueryGroups
import dev.wallupus.log.QueryLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: String) { ALL("All"), BLOCKED("Blocked"), ALLOWED("Allowed") }

@OptIn(FlowPreview::class)
@Composable
fun LogScreen() {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var top by rememberSaveable { mutableStateOf(false) } // false = most recent first
    var query by rememberSaveable { mutableStateOf("") }
    var entries by remember { mutableStateOf(QueryLog.snapshot()) }
    // Remember the row's key, not the Entry: the dialog then follows live counts while it's open.
    var selectedKey by remember { mutableStateOf<String?>(null) }

    // Snapshot at most twice a second; see QueryLog for why we don't stream every entry.
    LaunchedEffect(Unit) {
        QueryLog.version.sample(500).collect { entries = QueryLog.snapshot() }
    }

    // Derived, not stored: recomputed only when an input changes, never kept in sync by hand.
    val shown = remember(entries, filter, top, query) {
        val needle = query.trim().lowercase()
        entries.asSequence()
            .filter {
                when (filter) {
                    LogFilter.ALL -> true
                    LogFilter.BLOCKED -> it.blocked
                    LogFilter.ALLOWED -> !it.blocked
                }
            }
            .filter { e ->
                // App names come from the label cache: only apps already shown can match, which
                // is every app on screen. Cheap enough at 1000 rows × 2 Hz to skip indexing.
                needle.isEmpty() || needle in e.name ||
                    e.uids.any { AppLabels.peek(it)?.name?.lowercase()?.contains(needle) == true }
            }
            .let { s -> if (top) s.sortedByDescending { it.count } else s }
            .toList()
    }
    val totals = remember(shown) { shown.sumOf { it.count } }
    val clock = rememberClockFormats()

    Column {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LogFilter.entries.forEach {
                FilterChip(selected = filter == it, onClick = { filter = it }, label = { Text(it.label) })
            }
            Spacer(Modifier.weight(1f))
            FilterChip(selected = top, onClick = { top = !top }, label = { Text("Top") })
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            placeholder = { Text("Search domain or app", color = Palette.TextLo) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(14.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%,d names · %,d lookups".format(shown.size, totals),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextLo,
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = QueryLog::clear) { Text("Clear", color = Palette.TextLo) }
            }
        }
        if (shown.isEmpty()) {
            Text(
                if (entries.isEmpty()) "Nothing yet. Turn blocking on and use your phone." else "No matches.",
                Modifier.padding(16.dp),
                color = Palette.TextLo,
            )
        }
        LazyColumn {
            // Rows are unique per (name, outcome) now, so they get stable keys: Compose moves a
            // row instead of rebuilding it, and animateItem slides it to its new place.
            items(shown, key = { it.key }) { e ->
                LogRow(e, clock, Modifier.animateItem()) { selectedKey = e.key }
                HorizontalDivider(color = Palette.Line)
            }
        }
    }

    selectedKey?.let { key ->
        val entry = entries.firstOrNull { it.key == key }
        // Cleared or evicted under us: close after this frame (never write state mid-composition).
        if (entry == null) SideEffect { selectedKey = null }
        else EntryDialog(entry, clock) { selectedKey = null }
    }
}

@Composable
private fun LogRow(e: QueryGroups.Entry, clock: ClockFormats, modifier: Modifier, onClick: () -> Unit) {
    val app = rememberAppLabel(e.uids.firstOrNull() ?: -1)
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AppIcon(app)
            // Outcome as a dot on the icon's corner: the icon answers "who", the dot "what happened".
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(12.dp)
                    .border(2.dp, Palette.Ink, CircleShape)
                    .background(if (e.blocked) Palette.Crimson else Palette.Bone, CircleShape),
            )
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(
                e.name,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                color = if (e.blocked) Palette.TextHi else Palette.TextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val more = if (e.uids.size > 1) " +${e.uids.size - 1}" else ""
            Text(
                "${app?.name ?: "…"}$more · ${clock.format(e.lastMillis)}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextLo,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (e.count > 1) CountBadge(e.count, e.blocked)
    }
}

/** "×12" pill: crimson for blocked (the brand's "active"), graphite for allowed. */
@Composable
private fun CountBadge(count: Int, blocked: Boolean) {
    val (bg, fg) = if (blocked) Palette.CrimsonDeep to Palette.OnCrimson else Palette.GraphiteHigh to Palette.TextLo
    Text(
        "×" + if (count < 10_000) "%,d".format(count) else "${count / 1000}k",
        Modifier.background(bg, CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium.merge(Tabular),
        fontWeight = FontWeight.SemiBold,
        color = fg,
    )
}

/**
 * Detail + rule editor. Lets you pick how broad the rule is: `cdn.ads.example.com`,
 * `ads.example.com` or `example.com`. Every rule also covers subdomains, so picking a parent is
 * the "whole site" option.
 */
@Composable
private fun EntryDialog(entry: QueryGroups.Entry, clock: ClockFormats, onDismiss: () -> Unit) {
    // null = still loading the public suffix list (first use only; it's a disk read, so off main).
    val suffixes by produceState<List<String>?>(null, entry.name) {
        value = withContext(Dispatchers.IO) { ruleTargets(entry.name) }
    }
    var picked by remember { mutableStateOf<String?>(null) }
    val target = picked ?: suffixes?.firstOrNull()
    val existing = remember(entry.name) { Filters.matcher.get().userRuleFor(entry.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    if (entry.blocked) "BLOCKED" else "ALLOWED",
                    style = Overline,
                    color = if (entry.blocked) Palette.Crimson else Palette.Bone,
                )
                Spacer(Modifier.height(4.dp))
                Text(entry.name, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium)
            }
        },
        text = {
            Column {
                Fact("Times", "%,d".format(entry.count))
                Fact("Last", clock.formatFull(entry.lastMillis))
                if (entry.count > 1) Fact("First", clock.formatFull(entry.firstMillis))
                Fact("Types", entry.qtypes.joinToString(", ") { QueryLog.typeName(it) })
                Spacer(Modifier.height(10.dp))
                Text(if (entry.uids.size > 1) "ASKED BY (LATEST FIRST)" else "ASKED BY", style = Overline, color = Palette.TextLo)
                entry.uids.forEach { uid ->
                    val app = rememberAppLabel(uid)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app, size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(app?.name ?: "…", style = MaterialTheme.typography.bodyMedium)
                            app?.packageName?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextLo)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
                existing?.let { (domain, allow) ->
                    Text("Your rule: ${if (allow) "allow" else "block"} $domain", Modifier.padding(bottom = 8.dp))
                }
                val choices = suffixes ?: return@Column
                if (choices.isEmpty()) {
                    // wpad, router, search-domain lookups: nothing a rule could ever match.
                    Text("Local name, can't be filtered.", color = Palette.TextLo)
                    return@Column
                }
                Text("APPLY RULE TO", style = Overline, color = Palette.TextLo)
                choices.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = target == s, onClick = { picked = s }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = target == s, onClick = { picked = s })
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
                // No target = loading or unfilterable: hide the buttons rather than offer a no-op.
                target?.let { t ->
                    TextButton(onClick = { Filters.setUserRule(t, false); onDismiss() }) { Text("Block") }
                    TextButton(onClick = { Filters.setUserRule(t, true); onDismiss() }) { Text("Allow") }
                }
            }
        },
    )
}

@Composable
private fun Fact(label: String, value: String, valueColor: Color = Palette.TextHi) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(label, Modifier.width(56.dp), style = MaterialTheme.typography.bodySmall, color = Palette.TextLo)
        Text(value, style = MaterialTheme.typography.bodySmall.merge(Tabular), color = valueColor)
    }
}

/**
 * Locale-aware formats, honouring the phone's 12/24-hour setting ("j" skeleton). Today's rows
 * show just the time; anything older adds the date, since the log can span midnight.
 */
private class ClockFormats(is24: Boolean, locale: Locale) {
    private val time = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, if (is24) "Hms" else "hms a"), locale)
    private val dated = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, if (is24) "MMMd Hm" else "MMMd hm a"), locale)
    private val full = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, if (is24) "yMMMd Hms" else "yMMMd hms a"), locale)
    private val cal = Calendar.getInstance()

    fun format(millis: Long): String = (if (isToday(millis)) time else dated).format(Date(millis))
    fun formatFull(millis: Long): String = full.format(Date(millis))

    private fun isToday(millis: Long): Boolean {
        cal.timeInMillis = System.currentTimeMillis()
        val y = cal.get(Calendar.YEAR)
        val d = cal.get(Calendar.DAY_OF_YEAR)
        cal.timeInMillis = millis
        return cal.get(Calendar.YEAR) == y && cal.get(Calendar.DAY_OF_YEAR) == d
    }
}

// SimpleDateFormat isn't thread-safe, but composition runs on the main thread only.
@Composable
private fun rememberClockFormats(): ClockFormats {
    val ctx = LocalContext.current
    return remember { ClockFormats(DateFormat.is24HourFormat(ctx), Locale.getDefault()) }
}
