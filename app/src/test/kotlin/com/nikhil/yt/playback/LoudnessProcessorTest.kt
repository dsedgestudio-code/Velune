package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LoudnessProcessorTest {
    private fun run(gainDb: Float, amplitude: Float, headroomDb: Float = 0f): ShortArray {
        val p = LoudnessProcessor()
        p.gainDb = gainDb
        p.headroomDb = headroomDb
        p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val frames = 48_000
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i ->
            val s = (sin(2 * PI * 440 * i / 48_000) * amplitude * 32767).toInt().toShort()
            input.putShort(s); input.putShort(s)
        }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun boostNeverClips() {
        val out = run(gainDb = 15f, amplitude = 0.9f)
        val ceiling = LoudnessProcessor.CEILING * 32767
        assertTrue(out.all { abs(it.toInt()) <= ceiling + 1 })
    }

    @Test
    fun boostRaisesQuietAudio() {
        val peak = run(gainDb = 6f, amplitude = 0.1f).maxOf { abs(it.toInt()) }
        assertEquals(0.1f * 1.995f * 32767, peak.toFloat(), 200f) // +6 dB ≈ x2, limiter untouched
    }

    @Test
    fun makeupGainGivesBackTheLevel() {
        val p = LoudnessProcessor()
        p.makeupDb = 6f
        p.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        p.flush()
        val input = ByteBuffer.allocateDirect(4_800 * 4).order(ByteOrder.nativeOrder())
        repeat(4_800) { input.putFloat(0.1f) }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        assertEquals(0.1995f, out.get(4_000), 1e-3f)
    }

    @Test
    fun unityGainIsTransparent() {
        val peak = run(gainDb = 0f, amplitude = 0.5f).maxOf { abs(it.toInt()) }
        assertEquals(0.5f * 32767, peak.toFloat(), 50f)
    }

    @Test
    fun normalizationGainIsClamped() {
        assertEquals(-3f, LoudnessProcessor.normalizationGainDb(8f), 0f) // aims 5 dB above YouTube's reference
        assertEquals(6f, LoudnessProcessor.normalizationGainDb(-20f), 0f)
        assertEquals(-15f, LoudnessProcessor.normalizationGainDb(30f), 0f)
    }

    @Test
    fun eqHeadroomLowersTheCeiling() {
        val out = run(gainDb = 12f, amplitude = 0.9f, headroomDb = 6f)
        val ceiling = LoudnessProcessor.ceilingFor(6f) * 32767
        assertTrue(out.all { abs(it.toInt()) <= ceiling + 1 })
        assertTrue(ceiling < LoudnessProcessor.CEILING * 32767 * 0.55f)
    }

    @Test
    fun limiterLooksAhead_turningDownBeforeAPeakArrives() {
        // Quiet tone, then a sudden loud burst: the frames just before the burst must already be turned down,
        // which is what stops the limiter from chopping the waveform (the old crackle).
        val p = LoudnessProcessor()
        p.gainDb = 0f
        p.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        p.flush()
        val burstAt = 4_800
        val frames = 9_600
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i -> input.putFloat(if (i < burstAt) 0.2f else 1.0f) }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val samples = FloatArray(out.remaining()).also { out.get(it) }
        val lookahead = (LoudnessProcessor.LOOKAHEAD_SEC * 48_000).toInt()
        val burstOut = burstAt + lookahead // where the burst lands after the delay line
        assertTrue(samples.all { abs(it) <= LoudnessProcessor.CEILING + 1e-4f })
        assertTrue("already reducing before the peak", samples[burstOut - 2] < 0.2f * 0.99f)
        assertEquals(0.2f, samples[burstOut - lookahead - 10], 1e-3f) // untouched well before
    }

    private fun peakOfTone(hz: Double, profile: OutputProfile): Float {
        val p = LoudnessProcessor()
        p.outputProfile = profile
        p.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        p.flush()
        val frames = 48_000
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i -> input.putFloat((sin(2 * PI * hz * i / 48_000) * 0.5).toFloat()) }
        input.flip()
        p.queueInput(input)
        val out = p.output.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val samples = FloatArray(out.remaining()).also { out.get(it) }
        return samples.drop(24_000).maxOf { abs(it) } // after the filter settles
    }

    @Test
    fun phoneSpeaker_rollsOffDeepBass_butKeepsTheMids() {
        assertTrue(peakOfTone(30.0, OutputProfile.SPEAKER) < 0.5f * 0.15f) // ~-21 dB at 30 Hz
        assertEquals(0.5f, peakOfTone(1_000.0, OutputProfile.SPEAKER), 0.01f)
        assertEquals(0.5f, peakOfTone(40.0, OutputProfile.WIRED), 0.01f) // headphones keep the bass
    }

    @Test
    fun outputRoute_prefersHeadsetsOverSpeaker() {
        val speaker = android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        assertEquals(OutputProfile.BLUETOOTH, OutputProfile.from(listOf(speaker, android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertEquals(OutputProfile.WIRED, OutputProfile.from(listOf(speaker, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertEquals(OutputProfile.SPEAKER, OutputProfile.from(listOf(speaker)))
    }
}
