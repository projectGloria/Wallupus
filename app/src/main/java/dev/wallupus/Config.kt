package dev.wallupus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Upstream(val url: String, val bootstrap: List<String>)

data class Source(val id: String, val name: String, val url: String, val enabled: Boolean)

/** assets/config.json — the app's whole "settings screen", edited in your editor, not in the UI. */
class Config(val upstreams: List<Upstream>, val sources: List<Source>) {
    companion object {
        fun load(ctx: Context): Config {
            // Uses Android's bundled org.json: no serialization library just to read 30 lines.
            val root = JSONObject(ctx.assets.open("config.json").bufferedReader().use { it.readText() })
            val upstreams = root.getJSONArray("upstreams").objects().map {
                Upstream(it.getString("url"), it.getJSONArray("bootstrap").strings())
            }
            val sources = root.getJSONArray("sources").objects().map {
                Source(it.getString("id"), it.getString("name"), it.getString("url"), it.optBoolean("enabled", true))
            }
            require(upstreams.isNotEmpty()) { "config.json: at least one upstream is required" }
            require(upstreams.all { it.url.startsWith("https://") }) { "config.json: upstreams must be https://" }
            require(sources.map { it.id }.toSet().size == sources.size) { "config.json: duplicate source id" }
            return Config(upstreams, sources)
        }

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
        private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    }
}
