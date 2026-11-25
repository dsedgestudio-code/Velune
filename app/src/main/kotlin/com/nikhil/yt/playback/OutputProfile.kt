package com.nikhil.yt.playback

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * How the final stage should shape sound for what it plays through.
 * - Bluetooth re-encodes the audio (AAC on AirPods, SBC, LDAC), which overshoots near full scale: keep -1 dB.
 * - Wired and USB play what we send: -0.3 dB keeps the most dynamics.
 * - The phone's own speaker can't reproduce deep bass; feeding it only makes it rattle at high volume,
 *   so roll off below ~100 Hz (the phone's own DSP handles the rest; 150 Hz also cut the punch it can play).
 */
enum class OutputProfile(val ceilingDb: Float, val highPassHz: Float) {
    BLUETOOTH(-1.0f, 0f),
    WIRED(-0.3f, 0f),
    SPEAKER(-1.0f, 100f),
    ;

    companion object {
        /** Android sends media to the most recently connected headset, so its presence decides the route. */
        fun from(outputs: List<Int>): OutputProfile = when {
            outputs.any { it in BLUETOOTH_TYPES } -> BLUETOOTH
            outputs.any { it in WIRED_TYPES } -> WIRED
            else -> SPEAKER
        }

        private val BLUETOOTH_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= 31) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (Build.VERSION.SDK_INT >= 33) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }

        private val WIRED_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_HDMI,
        )
    }
}

/**
 * Watches audio outputs coming and going and reports the profile to use, plus a stable key for the
 * device itself ("speaker", or e.g. "bt:AirPods Pro") so settings can be remembered per device.
 */
class OutputRouteWatcher(context: Context, private val onChange: (OutputProfile, String) -> Unit) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = update()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = update()
    }

    fun start() {
        update()
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    fun stop() = audioManager.unregisterAudioDeviceCallback(callback)

    private fun update() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val profile = OutputProfile.from(devices.map { it.type })
        val device = devices.lastOrNull { OutputProfile.from(listOf(it.type)) == profile && profile != OutputProfile.SPEAKER }
        val key = if (device == null) "speaker" else "${profile.name.lowercase()}:${device.productName}"
        onChange(profile, key)
    }
}
