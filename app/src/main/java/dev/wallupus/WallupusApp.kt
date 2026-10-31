package dev.wallupus

import android.app.Application
import dev.wallupus.dns.DohClient
import dev.wallupus.dns.Resolvers
import dev.wallupus.filter.Filters
import dev.wallupus.filter.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Hand-rolled dependency graph. There are ~5 singletons in this app; a DI framework would be
 * more code than the thing it wires up.
 */
class WallupusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Blocklist load is ~2–3 MB of longs: keep it off the main thread (cold start jank),
        // the VPN thread also calls ensureLoaded() so it never filters with an empty list.
        appScope.launch(Dispatchers.IO) { Filters.ensureLoaded() }
        UpdateWorker.schedulePeriodic(this)
    }

    companion object {
        lateinit var instance: WallupusApp
            private set

        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val prefs: Prefs by lazy { Prefs(instance) }
        val config: Config by lazy { Config.load(instance) }

        /** One OkHttp instance = one connection pool + dispatcher; specialised via newBuilder(). */
        val http: OkHttpClient by lazy { OkHttpClient() }

        val doh: DohClient by lazy { DohClient(Resolvers.activeUpstreams(), http) }
    }
}
