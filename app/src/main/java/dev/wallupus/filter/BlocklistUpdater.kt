package dev.wallupus.filter

import android.content.Context
import dev.wallupus.WallupusApp
import dev.wallupus.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads every enabled source, compiles them on-device, and hot-swaps the result.
 *
 * Failure policy: never make things worse. A source that fails falls back to its last cached
 * copy; if the outcome looks broken (nothing usable, or suspiciously empty), the previous
 * blocklist stays in place untouched.
 */
object BlocklistUpdater {
    private const val MAX_SOURCE_BYTES = 64L shl 20 // 64 MB: biggest real lists are ~20 MB
    private val mutex = Mutex() // weekly job + "Update now" must not compile concurrently

    private val http by lazy {
        WallupusApp.http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** [installed] = a new blocklist is live. [message] = what went wrong, if anything. */
    data class Result(val installed: Boolean, val message: String?)

    suspend fun update(ctx: Context): Result = mutex.withLock {
        withContext(Dispatchers.IO) {
            Filters.setUpdating(true)
            try {
                runUpdate(ctx)
            } finally {
                Filters.setUpdating(false)
            }
        }
    }

    private fun runUpdate(ctx: Context): Result {
        val prefs = WallupusApp.prefs
        val sources = WallupusApp.config.sources.filter { it.enabled }
        val dir = File(ctx.filesDir, "sources").apply { mkdirs() }

        val failed = mutableListOf<String>()
        val usable = mutableListOf<() -> java.io.BufferedReader>()

        for (s in sources) {
            if (s.url.startsWith("asset://")) {
                val name = s.url.removePrefix("asset://")
                usable.add { ctx.assets.open(name).bufferedReader() }
                continue
            }
            val cached = File(dir, "${s.id}.txt")
            val ok = try {
                fetch(s, cached)
            } catch (_: IOException) {
                false
            }
            if (!ok) failed += s.name
            // Stale-but-present beats missing: a GitHub outage shouldn't unblock ads.
            if (cached.exists()) usable.add { cached.bufferedReader() }
        }

        val remoteEnabled = sources.count { !it.url.startsWith("asset://") }
        val remoteUsable = usable.size - (sources.size - remoteEnabled)
        if (remoteEnabled > 0 && remoteUsable == 0) {
            return finish(false, "No list could be downloaded: ${failed.joinToString()}")
        }

        val compiled = CompiledLists.build(usable.asSequence().map { it() })
        // A list host returning an HTML error page with HTTP 200 parses to ~0 domains.
        // Swapping that in would silently disable blocking — refuse it.
        if (remoteEnabled > 0 && compiled.block.size < 1_000) {
            return finish(false, "Compiled only ${compiled.block.size} domains — keeping previous lists")
        }

        compiled.writeTo(Filters.file())
        Filters.install(compiled)
        prefs.lastUpdateMillis = System.currentTimeMillis()
        return finish(true, if (failed.isEmpty()) null else "Used cached copy of: ${failed.joinToString()}")
    }

    private fun finish(installed: Boolean, message: String?): Result {
        WallupusApp.prefs.lastUpdateError = message
        Filters.refreshStatus()
        return Result(installed, message)
    }

    /** Conditional GET into [dest]. True if [dest] is now current (fresh download or 304). */
    private fun fetch(s: Source, dest: File): Boolean {
        val prefs = WallupusApp.prefs
        val (etag, lastMod) = prefs.validators(s.id)
        val req = Request.Builder().url(s.url).apply {
            // Only send validators if we still have the body they validate.
            if (dest.exists()) {
                etag?.let { header("If-None-Match", it) }
                lastMod?.let { header("If-Modified-Since", it) }
            }
        }.build()

        http.newCall(req).execute().use { r ->
            if (r.code == 304) return true
            if (!r.isSuccessful) return false
            val body = r.body ?: return false

            // Stream to a temp file with a hard size cap, then atomic rename: a half-downloaded
            // list never replaces a good cached one.
            val tmp = File(dest.parentFile, dest.name + ".part")
            var total = 0L
            body.byteStream().use { input ->
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_SOURCE_BYTES) {
                            tmp.delete()
                            return false
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            prefs.setValidators(s.id, r.header("ETag"), r.header("Last-Modified"))
            return true
        }
    }
}
