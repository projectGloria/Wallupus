package dev.wallupus.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One domain + outcome across every app that asked for it: the export's row. [QueryGroups] keys
 * per app, so a tracker hit by five apps is five entries there and one row here.
 * [byApp] = (uid, count), busiest first.
 */
data class DomainTotal(
    val name: String,
    val blocked: Boolean,
    val times: Int,
    val firstMillis: Long,
    val lastMillis: Long,
    val qtypes: List<Int>,
    val byApp: List<Pair<Int, Int>>,
)

/**
 * Folds a snapshot into one row per (name, outcome). Blocked rows first, then by [DomainTotal.times]
 * descending, ties by recency, then name, so the order is stable across exports.
 * A name that flipped (rule added mid-session) keeps two rows: they're different facts.
 */
fun foldByDomain(entries: List<QueryGroups.Entry>): List<DomainTotal> =
    entries.groupBy { it.name to it.blocked }.map { (k, rows) ->
        DomainTotal(
            name = k.first,
            blocked = k.second,
            times = rows.sumOf { it.count },
            firstMillis = rows.minOf { it.firstMillis },
            lastMillis = rows.maxOf { it.lastMillis },
            // LinkedHashSet via distinct(): keeps first-seen order across apps.
            qtypes = rows.flatMap { it.qtypes }.distinct(),
            byApp = rows.sortedWith(compareByDescending<QueryGroups.Entry> { it.count }.thenBy { it.uid })
                .map { it.uid to it.count },
        )
    }.sortedWith(
        compareByDescending<DomainTotal> { it.blocked }
            .thenByDescending { it.times }
            .thenByDescending { it.lastMillis }
            .thenBy { it.name },
    )

const val EXPORT_HEADER = "domain,outcome,times,apps,first_seen,last_seen,qtypes"

/**
 * RFC 4180 CSV, CRLF line ends (what Excel expects). [appName] maps a uid to a display label;
 * it's injected so this file stays free of Android calls and unit-testable.
 */
fun exportCsv(totals: List<DomainTotal>, appName: (Int) -> String, zone: ZoneId): String {
    // ISO_OFFSET_DATE_TIME minus fractional seconds: sortable, unambiguous, spreadsheet-parsable.
    val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX").withZone(zone)
    val sb = StringBuilder(64 + totals.size * 120)
    sb.append(EXPORT_HEADER).append("\r\n")
    for (t in totals) {
        val apps = t.byApp.joinToString("; ") { (uid, n) -> "${appName(uid)} ×$n" }
        sb.append(csvField(t.name)).append(',')
            .append(if (t.blocked) "blocked" else "allowed").append(',')
            .append(t.times).append(',')
            .append(csvField(apps)).append(',')
            .append(fmt.format(Instant.ofEpochMilli(t.firstMillis))).append(',')
            .append(fmt.format(Instant.ofEpochMilli(t.lastMillis))).append(',')
            .append(csvField(t.qtypes.joinToString("|") { QueryLog.typeName(it) }))
            .append("\r\n")
    }
    return sb.toString()
}

/** `wallupus-log-2026-11-05_1432.csv`: sorts by time, no characters SAF providers choke on. */
fun exportFileName(nowMillis: Long, zone: ZoneId): String =
    "wallupus-log-" + DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(zone).format(Instant.ofEpochMilli(nowMillis)) + ".csv"

/**
 * Quotes when needed, and defuses formula injection: app labels are picked by whoever wrote the
 * app, and a spreadsheet would execute `=HYPERLINK(...)` in a cell. A leading `'` makes it text
 * (OWASP's mitigation). Query names come off the wire too, so every free-text field goes through here.
 */
internal fun csvField(raw: String): String {
    val s = if (raw.isNotEmpty() && raw[0] in FORMULA_STARTS) "'$raw" else raw
    return if (s.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
        "\"" + s.replace("\"", "\"\"") + "\""
    } else {
        s
    }
}

private val FORMULA_STARTS = charArrayOf('=', '+', '-', '@', '\t', '\r')
