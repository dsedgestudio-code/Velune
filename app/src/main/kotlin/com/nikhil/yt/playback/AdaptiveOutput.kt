package com.nikhil.yt.playback

import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.DeviceSoundProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10

/**
 * Learns, per output device, how far past normal levelling it can be pushed and still sound clean.
 *
 * Android can't hear the headphones, so it learns from the two things it can measure:
 * - where you keep the volume: pinned at max on a device means that device is too quiet, so push it up;
 *   listening at low volume means it's loud enough, so ease back and keep the music's natural dynamics.
 * - how hard the limiter works: if it starts squashing, back off. That keeps it from ever "bursting".
 * The result is stored per device in the local database, so each pair of headphones keeps its own level.
 */
class AdaptiveOutput(private val database: MusicDatabase) {
    private val _profile = MutableStateFlow<DeviceSoundProfile?>(null)
    val profile: StateFlow<DeviceSoundProfile?> = _profile

    private var window = Window()

    /** Extra gain for the current device, in dB. */
    val pushDb: Float get() = _profile.value?.pushDb ?: 0f

    suspend fun onDevice(key: String) {
        if (_profile.value?.id == key) return
        _profile.value = database.deviceSoundProfile(key) ?: DeviceSoundProfile(id = key, name = nameOf(key))
        window = Window()
    }

    /**
     * Feed one sample of listening (every few seconds while playing). Returns true when the push changed.
     * [volume] is the share of the device's loudness in use: system volume × in-app volume, 0..1.
     */
    suspend fun onListened(elapsedMs: Long, volume: Float, stats: LoudnessProcessor.Stats): Boolean {
        val current = _profile.value ?: return false
        window.add(elapsedMs, volume, stats)
        if (window.ms < WINDOW_MS) return false
        val next = decide(current.pushDb, window.atMaxShare, window.lowShare, window.avgReductionDb, window.heavyShare)
        val updated = current.copy(pushDb = next, listenedMs = current.listenedMs + window.ms, updatedAt = System.currentTimeMillis())
        window = Window()
        _profile.value = updated
        database.saveDeviceSoundProfile(updated)
        return next != current.pushDb
    }

    private class Window {
        var ms = 0L; var atMaxMs = 0L; var lowMs = 0L
        var frames = 0L; var gainSum = 0.0; var heavy = 0L

        fun add(elapsed: Long, volume: Float, s: LoudnessProcessor.Stats) {
            ms += elapsed
            if (volume >= AT_MAX) atMaxMs += elapsed
            if (volume <= LOW) lowMs += elapsed
            frames += s.frames; gainSum += s.gainSum; heavy += s.heavyFrames
        }

        val atMaxShare get() = if (ms == 0L) 0f else atMaxMs.toFloat() / ms
        val lowShare get() = if (ms == 0L) 0f else lowMs.toFloat() / ms
        val heavyShare get() = if (frames == 0L) 0f else heavy.toFloat() / frames
        val avgReductionDb get() = if (frames == 0L) 0f else (-20.0 * log10((gainSum / frames).coerceAtLeast(1e-6))).toFloat()
    }

    companion object {
        const val WINDOW_MS = 60_000L
        const val STEP_DB = 0.5f
        const val MAX_PUSH_DB = 6f
        private const val AT_MAX = 0.9f
        private const val LOW = 0.5f

        /** Limiter working this hard on average means the push is already costing punch. */
        const val MAX_AVG_REDUCTION_DB = 2f
        /** More than this share of audio squashed past 6 dB is audible: back off. */
        const val MAX_HEAVY_SHARE = 0.005f

        /** One minute of listening in, one decision out. Cleanliness always wins over loudness. */
        fun decide(push: Float, atMaxShare: Float, lowShare: Float, avgReductionDb: Float, heavyShare: Float): Float {
            val next = when {
                avgReductionDb > MAX_AVG_REDUCTION_DB || heavyShare > MAX_HEAVY_SHARE -> push - STEP_DB
                atMaxShare >= 0.6f -> push + STEP_DB
                lowShare >= 0.6f -> push - STEP_DB / 2
                else -> push
            }
            return next.coerceIn(0f, MAX_PUSH_DB)
        }

        fun nameOf(key: String): String = key.substringAfter(':', "").ifBlank { key }
    }
}
