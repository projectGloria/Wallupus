package dev.wallupus.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory log of recent queries, grouped per name (see [QueryGroups]). Deliberately never
 * written to disk: a browsing history of every domain your phone touched, now with which app
 * asked, is exactly the data this app exists to protect. Process dies → log is gone.
 */
object QueryLog {
    // Groups, not raw queries: one row per (app, name, outcome), so 20k covers days of use.
    // Each group is ~300–400 B of heap (key string, Group, qtypes, map node): ~6–8 MB when full.
    // record() is O(1) whatever the size; the cost of going bigger is the UI's snapshot copy
    // (LogScreen derives off main for that reason), so raise this with care.
    private const val CAPACITY = 20_000
    private val groups = QueryGroups(CAPACITY)

    val sessionBlocked = AtomicLong()
    val sessionTotal = AtomicLong()

    // How much of the session counts is already in the stored lifetime totals. Flushing
    // periodically (not only on stop) matters because a killed process or a reboot never
    // gets a clean stop, and with always-on VPN that's the usual way a session ends.
    private val savedBlocked = AtomicLong()
    private val savedTotal = AtomicLong()

    fun unsavedBlocked(): Long = sessionBlocked.get() - savedBlocked.get()
    fun unsavedTotal(): Long = sessionTotal.get() - savedTotal.get()

    /** Marks everything counted so far as saved and returns (blocked, total) not yet persisted. */
    @Synchronized
    fun takeUnsaved(): Pair<Long, Long> {
        val b = sessionBlocked.get()
        val t = sessionTotal.get()
        return (b - savedBlocked.getAndSet(b)) to (t - savedTotal.getAndSet(t))
    }

    @Synchronized
    fun resetSession() {
        sessionBlocked.set(0)
        sessionTotal.set(0)
        savedBlocked.set(0)
        savedTotal.set(0)
    }

    // Bumped on every record. The UI samples this (e.g. every 500 ms) and then pulls a snapshot,
    // instead of us emitting a fresh 1000-element list for each DNS query.
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** [uid] = the asking app (Process.INVALID_UID if the kernel couldn't tell). */
    fun record(name: String, qtype: Int, blocked: Boolean, uid: Int) {
        groups.record(name, qtype, blocked, uid)
        sessionTotal.incrementAndGet()
        if (blocked) sessionBlocked.incrementAndGet()
        _version.update { it + 1 }
    }

    /** Most recently seen first. */
    fun snapshot(): List<QueryGroups.Entry> = groups.snapshot()

    fun clear() {
        groups.clear()
        _version.update { it + 1 }
    }

    fun typeName(qtype: Int): String = when (qtype) {
        1 -> "A"; 28 -> "AAAA"; 65 -> "HTTPS"; 64 -> "SVCB"; 5 -> "CNAME"
        12 -> "PTR"; 16 -> "TXT"; 33 -> "SRV"; 15 -> "MX"
        else -> qtype.toString()
    }
}
