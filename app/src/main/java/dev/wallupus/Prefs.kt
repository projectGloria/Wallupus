package dev.wallupus

import android.content.Context
import androidx.core.content.edit

/**
 * Everything persistent besides the compiled blocklist. SharedPreferences is plenty: a handful
 * of keys, written rarely. No DNS log is ever stored here — only counts.
 */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("wallupus", Context.MODE_PRIVATE)

    // getStringSet returns the instance backing the prefs cache; mutating it corrupts state
    // silently. Always copy out (toSet) and write a fresh set in.
    var userAllow: Set<String>
        get() = sp.getStringSet(K_ALLOW, emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet(K_ALLOW, v.toSet()) }

    var userBlock: Set<String>
        get() = sp.getStringSet(K_BLOCK, emptySet())!!.toSet()
        set(v) = sp.edit { putStringSet(K_BLOCK, v.toSet()) }

    var lastUpdateMillis: Long
        get() = sp.getLong(K_LAST_UPDATE, 0L)
        set(v) = sp.edit { putLong(K_LAST_UPDATE, v) }

    var lastUpdateError: String?
        get() = sp.getString(K_LAST_ERROR, null)
        set(v) = sp.edit { putString(K_LAST_ERROR, v) }

    val lifetimeBlocked: Long get() = sp.getLong(K_LIFE_BLOCKED, 0L)
    val lifetimeTotal: Long get() = sp.getLong(K_LIFE_TOTAL, 0L)

    fun addLifetime(blocked: Long, total: Long) = sp.edit {
        putLong(K_LIFE_BLOCKED, lifetimeBlocked + blocked)
        putLong(K_LIFE_TOTAL, lifetimeTotal + total)
    }

    /** HTTP validators per source, so weekly updates are a 304 when nothing changed. */
    fun validators(sourceId: String): Pair<String?, String?> =
        sp.getString("etag_$sourceId", null) to sp.getString("lastmod_$sourceId", null)

    fun setValidators(sourceId: String, etag: String?, lastModified: String?) = sp.edit {
        putString("etag_$sourceId", etag)
        putString("lastmod_$sourceId", lastModified)
    }

    private companion object {
        const val K_ALLOW = "user_allow"
        const val K_BLOCK = "user_block"
        const val K_LAST_UPDATE = "last_update"
        const val K_LAST_ERROR = "last_error"
        const val K_LIFE_BLOCKED = "life_blocked"
        const val K_LIFE_TOTAL = "life_total"
    }
}
