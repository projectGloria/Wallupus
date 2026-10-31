package dev.wallupus.log

/**
 * Recent queries folded by (name, blocked): the same tracker hit 300 times is one row with a
 * count, not 300 rows. A name that flips (you added a rule) gets a second row, because "blocked"
 * and "allowed" are separate facts worth seeing.
 *
 * Bounded LRU by *last seen*: when full, the group nobody has asked for the longest is dropped.
 * Pure JVM (clock injected) so it's unit-tested; [QueryLog] wraps it.
 */
class QueryGroups(private val capacity: Int, private val clock: () -> Long = System::currentTimeMillis) {

    /** Immutable view for the UI. [uids] most recent first; [qtypes] in first-seen order. */
    data class Entry(
        val name: String,
        val blocked: Boolean,
        val count: Int,
        val firstMillis: Long,
        val lastMillis: Long,
        val qtypes: List<Int>,
        val uids: List<Int>,
    ) {
        /** Unique per row: LazyColumn keys on it so rows keep identity as they reorder. */
        val key: String get() = (if (blocked) "b:" else "a:") + name
    }

    private class Group(val name: String, val blocked: Boolean, val firstMillis: Long) {
        var count = 0
        var lastMillis = 0L
        val qtypes = ArrayList<Int>(2) // almost always A + AAAA (+ HTTPS); linear scan beats a set
        val uids = ArrayList<Int>(2)

        fun toEntry() = Entry(name, blocked, count, firstMillis, lastMillis, qtypes.toList(), uids.toList())
    }

    // accessOrder=true: every get()/put() moves the group to the tail, so iteration order is
    // least- to most-recently seen, and removeEldestEntry evicts the stalest group.
    private val groups = object : LinkedHashMap<String, Group>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Group>) = size > capacity
    }

    @Synchronized
    fun record(name: String, qtype: Int, blocked: Boolean, uid: Int) {
        val now = clock()
        val key = (if (blocked) "b:" else "a:") + name
        val g = groups[key] ?: Group(name, blocked, now).also { groups[key] = it }
        g.count++
        g.lastMillis = now
        if (qtype !in g.qtypes) g.qtypes += qtype
        // Most recent asker first, capped: the row shows one icon, the detail a short list.
        val at = g.uids.indexOf(uid)
        if (at != 0) {
            if (at > 0) g.uids.removeAt(at)
            g.uids.add(0, uid)
            if (g.uids.size > MAX_UIDS) g.uids.removeAt(g.uids.lastIndex)
        }
    }

    /** Most recently seen first. */
    @Synchronized
    fun snapshot(): List<Entry> {
        val out = ArrayList<Entry>(groups.size)
        groups.values.forEach { out += it.toEntry() }
        out.reverse()
        return out
    }

    @Synchronized
    fun clear() = groups.clear()

    private companion object {
        const val MAX_UIDS = 6
    }
}
