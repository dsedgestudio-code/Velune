package com.nikhil.yt.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Home's mood dial: drag round the arc from calm to hype, slide from new to familiar, then play.
 * The word in the middle is the mood you'll get, so the dial never feels abstract.
 *
 * The dial moves on local state and only saves when you let go: saving on every drag frame wrote to disk
 * and recomposed all of Home 60 times a second, which is what made it lag.
 */
@Composable
fun MoodDial(
    energy: Float,
    familiarity: Float,
    onEnergyChange: (Float) -> Unit,
    onFamiliarityChange: (Float) -> Unit,
    onPlay: (energy: Float, familiarity: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val moods = stringArrayResource(R.array.mood_ladder)
    var dial by remember { mutableFloatStateOf(energy) }
    var fam by remember { mutableFloatStateOf(familiarity) }
    LaunchedEffect(energy) { dial = energy } // e.g. restored per-device or from another screen
    LaunchedEffect(familiarity) { fam = familiarity }
    val step = (dial * moods.lastIndex).roundToInt().coerceIn(0, moods.lastIndex)
    val mood = moods[step]
    var lastStep by remember { mutableIntStateOf(step) }
    val haptics = LocalHapticFeedback.current
    val commitEnergy by rememberUpdatedState(onEnergyChange)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val calm = MaterialTheme.colorScheme.tertiary
    val hype = MaterialTheme.colorScheme.primary
    val panel = MaterialTheme.colorScheme.surfaceContainer
    val dialLabel = stringResource(R.string.mood_dial)

    fun moveTo(value: Float) {
        dial = value.coerceIn(0f, 1f)
        val s = (dial * moods.lastIndex).roundToInt()
        if (s != lastStep) {
            lastStep = s
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) // once per mood, not per frame
        }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(20.dp),
    ) {
        Text(stringResource(R.string.mood_dial), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth().aspectRatio(2f)) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f)
                    .semantics {
                        contentDescription = dialLabel
                        stateDescription = mood
                        progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(dial, 0f..1f, moods.size - 2)
                        setProgress { moveTo(it); commitEnergy(dial); true }
                    }
                    .pointerInput(Unit) {
                        fun energyAt(p: Offset): Float {
                            val angle = atan2(size.height - p.y, p.x - size.width / 2f) // PI at the left (calm), 0 at the right (hype)
                            return 1f - (angle / PI.toFloat()).coerceIn(0f, 1f)
                        }
                        detectDragGestures(
                            onDragStart = { moveTo(energyAt(it)) },
                            onDragEnd = { commitEnergy(dial) },
                            onDragCancel = { commitEnergy(dial) },
                        ) { change, _ -> moveTo(energyAt(change.position)) }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            val angle = atan2(size.height - p.y, p.x - size.width / 2f)
                            moveTo(1f - (angle / PI.toFloat()).coerceIn(0f, 1f))
                            commitEnergy(dial)
                        }
                    },
            ) {
                val stroke = 18.dp.toPx()
                val radius = size.width / 2f - stroke
                val topLeft = Offset(size.width / 2f - radius, size.height - radius)
                val arc = Size(radius * 2, radius * 2)
                drawArc(track, 180f, 180f, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(
                    Brush.horizontalGradient(listOf(calm, hype)),
                    180f, 180f * dial, false, topLeft, arc,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                val theta = PI.toFloat() * (1f - dial)
                val knob = Offset(size.width / 2f + radius * cos(theta), size.height - radius * sin(theta))
                drawCircle(hype, radius = stroke * 0.9f, center = knob)
                drawCircle(panel, radius = stroke * 0.42f, center = knob)
            }
            Text(
                mood,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mood_new), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = fam,
                onValueChange = { fam = it },
                onValueChangeFinished = { onFamiliarityChange(fam) },
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(stringResource(R.string.mood_familiar), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { onEnergyChange(dial); onFamiliarityChange(fam); onPlay(dial, fam) }) {
                Text(stringResource(R.string.mood_play, mood))
            }
        }
    }
}
