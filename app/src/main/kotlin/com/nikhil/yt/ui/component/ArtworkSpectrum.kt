package com.nikhil.yt.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val BANDS = 6

/** Spectra's signature: a song's artwork split into a band of its own colours, dark to light. */
@Composable
fun ArtworkSpectrum(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    thickness: Dp = 3.dp,
) {
    val context = LocalContext.current
    val fallback = MaterialTheme.colorScheme.surfaceContainerHighest
    val sampled by produceState<List<Color>>(emptyList(), imageUrl) {
        if (imageUrl == null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(imageUrl).size(96).allowHardware(false).build()
                val bitmap = context.imageLoader.execute(request).image?.toBitmap() ?: return@runCatching emptyList()
                Palette.from(bitmap).maximumColorCount(16).generate().swatches
                    .sortedByDescending { it.population }
                    .take(BANDS)
                    .sortedBy { it.hsl[2] }
                    .map { Color(it.rgb) }
            }.getOrDefault(emptyList())
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.height(thickness),
    ) {
        for (i in 0 until BANDS) {
            val target = sampled.getOrNull(i) ?: sampled.lastOrNull() ?: fallback
            val color by animateColorAsState(target, tween(450), label = "band$i")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(thickness / 2))
                    .background(color)
            )
        }
    }
}
