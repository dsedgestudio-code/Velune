package com.nikhil.yt.playback

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Pauses after a set time. The music doesn't stop dead: over the last stretch (up to 10 minutes,
 * at most half the timer) [fade] eases the volume down, so you drift off instead of being woken by silence.
 */
class SleepTimer(
    private val scope: CoroutineScope,
    val player: Player,
    private val fade: MutableStateFlow<Float> = MutableStateFlow(1f),
    /** Runs right before the timer pauses (Listen Together keeps that pause on this phone). */
    private val beforePause: () -> Unit = {},
) : Player.Listener {
    private var sleepTimerJob: Job? = null
    var triggerTime by mutableStateOf(-1L)
        private set
    var pauseWhenSongEnd by mutableStateOf(false)
        private set
    val isActive: Boolean
        get() = triggerTime != -1L || pauseWhenSongEnd

    fun start(minute: Int) {
        clear()
        if (minute == -1) {
            pauseWhenSongEnd = true
            return
        }
        val totalMs = minute.minutes.inWholeMilliseconds
        triggerTime = System.currentTimeMillis() + totalMs
        val fadeMs = fadeLengthMs(totalMs)
        sleepTimerJob = scope.launch {
            while (isActive) {
                val left = triggerTime - System.currentTimeMillis()
                if (left <= 0) break
                fade.value = fadeFactor(left, fadeMs)
                delay(500)
            }
            beforePause()
            player.pause()
            triggerTime = -1L
            delay(300) // let the pause land before restoring volume for next time
            fade.value = 1f
        }
    }

    fun clear() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        pauseWhenSongEnd = false
        triggerTime = -1L
        fade.value = 1f
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            beforePause()
            player.pause()
        }
    }

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
        if (playbackState == Player.STATE_ENDED && pauseWhenSongEnd) {
            pauseWhenSongEnd = false
            beforePause()
            player.pause()
        }
    }

    companion object {
        fun fadeLengthMs(totalMs: Long): Long = minOf(10.minutes.inWholeMilliseconds, totalMs / 2)

        /** 1 until the fade starts, then an ease-out curve to 0 (sounds even to the ear, unlike a straight line). */
        fun fadeFactor(leftMs: Long, fadeMs: Long): Float {
            if (fadeMs <= 0 || leftMs >= fadeMs) return 1f
            val t = (leftMs.toFloat() / fadeMs).coerceIn(0f, 1f)
            return t * t
        }
    }
}
