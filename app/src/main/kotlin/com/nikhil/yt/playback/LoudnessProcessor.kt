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
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Applies loudness-normalization + volume-boost gain inside the audio pipeline (so gain can go above 0 dB,
 * which player.volume cannot), followed by a look-ahead peak limiter so loud audio never clips or crackles.
 *
 * The limiter delays audio by [LOOKAHEAD_SEC] and starts turning a peak down before it arrives, so it
 * lowers the level smoothly instead of chopping the waveform (which is what made loud songs "burst").
 */
@UnstableApi
class LoudnessProcessor : BaseAudioProcessor() {
    /** Total gain to apply in dB (normalization + boost). Safe to set from any thread; changes are ramped. */
    @Volatile
    var gainDb: Float = 0f

    /**
     * Extra room to leave for gain added after this processor (the system equalizer and bass boost),
     * so their boost doesn't push already-limited audio back over the edge.
     */
    @Volatile
    var headroomDb: Float = 0f

    /**
     * Level given back for what the stages before this one had to take off to stay clean in 16-bit
     * (Immersive's pre-attenuation, crossfeed's normalization). This processor's limiter catches the peaks.
     */
    @Volatile
    var makeupDb: Float = 0f

    /** What the audio plays through (Bluetooth, wired, phone speaker); see [OutputProfile]. */
    @Volatile
    var outputProfile: OutputProfile = OutputProfile.BLUETOOTH

    // Speaker bass roll-off: a 2nd-order Butterworth high-pass, one state per channel.
    private var sampleRate = 48_000f
    private var highPassHz = 0f
    private var hpB0 = 1f; private var hpB1 = 0f; private var hpB2 = 0f; private var hpA1 = 0f; private var hpA2 = 0f
    private var hpX1 = FloatArray(0); private var hpX2 = FloatArray(0); private var hpY1 = FloatArray(0); private var hpY2 = FloatArray(0)

    private var currentGain = 1f
    private var limiterEnv = 1f
    private var gainSmoothCoef = 0f
    private var attackCoef = 0f
    private var releaseCoef = 0f

    // Look-ahead: audio waits in [delay] while the limiter looks at what's coming.
    private var lookahead = 1
    private var channels = 2
    private var delay = FloatArray(0)
    private var required = FloatArray(0) // gain each delayed frame needs to stay under the ceiling
    private var frameCount = 0L
    private val window = SlidingMin()

    // Live level for the waveform: RMS of the input (pre-gain, so the shape doesn't change with volume).
    private var meterSumSq = 0f
    private var meterFrames = 0
    private var meterWindow = 1470

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        val rate = inputAudioFormat.sampleRate.toFloat()
        sampleRate = rate
        highPassHz = -1f // recompute the filter for this rate on the next buffer
        gainSmoothCoef = 1f - exp(-1f / (0.05f * rate)) // ~50 ms gain ramp: no clicks on song/slider change
        releaseCoef = 1f - exp(-1f / (0.25f * rate)) // ~250 ms limiter release: no pumping
        lookahead = (LOOKAHEAD_SEC * rate).toInt().coerceAtLeast(1)
        attackCoef = 1f - exp(-5f / lookahead) // reaches >99% of the needed reduction within the look-ahead
        channels = inputAudioFormat.channelCount
        meterWindow = (rate / 30f).toInt().coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val out = replaceOutputBuffer(remaining)
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val targetGain = 10f.pow((gainDb + makeupDb) / 20f)
        val profile = outputProfile
        val ceiling = ceilingFor(headroomDb, profile)
        if (delay.size != lookahead * channels) resetDelay()
        if (profile.highPassHz != highPassHz) setHighPass(profile.highPassHz)
        val filtering = highPassHz > 0f
        inputBuffer.order(ByteOrder.nativeOrder())
        bufGainSum = 0.0; bufHeavy = 0L; bufFrames = 0L

