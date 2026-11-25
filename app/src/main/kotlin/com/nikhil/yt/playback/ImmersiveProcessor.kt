/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Immersive sound": tight bass, clear presence, air, wider stereo and a faint hall ambience.
 * Runs before [LoudnessProcessor], whose limiter catches any peaks the EQ lifts.
 * Toggling cross-fades wet/dry, so switching it never clicks.
 */
@UnstableApi
class ImmersiveProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    private var mix = 0f
    private var mixCoef = 0f
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var echo = FloatArray(0)
    private var echoPos = 0
    private var taps = IntArray(0)

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        val fs = inputAudioFormat.sampleRate.toDouble()
        filters = Array(inputAudioFormat.channelCount) {
            arrayOf(
                Biquad.peaking(fs, 45.0, 0.7, 3.0), // sub-bass punch for earbuds
                Biquad.lowShelf(fs, 80.0, 4.5), // bass weight (earbuds need more)
                Biquad.peaking(fs, 250.0, 1.0, -2.5), // de-mud
                Biquad.peaking(fs, 3200.0, 1.2, 2.0), // presence: vocals step forward
                Biquad.peaking(fs, 6000.0, 1.0, 1.0), // detail
                Biquad.highShelf(fs, 10000.0, 2.5), // air / crispness
            )
        }
        // Early reflections of a mid-size hall: 13/19/29/37 ms (stereo ring buffer, interleaved L/R).
        taps = intArrayOf(13, 19, 29, 37).map { (it * fs / 1000).roundToInt() }.toIntArray()
        echo = FloatArray((taps.max() + 1) * 2)
        echoPos = 0
        mixCoef = 1f - exp(-1f / (0.15f * fs.toFloat())) // ~150 ms on/off cross-fade
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val channels = inputAudioFormat.channelCount
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val dry = FloatArray(channels)
        val wet = FloatArray(channels)
        val target = if (enabled) 1f else 0f
        inputBuffer.order(ByteOrder.nativeOrder())

        while (inputBuffer.remaining() >= channels * bytesPerSample) {
            for (ch in 0 until channels) dry[ch] = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
            mix += (target - mix) * mixCoef

            if (mix > 1e-4f) {
                for (ch in 0 until channels) {
                    var s = dry[ch] * HEADROOM
                    for (f in filters[ch]) s = f.process(s)
                    wet[ch] = s
                }
                if (channels == 2) widenAndAmbience(wet)
                for (ch in 0 until channels) {
                    val y = dry[ch] + (wet[ch] - dry[ch]) * mix
                    write(out, y, isFloat)
                }
            } else {
                for (ch in 0 until channels) write(out, dry[ch], isFloat)
            }
        }
        out.flip()
    }

    private fun widenAndAmbience(lr: FloatArray) {
        // Mid/side widening: mono-safe (mid untouched), spreads the stage.
        val mid = (lr[0] + lr[1]) * 0.5f
        val side = (lr[0] - lr[1]) * 0.5f * WIDTH
        var l = mid + side
        var r = mid - side

        // Faint early reflections, cross-fed (left reflections arrive on the right) like hall walls.
        val size = echo.size / 2
        var reflL = 0f
        var reflR = 0f
        for ((i, d) in taps.withIndex()) {
            val idx = ((echoPos - d + size) % size) * 2
            val g = REFLECTION_GAIN / (i + 1)
            reflL += echo[idx + 1] * g
            reflR += echo[idx] * g
        }
        echo[echoPos * 2] = l
        echo[echoPos * 2 + 1] = r
        echoPos = (echoPos + 1) % size
        l += reflL
        r += reflR
        lr[0] = l
        lr[1] = r
    }

    private fun write(out: ByteBuffer, y: Float, isFloat: Boolean) {
        if (isFloat) out.putFloat(y) else out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
    }

    override fun onFlush() {
        filters.forEach { chain -> chain.forEach(Biquad::reset) }
        echo.fill(0f)
        mix = if (enabled) 1f else 0f
    }

    /** RBJ audio-EQ-cookbook biquad, transposed direct form II. */
    internal class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var z1 = 0f
        private var z2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun reset() { z1 = 0f; z2 = 0f }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

            fun peaking(fs: Double, f0: Double, q: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs; val alpha = sin(w) / (2 * q)
                return make(1 + alpha * a, -2 * cos(w), 1 - alpha * a, 1 + alpha / a, -2 * cos(w), 1 - alpha / a)
            }

            fun lowShelf(fs: Double, f0: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs; val c = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0) // shelf slope S = 1
                val k = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) - (a - 1) * c + k), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - k),
                    (a + 1) + (a - 1) * c + k, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - k,
                )
            }

            fun highShelf(fs: Double, f0: Double, gainDb: Double): Biquad {
                val a = 10.0.pow(gainDb / 40); val w = 2 * PI * f0 / fs; val c = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0)
                val k = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) + (a - 1) * c + k), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - k),
                    (a + 1) - (a - 1) * c + k, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - k,
                )
            }
        }
    }

    companion object {
        private const val HEADROOM = 0.70f // -3 dB: more boosts now, keep headroom for the limiter
        private const val WIDTH = 1.5f // wider for earbuds (narrow imaging)
        private const val REFLECTION_GAIN = 0.08f // subtler: earbuds are already "in-head"
    }
}
