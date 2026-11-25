package com.nikhil.yt.utils

import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.innertube.models.ResponseContext
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.models.response.PlayerResponse.StreamingData.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatSelectionTest {
    private fun audio(itag: Int, mime: String, bitrate: Int, direct: Boolean = true) = Format(
        itag = itag, url = if (direct) "https://x/$itag" else null, mimeType = mime, bitrate = bitrate,
        width = null, height = null, contentLength = 1, quality = "tiny", fps = null, qualityLabel = null,
        averageBitrate = null, audioQuality = null, approxDurationMs = null, audioSampleRate = 48000,
        audioChannels = 2, loudnessDb = null, lastModified = null,
        signatureCipher = if (direct) null else "s=abc", cipher = null,
    )

    private val video = audio(137, "video/mp4; codecs=\"avc1\"", 4_000_000).copy(width = 1920, height = 1080)

    private fun response(vararg formats: Format) = PlayerResponse(
        responseContext = ResponseContext(null, null),
        playabilityStatus = PlayerResponse.PlayabilityStatus("OK", null),
        playerConfig = null,
        streamingData = PlayerResponse.StreamingData(null, formats.toList(), 21540),
        videoDetails = null,
        playbackTracking = null,
    )

    private val opus251 = audio(251, "audio/webm; codecs=\"opus\"", 140_000)
    private val aac140 = audio(140, "audio/mp4; codecs=\"mp4a.40.2\"", 130_000)
    private val opus249 = audio(249, "audio/webm; codecs=\"opus\"", 50_000)
    private val premium774 = audio(774, "audio/webm; codecs=\"opus\"", 260_000)

    private fun pick(quality: AudioQuality, metered: Boolean = false, avoid: Set<String> = emptySet(), vararg f: Format) =
        YTPlayerUtils.selectAudioFormatCandidates(response(*f), quality, metered, avoid)

    @Test
    fun highestPicksBestBitrateAndNeverVideo() {
        val picked = pick(AudioQuality.HIGHEST, f = arrayOf(video, opus249, aac140, opus251, premium774))
        assertEquals(774, picked.first().itag)
        assertTrue(picked.none { !it.isAudio })
    }

    @Test
    fun lowStaysAtOrBelowTarget() {
        assertEquals(249, pick(AudioQuality.LOW, f = arrayOf(opus249, aac140, opus251)).first().itag)
    }

    @Test
    fun autoOnMeteredNetworkCapsAtHigh() {
        assertEquals(251, pick(AudioQuality.AUTO, metered = true, f = arrayOf(opus251, aac140, premium774)).first().itag)
    }

    @Test
    fun avoidedCodecIsSkipped() {
        // Devices without an Opus decoder must get AAC even when Opus is better.
        assertEquals(140, pick(AudioQuality.HIGHEST, avoid = setOf("opus"), f = arrayOf(opus251, aac140, premium774)).first().itag)
    }

    @Test
    fun noAudioMeansNoCandidates() {
        assertTrue(pick(AudioQuality.HIGHEST, f = arrayOf(video)).isEmpty())
    }
}
