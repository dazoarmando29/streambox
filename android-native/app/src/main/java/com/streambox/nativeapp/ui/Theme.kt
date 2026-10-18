package com.streambox.nativeapp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.streambox.nativeapp.R

/* Obsidian Glow Cinematic tokens (design pack: DESIGN.md). Atmospheric
   dark, never true black; electric teal/cyan luminescence; frosted glass
   layers with ghost borders. These replace the old flat website palette
   while KEEPING the token names every screen already paints with. */
val Accent = Color(0xFF00F0B5)      // Neon Teal: play/CTA/progress/active
val AccentDim = Color(0xFF20D9B4)   // Softer teal: large fills, on-dark text
val Cyan = Color(0xFF00D2FF)        // Electric Cyan: focus traces, highlights
val Ink = Color(0xFF0B111E)         // Deep Obsidian canvas
val Card = Color(0xFF131B2C)        // Surface Low: cards, sheets
val Pill = Color(0xFF1B2539)        // Surface Medium: HUD islands, pills
val Dim = Color(0xFF8E9CAE)         // Muted text
val Soft = Color(0xFFC7D5E8)        // Soft text
val Glass = Color(0x14FFFFFF)       // Glass fill (8% white)
val Stroke = Color(0x1FFFFFFF)      // Ghost border (12% white)
val Amber = Color(0xFFF5A623)       // Parental lock + warnings only

/* Type: Space Grotesk carries the cinematic voice (titles, numerics, HUD
   labels, CTAs); Plus Jakarta Sans carries loglines and metadata. Both
   are SIL OFL and bundled - no network font fetch at runtime. */
val DisplayFont = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)
val BodyFont = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

/** Ambient neon bloom for primary CTAs (Watch Now / Play / Resume): a
    colored halo rather than a flat drop shadow. */
fun Modifier.glow(
    shape: Shape,
    color: Color = Accent,
    elevation: Dp = 18.dp,
): Modifier = this.shadow(elevation, shape, ambientColor = color, spotColor = color)

/** D-pad comfort: a NEON TRACE + bloom along whatever the remote is on.
    Three concentric strokes (wide faint halo -> bright core) fake a blurred
    glow without needing API 31 RenderEffect, so weak TV boxes bloom too.
    The gentle pop stays (0.14 pick-feel rule); the ring hugs the node's
    own shape - pills, circles and posters all get an exact trace. */
fun Modifier.focusRing(
    shape: Shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
    pop: Boolean = true,
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "focusRing",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused && pop) 1.06f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "focusPop",
    )
    onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .drawWithContent {
            drawContent()
            if (ring > 0f) {
                fun trace(width: Dp, alpha: Float) {
                    val stroke = Stroke(width = width.toPx())
                    val c = Accent.copy(alpha = alpha * ring)
                    when (val o = shape.createOutline(size, layoutDirection, this)) {
                        is Outline.Rectangle -> drawRect(color = c, style = stroke)
                        is Outline.Rounded -> drawRoundRect(
                            color = c,
                            topLeft = Offset(o.roundRect.left, o.roundRect.top),
                            size = Size(o.roundRect.width, o.roundRect.height),
                            cornerRadius = o.roundRect.topLeftCornerRadius,
                            style = stroke,
                        )
                        is Outline.Generic -> drawPath(o.path, color = c, style = stroke)
                    }
                }
                trace(10.dp, 0.16f)
                trace(5.dp, 0.32f)
                trace(2.5.dp, 0.95f)
            }
        }
}

/** Round focus ring for round targets (rail buttons, X/Play circles): pure
    drawCircle traces, so a circular control can NEVER pick up a boxy
    outline or plate - the 0.34 TV bug where square highlights showed
    behind the round rail/X buttons. Same 3-stroke bloom language as
    focusRing, same gentle pop. */
fun Modifier.focusRingCircle(pop: Boolean = true): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val ring by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "focusRingCircle",
    )
    val scale by animateFloatAsState(
        targetValue = if (focused && pop) 1.06f else 1f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "focusPopCircle",
    )
    onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .drawWithContent {
            drawContent()
            if (ring > 0f) {
                val r = size.minDimension / 2f
                fun trace(width: Dp, alpha: Float) {
                    drawCircle(
                        color = Accent.copy(alpha = alpha * ring),
                        radius = r,
                        style = Stroke(width = width.toPx()),
                    )
                }
                trace(10.dp, 0.16f)
                trace(5.dp, 0.32f)
                trace(2.5.dp, 0.95f)
            }
        }
}
