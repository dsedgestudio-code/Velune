package com.nikhil.yt.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.media3.exoplayer.offline.Download
import com.nikhil.yt.LocalDownloadUtil
import kotlinx.coroutines.flow.Flow

/** Download status of one song. Remembered: a new flow per recomposition would re-subscribe on every redraw. */
@Composable
fun rememberDownloadFlow(songId: String): Flow<Download?> {
    val downloadUtil = LocalDownloadUtil.current
    return remember(downloadUtil, songId) { downloadUtil.getDownload(songId) }
}
