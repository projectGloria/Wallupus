package dev.wallupus.log

/**
 * Which app sections of the Log are open: a default ("Expand all" / "Collapse all") plus the
 * apps the user flipped away from it by tapping their header. Immutable; every change returns
 * a new value.
 *
 * Only [openByDefault] is persisted. [flipped] holds UIDs of apps that appear in the log, and
 * the log itself is RAM-only, so those stay in RAM too: persisting them would write "this app
 * made DNS lookups" to disk (see the privacy invariants). They'd be meaningless after a restart
 * anyway, since the log starts empty.
 */
data class Expansion(val openByDefault: Boolean, val flipped: Set<Int> = emptySet()) {
    fun isOpen(uid: Int): Boolean = openByDefault != (uid in flipped)

    fun toggle(uid: Int): Expansion = copy(flipped = if (uid in flipped) flipped - uid else flipped + uid)

    /** Every one of [uids] is open. False for none, so an empty Log offers "Expand all". */
    fun allOpen(uids: Collection<Int>): Boolean = uids.isNotEmpty() && uids.all(::isOpen)

    /** "Expand all" / "Collapse all": becomes the default for apps that show up later, too. */
    fun setAll(open: Boolean): Expansion = Expansion(open)
}

/** The Log's outcome filter. Pure, so the All / Blocked / Allowed split is unit-tested. */
enum class LogFilter(val label: String) {
    ALL("All"),
    BLOCKED("Blocked"),
    ALLOWED("Allowed");

    fun matches(e: QueryGroups.Entry): Boolean = when (this) {
        ALL -> true
        BLOCKED -> e.blocked
        ALLOWED -> !e.blocked
    }
}
