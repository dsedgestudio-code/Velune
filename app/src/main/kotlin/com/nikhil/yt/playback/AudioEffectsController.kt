package com.nikhil.yt.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.nikhil.yt.constants.EqualizerBandLevelsMbKey
import com.nikhil.yt.constants.EqualizerBassBoostEnabledKey
import com.nikhil.yt.constants.EqualizerBassBoostStrengthKey
import com.nikhil.yt.constants.EqualizerEnabledKey
import com.nikhil.yt.constants.EqualizerOutputGainEnabledKey
import com.nikhil.yt.constants.EqualizerOutputGainMbKey
import com.nikhil.yt.constants.EqualizerSelectedProfileIdKey
import com.nikhil.yt.constants.EqualizerVirtualizerEnabledKey
import com.nikhil.yt.constants.EqualizerVirtualizerStrengthKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Equalizer, bass boost, virtualizer and output gain for the player's audio session.
 * All methods except the preset writers must be called on the main thread (the player's thread).
 */
class AudioEffectsController(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    /** Output gain is applied by LoudnessProcessor (gain + limiter), not a system effect. */
    private val boostGainDb: MutableStateFlow<Float>,
    /** How much the equalizer and bass boost add after our limiter, so it can leave that much room. */
    private val onHeadroomChanged: (Float) -> Unit = {},
) {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _capabilities = MutableStateFlow<EqCapabilities?>(null)
    val capabilities: StateFlow<EqCapabilities?> = _capabilities.asStateFlow()

    private var settings = EqSettings(
        enabled = false,
        bandLevelsMb = emptyList(),
        outputGainEnabled = false,
        outputGainMb = 0,
        bassBoostEnabled = false,
        bassBoostStrength = 0,
        virtualizerEnabled = false,
        virtualizerStrength = 0,
    )

    private var openedSessionId: Int? = null
    private var effectsSessionId: Int? = null
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null

    fun applySettings(newSettings: EqSettings) {
        settings = newSettings
        applyToEffects()
    }

    /** Playback started: attach effects and tell system equalizer apps about the session. */
    fun open(sessionId: Int) {
        if (openedSessionId != null || sessionId <= 0) return
        openedSessionId = sessionId
        ensureEffects(sessionId)
        broadcastOpen(sessionId)
    }

    /** Playback stopped or lost focus. */
    fun close() {
        val sessionId = openedSessionId ?: return
        openedSessionId = null
        release()
        broadcastClose(sessionId)
    }

    /** ExoPlayer can switch audio sessions mid-playback; move the effects along. */
    fun onAudioSessionChanged(newSessionId: Int) {
        val old = openedSessionId ?: return
        if (newSessionId <= 0 || old <= 0 || old == newSessionId) return
        broadcastClose(old)
        openedSessionId = newSessionId
        ensureEffects(newSessionId)
        broadcastOpen(newSessionId)
    }

    fun release() {
        effectsSessionId = null
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        _capabilities.value = null
    }

    fun destroy() {
        release()
        ioScope.cancel()
    }

    fun applyFlatPreset() {
        val bandCount = _capabilities.value?.bandCount
            ?: runCatching { equalizer?.numberOfBands?.toInt() }.getOrNull() ?: 0
        saveLevels(List(bandCount.coerceAtLeast(0)) { 0 }, profileId = "flat")
    }

    fun applySystemPreset(presetIndex: Int, currentSessionId: Int) {
        ensureEffects(currentSessionId)
        val eq = equalizer ?: return
        val maxPreset = runCatching { eq.numberOfPresets.toInt() }.getOrNull() ?: 0
        if (presetIndex !in 0 until maxPreset) return
        runCatching { eq.usePreset(presetIndex.toShort()) }.getOrNull() ?: return
        val bandCount = runCatching { eq.numberOfBands.toInt() }.getOrNull() ?: 0
        val levels = (0 until bandCount).map { band ->
            runCatching { eq.getBandLevel(band.toShort()).toInt() }.getOrNull() ?: 0
        }
        saveLevels(levels, profileId = "system:$presetIndex")
    }

    private fun saveLevels(levelsMb: List<Int>, profileId: String) {
        val encoded = runCatching { EqualizerJson.json.encodeToString(levelsMb) }.getOrNull().orEmpty()
        if (encoded.isBlank() && levelsMb.isNotEmpty()) return
        ioScope.launch {
            dataStore.edit { prefs ->
                prefs[EqualizerEnabledKey] = true
                prefs[EqualizerBandLevelsMbKey] = encoded
                prefs[EqualizerSelectedProfileIdKey] = profileId
            }
        }
    }

    private fun ensureEffects(sessionId: Int) {
        if (sessionId <= 0) return
        if (effectsSessionId == sessionId && equalizer != null) return
        release()
        effectsSessionId = sessionId
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        bassBoost = runCatching { BassBoost(0, sessionId) }.getOrNull()
        virtualizer = runCatching { Virtualizer(0, sessionId) }.getOrNull()
        equalizer?.let(::readCapabilities)
        applyToEffects()
    }

    private fun applyToEffects() {
        // Set before the early return: output gain needs no audio session.
        boostGainDb.value = if (settings.outputGainEnabled) settings.outputGainMb.coerceIn(-1500, 1500) / 100f else 0f
        onHeadroomChanged(postEffectsHeadroomDb(settings))
        val eq = equalizer ?: return
        val caps = _capabilities.value
        val bandCount = caps?.bandCount ?: eq.numberOfBands.toInt()
        val minMb = caps?.minBandLevelMb ?: runCatching { eq.bandLevelRange.getOrNull(0)?.toInt() }.getOrNull() ?: -1500
        val maxMb = caps?.maxBandLevelMb ?: runCatching { eq.bandLevelRange.getOrNull(1)?.toInt() }.getOrNull() ?: 1500

        val levels = resampleLevelsByIndex(settings.bandLevelsMb, bandCount)
        runCatching { eq.enabled = settings.enabled }
        for (band in 0 until bandCount) {
            val levelMb = levels.getOrNull(band)?.coerceIn(minMb, maxMb) ?: 0
            runCatching { eq.setBandLevel(band.toShort(), levelMb.toShort()) }
        }
        bassBoost?.let { bb ->
            runCatching { bb.enabled = settings.bassBoostEnabled }
            runCatching { bb.setStrength(settings.bassBoostStrength.toShort()) }
        }
        virtualizer?.let { v ->
            runCatching { v.enabled = settings.virtualizerEnabled }
            runCatching { v.setStrength(settings.virtualizerStrength.toShort()) }
        }
    }

    private fun readCapabilities(eq: Equalizer) {
        val bandCount = eq.numberOfBands.toInt().coerceAtLeast(0)
        val range = runCatching { eq.bandLevelRange }.getOrNull()
        _capabilities.value = EqCapabilities(
            bandCount = bandCount,
            minBandLevelMb = range?.getOrNull(0)?.toInt() ?: -1500,
            maxBandLevelMb = range?.getOrNull(1)?.toInt() ?: 1500,
            centerFreqHz = (0 until bandCount).map { band ->
                (runCatching { eq.getCenterFreq(band.toShort()) }.getOrNull() ?: 0) / 1000
            },
            systemPresets = (0 until eq.numberOfPresets.toInt()).map { idx ->
                runCatching { eq.getPresetName(idx.toShort()).toString() }.getOrNull() ?: "Preset ${idx + 1}"
            },
        )
    }

    private fun broadcastOpen(sessionId: Int) = context.sendBroadcast(
        Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
            putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        },
    )

    private fun broadcastClose(sessionId: Int) = context.sendBroadcast(
        Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION).apply {
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        },
    )

    companion object {
        /**
         * Room to leave for these system effects, which run after LoudnessProcessor's limiter. A boosted band
         * only lifts the peaks that fall in that band, so the real overshoot on full-range music is about half
         * the band's boost; reserving the whole boost (as before) made every EQ'd song several dB quieter.
         */
        fun postEffectsHeadroomDb(settings: EqSettings): Float {
            val eq = if (settings.enabled) (settings.bandLevelsMb.maxOrNull() ?: 0).coerceAtLeast(0) / 100f else 0f
            val bass = if (settings.bassBoostEnabled) settings.bassBoostStrength.coerceIn(0, 1000) / 1000f * 8f else 0f
            return ((eq + bass) * 0.5f).coerceIn(0f, 6f)
        }

        fun settingsFrom(prefs: Preferences): EqSettings {
            val levels = prefs[EqualizerBandLevelsMbKey]?.takeIf { it.isNotBlank() }
                ?.let { runCatching { EqualizerJson.json.decodeFromString<List<Int>>(it) }.getOrNull() }
                .orEmpty()
            return EqSettings(
                enabled = prefs[EqualizerEnabledKey] ?: false,
                bandLevelsMb = levels,
                outputGainEnabled = prefs[EqualizerOutputGainEnabledKey] ?: false,
                outputGainMb = prefs[EqualizerOutputGainMbKey] ?: 0,
                bassBoostEnabled = prefs[EqualizerBassBoostEnabledKey] ?: false,
                bassBoostStrength = (prefs[EqualizerBassBoostStrengthKey] ?: 0).coerceIn(0, 1000),
                virtualizerEnabled = prefs[EqualizerVirtualizerEnabledKey] ?: false,
                virtualizerStrength = (prefs[EqualizerVirtualizerStrengthKey] ?: 0).coerceIn(0, 1000),
            )
        }

        /** Maps saved band levels onto a device with a different band count by linear interpolation. */
        internal fun resampleLevelsByIndex(levelsMb: List<Int>, targetCount: Int): List<Int> {
            if (targetCount <= 0) return emptyList()
            if (levelsMb.isEmpty()) return List(targetCount) { 0 }
            if (levelsMb.size == targetCount) return levelsMb
            if (targetCount == 1) return listOf(levelsMb.sum() / levelsMb.size)
            val lastIndex = levelsMb.lastIndex.toFloat().coerceAtLeast(1f)
            return List(targetCount) { i ->
                val pos = i.toFloat() * lastIndex / (targetCount - 1).toFloat()
                val lo = kotlin.math.floor(pos).toInt().coerceIn(0, levelsMb.lastIndex)
                val hi = kotlin.math.ceil(pos).toInt().coerceIn(0, levelsMb.lastIndex)
                val t = (pos - lo.toFloat()).coerceIn(0f, 1f)
                (levelsMb[lo] + ((levelsMb[hi] - levelsMb[lo]) * t)).toInt()
            }
        }
    }
}
