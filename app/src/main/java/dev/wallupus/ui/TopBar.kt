package dev.wallupus.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Wordmark on the left, Home/Log segmented control on the right. */
@Composable
fun TopBar(tab: Int, onTab: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WolfGlyph(Palette.Crimson, Modifier.size(width = 24.dp, height = 26.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "Wallupus",
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
            color = Palette.TextHi,
        )
        Segmented(listOf("Home", "Log"), tab, onTab)
    }
}

private val SEGMENT_W = 68.dp
private val SEGMENT_H = 34.dp

/** Pill with a thumb that springs between options. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val thumbX by animateDpAsState(
        SEGMENT_W * selected,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "thumb",
    )
    Box(
        Modifier
            .clip(CircleShape)
            .background(Palette.Graphite)
            .border(1.dp, Palette.Line, CircleShape)
            .padding(3.dp),
    ) {
        Box(
            Modifier
                // Lambda offset: the thumb's position is read during layout, so its spring
                // moves it without recomposing the bar.
                .offset { IntOffset(thumbX.roundToPx(), 0) }
                .size(SEGMENT_W, SEGMENT_H)
                .clip(CircleShape)
                .background(Palette.GraphiteHigh)
                .border(1.dp, Palette.Line, CircleShape),
        )
        Row(Modifier.selectableGroup()) {
            options.forEachIndexed { i, label ->
                val color by animateColorAsState(if (i == selected) Palette.Crimson else Palette.TextLo, label = "seg")
                Box(
                    Modifier
                        .size(SEGMENT_W, SEGMENT_H)
                        .clip(CircleShape)
                        .selectable(i == selected, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
                }
            }
        }
    }
}
