package com.nikhil.yt.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import com.nikhil.yt.playback.AudioLevelMeter
import kotlin.math.PI
import kotlin.math.sin

/**
 * The player's backdrop: the artwork's own colours as light, not as flat paint.
 * A deep tone of the dominant colour, a glow behind the artwork, softer light from the second and
 * third colours, and a dark scrim at the bottom so controls stay readable. The glow breathes with
 * the music's loudness (the same signal that drives the waveform), slowly enough to never flicker.
 */
@Composable
fun ArtworkAura(colors: List<Color>, modifier: Modifier = Modifier) {
    val primary = colors.first()
    val second = colors.getOrElse(1) { primary }
    val third = colors.getOrElse(2) { second }
    val base = remember(primary) { primary.withHsv(saturation = 0.55f, value = 0.16f) }
    val glow = remember(primary) { primary.withHsv(saturation = 0.7f, value = 0.62f) }
    val light2 = remember(second) { second.withHsv(saturation = 0.6f, value = 0.5f) }
    val light3 = remember(third) { third.withHsv(saturation = 0.6f, value = 0.42f) }

    val level by AudioLevelMeter.level.collectAsState()
    val breath by animateFloatAsState(level, tween(420), label = "auraBreath")
    val drift by rememberInfiniteTransition(label = "auraDrift").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "auraDriftPhase",
    )

    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                val phase = 2f * PI.toFloat() * drift
                drawRect(base)
                glowAt(light2, Offset(w * (0.08f + 0.05f * sin(phase)), h * 0.12f), w * 0.95f, 0.45f)
                glowAt(light3, Offset(w * (0.95f - 0.05f * sin(phase + 2f)), h * 0.62f), w * 0.9f, 0.32f)
                glowAt(glow, Offset(w * 0.5f, h * (0.34f + 0.015f * sin(phase + 1f))), w * (0.85f + 0.12f * breath), 0.5f + 0.2f * breath)
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    )
                )
            }
    )
}

private fun DrawScope.glowAt(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawRect(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.55f to color.copy(alpha = alpha * 0.35f),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        )
    )
}

private fun Color.withHsv(saturation: Float, value: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    // Keep near-greys grey (black-and-white covers stay monochrome), only cap saturation for colours.
    hsv[1] = if (hsv[1] < 0.1f) hsv[1] else minOf(hsv[1], saturation).coerceAtLeast(0.35f)
    hsv[2] = value
    return Color(android.graphics.Color.HSVToColor(hsv))
}
