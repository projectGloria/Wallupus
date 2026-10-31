package dev.wallupus.filter

/**
 * Decides block/allow for a query name. Immutable: updates build a new instance and swap the
 * reference, so the hot path takes no locks.
 *
 * Every rule covers its subdomains: blocking `doubleclick.net` also blocks `ads.doubleclick.net`.
 * Precedence, highest first — the first rule that matches any suffix of the name wins:
 *   1. your allow rules   2. your block rules   3. list exceptions (@@)   4. list blocks
 * So you can always override a list, and a list's own exceptions beat its own blocks.
 */
class DomainMatcher(
    private val listBlock: LongArray, // sorted, deduped hashes
    private val listAllow: LongArray, // sorted, deduped hashes
    private val userAllow: Set<String>,
    private val userBlock: Set<String>,
) {
    val listBlockCount: Int get() = listBlock.size

    fun isBlocked(name: String): Boolean {
        // Precompute suffix start offsets once: "a.b.c" → [0, 2, 4]. Stop before the bare TLD;
        // no list should ever block "com", and the parser refuses single-label entries anyway.
        val starts = suffixStarts(name)
        if (starts.isEmpty()) return false

        if (starts.any { name.substring(it) in userAllow }) return false
        if (starts.any { name.substring(it) in userBlock }) return true

        if (listBlock.isEmpty()) return false
        val hashes = LongArray(starts.size) { DomainHash.of(name, starts[it]) }
        if (listAllow.isNotEmpty() && hashes.any { listAllow.binarySearch(it) >= 0 }) return false
        return hashes.any { listBlock.binarySearch(it) >= 0 }
    }

    /** Returns which user rule (if any) currently covers [name] — for the log's "remove rule" UI. */
    fun userRuleFor(name: String): Pair<String, Boolean>? {
        for (s in suffixStarts(name)) {
            val suffix = name.substring(s)
            if (suffix in userAllow) return suffix to true
            if (suffix in userBlock) return suffix to false
        }
        return null
    }

    private fun suffixStarts(name: String): IntArray {
        if (name.isEmpty() || name.indexOf('.') < 0) return IntArray(0)
        val out = ArrayList<Int>(6)
        out += 0
        var i = name.indexOf('.')
        // Last dot = TLD boundary; we don't emit the TLD itself.
        val lastDot = name.lastIndexOf('.')
        while (i in 0 until lastDot) {
            out += i + 1
            i = name.indexOf('.', i + 1)
        }
        return out.toIntArray()
    }

    companion object {
        val EMPTY = DomainMatcher(LongArray(0), LongArray(0), emptySet(), emptySet())
    }
}
