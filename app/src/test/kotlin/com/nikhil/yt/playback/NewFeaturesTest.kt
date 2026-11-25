package com.nikhil.yt.playback

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.nikhil.yt.constants.EqualizerBandLevelsMbKey
import com.nikhil.yt.constants.EqualizerEnabledKey
import com.nikhil.yt.constants.ImmersiveAudioKey
import com.nikhil.yt.constants.PlayerVolumeKey
import com.nikhil.yt.innertube.models.Artist
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.playback.queues.MoodMixQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NewFeaturesTest {
    @Test
    fun sleepFade_isSilentToTheEarUntilTheLastStretch() {
        assertEquals(10 * 60_000L, SleepTimer.fadeLengthMs(60 * 60_000L)) // long timer: 10-minute fade
        assertEquals(2 * 60_000L + 30_000L, SleepTimer.fadeLengthMs(5 * 60_000L)) // short: half the timer
        assertEquals(1f, SleepTimer.fadeFactor(20 * 60_000L, 10 * 60_000L))
        assertEquals(0.25f, SleepTimer.fadeFactor(5 * 60_000L, 10 * 60_000L), 1e-4f)
        assertEquals(0f, SleepTimer.fadeFactor(0L, 10 * 60_000L))
    }

    @Test
    fun deviceMemory_roundTripsSettingsAndClearsOnesTheDeviceNeverHad() {
        val airpods = mutablePreferencesOf(
            EqualizerEnabledKey to true,
            EqualizerBandLevelsMbKey to "0,300,0",
            PlayerVolumeKey to 0.8f,
        )
        val json = DeviceAudioMemory.encode(airpods)
        val speaker = mutablePreferencesOf(EqualizerEnabledKey to false, ImmersiveAudioKey to true)
        DeviceAudioMemory.decode(json, speaker)
        assertEquals(true, speaker[EqualizerEnabledKey])
        assertEquals("0,300,0", speaker[EqualizerBandLevelsMbKey])
        assertEquals(0.8f, speaker[PlayerVolumeKey]!!, 1e-6f)
        assertNull(speaker[ImmersiveAudioKey]) // AirPods never had Immersive on
    }

    @Test
    fun crossfeed_mixesTheOtherEarOnlyWhenEnabledOnHeadphones() {
        val p = CrossfeedProcessor().apply { enabled = true; headphones = true }
        p.prime(48_000)
        repeat(2_000) { p.cross(1f, 0f) } // left-only signal, settle the filter
        assertTrue("right ear hears some of the left", p.outR > 0.1f)
        assertTrue("left stays louder", p.outL > p.outR)
        assertTrue("no level jump", p.outL <= 1f)

        val off = CrossfeedProcessor().apply { enabled = true; headphones = false }
        off.prime(48_000)
        repeat(2_000) { off.cross(1f, 0f) }
        assertEquals(0f, off.outR, 1e-6f)
    }

    @Test
    fun moodDial_picksTheNearestMoodThisRegionHas() {
        val titles = listOf("Chill", "Energize", "Sleep", "Focus")
        assertEquals("Sleep", MoodMixQueue.pickMood(titles, 0f))
        assertEquals("Chill", MoodMixQueue.pickMood(titles, 0.2f))
        assertEquals("Energize", MoodMixQueue.pickMood(titles, 1f)) // no "Workout" here: next nearest
        assertNull(MoodMixQueue.pickMood(emptyList(), 0.5f))
    }

    @Test
    fun moodDial_familiarityDecidesKnownVersusNew() {
        val known = song("a", "artist1")
        val new = song("b", "stranger")
        val songs = listOf(new, known)
        val familiar = MoodMixQueue.rank(songs, setOf("a"), setOf("artist1"), 1f, Random(1))
        val fresh = MoodMixQueue.rank(songs, setOf("a"), setOf("artist1"), 0f, Random(1))
        assertEquals("a", familiar.first().id)
        assertEquals("b", fresh.first().id)
    }

    @Test
    fun moodDial_tasteLeadsAndDislikesAreDropped() {
        val loved = song("loved", "a1")
        val meh = song("meh", "a2")
        val disliked = song("nope", "a3")
        val scores = mapOf("loved" to 2.0, "meh" to 0.0, "nope" to -1.0)
        val out = MoodMixQueue.rank(listOf(meh, disliked, loved), emptySet(), emptySet(), 0.5f, Random(3), score = { scores.getValue(it.id) })
        assertEquals(listOf("loved", "meh"), out.map { it.id })
    }

    @Test
    fun moodDial_neverTheSameArtistTwiceInARow() {
        val songs = listOf(song("x1", "x"), song("x2", "x"), song("y1", "y"))
        val out = MoodMixQueue.rank(songs, emptySet(), emptySet(), 0.5f, Random(1), score = { if (it.id.startsWith("x")) 2.0 else 0.0 })
        assertEquals(listOf("x", "y", "x"), out.map { it.artists.first().id })
    }

    @Test
    fun adaptiveOutput_pushesQuietDevices_neverPastClean() {
        // Volume pinned at max, limiter relaxed: push up a step.
        assertEquals(1.5f, AdaptiveOutput.decide(1f, atMaxShare = 0.9f, lowShare = 0f, avgReductionDb = 0.5f, heavyShare = 0f), 1e-6f)
        // Still pinned, but the limiter is squashing: cleanliness wins, back off.
        assertEquals(0.5f, AdaptiveOutput.decide(1f, 0.9f, 0f, avgReductionDb = 3f, heavyShare = 0f), 1e-6f)
        assertEquals(0.5f, AdaptiveOutput.decide(1f, 0.9f, 0f, 0.5f, heavyShare = 0.01f), 1e-6f)
        // Listening quietly: ease back toward natural.
        assertEquals(0.75f, AdaptiveOutput.decide(1f, 0f, lowShare = 0.8f, 0f, 0f), 1e-6f)
        // In between: hold.
        assertEquals(1f, AdaptiveOutput.decide(1f, 0.3f, 0.3f, 0.5f, 0f), 1e-6f)
        // Bounds.
        assertEquals(AdaptiveOutput.MAX_PUSH_DB, AdaptiveOutput.decide(AdaptiveOutput.MAX_PUSH_DB, 1f, 0f, 0f, 0f), 1e-6f)
        assertEquals(0f, AdaptiveOutput.decide(0f, 0f, 1f, 0f, 0f), 1e-6f)
        assertEquals("AirPods Pro", AdaptiveOutput.nameOf("bluetooth:AirPods Pro"))
    }

    private fun song(id: String, artistId: String) = SongItem(
        id = id,
        title = id,
        artists = listOf(Artist(name = artistId, id = artistId)),
        thumbnail = "",
    )
}
