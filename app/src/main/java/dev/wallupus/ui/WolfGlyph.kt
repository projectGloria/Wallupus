package dev.wallupus.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import dev.wallupus.R

/**
 * The launcher icon's she-wolf as a single-colour mark, tinted to [color]. Drawn from the
 * ic_wolf_glyph vector (even-odd, so eyes/nose are holes) rather than re-plotted in Compose,
 * so the icon and the in-app mark can't drift apart. Aspect ratio is 54:58.
 */
@Composable
fun WolfGlyph(color: Color, modifier: Modifier = Modifier) {
    Image(
        painterResource(R.drawable.ic_wolf_glyph),
        contentDescription = null, // decorative: the button/wordmark next to it carries the meaning
        modifier = modifier,
        colorFilter = ColorFilter.tint(color),
    )
}
