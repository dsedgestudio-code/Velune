package com.nikhil.yt.playback

import android.content.Context
import androidx.core.content.edit

/** Remembers where you stopped in anything long (mixes, podcasts, live sets) so it picks up there next time. */
class LongTrackResume(context: Context) {
    private val prefs = context.getSharedPreferences("long_track_resume", Context.MODE_PRIVATE)

    fun save(id: String, positionMs: Long, durationMs: Long) {
        if (durationMs < MIN_DURATION_MS) return
        prefs.edit {
            if (positionMs < EDGE_MS || positionMs > durationMs - EDGE_MS) remove(id) else putLong(id, positionMs)
        }
    }

    fun positionFor(id: String): Long? = prefs.getLong(id, -1L).takeIf { it > 0 }

    companion object {
        const val MIN_DURATION_MS = 15 * 60_000L
        const val EDGE_MS = 30_000L
    }
}

/**
 * Songs you skip within seconds every time. After [PROMPT_AFTER] instant skips we ask once whether to
 * hide it; hidden songs are passed over when the queue reaches them on its own (picking one still plays it).
 */
class SkipHabits(context: Context) {
    private val prefs = context.getSharedPreferences("skip_habits", Context.MODE_PRIVATE)

    /** Records an instant skip; true when it's time to offer hiding this song. */
    fun recordInstantSkip(id: String): Boolean {
        if (isHidden(id) || prefs.getBoolean("asked:$id", false)) return false
        val count = prefs.getInt("n:$id", 0) + 1
        prefs.edit { putInt("n:$id", count) }
        if (count < PROMPT_AFTER) return false
        prefs.edit { putBoolean("asked:$id", true) }
        return true
    }

    /** Listening through means the habit is broken: start counting again. */
    fun recordListened(id: String) = prefs.edit { remove("n:$id") }

    fun isHidden(id: String) = prefs.getBoolean("hidden:$id", false)

    fun setHidden(id: String, hidden: Boolean) = prefs.edit {
        if (hidden) putBoolean("hidden:$id", true) else remove("hidden:$id")
    }

    fun hiddenCount() = prefs.all.keys.count { it.startsWith("hidden:") }

    fun unhideAll() = prefs.edit { prefs.all.keys.filter { it.startsWith("hidden:") }.forEach(::remove) }

    companion object {
        const val PROMPT_AFTER = 3
    }
}
