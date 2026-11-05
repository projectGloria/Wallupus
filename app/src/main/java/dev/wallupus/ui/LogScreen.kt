package dev.wallupus.ui

import android.content.Context
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wallupus.filter.Filters
import dev.wallupus.filter.ruleTargets
import dev.wallupus.log.AppGroup
import dev.wallupus.log.QueryGroups
import dev.wallupus.log.groupByApp
import dev.wallupus.log.monogram
import dev.wallupus.log.QueryLog
import dev.wallupus.log.exportCsv
import dev.wallupus.log.exportFileName
import dev.wallupus.log.foldByDomain
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class LogFilter(val label: String) { ALL("All"), BLOCKED("Blocked"), ALLOWED("Allowed") }

@OptIn(FlowPreview::class, ExperimentalFoundationApi::class) // stickyHeader
@Composable
fun LogScreen() {
    var filter by rememberSaveable { mutableStateOf(LogFilter.ALL) }
    var top by rememberSaveable { mutableStateOf(false) } // false = most recent first
    var query by rememberSaveable { mutableStateOf("") }
    // Snapshot at most twice a second; see QueryLog for why we don't stream every entry.
    // WithLifecycle so a backgrounded Log stops copying 1000 groups every 500 ms.
    val tick by remember { QueryLog.version.sample(500) }.collectAsStateWithLifecycle(QueryLog.version.value)
    // Remember the row's key, not the Entry: the dialog then follows live counts while it's open.
    var selectedKey by remember { mutableStateOf<String?>(null) }

    // Still derived, not stored by hand, but on Default instead of in composition: copying,
    // filtering and sorting up to 20k groups at 2 Hz on main would drop frames. produceState
    // relaunches (cancelling the stale run) when any key changes, and keeps showing the last
    // result meanwhile, so typing in search never flashes an empty list. null = first build.
    val view by produceState<LogView?>(null, tick, filter, top, query) {
        value = withContext(Dispatchers.Default) { buildLogView(QueryLog.snapshot(), filter, top, query) }
    }
    val entries = view?.entries.orEmpty()
    val shown = view?.shown.orEmpty()
    val totals = view?.totals ?: 0
    val names = view?.names ?: 0

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // SAF "Save as": the user picks the destination, and that document is the only copy. No
    // cache file, no FileProvider, no share intent (see CLAUDE.md privacy invariants).
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@rememberLauncherForActivityResult // picker cancelled
        scope.launch { exportLog(ctx.applicationContext, uri) }
    }
    // Collapsed app sections, by UID. Saveable as a list: survives rotation and tab switches.
    var collapsed by rememberSaveable { mutableStateOf(listOf<Int>()) }
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
                "%,d apps · %,d names · %,d lookups".format(shown.size, names, totals),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextLo,
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = {
                    exporter.launch(exportFileName(System.currentTimeMillis(), ZoneId.systemDefault()))
                }) { Text("Export", color = Palette.TextLo) }
                TextButton(onClick = QueryLog::clear) { Text("Clear", color = Palette.TextLo) }
            }
        }
        // view == null: first build still running; say nothing rather than "Nothing yet".
        if (view != null && shown.isEmpty()) {
            Text(
                if (entries.isEmpty()) "Nothing yet. Turn blocking on and use your phone." else "No matches.",
                Modifier.padding(16.dp),
                color = Palette.TextLo,
            )
        }
        LazyColumn {
            // Stable keys per app header and per (app, name, outcome) row: Compose moves a row
            // instead of rebuilding it, and animateItem slides it to its new place.
            shown.forEach { group ->
                val open = group.uid !in collapsed
                // Sticky: while scrolling a chatty app's domains, you still see whose they are.
                stickyHeader(key = "app:${group.uid}") {
                    AppHeader(group, open, clock) {
                        collapsed = if (open) collapsed + group.uid else collapsed - group.uid
                    }
                }
                if (open) {
                    items(group.entries, key = { it.key }) { e ->
                        DomainRow(e, Modifier.animateItem()) { selectedKey = e.key }
                    }
                }
                item(key = "end:${group.uid}") { HorizontalDivider(color = Palette.Line) }
            }
        }
    }

    selectedKey?.let { key ->
        val entry = entries.firstOrNull { it.key == key }
        // Cleared or evicted under us: close after this frame (never write state mid-composition).
        if (entry == null) {
            SideEffect { selectedKey = null }
        } else {
            // Every app that asked for this name (same outcome), busiest first. Derived from the
            // snapshot, since rows are per app now.
            // Keyed so a 20k scan runs once per snapshot while the dialog is open, not per recomposition.
            val askers = remember(entries, entry.name, entry.blocked) {
                entries.filter { it.name == entry.name && it.blocked == entry.blocked }.sortedByDescending { it.count }
            }
            EntryDialog(entry, askers, clock) { selectedKey = null }
        }
    }
}

/** One derived frame of the Log: the raw snapshot (for the dialog) plus the filtered sections. */
private class LogView(
    val entries: List<QueryGroups.Entry>,
    val shown: List<AppGroup>,
    val totals: Int,
    val names: Int,
)

