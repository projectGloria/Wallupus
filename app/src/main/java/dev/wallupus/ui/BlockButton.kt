package dev.wallupus.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class BlockState { Off, Connecting, On }

private val DISC = 172.dp
private val DIAL_GAP = 16.dp // disc edge → inner end of the tick ring
private val OUTER = 304.dp // room for the sonar rings beyond the dial
private const val TICKS = 72 // one every 5°, a long one every 30°
private const val RINGS = 3

/**
 * The Home tab's on/off control: a glassy disc carrying the brand funnel, inside a tick dial.
 *
 * Every per-frame value (energy, glow, dial, rings, press, pulse) is read inside a draw or
 * graphicsLayer lambda, never in the composable body, so a running animation only invalidates the
 * draw phase. Composition and layout don't re-run at 60 fps just to move a circle.
 */
@Composable
fun BlockButton(
    state: BlockState,
    blockedCount: Long,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Low damping gives a small overshoot on release, so the disc feels springy rather than linear.
    val pressScale by animateFloatAsState(
        if (pressed) 0.93f else 1f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "press",
    )

    // One transition for every state-dependent property, so they all retarget together when the
    // state flips mid-animation (e.g. tap on, consent denied half a second later).
    val transition = updateTransition(state, label = "block")
    // 0 = idle graphite, 1 = lit crimson. The disc's gradient is a lerp on this single number.
    val energy by transition.animateFloat({ tween(550, easing = FastOutSlowInEasing) }, label = "energy") {
        when (it) {
            BlockState.Off -> 0f
            BlockState.Connecting -> 0.15f
            BlockState.On -> 1f
        }
    }
    // Slower and springier than the fill, so the aura blooms just after the disc lights up.
    val glow by transition.animateFloat(
        { spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessVeryLow) },
        label = "glow",
    ) { if (it == BlockState.On) 1f else 0f }
    val ink by transition.animateColor({ tween(450) }, label = "ink") {
        if (it == BlockState.On) Palette.OnCrimson else Palette.TextHi
    }

    // Brief flash each time the blocked count goes up. The counts arrive sampled at 2 Hz
    // (HomeScreen), so even a burst of ad traffic can't pulse faster than that.
    val pulse = remember { Animatable(0f) }
    val latestCount by rememberUpdatedState(blockedCount)
    LaunchedEffect(pulse) {
        // drop(1): no flash on first composition (e.g. switching back to the Home tab).
        // collectLatest: a new block cancels the running flash and restarts it from the top.
        snapshotFlow { latestCount }.drop(1).collectLatest { n ->
            if (n == 0L) return@collectLatest // session reset on stop, not a block
            pulse.snapTo(1f)
            pulse.animateTo(0f, tween(500, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier.size(OUTER), contentAlignment = Alignment.Center) {
        // Static layer: crimson aura (On) and the dim dial. No clock here, so Off costs nothing.
        Spacer(
            Modifier.size(OUTER).drawBehind {
                val g = glow * 0.9f + pulse.value * 0.4f
                if (g > 0.01f) {
                    val r = size.minDimension / 2
                    drawCircle(
                        Brush.radialGradient(listOf(Palette.Crimson.copy(alpha = 0.26f * g), Color.Transparent), center, r),
                        radius = r,
                    )
                }
                drawTicks(dialRadius(), Palette.TextLo) { 0.22f }
            },
        )
        // AnimatedVisibility removes these from composition once their exit fade ends, which
        // also stops their infinite transitions: an idle screen ticks no frames at all.
        AnimatedVisibility(state == BlockState.On, enter = fadeIn(tween(700)), exit = fadeOut(tween(250))) {
            ActiveHalo(Modifier.size(OUTER))
        }
        AnimatedVisibility(state == BlockState.Connecting, enter = fadeIn(), exit = fadeOut()) {
            ConnectingSweep(Modifier.size(OUTER))
        }

        Box(
            Modifier
                .size(DISC)
                .graphicsLayer {
                    val s = pressScale * (1f + 0.035f * pulse.value)
                    scaleX = s
                    scaleY = s
                }
                // Before clip(): a soft dark pool under the disc that lifts it off the page.
                .drawBehind {
                    val c = center + Offset(0f, 12.dp.toPx())
                    val r = size.minDimension * 0.62f
                    drawCircle(
                        Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent), c, r),
                        radius = r,
                        center = c,
                    )
                }
                .clip(CircleShape)
                .drawBehind {
                    val e = energy
                    // Diagonal light: lit upper-left, shaded lower-right, in both states.
                    drawRect(
                        Brush.linearGradient(
                            listOf(lerp(Palette.DiscTop, Palette.CrimsonBright, e), lerp(Palette.DiscBottom, Palette.CrimsonDeep, e)),
                            start = Offset.Zero,
                            end = Offset(size.width, size.height),
                        ),
                    )
                    // Glass sheen: a soft highlight near the top edge.
                    val sheen = Offset(size.width * 0.32f, size.height * 0.2f)
                    drawCircle(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.12f), Color.Transparent), sheen, size.width * 0.5f),
                        radius = size.width * 0.5f,
                        center = sheen,
                    )
                }
                // Hairline rim, bright on top and fading out below, like light catching an edge.
                .border(
                    1.dp,
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.03f))),
                    CircleShape,
                )
                // toggleable + Role.Switch: TalkBack announces an on/off switch, like the old Switch.
                .toggleable(
                    value = state != BlockState.Off,
                    interactionSource = interaction,
                    indication = null, // the press scale is the feedback; a square ripple would fight it
                    enabled = state != BlockState.Connecting, // no second consent dialog on a double tap
                    role = Role.Switch,
                ) { on ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onToggle(on)
                }
                .semantics {
                    stateDescription = when (state) {
                        BlockState.Off -> "Off"
                        BlockState.Connecting -> "Connecting"
                        BlockState.On -> "Blocking"
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                WolfGlyph(ink, Modifier.size(width = 44.dp, height = 47.dp))
                Spacer(Modifier.size(12.dp))
                AnimatedContent(
                    state,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 80)) + slideInVertically(tween(300)) { it / 2 }) togetherWith
                            fadeOut(tween(100))
                    },
                    label = "label",
                ) { s ->
                    Text(
                        when (s) {
                            BlockState.Off -> "BLOCK"
                            BlockState.Connecting -> "STARTING"
                            BlockState.On -> "BLOCKING"
                        },
                        style = Overline.copy(fontSize = 14.sp, letterSpacing = 3.sp),
                        color = ink,
                    )
                }
            }
        }
    }
}

