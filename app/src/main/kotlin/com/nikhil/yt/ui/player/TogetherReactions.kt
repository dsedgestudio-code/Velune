package com.nikhil.yt.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.R
import com.nikhil.yt.together.TogetherController
import com.nikhil.yt.together.TogetherReaction
import com.nikhil.yt.together.TogetherSessionState
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private val REACTIONS = listOf("🔥", "❤️", "😂", "😭", "🙌")

private fun TogetherSessionState.canReact() =
    this is TogetherSessionState.Hosting || (this is TogetherSessionState.Joined && !isPending)

/** Five quick reactions under the controls, only while you're in a session. Otherwise just the usual gap. */
@Composable
fun TogetherReactionBar(controller: TogetherController, tint: Color) {
    val state by controller.state.collectAsState()
    if (!state.canReact()) {
        Spacer(Modifier.height(30.dp))
        return
    }
    val haptics = LocalHapticFeedback.current
    val label = stringResource(R.string.together_react)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        REACTIONS.forEach { emoji ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.10f))
                    .clickable(role = Role.Button, onClickLabel = "$label $emoji") {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        controller.react(emoji)
                    },
            ) { Text(emoji, fontSize = 20.sp) }
        }
    }
}

private class Floating(val id: Long, val reaction: TogetherReaction, val x: Float, val sway: Float)

/** Reactions from everyone in the session drift up over the player and fade, with the sender's name. */
@Composable
fun TogetherReactionLayer(controller: TogetherController) {
    val items = remember { mutableStateListOf<Floating>() }
    LaunchedEffect(controller) {
        var next = 0L
        controller.reactions.collect { r ->
            items += Floating(next++, r, x = Random.nextFloat() * 0.6f + 0.2f, sway = if (Random.nextBoolean()) 1f else -1f)
            if (items.size > 20) items.removeAt(0)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val swayPx = with(LocalDensity.current) { 14.dp.toPx() }
        items.forEach { item ->
            key(item.id) {
                val t = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    t.animateTo(1f, tween(2800, easing = LinearEasing))
                    items.remove(item)
                }
                val p = t.value
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer {
                        val eased = 1f - (1f - p) * (1f - p) // ease-out rise
                        translationX = widthPx * item.x - size.width / 2f + sin(p * 2f * PI.toFloat()) * swayPx * item.sway
                        translationY = heightPx * (0.72f - 0.45f * eased)
                        alpha = when {
                            p < 0.08f -> p / 0.08f
                            p > 0.65f -> ((1f - p) / 0.35f).coerceAtLeast(0f)
                            else -> 1f
                        }
                        val pop = if (p < 0.12f) 0.6f + 0.4f * (p / 0.12f) else 1f
                        scaleX = pop
                        scaleY = pop
                    },
                ) {
                    Text(item.reaction.emoji, fontSize = 40.sp)
                    Text(
                        item.reaction.name,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = Color.White,
                            shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
                        ),
                    )
                }
            }
        }
    }
}
