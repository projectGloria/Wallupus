package dev.wallupus.filter

import java.io.Reader

/**
 * Per-source sanity check, run on a fresh download *before* it replaces the cached copy.
 *
 * The updater's combined "< 1000 domains" guard can't catch one broken source while another
 * healthy one carries the total. A captive portal or CDN error page served with HTTP 200 would
 * otherwise overwrite a good cached list and quietly drop all of its domains.
 */
internal object SourceCheck {
    /** Lists don't lose three quarters of their rules in a week; an error page does. */
    private const val MAX_SHRINK_FACTOR = 4

    fun countRules(reader: Reader): Int =
        reader.buffered().useLines { lines -> lines.count { ListParser.parseLine(it) != null } }

    /** [previous] = rule count of the cached copy, or null on the first download. */
    fun accept(fresh: Int, previous: Int?): Boolean =
        fresh > 0 && (previous == null || fresh.toLong() * MAX_SHRINK_FACTOR >= previous)
}
