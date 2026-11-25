package com.nikhil.yt.playback

import com.nikhil.yt.playback.AdaptiveShuffle.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AdaptiveShuffleTest {
    @Test
    fun currentFirstAndEverySongExactlyOnce() {
        val tracks = List(50) { Track() }
        val order = AdaptiveShuffle.order(tracks, currentIndex = 7, random = Random(1))
        assertEquals(7, order[0])
        assertEquals((0 until 50).toSet(), order.toSet())
        assertEquals(50, order.size)
    }

    @Test
    fun recentlySkippedGoLast() {
        val tracks = List(20) { Track(skippedRecently = it in setOf(2, 5, 9)) }
        val order = AdaptiveShuffle.order(tracks, currentIndex = 0, random = Random(2))
        assertEquals(setOf(2, 5, 9), order.takeLast(3).toSet())
    }

    @Test
    fun freshAndLessPlayedComeEarlierOnAverage() {
        // index 0 current, 1 = fresh unplayed download, 2 = old heavily played, rest neutral
        val tracks = listOf(Track(), Track(plays = 0f, downloadedDaysAgo = 0f), Track(plays = 30f)) + List(37) { Track(plays = 3f) }
        var freshPos = 0.0
        var stalePos = 0.0
        val random = Random(3)
        repeat(2000) {
            val order = AdaptiveShuffle.order(tracks, 0, random).toList()
            freshPos += order.indexOf(1)
            stalePos += order.indexOf(2)
        }
        assertTrue("fresh avg ${freshPos / 2000} vs stale ${stalePos / 2000}", freshPos < stalePos / 3)
    }

    @Test
    fun recentlyHeardGoAfterFreshButBeforeSkipped_oldestHeardFirst() {
        val tracks = List(10) { i ->
            when (i) {
                1 -> Track(heardAt = 2_000L)
                2 -> Track(heardAt = 1_000L)
                3 -> Track(skippedRecently = true)
                else -> Track()
            }
        }
        val order = AdaptiveShuffle.order(tracks, currentIndex = 0, random = Random(4)).toList()
        assertEquals(listOf(2, 1, 3), order.takeLast(3)) // heard (oldest first), then skipped
        assertEquals(setOf(4, 5, 6, 7, 8, 9), order.subList(1, 7).toSet())
    }

    @Test
    fun historyIsKeptAndNeverReplayed() {
        val tracks = List(12) { Track() }
        val order = AdaptiveShuffle.order(tracks, currentIndex = 5, random = Random(5), history = listOf(9, 2)).toList()
        assertEquals(listOf(9, 2, 5), order.take(3))
        assertEquals((0 until 12).toSet(), order.toSet())
        assertEquals(12, order.size)
    }

    @Test
    fun weights() {
        assertEquals(1.0, AdaptiveShuffle.weight(Track()), 1e-9)
        assertEquals(0.5, AdaptiveShuffle.weight(Track(plays = 3f)), 1e-9)
        assertEquals(3.0, AdaptiveShuffle.weight(Track(downloadedDaysAgo = 0f)), 1e-9)
        assertEquals(1.0, AdaptiveShuffle.weight(Track(downloadedDaysAgo = 30f)), 1e-9)
    }
}
