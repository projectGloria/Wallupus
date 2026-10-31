package dev.wallupus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wallupus.R

// Explicit map, not Resources.getIdentifier: R8's resource shrinker can only see references it
// can trace, and would strip logos that are only reachable by a name built at runtime.
private fun logoRes(icon: String?): Int? = when (icon) {
    "quad9" -> R.drawable.ic_dns_quad9
    "cloudflare" -> R.drawable.ic_dns_cloudflare
    "mullvad" -> R.drawable.ic_dns_mullvad
    "adguard" -> R.drawable.ic_dns_adguard
    "google" -> R.drawable.ic_dns_google
    else -> null
}

// Shared instance: a desaturating filter is a constant, no need to build one per frame.
private val Greyscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * A resolver's bundled logo in brand colours, or a tinted globe for custom/unknown ones.
 * [muted] = DNS switched off: greyscale and dimmed, so the colour itself says "active".
 */
@Composable
fun ResolverLogo(icon: String?, modifier: Modifier = Modifier, size: Dp = 32.dp, muted: Boolean = false) {
    val res = logoRes(icon)
    Image(
        painterResource(res ?: R.drawable.ic_dns_custom),
        contentDescription = null, // the resolver's name is always shown next to it
        modifier = modifier.size(size),
        alpha = if (muted) 0.45f else 1f,
        colorFilter = when {
            res == null -> ColorFilter.tint(if (muted) Palette.TextLo else Palette.TextHi)
            muted -> Greyscale
            else -> null
        },
    )
}
