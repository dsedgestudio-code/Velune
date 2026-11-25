package com.nikhil.yt.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R

/** Buffering indicator: the Spectra ghost floating and breathing. */
@Composable
fun VeluneLoader(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color? = null,
) {
    val tint = color ?: MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "spectra_loader")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "float",
    )
    Icon(
        painter = painterResource(R.drawable.ic_spectra_ghost),
        contentDescription = null,
        tint = tint,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                translationY = (0.5f - phase) * size.toPx() * 0.12f
                alpha = 0.6f + 0.4f * phase
                rotationZ = (phase - 0.5f) * 6f
            },
    )
}
