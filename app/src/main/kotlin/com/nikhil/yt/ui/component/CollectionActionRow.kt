package com.nikhil.yt.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nikhil.yt.R

/**
 * Header actions for albums and playlists: quiet secondary icons on the left,
 * shuffle and one dominant play button on the right.
 */
@Composable
fun CollectionActionRow(
    onPlay: (() -> Unit)?,
    onShuffle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondary: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        secondary()
        Spacer(Modifier.weight(1f))
        if (onShuffle != null) {
            FilledTonalIconButton(onClick = onShuffle, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.shuffle), stringResource(R.string.shuffle), Modifier.size(22.dp))
            }
        }
        if (onPlay != null) {
            FilledIconButton(onClick = onPlay, modifier = Modifier.padding(start = 8.dp, end = 8.dp).size(60.dp)) {
                Icon(painterResource(R.drawable.play), stringResource(R.string.play), Modifier.size(30.dp))
            }
        }
    }
}

/** A quiet icon action for [CollectionActionRow]'s secondary slot. */
@Composable
fun CollectionAction(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), contentDescription, tint = tint, modifier = Modifier.size(24.dp))
    }
}
