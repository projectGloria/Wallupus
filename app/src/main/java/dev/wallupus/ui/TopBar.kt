package dev.wallupus.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.wallupus.R

/** One segment of the tab control: a word, or an icon with a spoken label. */
sealed class Tab(val width: Dp) {
    class Label(val text: String) : Tab(62.dp)
    class Icon(@DrawableRes val res: Int, val description: String) : Tab(44.dp)
}

val Tabs = listOf(Tab.Label("Home"), Tab.Label("Log"), Tab.Label("Lists"), Tab.Icon(R.drawable.ic_settings, "Settings"))

/**
 * Wolf glyph, the DNS badge, then the tab control. The text wordmark is gone: four segments plus
 * the badge only fit a 360 dp phone without it, and the glyph alone carries the brand.
 */
@Composable
fun TopBar(tab: Int, onTab: (Int) -> Unit, dnsIcon: String?, dnsActive: Boolean, onDnsToggle: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WolfGlyph(Palette.Crimson, Modifier.size(width = 24.dp, height = 26.dp))
        Spacer(Modifier.width(12.dp))
        DnsBadge(dnsIcon, dnsActive, onDnsToggle)
        Spacer(Modifier.weight(1f))
        Segmented(Tabs, tab, onTab)
    }
}

/**
 * The active resolver's logo: full colour in a crimson ring while lookups go to it, greyscale
 * when DNS is off (the network's own resolver answers). Tap toggles; works with blocking off.
 */
@Composable
private fun DnsBadge(icon: String?, active: Boolean, onToggle: (Boolean) -> Unit) {
    val ring by animateColorAsState(if (active) Palette.Crimson else Palette.Line, label = "dnsRing")
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Palette.Graphite)
            .border(1.5.dp, ring, CircleShape)
            .toggleable(active, role = Role.Switch, onValueChange = onToggle)
            .semantics { contentDescription = "Encrypted DNS" },
        contentAlignment = Alignment.Center,
    ) {
        ResolverLogo(icon, size = 20.dp, muted = !active)
    }
}

private val SEGMENT_H = 34.dp

/** Pill with a thumb that springs between options. */
@Composable
private fun Segmented(options: List<Tab>, selected: Int, onSelect: (Int) -> Unit) {
    // Segments differ in width now, so the thumb's x is the sum of the widths before it.
    val thumbX by animateDpAsState(
        options.take(selected).fold(0.dp) { acc, t -> acc + t.width },
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "thumb",
    )
    val thumbW by animateDpAsState(
        options[selected].width,
        spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
        label = "thumbW",
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
                .size(thumbW, SEGMENT_H)
                .clip(CircleShape)
                .background(Palette.GraphiteHigh)
                .border(1.dp, Palette.Line, CircleShape),
        )
        Row(Modifier.selectableGroup()) {
            options.forEachIndexed { i, option ->
                val color by animateColorAsState(if (i == selected) Palette.Crimson else Palette.TextLo, label = "seg")
                Box(
                    Modifier
                        .size(option.width, SEGMENT_H)
                        .clip(CircleShape)
                        .selectable(i == selected, role = Role.Tab, onClick = { onSelect(i) }),
                    contentAlignment = Alignment.Center,
                ) {
                    when (option) {
                        is Tab.Label -> Text(option.text, style = MaterialTheme.typography.labelLarge, color = color)
                        is Tab.Icon -> Image(
                            painterResource(option.res),
                            contentDescription = option.description,
                            modifier = Modifier.size(20.dp),
                            colorFilter = ColorFilter.tint(color),
                        )
                    }
                }
            }
        }
    }
}
