package com.nikhil.yt.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

private const val BAR_COUNT = 56
private const val MIN_HEIGHT_FRACTION = 0.08f
private const val LIVE_REACH = 4
private const val LENS_REACH = 6f

/**
 * Spectra's seek bar: the song drawn as its own waveform.
 * - [shape]: the song's real loudness (0..1 per slot, any length). Slots not learned yet draw as a calm
 *   low line that fills in as the song plays.
 * - [liveLevel]: current loudness. While [isPlaying], a ripple travels through the bars at that strength,
 *   the bars at the playhead bounce, and the playhead glows with the beat. Paused, everything settles.
 * - A new [revealKey] (the song) makes the bars rise into place left to right.
 * - Dragging magnifies the bars under the finger so the landing point is precise.
 */
@Composable
fun WaveformSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    activeColor: Color,
    modifier: Modifier = Modifier,
    activeColorEnd: Color = activeColor,
    inactiveColor: Color = activeColor.copy(alpha = 0.18f),
    shape: FloatArray? = null,
    liveLevel: State<Float>? = null,
    isPlaying: Boolean = liveLevel != null,
    revealKey: Any? = null,
    barCount: Int = BAR_COUNT,
) {
    val span = valueRange.endInclusive - valueRange.start
    val fraction = if (span == 0f) 0f else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastTickBar by remember { mutableIntStateOf(-1) }

    val fallback = remember(barCount) {
        FloatArray(barCount) { i ->
            val t = i.toFloat() / barCount
            val w = abs(sin(t * 11.3f + 0.7f)) + abs(sin(t * 17.1f + 2.3f)) * 0.6f
            (w / 1.6f * 0.35f).coerceIn(MIN_HEIGHT_FRACTION, 0.35f)
        }
    }
    val bars = remember(shape, barCount) {
        if (shape == null || shape.isEmpty()) fallback
        else FloatArray(barCount) { i ->
            val from = i * shape.size / barCount
            val to = maxOf(from + 1, (i + 1) * shape.size / barCount)
            var peak = 0f
            for (j in from until minOf(to, shape.size)) peak = maxOf(peak, shape[j])
            if (peak > 0f) peak.coerceIn(MIN_HEIGHT_FRACTION, 1f) else fallback[i]
        }
    }

    // Ripple phase advances only while playing, so a paused player costs no frames.
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) phase = (phase + (now - last) / 1_000_000_000f * 1.6f) % 1000f
                last = now
            }
        }
    }
    val live by animateFloatAsState(if (isPlaying) liveLevel?.value ?: 0f else 0f, tween(110), label = "live")
    val ripple by animateFloatAsState(if (isPlaying) 1f else 0f, tween(600), label = "ripple")
    val lens by animateFloatAsState(if (dragging) 1f else 0f, tween(180), label = "lens")

    val reveal = remember { Animatable(1f) }
    LaunchedEffect(revealKey) {
        if (revealKey == null) return@LaunchedEffect
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }

    fun seekTo(x: Float, width: Int, finished: Boolean) {
        val f = (x / width).coerceIn(0f, 1f)
        dragFraction = f
        val bar = (f * barCount).toInt()
        if (bar != lastTickBar) {
            lastTickBar = bar
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        onValueChange(valueRange.start + f * span)
        if (finished) onValueChangeFinished()
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { target ->
                    onValueChange(target.coerceIn(valueRange.start, valueRange.endInclusive))
                    onValueChangeFinished()
                    true
                }
            }
            .pointerInput(valueRange) {
                detectTapGestures { offset -> seekTo(offset.x, size.width, finished = true) }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = true; dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { dragging = false; onValueChangeFinished() },
                    onDragCancel = { dragging = false; onValueChangeFinished() },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        seekTo(change.position.x, size.width, finished = false)
                    }
                )
            }
    ) {
        val gap = 2.dp.toPx()
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val playheadBar = fraction * barCount
        val lensCenter = dragFraction * barCount
        val revealProgress = reveal.value
        val twoPi = 2f * PI.toFloat()

        for (i in 0 until barCount) {
            val position = i.toFloat() / barCount
            var h = bars[i]

            // Travelling ripple: strongest in what has played, a whisper ahead of the playhead.
            val played = position <= fraction
            val rippleStrength = ripple * (0.06f + 0.30f * live) * (if (played) 1f else 0.35f)
            h *= 1f + rippleStrength * sin(i * 0.55f - phase * twoPi)

            // Bars at the playhead bounce with the beat.
            val distance = abs(i - playheadBar)
            if (distance <= LIVE_REACH && live > 0f) {
                val falloff = 1f - distance / (LIVE_REACH + 1f)
                h *= 0.75f + 0.6f * live * falloff
            }

            // Seeking lens: magnify the bars under the finger.
            if (lens > 0f) {
                val d = (i - lensCenter) / LENS_REACH
                h *= 1f + 0.55f * lens * exp(-d * d * 2f)
            }

            // Song change: bars rise into place left to right.
            val rise = ((revealProgress * 1.6f) - position * 0.6f).coerceIn(0f, 1f)
            h *= rise

            val barHeight = h.coerceIn(0f, 1f) * size.height
            if (barHeight <= 0f) continue
            val color = if (played) lerp(activeColor, activeColorEnd, position) else inactiveColor
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }

        // Playhead: a slim capsule with a soft glow that pulses with the music.
        val x = (fraction * size.width).coerceIn(barWidth / 2f, size.width - barWidth / 2f)
        val headColor = lerp(activeColor, activeColorEnd, fraction)
        val glowRadius = size.height * (0.32f + 0.22f * live + 0.15f * lens)
        drawCircle(
            brush = Brush.radialGradient(
                0f to headColor.copy(alpha = 0.16f + 0.14f * live),
                1f to Color.Transparent,
                center = Offset(x, size.height / 2f),
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = Offset(x, size.height / 2f),
        )
        val capsuleWidth = 3.dp.toPx()
        drawRoundRect(
            color = headColor,
            topLeft = Offset(x - capsuleWidth / 2f, 0f),
            size = Size(capsuleWidth, size.height),
            cornerRadius = CornerRadius(capsuleWidth / 2f, capsuleWidth / 2f),
        )
    }
}
