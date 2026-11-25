package com.nikhil.yt.playback.queues

import androidx.media3.common.MediaItem
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.PlaylistItem
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.playback.TasteModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The Home mood dial, as an endless personal feed. [energy] runs calm (0) to hype (1); [familiarity] runs
 * new (0) to familiar (1).
 *
 * Songs come from three places, fetched in parallel: YouTube Music's own mood playlists, mood playlists
 * around the artists you've played most this month (so the mix is in your languages, not just the region's
 * charts), and a radio from the in-mood song you're most likely to love. Every batch is ranked by the taste
 * model, which reacts to what you did minutes ago: skip two songs by an artist and the next batch moves on.
 */
class MoodMixQueue(
    private val database: MusicDatabase,
    private val taste: TasteModel?,
    private val isHidden: (String) -> Boolean,
    private val energy: Float,
    private val familiarity: Float,
) : Queue {
    override val preloadItem: MediaMetadata? = null
    override val shuffle: Boolean = false

    private val seen = HashSet<String>()
    private val pendingPlaylists = ArrayDeque<String>()
    private var knownSongs: Set<String> = emptySet()
    private var knownArtists: Set<String?> = emptySet()
    private var lastBest: String? = null
    private var exhausted = false

    override suspend fun getInitialStatus(): Queue.Status = withContext(Dispatchers.IO) {
        val step = (energy.coerceIn(0f, 1f) * LADDER.lastIndex).roundToInt()
        val now = System.currentTimeMillis()
        val (moodTitle, songs) = coroutineScope {
            val interest = async { runCatching { database.songsWithInterest() }.getOrDefault(emptyList()) }
            val official = async { runCatching { officialMoodPlaylists() }.getOrNull() }
            val personal = async {
                val artists = runCatching {
                    database.mostPlayedArtists(now - 30L * DAY_MS, limit = 3, toTimeStamp = now).first().map { it.artist.name }
                }.getOrDefault(emptyList())
                artists.map { name ->
                    async {
                        YouTube.search("${SEARCH_TERMS[step]} $name", YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST)
                            .getOrNull()?.items?.filterIsInstance<PlaylistItem>()?.firstOrNull()?.id
                    }
                }.awaitAll().filterNotNull()
            }

            val known = interest.await()
            knownSongs = known.map { it.id }.toHashSet()
            knownArtists = known.flatMap { s -> s.artists.map { it.id } }.toHashSet()

            val (title, officialIds) = official.await() ?: (null to emptyList())
            pendingPlaylists += officialIds.drop(FIRST_OFFICIAL)
            val toLoad = officialIds.take(FIRST_OFFICIAL) + personal.await()
            (title ?: SEARCH_TERMS[step].replaceFirstChar { it.uppercase() }) to loadPlaylists(toLoad)
        }
        val batch = nextBatch(songs, FIRST_SIZE)
        if (batch.isEmpty()) error("No songs for this mood")
        Queue.Status(moodTitle, batch, 0, 0L)
    }

    override fun hasNextPage(): Boolean = !exhausted

    override suspend fun nextPage(): List<MediaItem> = withContext(Dispatchers.IO) {
        // Follow the mood you're in right now: the song you enjoyed most this session, if it came from this mix.
        val seed = taste?.sessionSeed()?.takeIf { it in seen } ?: lastBest
        val songs = coroutineScope {
            val more = async { loadPlaylists(List(minOf(2, pendingPlaylists.size)) { pendingPlaylists.removeFirst() }) }
            val radio = async { seed?.let { YouTube.next(WatchEndpoint(videoId = it)).getOrNull()?.items }.orEmpty() }
            more.await() + radio.await()
        }
        nextBatch(songs, NEXT_SIZE).also { if (it.isEmpty()) exhausted = true }
    }

    private suspend fun officialMoodPlaylists(): Pair<String, List<String>> {
        val moods = YouTube.moodAndGenres().getOrThrow().flatMap { it.items }
        val title = pickMood(moods.map { it.title }, energy) ?: error("No moods available")
        val mood = moods.first { it.title == title }
        val ids = YouTube.browse(mood.endpoint.browseId, mood.endpoint.params).getOrThrow()
            .items.flatMap { it.items }.filterIsInstance<PlaylistItem>().map { it.id }
        return title to ids
    }

    private suspend fun loadPlaylists(ids: List<String>): List<SongItem> = coroutineScope {
        ids.map { async { YouTube.playlist(it).getOrNull()?.songs.orEmpty() } }.awaitAll().flatten()
    }

    private fun nextBatch(songs: List<SongItem>, size: Int): List<MediaItem> {
        val fresh = songs.distinctBy { it.id }.filter { it.id !in seen && !isHidden(it.id) }
        val ranked = rank(fresh, knownSongs, knownArtists, familiarity, score = { s -> taste?.score(s.id, s.artists.mapNotNull { it.id }) ?: 0.0 })
            .take(size)
        lastBest = ranked.firstOrNull()?.id ?: lastBest
        seen += ranked.map { it.id }
        return ranked.map { it.toMediaItem() }
    }

    companion object {
        private const val FIRST_OFFICIAL = 2
        private const val FIRST_SIZE = 25
        private const val NEXT_SIZE = 20
        private const val DAY_MS = 24L * 60 * 60 * 1000

        /** How much your taste weighs against the new/familiar dial. Taste scores run about -1..2.5. */
        private const val TASTE_WEIGHT = 0.6

        /** Calm to hype, as YouTube Music names its moods. Matched by substring, so wording can drift. */
        val LADDER = listOf("sleep", "relax", "chill", "focus", "romance", "feel good", "commute", "party", "energi", "workout")

        /** The same ladder as words people search with ("chill Arijit Singh"). */
        private val SEARCH_TERMS = listOf("sleep", "relaxing", "chill", "focus", "romantic", "feel good", "road trip", "party", "energetic", "workout")

        /** The mood nearest the dial position that this region's YouTube Music actually offers. */
        fun pickMood(titles: List<String>, energy: Float): String? {
            val target = energy.coerceIn(0f, 1f) * LADDER.lastIndex
            return LADDER.indices.sortedBy { abs(it - target) }
                .firstNotNullOfOrNull { i -> titles.firstOrNull { it.lowercase().contains(LADDER[i]) } }
        }

        /**
         * Familiar = played before (1) or by an artist you play (0.5); new = neither. The dial blends the two,
         * your taste [score] lifts what you'll love, songs you've shown you dislike are dropped, and the same
         * artist never plays twice in a row.
         */
        fun rank(
            songs: List<SongItem>,
            knownSongs: Set<String>,
            knownArtists: Set<String?>,
            familiarity: Float,
            random: Random = Random,
            score: (SongItem) -> Double = { 0.0 },
        ): List<SongItem> {
            val ranked = songs.map { it to score(it) }
                .filter { it.second > -0.5 }
                .sortedByDescending { (song, taste) ->
                    val known = when {
                        song.id in knownSongs -> 1f
                        song.artists.any { it.id in knownArtists } -> 0.5f
                        else -> 0f
                    }
                    familiarity * known + (1 - familiarity) * (1 - known) + TASTE_WEIGHT * taste + random.nextFloat() * 0.25f
                }
                .map { it.first }
            return TasteModel.spreadArtists(ranked) { s -> s.artists.mapNotNull { it.id } }
        }
    }
}
