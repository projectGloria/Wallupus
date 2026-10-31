package dev.wallupus.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory ring buffer of recent queries. Deliberately never written to disk: a browsing
 * history of every domain your phone touched is exactly the data this app exists to protect.
 * Process dies → log is gone.
 */
object QueryLog {
    data class Entry(val timeMillis: Long, val name: String, val qtype: Int, val blocked: Boolean)

    private const val CAPACITY = 1000
    private val buffer = ArrayDeque<Entry>(CAPACITY)

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

    fun record(name: String, qtype: Int, blocked: Boolean) {
        synchronized(buffer) {
            if (buffer.size == CAPACITY) buffer.removeFirst()
            buffer.addLast(Entry(System.currentTimeMillis(), name, qtype, blocked))
        }
        sessionTotal.incrementAndGet()
        if (blocked) sessionBlocked.incrementAndGet()
        _version.update { it + 1 }
    }

    /** Newest first. */
    fun snapshot(): List<Entry> = synchronized(buffer) { buffer.reversed() }

    fun clear() {
        synchronized(buffer) { buffer.clear() }
        _version.update { it + 1 }
    }

    fun typeName(qtype: Int): String = when (qtype) {
        1 -> "A"; 28 -> "AAAA"; 65 -> "HTTPS"; 64 -> "SVCB"; 5 -> "CNAME"
        12 -> "PTR"; 16 -> "TXT"; 33 -> "SRV"; 15 -> "MX"
        else -> qtype.toString()
    }
}
