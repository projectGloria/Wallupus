package dev.wallupus.filter

import dev.wallupus.WallupusApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the live [DomainMatcher]. The packet loop reads [matcher] lock-free on every query;
 * writers (list update, user rule edit) build a new matcher and swap the reference.
 */
object Filters {
    data class Status(
        val listDomains: Int = 0,
        val lastUpdateMillis: Long = 0,
        val lastError: String? = null,
        val updating: Boolean = false,
        val userAllow: Set<String> = emptySet(),
        val userBlock: Set<String> = emptySet(),
    )

    val matcher = AtomicReference(DomainMatcher.EMPTY)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private var lists = CompiledLists.EMPTY
    @Volatile private var loaded = false

    // Serialises the disk read on its own lock so a rule edit on the main thread never waits
    // behind it; the Filters monitor is only held for the cheap swap afterwards.
    private val loadLock = Any()

    private val prefs get() = WallupusApp.prefs

    fun file(): File = File(WallupusApp.instance.filesDir, "blocklist.bin")

    /** Idempotent; first caller pays the disk read, concurrent callers wait for it, later ones return at once. */
    fun ensureLoaded() {
        if (loaded) return
        synchronized(loadLock) {
            if (loaded) return
            val read = CompiledLists.readOrEmpty(file())
            synchronized(this) {
                // An update may have installed fresher lists while we were reading; keep those.
                if (loaded) return
                lists = read
                loaded = true
                rebuild()
            }
        }
    }

    @Synchronized
    fun install(newLists: CompiledLists) {
        lists = newLists
        loaded = true
        rebuild()
    }

    /** allow=true → always allow, false → always block, null → remove any user rule. */
    @Synchronized
    fun setUserRule(domain: String, allow: Boolean?) {
        val d = ListParser.normalize(domain) ?: return
        prefs.userAllow = if (allow == true) prefs.userAllow + d else prefs.userAllow - d
        prefs.userBlock = if (allow == false) prefs.userBlock + d else prefs.userBlock - d
        rebuild()
    }

    fun setUpdating(updating: Boolean) = _status.update { it.copy(updating = updating) }

    /** Must be called with the monitor held. */
    private fun rebuild() {
        val allow = prefs.userAllow
        val block = prefs.userBlock
        matcher.set(DomainMatcher(lists.block, lists.allow, allow, block))
        _status.update {
            it.copy(
                listDomains = lists.block.size,
                lastUpdateMillis = prefs.lastUpdateMillis,
                lastError = prefs.lastUpdateError,
                userAllow = allow,
                userBlock = block,
            )
        }
    }

    /** Re-publish status after the updater wrote new timestamps/errors to prefs. */
    @Synchronized
    fun refreshStatus() = rebuild()
}
