package dev.wallupus.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import dev.wallupus.R

/**
 * The brand artwork (the she-wolf from Wallupus.png) in a circle. [muted] drops it to a dim
 * greyscale, for "off" states: crimson means "active" across the app, and the art's red field
 * would otherwise read as on. Size it with [modifier]; the art is square.
 */
@Composable
fun WolfMark(modifier: Modifier = Modifier, muted: Boolean = false) {
    val filter = remember(muted) {
        if (!muted) null
        else ColorFilter.colorMatrix(
            ColorMatrix().apply { setToSaturation(0f) }.apply { timesAssign(ColorMatrix().apply { setToScale(0.6f, 0.6f, 0.6f, 1f) }) },
        )
    }
    Image(
        painterResource(R.drawable.wallupus_art),
        contentDescription = null, // decorative: the button or bar around it carries the meaning
        modifier = modifier.clip(CircleShape),
        contentScale = ContentScale.Crop,
        colorFilter = filter,
    )
}
