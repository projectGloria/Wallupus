package dev.wallupus

import android.content.Context
import dev.wallupus.dns.isIpLiteral
import org.json.JSONArray
import org.json.JSONObject

data class Upstream(val url: String, val bootstrap: List<String>)

/**
 * One choice on the Settings screen. [upstreams] are tried in order (sticky failover).
 * [icon] names a bundled logo (see ui/ResolverIcon.kt); null or unknown = generic globe.
 */
data class Resolver(
    val id: String,
    val name: String,
    val note: String,
    val upstreams: List<Upstream>,
    val icon: String? = null,
)

data class Source(val id: String, val name: String, val url: String, val enabled: Boolean)

/**
 * assets/config.json — resolver presets, blocklist sources and the update URL, edited in your
 * editor and rebuilt. Which resolver is *active* is the user's pick (Settings), kept in [Prefs].
 * [updates] = GitHub "latest release" API URL for the manual updater; null hides the updater.
 */
class Config(
    val resolvers: List<Resolver>,
    val defaultResolver: Resolver,
    val sources: List<Source>,
    val updates: String?,
) {
    companion object {
        /** Reserved id for the user's own DoH URL; a preset can't claim it. */
        const val CUSTOM_RESOLVER = "custom"

        fun load(ctx: Context): Config {
            // Uses Android's bundled org.json: no serialization library just to read 30 lines.
            val root = JSONObject(ctx.assets.open("config.json").bufferedReader().use { it.readText() })
            val resolvers = root.getJSONArray("resolvers").objects().map { r ->
                Resolver(
                    r.getString("id"),
                    r.getString("name"),
                    r.optString("note"),
                    r.getJSONArray("upstreams").objects().map {
                        Upstream(it.getString("url"), it.getJSONArray("bootstrap").strings())
                    },
                    r.optString("icon").takeIf { it.isNotEmpty() },
                )
            }
            val sources = root.getJSONArray("sources").objects().map {
                Source(it.getString("id"), it.getString("name"), it.getString("url"), it.optBoolean("enabled", true))
            }
            require(resolvers.isNotEmpty()) { "config.json: at least one resolver is required" }
            require(resolvers.map { it.id }.toSet().size == resolvers.size) { "config.json: duplicate resolver id" }
            require(resolvers.none { it.id == CUSTOM_RESOLVER }) { "config.json: resolver id '$CUSTOM_RESOLVER' is reserved" }
            val upstreams = resolvers.flatMap { it.upstreams }
            require(resolvers.all { it.upstreams.isNotEmpty() }) { "config.json: every resolver needs an upstream" }
            require(upstreams.all { it.url.startsWith("https://") }) { "config.json: upstreams must be https://" }
            // A hostname here would make InetAddress.getByName do the very plain-text lookup that
            // bootstrap IPs exist to avoid.
            require(upstreams.all { u -> u.bootstrap.all(::isIpLiteral) }) { "config.json: bootstrap entries must be IP literals" }
            // Empty is only for the user's custom URL; a preset without IPs would quietly fall
            // back to looking its own hostname up in plain text.
            require(upstreams.all { it.bootstrap.isNotEmpty() }) { "config.json: every upstream needs bootstrap IPs" }
            val defaultResolver = resolvers.firstOrNull { it.id == root.optString("defaultResolver") }
                ?: error("config.json: defaultResolver must name a resolver")
            require(sources.map { it.id }.toSet().size == sources.size) { "config.json: duplicate source id" }
            // A plain-http list could be rewritten in transit into "block nothing" or "block your bank".
            require(sources.all { it.url.startsWith("https://") || it.url.startsWith("asset://") }) {
                "config.json: sources must be https:// or asset://"
            }
            // The id becomes a file name under filesDir/sources/: no separators, no "..".
            require(sources.all { s -> s.id.isNotEmpty() && s.id.all { it.isLetterOrDigit() || it == '-' || it == '_' } }) {
                "config.json: source ids may only use letters, digits, '-' and '_'"
            }
            val updates = root.optString("updates").takeIf { it.isNotEmpty() }
            require(updates == null || updates.startsWith("https://")) { "config.json: updates must be https://" }
            return Config(resolvers, defaultResolver, sources, updates)
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
