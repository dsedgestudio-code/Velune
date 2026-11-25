package com.nikhil.yt.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
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
import com.nikhil.yt.constants.ImmersiveAudioKey
import com.nikhil.yt.constants.PlayerVolumeKey
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * Remembers EQ, bass boost, Immersive and volume per output device, and swaps them in when that
 * device connects. A device seen for the first time keeps whatever is set now.
 */
class DeviceAudioMemory(context: Context, private val dataStore: DataStore<Preferences>) {
    private val prefs = context.getSharedPreferences("device_audio_memory", Context.MODE_PRIVATE)
    private var current: String? = null

    suspend fun onDeviceChanged(device: String) {
        val previous = current
        current = device
        if (previous == null || previous == device) return
        val snapshot = dataStore.data.first()
        prefs.edit().putString(previous, encode(snapshot)).apply()
        val saved = prefs.getString(device, null) ?: return
        dataStore.edit { decode(saved, it) }
    }

    companion object {
        private val BOOLS = listOf(
            EqualizerEnabledKey, EqualizerBassBoostEnabledKey, EqualizerOutputGainEnabledKey,
            EqualizerVirtualizerEnabledKey, ImmersiveAudioKey,
        )
        private val INTS = listOf(EqualizerBassBoostStrengthKey, EqualizerOutputGainMbKey, EqualizerVirtualizerStrengthKey)
        private val STRINGS = listOf(EqualizerBandLevelsMbKey, EqualizerSelectedProfileIdKey)
        private val FLOATS = listOf(PlayerVolumeKey)

        internal fun encode(p: Preferences): String = JSONObject().apply {
            (BOOLS + INTS + STRINGS + FLOATS).forEach { key -> p[key]?.let { put(key.name, it) } }
        }.toString()

        internal fun decode(json: String, p: MutablePreferences) {
            val o = JSONObject(json)
            BOOLS.forEach { k -> if (o.has(k.name)) p[k] = o.getBoolean(k.name) else p.remove(k) }
            INTS.forEach { k -> if (o.has(k.name)) p[k] = o.getInt(k.name) else p.remove(k) }
            STRINGS.forEach { k -> if (o.has(k.name)) p[k] = o.getString(k.name) else p.remove(k) }
            FLOATS.forEach { k -> if (o.has(k.name)) p[k] = o.getDouble(k.name).toFloat() else p.remove(k) }
        }
    }
}
