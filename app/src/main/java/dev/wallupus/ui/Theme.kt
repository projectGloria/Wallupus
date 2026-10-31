package dev.wallupus.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Brand palette: blood crimson on near-black, matching the she-wolf launcher icon. Fixed rather
 * than Material You, since wallpaper-derived colours made the app look like every stock M3 app.
 * Crimson means "active" here, so warnings use [Ember] (orange) to stay distinguishable from it.
 */
object Palette {
    val Ink = Color(0xFF0A0708) // window background (also res/values/colors.xml, for cold start)
    val Graphite = Color(0xFF151012) // panels
    val GraphiteHigh = Color(0xFF1F171A) // raised: panel top edge, selected segment
    val Line = Color(0xFF2C2125) // hairline borders
    val DiscTop = Color(0xFF2C2226) // idle button, lit corner
    val DiscBottom = Color(0xFF130E10) // idle button, shaded corner

    val Crimson = Color(0xFFE3324E) // the brand red: lit dial, accents, "blocked"
    val CrimsonBright = Color(0xFFF2526B) // lit button, highlight corner
    val CrimsonDeep = Color(0xFF7E0C22) // lit button, shaded corner; background aura
    val OnCrimson = Color(0xFFFFF3F4)
    val Bone = Color(0xFFF3D9DC) // the icon's moon: "allowed", neutral highlights

    val Amber = Color(0xFFF2B35E)
    val Ember = Color(0xFFFF8A4C) // warnings and errors
    val EmberWash = Color(0xFF2A1710)

    val TextHi = Color(0xFFF1E9EA)
    val TextLo = Color(0xFF9A8D90)

    /**
     * Domain monogram discs in the Log: muted, low-chroma tints so a screen full of them stays
     * quiet next to the crimson accents. Indexed by `monogram()`'s stable hash.
     */
    val Monograms = listOf(
        Color(0xFF3A2A30), Color(0xFF2C3238), Color(0xFF33302A),
        Color(0xFF2A3530), Color(0xFF362B3A), Color(0xFF3A3028),
    )
}

private val Scheme = darkColorScheme(
    primary = Palette.Crimson,
    onPrimary = Palette.OnCrimson,
    primaryContainer = Color(0xFF3D0F1A),
    onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Palette.TextLo,
    onSecondary = Palette.Ink,
    secondaryContainer = Palette.GraphiteHigh,
    onSecondaryContainer = Palette.TextHi,
    background = Palette.Ink,
    onBackground = Palette.TextHi,
    surface = Palette.Ink,
    onSurface = Palette.TextHi,
    surfaceVariant = Palette.GraphiteHigh,
    onSurfaceVariant = Palette.TextLo,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Color(0xFF110D0E),
    surfaceContainer = Palette.Graphite,
    surfaceContainerHigh = Palette.GraphiteHigh,
    surfaceContainerHighest = Color(0xFF261D20),
    outline = Color(0xFF45373B),
    outlineVariant = Palette.Line,
    error = Palette.Ember,
    onError = Color(0xFF3B1505),
    errorContainer = Palette.EmberWash,
    onErrorContainer = Color(0xFFFFCDB3),
)

/** Small spaced caps for section labels ("BLOCKLIST", "QUERIES"). */
val Overline = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.8.sp)

/** Tabular figures: every digit is the same width, so rolling counters don't jitter sideways. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Always dark, whatever the system setting: the glow and rings only read on a dark background. */
@Composable
fun WallupusTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}

private val PanelShape = RoundedCornerShape(22.dp)

// Lighter at the top edge, like light falling on a raised slab: cheap depth without shadows,
// which render poorly on near-black anyway.
private val PanelBrush = Brush.verticalGradient(listOf(Palette.GraphiteHigh, Palette.Graphite))

/** The app's card: graphite slab, hairline border, generous padding. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    brush: Brush = PanelBrush,
    border: Color = Palette.Line,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .background(brush)
            .border(1.dp, border, PanelShape)
            .padding(18.dp),
        content = content,
    )
}
