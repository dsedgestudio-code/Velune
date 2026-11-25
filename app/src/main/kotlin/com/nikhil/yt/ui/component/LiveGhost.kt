package com.nikhil.yt.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.yt.R
import com.nikhil.yt.playback.AudioLevelMeter
import java.time.Month
import java.time.LocalDate

/**
 * The Spectra ghost in the top bar, alive: it bobs with the music while something plays, and tilts
 * and dozes (a drifting "z") when paused. In December it wears a hat.
 */
@Composable
fun LiveGhost(isPlaying: Boolean, tint: Color, modifier: Modifier = Modifier, size: Dp = 30.dp) {
    val level by AudioLevelMeter.level.collectAsState()
    val bounce by animateFloatAsState(if (isPlaying) level else 0f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "bounce")
    val tilt by animateFloatAsState(if (isPlaying) 0f else -10f, tween(600), label = "tilt")
    val festive = remember { LocalDate.now().month == Month.DECEMBER }

    Box(modifier.size(size)) {
        Icon(
            painter = painterResource(R.drawable.ic_spectra_ghost),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    translationY = -bounce * size.toPx() * 0.16f
                    scaleY = 1f + bounce * 0.06f
                    scaleX = 1f - bounce * 0.03f
                    rotationZ = tilt
                },
        )
        if (festive) {
            Canvas(Modifier.size(size).graphicsLayer { translationY = -bounce * size.toPx() * 0.16f; rotationZ = tilt }) {
                // A small red hat over the crown of the head, tipped to one side, with a white pom-pom.
                val w = this.size.width
                val hat = Path().apply {
                    moveTo(w * 0.30f, w * 0.20f)
                    lineTo(w * 0.62f, w * 0.17f)
                    lineTo(w * 0.80f, w * -0.06f)
                    close()
                }
                drawPath(hat, Color(0xFFE0383E))
                drawCircle(Color.White, radius = w * 0.06f, center = Offset(w * 0.80f, w * -0.06f))
            }
        }
        if (!isPlaying) {
            // Only ticks while paused, so playing costs nothing extra.
            val doze = rememberInfiniteTransition(label = "doze")
            val z by doze.animateFloat(0f, 1f, infiniteRepeatable(tween(2400), RepeatMode.Restart), label = "z")
            Text(
                "z",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = tint.copy(alpha = (1f - z) * 0.8f),
                modifier = Modifier
                    .offset(x = size * 0.85f, y = -size * 0.05f)
                    .graphicsLayer {
                        translationY = -z * 14.dp.toPx()
                        translationX = z * 4.dp.toPx()
                        val s = 0.7f + z * 0.5f
                        scaleX = s; scaleY = s
                    },
            )
        }
    }
}
