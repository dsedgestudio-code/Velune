package com.nikhil.yt.playback

import android.content.Context
import android.util.LruCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.log10
import kotlin.math.max

/**
 * Live loudness of the audio being played, 0..1 on a perceptual (dB) scale, ~30 updates per second.
 * Fed by [LoudnessProcessor] from the audio thread.
 * shortcut: measured where samples enter the output buffer, so it leads the speaker by the buffer
 * latency (usually under 200 ms); delay by AudioTrack latency if the bounce ever looks early.
 */
object AudioLevelMeter {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    internal fun publishRms(rms: Float) {
        _level.value = toPerceptual(rms)
    }

    internal fun reset() {
        _level.value = 0f
    }

    /** -48 dBFS (near silence) maps to 0, full scale to 1. */
    internal fun toPerceptual(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20f * log10(rms)
        return ((db + 48f) / 48f).coerceIn(0f, 1f)
    }
}

/**
 * Each song's real loudness shape, learned while it plays: [BINS] slots over the song's length, each
 * keeping the loudest level heard there. Stored as one tiny file per song so the next play shows the
 * true shape from the start.
 */
object WaveformStore {
    const val BINS = 96

    private val memory = LruCache<String, FloatArray>(64)
    private val _current = MutableStateFlow<Pair<String, FloatArray>?>(null)

    /** Shape of the playing song as learned so far (a fresh copy roughly once a second). */
    val current: StateFlow<Pair<String, FloatArray>?> = _current.asStateFlow()

    private var recordingId: String? = null
    private var recording: FloatArray? = null
    private var dirty = false
    private var lastPublishMs = 0L

    private fun dir(context: Context) = File(context.cacheDir, "waveforms").apply { mkdirs() }

    private fun load(context: Context, id: String): FloatArray {
        memory.get(id)?.let { return it }
        val file = File(dir(context), id)
        val shape = runCatching {
            val bytes = file.readBytes()
            if (bytes.size != BINS) null else FloatArray(BINS) { (bytes[it].toInt() and 0xFF) / 255f }
        }.getOrNull() ?: FloatArray(BINS)
        memory.put(id, shape)
        return shape
    }

    /** Call on the main thread when the playing song changes. */
    fun startSong(context: Context, id: String?) {
        save(context)
        recordingId = id
        recording = id?.let { load(context, it) }
        _current.value = if (id != null && recording != null) id to recording!!.copyOf() else null
    }

    /** Record the current live level at [fraction] (0..1) of the song. Main thread. */
    fun record(fraction: Float, level: Float) {
        val shape = recording ?: return
        val id = recordingId ?: return
        if (fraction !in 0f..1f || level <= 0f) return
        val bin = (fraction * (BINS - 1)).toInt()
        if (level > shape[bin]) {
            shape[bin] = max(shape[bin], level)
            dirty = true
        }
        val now = System.currentTimeMillis()
        if (dirty && now - lastPublishMs > 1000) {
            lastPublishMs = now
            _current.value = id to shape.copyOf()
        }
    }

    fun save(context: Context) {
        val id = recordingId ?: return
        val shape = recording ?: return
        if (!dirty) return
        dirty = false
        val bytes = ByteArray(BINS) { (shape[it].coerceIn(0f, 1f) * 255).toInt().toByte() }
        runCatching { File(dir(context), id).writeBytes(bytes) }
    }
}
