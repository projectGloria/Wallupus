package dev.wallupus.dns

/**
 * Tiny LRU of upstream answers keyed by (qtype, name).
 *
 * Every DoH round-trip costs radio time and battery; apps re-resolve the same handful of hosts
 * constantly, so even 512 entries absorbs most traffic.
 *
 * Trade-off we accept: cached answers are replayed with their original TTL fields rather than
 * decremented ones, so a client may hold a record for up to ~2× its TTL. Rewriting TTLs means
 * walking and mutating every RR; not worth it for a blocker.
 */
class DnsCache(
    private val capacity: Int = 512,
    private val clock: () -> Long = System::nanoTime, // injectable so tests don't sleep
) {
    private class Entry(val response: ByteArray, val expiresAtNanos: Long)

    // accessOrder=true turns LinkedHashMap into an LRU; removeEldestEntry does the eviction.
    private val map = object : LinkedHashMap<String, Entry>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > capacity
    }

    private fun key(q: DnsQuestion) = "${q.qtype}:${q.name}"

    /** Returns a cached response (with ID still from the original query — caller must re-ID). */
    @Synchronized
    fun get(q: DnsQuestion): ByteArray? {
        val k = key(q)
        val e = map[k] ?: return null
        if (clock() >= e.expiresAtNanos) {
            map.remove(k)
            return null
        }
        return e.response
    }

    @Synchronized
    fun put(q: DnsQuestion, response: ByteArray) {
        val ttl = DnsMessage.cacheTtlSeconds(response) ?: return
        map[key(q)] = Entry(response, clock() + ttl * 1_000_000_000L)
    }

    @Synchronized
    fun clear() = map.clear()
}
