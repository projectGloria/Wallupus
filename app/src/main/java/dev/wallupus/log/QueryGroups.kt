package dev.wallupus.log

/**
 * Recent queries folded by (asking app, name, blocked): the same tracker hit 300 times by one app
 * is one row with a count, not 300 rows. Keyed per app so every count is that app's own: the Log
 * shows apps with their domains underneath. A name that flips (you added a rule) gets a second
 * row, because "blocked" and "allowed" are separate facts worth seeing.
 *
 * Bounded LRU by *last seen*: when full, the row nobody has asked for the longest is dropped.
 * Pure JVM (clock injected) so it's unit-tested; [QueryLog] wraps it.
 */
class QueryGroups(private val capacity: Int, private val clock: () -> Long = System::currentTimeMillis) {

    /** Immutable view for the UI. [qtypes] in first-seen order. */
    data class Entry(
        val uid: Int,
        val name: String,
        val blocked: Boolean,
        val count: Int,
        val firstMillis: Long,
        val lastMillis: Long,
        val qtypes: List<Int>,
    ) {
        /** Unique per row: LazyColumn keys on it so rows keep identity as they reorder. */
        val key: String get() = key(uid, name, blocked)
    }

    private class Group(val uid: Int, val name: String, val blocked: Boolean, val firstMillis: Long) {
        var count = 0
        var lastMillis = 0L
        val qtypes = ArrayList<Int>(2) // almost always A + AAAA (+ HTTPS); linear scan beats a set

        fun toEntry() = Entry(uid, name, blocked, count, firstMillis, lastMillis, qtypes.toList())
    }

    // accessOrder=true: every get()/put() moves the group to the tail, so iteration order is
    // least- to most-recently seen, and removeEldestEntry evicts the stalest group.
    private val groups = object : LinkedHashMap<String, Group>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Group>) = size > capacity
    }

    @Synchronized
    fun record(name: String, qtype: Int, blocked: Boolean, uid: Int) {
        val now = clock()
        val key = key(uid, name, blocked)
        val g = groups[key] ?: Group(uid, name, blocked, now).also { groups[key] = it }
        g.count++
        g.lastMillis = now
        if (qtype !in g.qtypes) g.qtypes += qtype
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
        fun key(uid: Int, name: String, blocked: Boolean) = "$uid|" + (if (blocked) "b:" else "a:") + name
    }
}
