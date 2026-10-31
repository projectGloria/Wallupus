package dev.wallupus.filter

/**
 * Decides block/allow for a query name. Immutable: updates build a new instance and swap the
 * reference, so the hot path takes no locks and makes zero heap allocations on list lookups.
 *
 * Every rule covers its subdomains: blocking `doubleclick.net` also blocks `ads.doubleclick.net`.
 * Precedence, highest first:
 *   1. your rules (most-specific suffix wins; allow beats block at the same suffix)
 *   2. list exceptions (@@)
 *   3. list blocks
 * So you can always override a list, a subdomain user rule can carve out an exception to a
 * parent user rule, and a list's own exceptions beat its own blocks.
 */
class DomainMatcher(
    private val listBlock: LongArray, // sorted, deduped hashes
    private val listAllow: LongArray, // sorted, deduped hashes
    private val userAllow: Set<String>,
    private val userBlock: Set<String>,
) {
    val listBlockCount: Int get() = listBlock.size

    fun isBlocked(name: String): Boolean {
        userRuleFor(name)?.let { return !it.second }
        if (listBlock.isEmpty()) return false

        var blocked = false
        forEachSuffix(name) { s ->
            val h = DomainHash.of(name, s)
            if (listAllow.isNotEmpty() && listAllow.binarySearch(h) >= 0) return false
            if (!blocked && listBlock.binarySearch(h) >= 0) {
                if (listAllow.isEmpty()) return true
                blocked = true
            }
        }
        return blocked
    }

    /** Returns which user rule (if any) currently covers [name] — for the log's "remove rule" UI. */
    fun userRuleFor(name: String): Pair<String, Boolean>? {
        // Guard first: most installs have no user rules, and this runs on every query.
        if (userAllow.isEmpty() && userBlock.isEmpty()) return null
        forEachSuffix(name) { s ->
            val suffix = name.substring(s)
            if (suffix in userAllow) return suffix to true
            if (suffix in userBlock) return suffix to false
        }
        return null
    }

    /**
     * Visits suffix start offsets from most specific to least specific ("a.b.c" → 0, 2),
     * stopping before the bare TLD ("c"). Inlined so the hot path allocates nothing.
     */
    private inline fun forEachSuffix(name: String, action: (Int) -> Unit) {
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0) return
        action(0)
        var dot = name.indexOf('.')
        while (dot in 0 until lastDot) {
            action(dot + 1)
            dot = name.indexOf('.', dot + 1)
        }
    }

    companion object {
        val EMPTY = DomainMatcher(LongArray(0), LongArray(0), emptySet(), emptySet())
    }
}