        while (inputBuffer.remaining() >= channels * bytesPerSample) {
            currentGain += (targetGain - currentGain) * gainSmoothCoef
            val slot = (frameCount % lookahead).toInt()
            val base = slot * channels

            // The frame leaving the delay line now (written `lookahead` frames ago).
            val outRequired = required[slot]

            var peak = 0f
            for (ch in 0 until channels) {
                val s = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
                meterSumSq += s * s
                val gained = (if (filtering) highPass(ch, s) else s) * currentGain
                val leaving = delay[base + ch]
                delay[base + ch] = gained
                // True peak: the wave can crest between two samples, and that crest is what overshoots once
                // the DAC or a Bluetooth codec rebuilds it. Estimate it at the midpoint of the last interval.
                val between = (9f * (tp1[ch] + tp2[ch]) - tp3[ch] - gained) / 16f
                tp3[ch] = tp2[ch]; tp2[ch] = tp1[ch]; tp1[ch] = gained
                peak = maxOf(peak, abs(gained), abs(between))
                scratch[ch] = leaving
            }
            val need = if (peak > ceiling) ceiling / peak else 1f
            required[slot] = need
            window.push(frameCount, need, lookahead)
            frameCount++

            val target = window.min()
            limiterEnv += (target - limiterEnv) * (if (target < limiterEnv) attackCoef else releaseCoef)
            val gain = minOf(limiterEnv, outRequired) // guarantee: the outgoing frame is never over the ceiling
            bufGainSum += gain
            bufFrames++
            if (gain < HEAVY_GAIN) bufHeavy++

            if (++meterFrames >= meterWindow) {
                AudioLevelMeter.publishRms(sqrt(meterSumSq / (meterFrames * channels)))
                meterSumSq = 0f
                meterFrames = 0
            }
            for (ch in 0 until channels) {
                val y = scratch[ch] * gain
                if (isFloat) out.putFloat(y) else out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
        synchronized(statsLock) {
            statGainSum += bufGainSum; statHeavy += bufHeavy; statFrames += bufFrames
        }
    }

    private var bufGainSum = 0.0
    private var bufHeavy = 0L
    private var bufFrames = 0L
    private val statsLock = Any()
    private var statGainSum = 0.0
    private var statHeavy = 0L
    private var statFrames = 0L

    /** How hard the limiter worked since the last call: frames seen, their summed gain, and frames cut by more than 6 dB. */
    data class Stats(val frames: Long, val gainSum: Double, val heavyFrames: Long)

    fun takeStats(): Stats = synchronized(statsLock) {
        Stats(statFrames, statGainSum, statHeavy).also { statGainSum = 0.0; statHeavy = 0L; statFrames = 0L }
    }

    /** At the end of a stream, let the last [lookahead] frames out of the delay line. */
    override fun onQueueEndOfStream() {
        if (delay.isEmpty() || frameCount == 0L) return
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val pending = minOf(frameCount, lookahead.toLong()).toInt()
        val oldest = if (frameCount < lookahead) 0L else frameCount // slot of the oldest frame still waiting
        val out = replaceOutputBuffer(pending * channels * bytesPerSample)
        for (i in 0 until pending) {
            val slot = ((oldest + i) % lookahead).toInt()
            val gain = minOf(limiterEnv, required[slot])
            for (ch in 0 until channels) {
                val y = delay[slot * channels + ch] * gain
                if (isFloat) out.putFloat(y) else out.putShort((y * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
        resetDelay()
    }

    override fun onFlush() {
        currentGain = 10f.pow((gainDb + makeupDb) / 20f) // seek/new song: start at the right level instead of ramping
        limiterEnv = 1f
        resetDelay()
        highPassHz = -1f // clear the filter's memory: no thump from the previous position
        meterSumSq = 0f
        meterFrames = 0
        AudioLevelMeter.reset()
    }

    override fun onReset() {
        delay = FloatArray(0)
        required = FloatArray(0)
    }

    private var scratch = FloatArray(8)
    private var tp1 = FloatArray(8); private var tp2 = FloatArray(8); private var tp3 = FloatArray(8) // last 3 samples per channel

    private fun setHighPass(hz: Float) {
        highPassHz = hz
        hpX1 = FloatArray(channels); hpX2 = FloatArray(channels); hpY1 = FloatArray(channels); hpY2 = FloatArray(channels)
        if (hz <= 0f) return
        val w0 = 2.0 * Math.PI * hz / sampleRate
        val cos = kotlin.math.cos(w0)
        val alpha = kotlin.math.sin(w0) / (2.0 * 0.70710678)
        val a0 = 1.0 + alpha
        hpB0 = ((1.0 + cos) / 2.0 / a0).toFloat()
        hpB1 = (-(1.0 + cos) / a0).toFloat()
        hpB2 = hpB0
        hpA1 = (-2.0 * cos / a0).toFloat()
        hpA2 = ((1.0 - alpha) / a0).toFloat()
    }

    private fun highPass(ch: Int, x: Float): Float {
        val y = hpB0 * x + hpB1 * hpX1[ch] + hpB2 * hpX2[ch] - hpA1 * hpY1[ch] - hpA2 * hpY2[ch]
        hpX2[ch] = hpX1[ch]; hpX1[ch] = x
        hpY2[ch] = hpY1[ch]; hpY1[ch] = y
        return y
    }

    private fun resetDelay() {
        delay = FloatArray(lookahead * channels)
        required = FloatArray(lookahead) { 1f }
        scratch = FloatArray(channels.coerceAtLeast(1))
        tp1 = FloatArray(channels.coerceAtLeast(1)); tp2 = FloatArray(tp1.size); tp3 = FloatArray(tp1.size)
        frameCount = 0L
        window.clear(lookahead)
    }

    /** Minimum of the last `size` values in O(1) amortized time (monotonic queue). */
    private class SlidingMin {
        private var frames = LongArray(2)
        private var values = FloatArray(2)
        private var head = 0
        private var count = 0

        fun clear(size: Int) {
            if (frames.size < size + 2) {
                frames = LongArray(size + 2)
                values = FloatArray(size + 2)
            }
            head = 0
            count = 0
        }

        fun push(frame: Long, value: Float, size: Int) {
            while (count > 0 && values[(head + count - 1) % values.size] >= value) count--
            val tail = (head + count) % values.size
            frames[tail] = frame
            values[tail] = value
            count++
            while (frames[head] <= frame - size) {
                head = (head + 1) % values.size
                count--
            }
        }

        fun min(): Float = if (count == 0) 1f else values[head]
    }

    companion object {
        /**
         * YouTube's loudnessDb is relative to its reference level (positive = louder than reference).
         * We aim [TARGET_OFFSET_DB] above that reference (about -9 LUFS, where most modern masters already sit),
         * so typical songs play close to as mastered instead of being turned down; only the outliers move.
         * Upward gain is capped at +6 dB so quiet, dynamic recordings aren't squashed.
         */
        fun normalizationGainDb(loudnessDb: Float): Float = (TARGET_OFFSET_DB - loudnessDb).coerceIn(-15f, 6f)

        const val TARGET_OFFSET_DB = 5f

        /** -1 dBFS, the Bluetooth ceiling: room for codec overshoot when AAC (AirPods) or SBC re-encodes. */
        const val CEILING = 0.8912509f

        const val LOOKAHEAD_SEC = 0.005f

        /** Below this the limiter is cutting more than 6 dB: audible squashing, the "burst" to avoid. */
        const val HEAVY_GAIN = 0.5f

        fun ceilingFor(headroomDb: Float, profile: OutputProfile = OutputProfile.BLUETOOTH): Float =
            10f.pow((profile.ceilingDb - headroomDb.coerceIn(0f, 12f)) / 20f)
    }
}
