package dev.wallupus.log

/** One app's section in the Log: its rows by count (busiest first) plus header totals. */
data class AppGroup(
    val uid: Int,
    val entries: List<QueryGroups.Entry>,
    val total: Int,
    val blocked: Int,
    val lastMillis: Long,
)

/**
 * Folds a (filtered) snapshot into per-app sections. Apps are ordered by most recent activity,
 * or by lookup count when [byCount]. Pure, so the Log's grouping is unit-tested.
 */
fun groupByApp(entries: List<QueryGroups.Entry>, byCount: Boolean): List<AppGroup> {
    val groups = entries.groupBy { it.uid }.map { (uid, rows) ->
        AppGroup(
            uid = uid,
            // Ties broken by recency, so equal counts don't shuffle every refresh.
            entries = rows.sortedWith(compareByDescending<QueryGroups.Entry> { it.count }.thenByDescending { it.lastMillis }),
            total = rows.sumOf { it.count },
            blocked = rows.filter { it.blocked }.sumOf { it.count },
            lastMillis = rows.maxOf { it.lastMillis },
        )
    }
    return if (byCount) groups.sortedByDescending { it.total } else groups.sortedByDescending { it.lastMillis }
}

/**
 * Letter + palette slot for a domain's monogram disc, from its second-level label
 * (`ads.doubleclick.net` → `D`): all of one site's hosts share a disc. Purely local; nothing is
 * fetched, since a favicon request would leak the name (or contact the blocked tracker itself).
 */
fun monogram(name: String, slots: Int): Pair<Char, Int> {
    val labels = name.split('.').filter { it.isNotEmpty() }
    val site = labels.getOrNull(labels.size - 2) ?: labels.firstOrNull() ?: "?"
    val letter = site.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    return letter to Math.floorMod(site.hashCode(), slots)
}
