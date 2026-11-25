/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

import android.content.Context
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Smart shuffle: still random, but tilted toward fresh downloads and less-played songs,
 * and songs skipped in the last few hours go to the very end.
 */
object AdaptiveShuffle {
    const val SKIP_COOLDOWN_MS = 4 * 60 * 60 * 1000L
    /** Songs heard within this window go to the back of a shuffle, so a reshuffle doesn't replay them. */
    const val HEARD_COOLDOWN_MS = 48 * 60 * 60 * 1000L
    private const val NEW_DOWNLOAD_DAYS = 14f

    data class Track(
        val plays: Float = 0f,
        /** Days since download finished; null if not downloaded / unknown. */
        val downloadedDaysAgo: Float? = null,
        val skippedRecently: Boolean = false,
        /** When this song was last listened to (ms epoch), if within [HEARD_COOLDOWN_MS]; else null. */
        val heardAt: Long? = null,
    )

    fun weight(t: Track): Double {
        val lessPlayed = 1.0 / sqrt(1.0 + t.plays.coerceAtLeast(0f)) // 0 plays = 1, 3 = 0.5, 15 = 0.25
        val fresh = t.downloadedDaysAgo
            ?.takeIf { it in 0f..NEW_DOWNLOAD_DAYS }
            ?.let { 1.0 + 2.0 * (1.0 - it / NEW_DOWNLOAD_DAYS) } // just downloaded = 3x, fades over 2 weeks
            ?: 1.0
        return lessPlayed * fresh
    }

    /**
     * Play order (indices into [tracks]): [history] (already played, kept as-is), then [currentIndex]
     * (or a weighted pick when -1), then:
     *  1. songs not heard recently, weighted random (Efraimidis–Spirakis: smaller -ln(u)/w plays sooner),
     *  2. songs heard in the last 48 h, the longest-ago first,
     *  3. songs skipped in the last 4 h.
     */
    fun order(
        tracks: List<Track>,
        currentIndex: Int,
        random: Random = Random.Default,
        history: List<Int> = emptyList(),
    ): IntArray {
        val fixed = history.toSet() + currentIndex
        val rest = tracks.indices.filter { it !in fixed }
        val (skipped, notSkipped) = rest.partition { tracks[it].skippedRecently }
        val (heard, fresh) = notSkipped.partition { tracks[it].heardAt != null }
        val weighted = fresh.map { it to -ln(1.0 - random.nextDouble()) / weight(tracks[it]) }
            .sortedBy { it.second }.map { it.first }
        val head = history + if (currentIndex >= 0) listOf(currentIndex) else emptyList()
        return (head + weighted + heard.sortedBy { tracks[it].heardAt } + skipped.shuffled(random)).toIntArray()
    }
}

/** Per-song timestamps kept across app restarts (and service kills) for [windowMs]. */
class RecentMemory(context: Context, name: String, private val windowMs: Long) {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun record(mediaId: String, now: Long = System.currentTimeMillis()) {
        val editor = prefs.edit().putLong(mediaId, now)
        prefs.all.forEach { (id, at) -> if (at is Long && now - at > windowMs) editor.remove(id) }
        editor.apply()
    }

    /** Timestamp if within the window, else null. */
    fun recentAt(mediaId: String, now: Long = System.currentTimeMillis()): Long? =
        prefs.getLong(mediaId, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE && now - it < windowMs }
}
