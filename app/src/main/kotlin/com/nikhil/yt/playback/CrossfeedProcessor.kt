package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * "Concert hall" for headphones: each ear hears a little of the other channel, low-passed and
 * ~0.3 ms late, the way speakers in a room reach both ears. Hard-panned mixes stop feeling like
 * they're inside your skull. Only runs on headphones; toggling fades in/out so it never clicks.
 */
@UnstableApi
class CrossfeedProcessor : BaseAudioProcessor() {
    @Volatile var enabled = false
    @Volatile var headphones = true

    private var mix = 0f
    private var mixCoef = 0f
    private var lpCoef = 0f
    private var lpL = 0f
    private var lpR = 0f
    private var delayL = FloatArray(1)
    private var delayR = FloatArray(1)
    private var pos = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 2) return AudioFormat.NOT_SET // mono/surround: nothing to cross
        val rate = inputAudioFormat.sampleRate.toFloat()
        lpCoef = 1f - exp(-2f * PI.toFloat() * CUTOFF_HZ / rate)
        mixCoef = 1f - exp(-1f / (0.2f * rate))
        val n = (DELAY_SEC * rate).roundToInt().coerceAtLeast(1)
        delayL = FloatArray(n)
        delayR = FloatArray(n)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val out = replaceOutputBuffer(inputBuffer.remaining())
        val isFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val target = if (enabled && headphones) 1f else 0f
        inputBuffer.order(ByteOrder.nativeOrder())
        val frameBytes = if (isFloat) 8 else 4
        while (inputBuffer.remaining() >= frameBytes) {
            val l = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
            val r = if (isFloat) inputBuffer.float else inputBuffer.short / 32768f
            mix += (target - mix) * mixCoef
            cross(l, r)
            if (isFloat) {
                out.putFloat(outL); out.putFloat(outR)
            } else {
                out.putShort((outL * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
                out.putShort((outR * 32767f).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        out.flip()
    }

    internal var outL = 0f
    internal var outR = 0f

    internal fun cross(l: Float, r: Float) {
        val dl = delayL[pos]; val dr = delayR[pos]
        delayL[pos] = l; delayR[pos] = r
        pos = (pos + 1) % delayL.size
        lpL += (dl - lpL) * lpCoef
        lpR += (dr - lpR) * lpCoef
        val g = GAIN * mix
        val norm = 1f / (1f + g)
        outL = (l + g * lpR) * norm
        outR = (r + g * lpL) * norm
    }

    override fun onFlush() {
        delayL.fill(0f); delayR.fill(0f); lpL = 0f; lpR = 0f; pos = 0
        mix = if (enabled && headphones) 1f else 0f
    }

    internal fun prime(rate: Int) = onConfigure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)).also { onFlush() }

    companion object {
        const val CUTOFF_HZ = 700f
        const val DELAY_SEC = 0.0003f
        const val GAIN = 0.45f // about -7 dB of the opposite ear
    }
}
