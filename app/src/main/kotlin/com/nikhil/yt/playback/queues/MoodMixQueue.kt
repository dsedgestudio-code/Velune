package com.nikhil.yt.playback.queues

import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * The Home mood dial. [energy] runs calm (0) to hype (1) and picks one of YouTube Music's own mood
 * pages; [familiarity] runs new (0) to familiar (1) and decides how much of the mix is songs and
 * artists you already play versus ones you've never heard.
 */
class MoodMixQueue(
    private val database: MusicDatabase,
    private val energy: Float,
    private val familiarity: Float,
) : Queue {
    override val preloadItem: MediaMetadata? = null
    override val shuffle: Boolean = false

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        val moods = YouTube.moodAndGenres().getOrThrow().flatMap { it.items }
        val mood = pickMood(moods.map { it.title }, energy)?.let { title -> moods.first { it.title == title } }
            ?: error("No moods available")
        val playlists = YouTube.browse(mood.endpoint.browseId, mood.endpoint.params).getOrThrow()
            .items.flatMap { it.items }.filterIsInstance<PlaylistItem>().take(PLAYLISTS)
        val songs = coroutineScope {
            playlists.map { async { YouTube.playlist(it.id).getOrNull()?.songs.orEmpty() } }.awaitAll()
        }.flatten().distinctBy { it.id }

        val interest = database.songsWithInterest()
        val knownSongs = interest.map { it.id }.toHashSet()
        val knownArtists = interest.flatMap { s -> s.artists.map { it.id } }.toHashSet()
        val mix = rank(songs, knownSongs, knownArtists, familiarity).take(SIZE)
        Queue.Status(mood.title, mix.map { it.toMediaItem() }, 0, 0L)
    }

    override fun hasNextPage(): Boolean = false

    override suspend fun nextPage() = throw UnsupportedOperationException()

    companion object {
        private const val PLAYLISTS = 3
        private const val SIZE = 40

        /** Calm to hype, as YouTube Music names its moods. Matched by substring, so wording can drift. */
        val LADDER = listOf("sleep", "relax", "chill", "focus", "romance", "feel good", "commute", "party", "energi", "workout")

        /** The mood nearest the dial position that this region's YouTube Music actually offers. */
        fun pickMood(titles: List<String>, energy: Float): String? {
            val target = energy.coerceIn(0f, 1f) * LADDER.lastIndex
            return LADDER.indices.sortedBy { abs(it - target) }
                .firstNotNullOfOrNull { i -> titles.firstOrNull { it.lowercase().contains(LADDER[i]) } }
        }

        /** Familiar = played before (1) or by an artist you play (0.5); new = neither. The dial blends the two. */
        fun rank(songs: List<SongItem>, knownSongs: Set<String>, knownArtists: Set<String?>, familiarity: Float, random: Random = Random): List<SongItem> =
            songs.sortedByDescending { song ->
                val known = when {
                    song.id in knownSongs -> 1f
                    song.artists.any { it.id in knownArtists } -> 0.5f
                    else -> 0f
                }
                familiarity * known + (1 - familiarity) * (1 - known) + random.nextFloat() * 0.25f
            }
    }
}
