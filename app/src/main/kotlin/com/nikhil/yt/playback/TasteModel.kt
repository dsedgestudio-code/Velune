/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

import android.content.Context
import androidx.media3.common.MediaItem
import com.nikhil.yt.extensions.metadata
import com.nikhil.yt.db.MusicDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tanh
import kotlin.random.Random

/**
 * On-device taste model, Reels-style: learns from what you actually do (finish, repeat, like, skip early),
 * reacts within the session, and slowly forgets so taste can change. No data leaves the phone.
 */
class TasteModel(private val now: () -> Long = System::currentTimeMillis) {
    class Signal(var score: Double, var at: Long)
    private class Event(val songId: String, val artists: List<String>, val reward: Double, val at: Long)

    internal val songs = HashMap<String, Signal>()
    internal val artists = HashMap<String, Signal>()
    private val session = ArrayDeque<Event>()

    /** Liked songs are a standing state (not a decaying event): kept in sync with the database. */
    @Volatile var likedSongs: Set<String> = emptySet()
    @Volatile var likedArtistCounts: Map<String, Int> = emptyMap()

    @Synchronized
    fun feedback(songId: String, artistKeys: List<String>, reward: Double) {
        val t = now()
        bump(songs, songId, reward, t)
        artistKeys.forEach { bump(artists, it, reward * 0.5, t) }
        addSessionEvent(Event(songId, artistKeys, reward, t))
    }

    /** Artist-only signal (e.g. you searched for them). [inSession]: also counts toward the current mood. */
    @Synchronized
    fun artistFeedback(artistKeys: List<String>, reward: Double, inSession: Boolean) {
        val t = now()
        artistKeys.forEach { bump(artists, it, reward, t) }
        if (inSession) addSessionEvent(Event("", artistKeys, reward, t))
    }

    private fun addSessionEvent(e: Event) {
        session.addLast(e)
        while (session.size > 100 || (session.isNotEmpty() && e.at - session.first().at > SESSION_MS)) session.removeFirst()
    }

    /** Positive = you'll probably like it. Session mood weighs 1.5x long-term taste. */
    @Synchronized
    fun score(songId: String, artistKeys: List<String>): Double {
        val t = now()
        val liked = (if (songId in likedSongs) LIKED_STATE else 0.0) +
            artistKeys.sumOf { ln(1.0 + (likedArtistCounts[it] ?: 0)) * 0.7 } // 1 liked song ≈ 0.5, 10 ≈ 1.7
        val longTerm = liked + decayed(songs[songId], t) + artistKeys.sumOf { decayed(artists[it], t) }
        val mood = session.filter { t - it.at < SESSION_MS && it.artists.any(artistKeys::contains) }.sumOf { it.reward }
        return tanh(longTerm / 4) + 1.5 * tanh(mood / 2)
    }

    /** The song you've enjoyed most in this session: a second seed so the feed follows your current mood. */
    @Synchronized
    fun sessionSeed(): String? {
        val t = now()
        return session.filter { it.reward > 0 && it.songId.isNotEmpty() && t - it.at < SESSION_MS }
            .maxByOrNull { it.reward - (t - it.at) / SESSION_MS.toDouble() }?.songId
    }

    /**
     * Ranks candidates: best-scored first with light jitter, ~1 in 5 slots for artists you've never heard
     * (exploration), strongly disliked last, and never the same lead artist twice in a row.
     */
    fun <T> rank(items: List<T>, id: (T) -> String, artistKeys: (T) -> List<String>, random: Random = Random.Default): List<T> {
        val scored = items.map { Triple(it, score(id(it), artistKeys(it)), random.nextDouble() * 0.2) }
        val disliked = scored.filter { it.second < -0.5 }.map { it.first }
        val unknown = scored.filter { it.second in -0.05..0.05 }.map { it.first }.shuffled(random).toMutableList()
        val known = scored.filter { it.second > 0.05 || it.second in -0.5..-0.05 }
            .sortedByDescending { it.second + it.third }.map { it.first }.toMutableList()

        val mixed = mutableListOf<T>()
        while (known.isNotEmpty() || unknown.isNotEmpty()) {
            val explore = unknown.isNotEmpty() && (known.isEmpty() || (mixed.size + 1) % EXPLORE_EVERY == 0)
            mixed += if (explore) unknown.removeAt(0) else known.removeAt(0)
        }
        return spreadArtists(mixed + disliked, artistKeys)
    }

    private fun <T> spreadArtists(list: List<T>, artistKeys: (T) -> List<String>): List<T> {
        val out = list.toMutableList()
        for (i in 1 until out.size) {
            val prev = artistKeys(out[i - 1]).firstOrNull() ?: continue
            if (artistKeys(out[i]).firstOrNull() != prev) continue
            val swap = (i + 1 until out.size).firstOrNull { artistKeys(out[it]).firstOrNull() != prev } ?: continue
            out.add(i, out.removeAt(swap))
        }
        return out
    }

    private fun bump(map: HashMap<String, Signal>, key: String, reward: Double, t: Long) {
        map[key] = Signal((decayed(map[key], t) + reward).coerceIn(-10.0, 10.0), t)
    }

