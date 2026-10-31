package dev.wallupus.dns

import dev.wallupus.Config
import dev.wallupus.Upstream
import dev.wallupus.WallupusApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which upstream answers the phone's DNS: a preset from config.json or the user's own DoH URL.
 * The choice persists in Prefs and is pushed into the live [DohClient], so switching takes
 * effect on the next query, with no VPN restart.
 */
object Resolvers {
    private val config get() = WallupusApp.config
    private val prefs get() = WallupusApp.prefs

    /** Id of the active choice: a preset id or [Config.CUSTOM_RESOLVER]. */
    private val _selected = MutableStateFlow(resolveId(prefs.resolverId))
    val selected: StateFlow<String> = _selected.asStateFlow()

    /** The upstreams [selected] stands for. Read once when the DoH client is built. */
    fun activeUpstreams(): List<Upstream> = upstreamsFor(_selected.value)

    /** Saved custom endpoint, for pre-filling the form; null until one has passed a test. */
    fun savedCustom(): Upstream? = prefs.customUpstream

    fun select(id: String) {
        val resolved = resolveId(id)
        prefs.resolverId = resolved
        WallupusApp.doh.use(upstreamsFor(resolved))
        _selected.value = resolved
    }

    /** Callers test [upstream] with [DohClient.probe] first: a dead resolver means no DNS at all. */
    fun selectCustom(upstream: Upstream) {
        prefs.customUpstream = upstream
        select(Config.CUSTOM_RESOLVER)
    }

    // A preset removed from config.json in a later build, or "custom" with nothing saved, falls
    // back to the default instead of leaving the phone with no resolver.
    private fun resolveId(id: String?): String = when {
        id == Config.CUSTOM_RESOLVER && prefs.customUpstream != null -> id
        config.resolvers.any { it.id == id } -> id!!
        else -> config.defaultResolver.id
    }

    private fun upstreamsFor(id: String): List<Upstream> =
        if (id == Config.CUSTOM_RESOLVER) listOf(prefs.customUpstream!!)
        else config.resolvers.first { it.id == id }.upstreams
}
