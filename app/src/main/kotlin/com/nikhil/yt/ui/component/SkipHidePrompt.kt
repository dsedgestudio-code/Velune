package com.nikhil.yt.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nikhil.yt.LocalPlayerAwareWindowInsets
import com.nikhil.yt.R
import com.nikhil.yt.playback.MusicService

/** "You always skip X. Hide it from autoplay?" — asked once per song, with Undo after hiding. */
@Composable
fun SkipHidePrompt(service: MusicService) {
    val host = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(service) {
        service.hidePrompts.collect { (id, title) ->
            val answer = host.showSnackbar(
                message = context.getString(R.string.skip_hide_prompt, title),
                actionLabel = context.getString(R.string.skip_hide),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            if (answer != SnackbarResult.ActionPerformed) return@collect
            service.skipHabits.setHidden(id, true)
            val undo = host.showSnackbar(context.getString(R.string.skip_hidden), context.getString(R.string.undo))
            if (undo == SnackbarResult.ActionPerformed) service.skipHabits.setHidden(id, false)
        }
    }
    Box(Modifier.fillMaxSize()) {
        SnackbarHost(
            hostState = host,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        )
    }
}