/** Runs on Dispatchers.Default. AppLabels.peek is a ConcurrentHashMap read, safe off main. */
private fun buildLogView(entries: List<QueryGroups.Entry>, filter: LogFilter, top: Boolean, query: String): LogView {
    val needle = query.trim().lowercase()
    val shown = entries.asSequence()
        .filter {
            when (filter) {
                LogFilter.ALL -> true
                LogFilter.BLOCKED -> it.blocked
                LogFilter.ALLOWED -> !it.blocked
            }
        }
        .filter { e ->
            // App names come from the label cache: only apps already shown can match, which is
            // every app on screen.
            needle.isEmpty() || needle in e.name ||
                AppLabels.peek(e.uid)?.name?.lowercase()?.contains(needle) == true
        }
        .toList()
        .let { groupByApp(it, byCount = top) }
    return LogView(entries, shown, shown.sumOf { it.total }, shown.sumOf { it.entries.size })
}

/**
 * Snapshot → one row per domain → CSV into the picked document. Fresh snapshot (the whole log,
 * not the filtered view). Labels via AppLabels.load: cached, and resolves apps the user never
 * scrolled to. Nothing outlives this call.
 */
private suspend fun exportLog(ctx: Context, uri: Uri) {
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val totals = foldByDomain(QueryLog.snapshot())
            val labels = totals.flatMap { t -> t.byApp.map { it.first } }.distinct()
                .associateWith { AppLabels.load(ctx, it).name }
            val csv = exportCsv(totals, { labels[it] ?: "Unknown app" }, ZoneId.systemDefault())
            // "wt": truncate, in case the user picked an existing file to overwrite.
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Can't open file")
            out.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
            totals.size
        }
    }
    // Back on Main (the caller's scope): Toast needs a Looper thread.
    val msg = result.fold(
        { "Exported %,d domains".format(it) },
        { e ->
            if (e is CancellationException) throw e // leaving the screen isn't an error
            "Export failed: ${e.message ?: e.javaClass.simpleName}"
        },
    )
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

/** App section header: icon, name, and its blocked / total counts. Tap folds the section. */
@Composable
private fun AppHeader(group: AppGroup, open: Boolean, clock: ClockFormats, onToggle: () -> Unit) {
    val app = rememberAppLabel(group.uid)
    Row(
        Modifier
            .fillMaxWidth()
            // Opaque: a sticky header slides over the rows beneath it.
            .background(Palette.Ink)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 8.dp)) {
            Text(
                app?.name ?: "…",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    if (group.blocked > 0) append("%,d blocked · ".format(group.blocked))
                    append("%,d lookups · ".format(group.total))
                    append(clock.format(group.lastMillis))
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (group.blocked > 0) Palette.Crimson else Palette.TextLo,
                maxLines = 1,
            )
        }
        Text(if (open) "−" else "+", style = MaterialTheme.typography.titleMedium, color = Palette.TextLo)
    }
}

/** One domain under its app: monogram, name, outcome dot, count. */
@Composable
private fun DomainRow(e: QueryGroups.Entry, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // The outcome is only a dot's colour on screen: say it. Replaces the children's
            // "e", "name", "×12" with one line; the click action above is kept.
            .clearAndSetSemantics {
                contentDescription = "${e.name}, ${if (e.blocked) "blocked" else "allowed"}, ${e.count} " +
                    if (e.count == 1) "lookup" else "lookups"
            }
            .padding(start = 32.dp, end = 16.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            DomainMonogram(e.name)
            // Outcome as a dot on the disc's corner: the disc answers "which site", the dot "what happened".
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(10.dp)
                    .border(2.dp, Palette.Ink, CircleShape)
                    .background(if (e.blocked) Palette.Crimson else Palette.Bone, CircleShape),
            )
        }
        Text(
            e.name,
            Modifier.weight(1f).padding(start = 14.dp, end = 8.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            color = if (e.blocked) Palette.TextHi else Palette.TextLo,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CountBadge(e.count, e.blocked)
    }
}

/** Local stand-in for a favicon: the site's initial on a stable muted tint. Never fetches. */
@Composable
private fun DomainMonogram(name: String, size: Dp = 26.dp) {
    val (letter, slot) = remember(name) { monogram(name, Palette.Monograms.size) }
    Box(
        Modifier.size(size).clip(CircleShape).background(Palette.Monograms[slot]),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter.toString(), color = Palette.TextHi, fontSize = (size.value * 0.46f).sp, fontWeight = FontWeight.SemiBold)
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
private fun EntryDialog(entry: QueryGroups.Entry, askers: List<QueryGroups.Entry>, clock: ClockFormats, onDismiss: () -> Unit) {
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
                Fact("Times", "%,d".format(askers.sumOf { it.count }))
                Fact("Last", clock.formatFull(askers.maxOf { it.lastMillis }))
                Fact("First", clock.formatFull(askers.minOf { it.firstMillis }))
                Fact("Types", askers.flatMap { it.qtypes }.distinct().joinToString(", ") { QueryLog.typeName(it) })
                Spacer(Modifier.height(10.dp))
                Text("ASKED BY", style = Overline, color = Palette.TextLo)
                askers.forEach { a ->
                    val app = rememberAppLabel(a.uid)
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app, size = 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app?.name ?: "…", style = MaterialTheme.typography.bodyMedium)
                            app?.packageName?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextLo)
                            }
                        }
                        Text("×%,d".format(a.count), style = MaterialTheme.typography.bodySmall.merge(Tabular), color = Palette.TextLo)
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
