package dev.wallupus.vpn

import dev.wallupus.WallupusApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the tunnel does, as two independent switches: filter through the blocklist, and/or send
 * lookups to the chosen DoH resolver (off = the network's own DNS). The VPN runs iff either is on.
 *
 * Same publish-an-immutable-value pattern as the matcher: the packet loop does one volatile read
 * per query and never locks.
 */
object Modes {
    data class Mode(val blocking: Boolean, val dns: Boolean) {
        val any: Boolean get() = blocking || dns
    }

    private val prefs get() = WallupusApp.prefs

    private val _state = MutableStateFlow(Mode(prefs.blocking, prefs.dnsEnabled))
    val state: StateFlow<Mode> = _state.asStateFlow()

    fun set(mode: Mode) {
        prefs.blocking = mode.blocking
        prefs.dnsEnabled = mode.dns
        _state.value = mode
    }

    /**
     * The service is starting (Always-On, STICKY restart) with nothing switched on: the system
     * only does that when the user wants the VPN, so fall back to the original meaning, blocking.
     */
    fun ensureAny() {
        if (!_state.value.any) set(_state.value.copy(blocking = true))
    }
}