    internal fun decayed(s: Signal?, t: Long): Double = s?.let { it.score * 0.5.pow((t - it.at).toDouble() / HALF_LIFE_MS) } ?: 0.0

    companion object {
        const val HALF_LIFE_MS = 21L * 24 * 60 * 60 * 1000
        const val SESSION_MS = 2L * 60 * 60 * 1000
        const val EXPLORE_EVERY = 5

        const val LIKED_STATE = 1.5
        const val SEARCHED = 0.8
        const val SEARCHED_LONG_AGO = 0.3
        const val FINISHED = 1.0
        const val REPEATED = 1.5
        const val LIKED = 2.0
        const val UNLIKED = -1.0
        const val DOWNLOADED = 1.5
        const val SKIPPED_INSTANTLY = -1.0 // < 15 s
        const val SKIPPED_EARLY = -0.5 // before halfway
    }
}

/** App-wide taste model, saved to a small JSON file so learning survives restarts. */
@Singleton
class TasteStore @Inject constructor(
    @ApplicationContext context: Context,
    private val database: MusicDatabase,
) {
    val model = TasteModel()
    private val file = File(context.filesDir, "taste_model.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Highest search-history id already learned from (ids only grow; the table has no timestamps). */
    @Volatile private var lastSearchId = -1L

    init {
        runCatching {
            if (!file.exists()) return@runCatching
            val json = JSONObject(file.readText())
            for ((name, map) in listOf("songs" to model.songs, "artists" to model.artists)) {
                val obj = json.optJSONObject(name) ?: continue
                obj.keys().forEach { k -> obj.getJSONArray(k).let { map[k] = TasteModel.Signal(it.getDouble(0), it.getLong(1)) } }
            }
            lastSearchId = json.optLong("lastSearchId", -1L)
        }
        scope.launch { syncLikes() }
        scope.launch { syncSearches() }
    }

    /**
     * Liked songs straight from the database, so a like/unlike anywhere in the app (player, library, menus)
     * updates taste the moment it's saved. Changes also nudge the current mood.
     */
    private suspend fun syncLikes() {
        var previous: Map<String, List<String>>? = null
        database.likedSongsByPlayTimeAsc().collect { songs ->
            val liked = songs.associate { it.id to it.artists.map { a -> a.id } }
            model.likedSongs = liked.keys
            model.likedArtistCounts = liked.values.flatten().groupingBy { it }.eachCount()
            previous?.let { before ->
                (liked.keys - before.keys).forEach { model.feedback(it, liked.getValue(it), TasteModel.LIKED) }
                (before.keys - liked.keys).forEach { model.feedback(it, before.getValue(it), TasteModel.UNLIKED) }
                if (liked.keys != before.keys) save()
            }
            previous = liked
        }
    }

    /** Searching for an artist in your library = strong interest (and, if fresh, your current mood). */
    private suspend fun syncSearches() {
        database.searchHistory().collect { history ->
            val fresh = history.filter { it.id > lastSearchId }
            if (fresh.isEmpty()) return@collect
            val firstRun = lastSearchId < 0
            val artists = database.allArtistsByPlayTime().first()
                .map { it.artist }
                .filter { it.name.length >= 3 }
                .associateBy { it.name.lowercase() }
            for (search in fresh) {
                val q = search.query.lowercase()
                val matched = artists.filterKeys { name -> name in q || (q.length >= 4 && q in name) }.values.map { it.id }
                if (matched.isEmpty()) continue
                if (firstRun) model.artistFeedback(matched, TasteModel.SEARCHED_LONG_AGO, inSession = false)
                else model.artistFeedback(matched, TasteModel.SEARCHED, inSession = true)
            }
            lastSearchId = fresh.maxOf { it.id }
            save()
        }
    }

    fun feedback(item: MediaItem?, reward: Double) {
        item ?: return
        model.feedback(item.mediaId, artistKeys(item), reward)
        save()
    }

    fun feedback(songId: String, artistKeys: List<String>, reward: Double) {
        model.feedback(songId, artistKeys, reward)
        save()
    }

    fun rank(items: List<MediaItem>): List<MediaItem> = model.rank(items, { it.mediaId }, { artistKeys(it) })

    @Synchronized
    private fun save() = runCatching {
        val t = System.currentTimeMillis()
        val json = JSONObject()
        for ((name, map) in listOf("songs" to model.songs, "artists" to model.artists)) {
            val obj = JSONObject()
            synchronized(model) {
                // Keep the file small: drop signals that have faded out, cap at the 5000 strongest.
                map.entries.filter { kotlin.math.abs(model.decayed(it.value, t)) > 0.05 }
                    .sortedByDescending { kotlin.math.abs(model.decayed(it.value, t)) }.take(5000)
                    .forEach { (k, s) -> obj.put(k, org.json.JSONArray().put(s.score).put(s.at)) }
            }
            json.put(name, obj)
        }
        json.put("lastSearchId", lastSearchId)
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file) // atomic replace: a crash mid-write never corrupts the saved taste
    }

    companion object {
        fun artistKeys(item: MediaItem): List<String> =
            item.metadata?.artists?.map { it.id ?: it.name.lowercase() }
                ?: listOfNotNull(item.mediaMetadata.artist?.toString()?.lowercase())
    }
}
