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

    /** Settings → DNS: a preset id from config.json, or [Config.CUSTOM_RESOLVER]. null = config default. */
    var resolverId: String?
        get() = sp.getString(K_RESOLVER, null)
        set(v) = sp.edit { putString(K_RESOLVER, v) }

    /** The last custom DoH endpoint that passed its test; kept even while a preset is active. */
    var customUpstream: Upstream?
        get() = sp.getString(K_CUSTOM_URL, null)?.let { url ->
            Upstream(url, sp.getString(K_CUSTOM_BOOT, "")!!.split(',').filter { it.isNotEmpty() })
        }
        set(v) = sp.edit {
            putString(K_CUSTOM_URL, v?.url)
            putString(K_CUSTOM_BOOT, v?.bootstrap?.joinToString(","))
        }

    /** Filter lookups through the blocklist. Defaults on: before the split, running = blocking. */
    var blocking: Boolean
        get() = sp.getBoolean(K_BLOCKING, true)
        set(v) = sp.edit { putBoolean(K_BLOCKING, v) }

    /** Send lookups to the chosen DoH resolver; off = the network's own DNS. Defaults on, as before. */
    var dnsEnabled: Boolean
        get() = sp.getBoolean(K_DNS, true)
        set(v) = sp.edit { putBoolean(K_DNS, v) }

    val lifetimeBlocked: Long get() = sp.getLong(K_LIFE_BLOCKED, 0L)
    val lifetimeTotal: Long get() = sp.getLong(K_LIFE_TOTAL, 0L)

    fun addLifetime(blocked: Long, total: Long) = sp.edit {
        putLong(K_LIFE_BLOCKED, lifetimeBlocked + blocked)
        putLong(K_LIFE_TOTAL, lifetimeTotal + total)
    }

    /** HTTP validators + last verified rule count per source, so updates skip re-parsing the old file. */
    fun validators(sourceId: String): Pair<String?, String?> =
        sp.getString("etag_$sourceId", null) to sp.getString("lastmod_$sourceId", null)

    fun ruleCount(sourceId: String): Int? =
        sp.getInt("rules_$sourceId", -1).takeIf { it > 0 }

    fun setValidators(sourceId: String, etag: String?, lastModified: String?, rules: Int) = sp.edit {
        putString("etag_$sourceId", etag)
        putString("lastmod_$sourceId", lastModified)
        putInt("rules_$sourceId", rules)
    }

    private companion object {
        const val K_ALLOW = "user_allow"
        const val K_BLOCK = "user_block"
        const val K_LAST_UPDATE = "last_update"
        const val K_LAST_ERROR = "last_error"
        const val K_LIFE_BLOCKED = "life_blocked"
        const val K_LIFE_TOTAL = "life_total"
        const val K_RESOLVER = "resolver"
        const val K_CUSTOM_URL = "custom_doh_url"
        const val K_CUSTOM_BOOT = "custom_doh_bootstrap"
        const val K_BLOCKING = "mode_blocking"
        const val K_DNS = "mode_dns"
    }
}
