/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class YearInMusicViewModel
@Inject
constructor(
    val database: MusicDatabase,
) : ViewModel() {

    val selectedYear = MutableStateFlow(LocalDateTime.now().year)

    /** null = the whole year; 1..12 = just that month (monthly Wrapped). */
    val selectedMonth = MutableStateFlow<Int?>(null)

    val firstEvent = database
        .firstEvent()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val availableYears = firstEvent.map { event ->
        val startYear = event?.event?.timestamp?.year ?: LocalDateTime.now().year
        val currentYear = LocalDateTime.now().year
        (currentYear downTo startYear).toList()
    }.stateIn(viewModelScope, SharingStarted.Lazily, listOf(LocalDateTime.now().year))

    /** [from, to] in epoch ms for the chosen year or month. */
    private val range = combine(selectedYear, selectedMonth) { year, month ->
        periodRange(year, month)
    }

    val topSongsStats = range.flatMapLatest { (from, to) ->
        database.mostPlayedSongsStats(fromTimeStamp = from, limit = 5, toTimeStamp = to)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val topSongs = range.flatMapLatest { (from, to) ->
        database.mostPlayedSongs(fromTimeStamp = from, limit = 5, toTimeStamp = to)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val topArtists = range.flatMapLatest { (from, to) ->
        database.mostPlayedArtists(fromTimeStamp = from, limit = 5, toTimeStamp = to).map { artists ->
            artists.filter { it.artist.isYouTubeArtist }
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val topAlbums = range.flatMapLatest { (from, to) ->
        database.mostPlayedAlbums(fromTimeStamp = from, limit = 5, toTimeStamp = to)
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val totalListeningTime = topSongsStats.map { songs ->
        songs.sumOf { stat -> stat.timeListened ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val totalSongsPlayed = topSongsStats.map { songs ->
        songs.sumOf { stat -> stat.songCountListened.toLong() }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    init {
        viewModelScope.launch {
            topArtists.collect { artists ->
                artists
                    .map { it.artist }
                    .filter {
                        it.thumbnailUrl == null || Duration.between(
                            it.lastUpdateTime,
                            LocalDateTime.now()
                        ) > Duration.ofDays(10)
                    }.forEach { artist ->
                        YouTube.artist(artist.id).onSuccess { artistPage ->
                            database.query {
                                update(artist, artistPage)
                            }
                        }
                    }
            }
        }

        viewModelScope.launch {
            topAlbums.collect { albums ->
                albums
                    .filter { it.album.songCount == 0 }
                    .forEach { album ->
                        YouTube
                            .album(album.id)
                            .onSuccess { albumPage ->
                                database.query {
                                    update(album.album, albumPage, album.artists)
                                }
                            }.onFailure {
                                reportException(it)
                                if (it.message?.contains("NOT_FOUND") == true) {
                                    database.query {
                                        delete(album.album)
                                    }
                                }
                            }
                    }
            }
        }
    }
}

/** Start and end (epoch ms, UTC like the rest of the stats) of a year, or of one month in it. */
fun periodRange(year: Int, month: Int?): Pair<Long, Long> {
    val start = LocalDateTime.of(year, month ?: 1, 1, 0, 0)
    val end = if (month == null) start.plusYears(1) else start.plusMonths(1)
    return start.toInstant(ZoneOffset.UTC).toEpochMilli() to end.toInstant(ZoneOffset.UTC).toEpochMilli() - 1
}
