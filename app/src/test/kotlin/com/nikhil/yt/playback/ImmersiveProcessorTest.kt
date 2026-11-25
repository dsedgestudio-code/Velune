package com.nikhil.yt.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class ImmersiveProcessorTest {
    private fun input(): ShortArray = ShortArray(48_000 * 2) { i ->
        val frame = i / 2
        val l = sin(2 * PI * 100 * frame / 48_000) * 0.4 + sin(2 * PI * 5000 * frame / 48_000) * 0.1
        val r = sin(2 * PI * 100 * frame / 48_000) * 0.4 - sin(2 * PI * 5000 * frame / 48_000) * 0.1
        ((if (i % 2 == 0) l else r) * 32767).toInt().toShort()
    }

    private fun run(enabled: Boolean, samples: ShortArray): ShortArray {
        val p = ImmersiveProcessor()
        p.enabled = enabled
        p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        val buf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        samples.forEach { buf.putShort(it) }
        buf.flip()
        p.queueInput(buf)
        val out = p.output.order(ByteOrder.nativeOrder()).asShortBuffer()
        return ShortArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun disabledIsBitTransparent() {
        val samples = input()
        assertArrayEquals(samples, run(enabled = false, samples))
    }

    @Test
    fun enabledIsStableAndAudible() {
        val samples = input()
        val out = run(enabled = true, samples)
        assertTrue(out.size == samples.size)
        // Filters stable: settled tail stays in a sane range (no runaway feedback).
        val tailPeak = out.takeLast(4800).maxOf { abs(it.toInt()) }
        assertTrue("tail peak $tailPeak", tailPeak in 3000..32767)
        assertTrue(out.indices.any { abs(out[it] - samples[it]) > 200 })
    }

    @Test
    fun centredVocalStaysCentred() {
        // Identical in both ears (a centred vocal): only a faint room may appear around it, never width.
        val mono = ShortArray(48_000 * 2) { i -> (sin(2 * PI * 440 * (i / 2) / 48_000) * 0.3 * 32767).toInt().toShort() }
        val out = run(enabled = true, mono)
        val mid = (24_000 until 48_000).maxOf { abs(out[it * 2] + out[it * 2 + 1]) / 2f }
        val side = (24_000 until 48_000).maxOf { abs(out[it * 2] - out[it * 2 + 1]) / 2f }
        assertTrue("side $side vs mid $mid", side < mid * 0.12f)
    }

    @Test
    fun sidesGetWiderButSideBassStaysPut() {
        // Left-only tone: equal mid and side going in; the tone EQ hits both alike, so side/mid shows the width.
        fun sideRatio(hz: Double): Float {
            val leftOnly = ShortArray(48_000 * 2) { i ->
                if (i % 2 == 0) (sin(2 * PI * hz * (i / 2) / 48_000) * 0.2 * 32767).toInt().toShort() else 0
            }
            val out = run(enabled = true, leftOnly)
            val mid = (24_000 until 48_000).maxOf { abs(out[it * 2] + out[it * 2 + 1]) / 2f }
            val side = (24_000 until 48_000).maxOf { abs(out[it * 2] - out[it * 2 + 1]) / 2f }
            return side / mid
        }
        assertTrue("treble sides widen", sideRatio(2_000.0) > sideRatio(40.0) * 1.2f)
    }
}
