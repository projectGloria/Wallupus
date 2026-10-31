package dev.wallupus.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import dev.wallupus.WallupusApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

/**
 * Manual app updates from GitHub Releases: nothing runs until you tap "Check for update", and
 * nothing installs until you tap "Install update" and confirm in the system installer.
 *
 * Trust model: we don't verify the APK ourselves beyond a sanity check. Android refuses to
 * install an update that isn't signed with the same key as the installed app, so a swapped
 * download (or a compromised release page without the signing key) can't replace Wallupus.
 */
object AppUpdater {
    private const val MAX_APK_BYTES = 64L shl 20

    data class Release(val versionName: String, val versionCode: Int, val apkUrl: String, val size: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release) : State
        /** Downloaded, checked, installer opened. Tapping install again reuses the file. */
        data class Ready(val release: Release) : State
        /** [release] = what to retry installing, if we got that far. */
        data class Failed(val message: String, val release: Release?) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    private val http by lazy {
        WallupusApp.http.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * False for debug builds: they're a different app id (`.debug`) signed with the debug key,
     * so a release from GitHub could never install over them. Also false if config.json has
     * no `updates` URL (e.g. a build meant for F-Droid, which forbids self-updaters).
     */
    fun supported(ctx: Context): Boolean =
        WallupusApp.config.updates != null && ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0

    fun check() {
        if (_state.value is State.Checking || _state.value is State.Downloading) return
        _state.value = State.Checking
        WallupusApp.appScope.launch(Dispatchers.IO) {
            _state.value = try {
                val latest = fetchLatest()
                if (latest.versionCode > installedVersionCode()) {
                    State.Available(latest)
                } else {
                    apkFile().delete() // the update we downloaded last time is now installed
                    State.UpToDate
                }
            } catch (e: IOException) {
                State.Failed("Couldn't check for updates: ${e.message}", null)
            } catch (_: org.json.JSONException) {
                State.Failed("Unexpected reply from GitHub", null)
            }
        }
    }

    fun install(release: Release) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(release)
        WallupusApp.appScope.launch(Dispatchers.IO) {
            val apk = apkFile()
            try {
                // Reuse an earlier download of this exact release: the user backed out of the
                // installer, or switched away mid-download and Android dropped the installer
                // launch (background activity starts are silently ignored on 10+).
                val reusable = apk.exists() && runCatching { verify(apk, release) }.isSuccess
                if (!reusable) {
                    download(release, apk)
                    verify(apk, release)
                }
                _state.value = State.Ready(release)
                launchInstaller(apk, release)
            } catch (e: IOException) {
                apk.delete()
                _state.value = State.Failed("Download failed: ${e.message}", release)
            }
        }
    }

    private fun fetchLatest(): Release {
        val req = Request.Builder()
            .url(WallupusApp.config.updates!!)
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body?.string() ?: throw IOException("empty reply")
        }
        val root = JSONObject(body)
        val tag = root.getString("tag_name")
        val code = versionCodeOf(tag) ?: throw IOException("unrecognised release tag $tag")
        val assets = root.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject)
            .firstOrNull { it.getString("name").endsWith(".apk") }
            ?: throw IOException("release $tag has no APK")
        val url = apk.getString("browser_download_url")
        if (!url.startsWith("https://")) throw IOException("refusing non-https download")
        return Release(tag.removePrefix("v"), code, url, apk.getLong("size"))
    }

    private fun download(release: Release, dest: File) {
        if (release.size !in 1..MAX_APK_BYTES) throw IOException("unexpected APK size ${release.size}")
        dest.parentFile!!.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("empty reply")
            var total = 0L
            body.byteStream().use { input ->
                tmp.outputStream().buffered().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > release.size) break // more than GitHub said: stop reading
                        out.write(buf, 0, n)
                    }
                }
            }
            // A truncated or padded download would only fail later inside the installer with a
            // vague "problem parsing the package"; catch it here with a useful message.
            if (total != release.size) {
                tmp.delete()
                throw IOException("got $total of ${release.size} bytes")
            }
        }
        try {
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("couldn't save the APK", e)
        }
    }

    /** Sanity check before bothering the user: right app, the version we expected, and newer. */
    @Suppress("DEPRECATION") // the PackageInfoFlags overload is API 33+
    private fun verify(apk: File, release: Release) {
        val ctx = WallupusApp.instance
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?: throw IOException("not a valid APK")
        if (info.packageName != ctx.packageName) throw IOException("APK is for ${info.packageName}")
        if (info.longVersionCode != release.versionCode.toLong()) throw IOException("APK version doesn't match the release")
        if (info.longVersionCode <= installedVersionCode()) throw IOException("APK isn't newer than this version")
    }

    private fun launchInstaller(apk: File, release: Release) {
        val ctx = WallupusApp.instance
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: RuntimeException) {
            // No installer activity (stripped ROM) or a ROM-specific block. The APK stays put,
            // so retrying won't download it again.
            _state.value = State.Failed("Couldn't open the system installer", release)
        }
    }

    private fun installedVersionCode(): Long {
        val ctx = WallupusApp.instance
        return ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode
    }

    private fun apkFile() = File(WallupusApp.instance.cacheDir, "updates/wallupus.apk")
}
