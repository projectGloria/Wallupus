package dev.wallupus.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** What the Log shows for a UID. [icon] null = no launcher icon (system UIDs, unknown). */
class AppLabel(val name: String, val packageName: String?, val icon: ImageBitmap?)

/**
 * UID → app name + icon, resolved once per UID and kept in RAM for the process lifetime.
 * Names of installed apps aren't private the way query names are, but they still never leave
 * memory. Seeing other apps at all needs QUERY_ALL_PACKAGES on Android 11+ (see the manifest).
 */
object AppLabels {
    private val cache = ConcurrentHashMap<Int, AppLabel>()

    /** Cached label or null; never blocks. Search uses this so it matches app names already shown. */
    fun peek(uid: Int): AppLabel? = cache[uid]

    /** Disk-backed PackageManager calls plus icon rasterising: off main, once per UID. */
    suspend fun load(ctx: Context, uid: Int): AppLabel =
        cache[uid] ?: withContext(Dispatchers.IO) { resolve(ctx.applicationContext, uid) }.also { cache[uid] = it }

    private fun resolve(ctx: Context, uid: Int): AppLabel {
        if (uid < 0) return AppLabel("Unknown app", null, null) // Process.INVALID_UID: owner already gone
        // System UIDs (root, system_server, netd…) are shared by dozens of packages; naming one
        // arbitrary package would mislead, so they get a role name instead.
        if (!Process.isApplicationUid(uid)) return AppLabel(systemName(uid), null, null)
        val pm = ctx.packageManager
        val pkgs = pm.getPackagesForUid(uid)?.sorted().orEmpty()
        val pkg = pkgs.firstOrNull() ?: return AppLabel("Uninstalled app ($uid)", null, null)
        return try {
            val info = pm.getApplicationInfo(pkg, 0)
            val label = pm.getApplicationLabel(info).toString()
            // Rasterise once at the exact size we draw: adaptive icons are layered vectors, and
            // re-drawing those per row per frame would be the slow path.
            val px = (ICON_SIZE.value * ctx.resources.displayMetrics.density).toInt()
            val icon = pm.getApplicationIcon(info).toBitmap(px, px).asImageBitmap()
            // sharedUserId apps (rare, deprecated) can't be told apart by UID; say so.
            AppLabel(if (pkgs.size > 1) "$label +${pkgs.size - 1}" else label, pkg, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            AppLabel(pkg, pkg, null) // uninstalled between the two calls
        }
    }

    private fun systemName(uid: Int): String = when (uid % 100_000) { // strip the user/profile id
        0 -> "Root"
        Process.SYSTEM_UID -> "Android system"
        1051 -> "DNS resolver" // AID_DNS
        1052 -> "Network tether" // AID_DNS_TETHER: hotspot clients
        1073 -> "Network stack" // AID_NETWORK_STACK: captive-portal checks
        else -> "System ($uid)"
    }
}

val ICON_SIZE: Dp = 36.dp

@Composable
fun rememberAppLabel(uid: Int): AppLabel? {
    val ctx = LocalContext.current
    // produceState's value isn't keyed on uid, so a recycled row would briefly show the previous
    // app: peek first so a cached label is there on the same frame.
    val label by produceState(AppLabels.peek(uid), uid) { value = AppLabels.peek(uid) ?: AppLabels.load(ctx, uid) }
    return label
}

/** App icon, or a lettered disc for system/unknown UIDs (and while the icon loads). */
@Composable
fun AppIcon(label: AppLabel?, modifier: Modifier = Modifier, size: Dp = ICON_SIZE) {
    val icon = label?.icon
    if (icon != null) {
        Image(icon, contentDescription = label.name, modifier.size(size))
    } else {
        Box(
            modifier.size(size).clip(CircleShape).background(Palette.GraphiteHigh),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label?.name?.firstOrNull()?.uppercase() ?: "",
                color = Palette.TextLo,
                fontSize = (size.value * 0.42f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
