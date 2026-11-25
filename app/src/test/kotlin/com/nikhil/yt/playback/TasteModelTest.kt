package com.nikhil.yt.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TasteModelTest {
    private data class S(val id: String, val artist: String)

    private var clock = 1_000_000_000L
    private val model = TasteModel { clock }
    private fun rank(items: List<S>) = model.rank(items, { it.id }, { listOf(it.artist) }, Random(7))

    @Test
    fun likedArtistRanksAboveSkippedArtist() {
        repeat(3) { model.feedback("a$it", listOf("loved"), TasteModel.FINISHED) }
        repeat(3) { model.feedback("b$it", listOf("meh"), TasteModel.SKIPPED_INSTANTLY) }
        val ranked = rank(listOf(S("x", "meh"), S("y", "loved")))
        assertEquals("y", ranked.first().id)
        assertEquals("x", ranked.last().id)
    }

    @Test
    fun sessionMoodFadesButLongTermStays() {
        model.feedback("s", listOf("a"), TasteModel.LIKED)
        val fresh = model.score("other", listOf("a"))
        clock += TasteModel.SESSION_MS + 1
        val later = model.score("other", listOf("a"))
        assertTrue("session boost should fade: $fresh -> $later", later < fresh && later > 0)
        clock += TasteModel.HALF_LIFE_MS
        assertTrue(model.score("other", listOf("a")) < later)
    }

    @Test
    fun explorationAndArtistSpread() {
        repeat(5) { model.feedback("k$it", listOf("known"), TasteModel.FINISHED) }
        val items = List(8) { S("k$it", "known") } + List(4) { S("n$it", "new$it") }
        val ranked = rank(items)
        assertEquals(items.toSet(), ranked.toSet())
        // an unknown artist shows up within the first EXPLORE_EVERY slots
        assertTrue(ranked.take(TasteModel.EXPLORE_EVERY).any { it.artist.startsWith("new") })
        // no back-to-back same artist while alternatives remain
        val firstRepeat = (1 until ranked.size).firstOrNull { ranked[it].artist == ranked[it - 1].artist }
        assertTrue(firstRepeat == null || ranked.drop(firstRepeat).all { it.artist == "known" })
    }

    @Test
    fun likedStateBoostsAndUnlikeRemovesIt() {
        val neutral = model.score("song", listOf("artist"))
        model.likedSongs = setOf("song")
        model.likedArtistCounts = mapOf("artist" to 5)
        val liked = model.score("song", listOf("artist"))
        assertTrue(liked > neutral + 0.3)
        // another song by a liked artist also rises
        assertTrue(model.score("other", listOf("artist")) > neutral)
        model.likedSongs = emptySet()
        model.likedArtistCounts = emptyMap()
        assertEquals(neutral, model.score("song", listOf("artist")), 1e-9)
    }

    @Test
    fun searchedArtistCountsTowardMoodButIsNotASeed() {
        model.artistFeedback(listOf("searched"), TasteModel.SEARCHED, inSession = true)
        assertTrue(model.score("x", listOf("searched")) > 0.3)
        assertEquals(null, model.sessionSeed())
    }

    @Test
    fun sessionSeedIsBestRecentPositive() {
        model.feedback("meh", listOf("x"), TasteModel.SKIPPED_EARLY)
        model.feedback("good", listOf("y"), TasteModel.FINISHED)
        model.feedback("great", listOf("z"), TasteModel.LIKED)
        assertEquals("great", model.sessionSeed())
        assertNotEquals("meh", model.sessionSeed())
    }
}