/**
 * "● PROTECTED"-style pill above the button; the dot breathes while blocking. [dnsOnly]: the
 * tunnel is up for encrypted DNS alone, so "not protected" would read as "nothing is running".
 */
@Composable
fun StatusChip(state: BlockState, dnsOnly: Boolean, modifier: Modifier = Modifier) {
    val (text, target) = when (state) {
        BlockState.Off -> (if (dnsOnly) "ENCRYPTED DNS ONLY" else "NOT PROTECTED") to Palette.TextLo
        BlockState.Connecting -> "CONNECTING" to Palette.Amber
        BlockState.On -> "PROTECTED" to Palette.Crimson
    }
    val dot by animateColorAsState(target, tween(400), label = "dot")
    Row(
        modifier
            .clip(CircleShape)
            .background(Palette.Graphite)
            .border(1.dp, Palette.Line, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp), contentAlignment = Alignment.Center) {
            if (state == BlockState.On) Breath(dot)
            Spacer(Modifier.size(8.dp).background(dot, CircleShape))
        }
        Spacer(Modifier.width(10.dp))
        AnimatedContent(text, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "chip") {
            Text(it, style = Overline, color = Palette.TextHi)
        }
    }
}

/** Halo expanding out of the status dot. Its own composable so the clock only runs while On. */
@Composable
private fun Breath(color: Color) {
    val t by rememberInfiniteTransition(label = "breath").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing)), label = "t",
    )
    Spacer(
        Modifier.size(8.dp).drawBehind {
            drawCircle(color, radius = size.minDimension / 2 * (1f + 1.4f * t), alpha = 0.45f * (1f - t))
        },
    )
}

/** Blocking: lit dial turning slowly, plus sonar rings drifting out past it. */
@Composable
private fun ActiveHalo(modifier: Modifier) {
    val clock = rememberInfiniteTransition(label = "halo")
    val t by clock.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "rings")
    val spin by clock.animateFloat(0f, 360f, infiniteRepeatable(tween(30_000, easing = LinearEasing)), label = "spin")
    Spacer(
        modifier.drawBehind {
            val dial = dialRadius()
            // Two bright lobes opposite each other; rotating the whole dial makes them orbit.
            drawTicks(dial, Palette.Crimson, rotation = spin) { i ->
                val lobe = (1f + cos(i * 2f * 2f * PI.toFloat() / TICKS)) / 2f
                0.25f + 0.65f * lobe * lobe
            }
            val start = dial + 14.dp.toPx()
            val max = size.minDimension / 2
            val stroke = Stroke(1.5.dp.toPx())
            repeat(RINGS) { i ->
                val p = (t + i / RINGS.toFloat()) % 1f
                // Ease the radius rather than the clock, so rings decelerate as they fade out.
                val r = start + (max - start) * FastOutSlowInEasing.transform(p)
                drawCircle(Palette.Crimson, radius = r, alpha = 0.35f * (1f - p), style = stroke)
            }
        },
    )
}

/** Connecting: a comet of lit ticks chasing around the dial. */
@Composable
private fun ConnectingSweep(modifier: Modifier) {
    val head by rememberInfiniteTransition(label = "sweep").animateFloat(
        0f, TICKS.toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "head",
    )
    Spacer(
        modifier.drawBehind {
            drawTicks(dialRadius(), Palette.Crimson) { i ->
                val behind = ((head - i) % TICKS + TICKS) % TICKS // how far this tick trails the head
                if (behind > TAIL) 0f else 1f - behind / TAIL
            }
        },
    )
}

private const val TAIL = 22f

private fun DrawScope.dialRadius() = DISC.toPx() / 2 + DIAL_GAP.toPx()

/** Radial tick marks around [center]; [alpha] per tick, 0 skips it. Tick 0 sits at 12 o'clock. */
private inline fun DrawScope.drawTicks(
    radius: Float,
    color: Color,
    rotation: Float = 0f,
    alpha: (Int) -> Float,
) {
    val short = 5.dp.toPx()
    val long = 10.dp.toPx()
    val width = 1.5.dp.toPx()
    for (i in 0 until TICKS) {
        val a = alpha(i)
        if (a <= 0.01f) continue
        val len = if (i % 6 == 0) long else short
        val rad = ((i * 360f / TICKS + rotation - 90f) * PI / 180f).toFloat()
        val dx = cos(rad)
        val dy = sin(rad)
        drawLine(
            color,
            Offset(center.x + dx * radius, center.y + dy * radius),
            Offset(center.x + dx * (radius + len), center.y + dy * (radius + len)),
            strokeWidth = width,
            cap = StrokeCap.Round,
            alpha = a.coerceAtMost(1f),
        )
    }
}
