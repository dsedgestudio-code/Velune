/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.playback

import android.content.Context
import android.media.MediaCodecList
import android.net.ConnectivityManager
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import javax.inject.Inject
import javax.inject.Singleton
import androidx.media3.common.C
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.PlayerStreamClient
import com.nikhil.yt.constants.PlayerStreamClientKey
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.FormatEntity
import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.di.DownloadCache
import com.nikhil.yt.di.PlayerCache
import com.nikhil.yt.utils.YTPlayerUtils
import com.nikhil.yt.utils.StreamClientUtils
import com.nikhil.yt.utils.enumPreference
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.constants.DownloadWifiOnlyKey
import com.nikhil.yt.constants.SmartDownloadsKey
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: Cache,
    @PlayerCache val playerCache: Cache,
    private val taste: TasteStore,
) {
    private val connectivityManager = context.getSystemService<ConnectivityManager>()!!
    private val preferredStreamClient by enumPreference(context, PlayerStreamClientKey, PlayerStreamClient.ANDROID_VR)
    private val songUrlCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    private val retryAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val pendingUpgrades = java.util.concurrent.ConcurrentHashMap<String, androidx.media3.exoplayer.offline.DownloadRequest>()
    private val upgradedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val appContext = context
    private val smartPrefs = context.getSharedPreferences("smart_downloads", Context.MODE_PRIVATE)
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Anti-Bot & Throttling Fields
    private val downloadExecutor = Executors.newFixedThreadPool(6)
    @Volatile private var currentMaxParallelDownloads = 6
    @Volatile private var cooldownUntilMs = 0L
    private val consecutiveThrottleSignals = AtomicInteger(0)

    private val avoidStreamCodecs: Set<String> by lazy {
        if (deviceSupportsMimeType("audio/opus")) emptySet() else setOf("opus")
    }

    private val mediaOkHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .proxy(YouTube.streamProxy)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isYouTubeMediaHost =
                    host.endsWith("googlevideo.com") ||
                            host.endsWith("googleusercontent.com") ||
                            host.endsWith("youtube.com") ||
                            host.endsWith("youtube-nocookie.com") ||
                            host.endsWith("ytimg.com")

                if (!isYouTubeMediaHost) return@addInterceptor chain.proceed(request)

                // Force ANDROID_VR User-Agent to match download stream creation
                val exactClientName = "ANDROID_VR"
                val userAgent = StreamClientUtils.resolveUserAgent(exactClientName)
                val originReferer = StreamClientUtils.resolveOriginReferer(exactClientName)

                val builder = request.newBuilder().header("User-Agent", userAgent)
                originReferer.origin?.let { builder.header("Origin", it) }
                originReferer.referer?.let { builder.header("Referer", it) }

                chain.proceed(builder.build())
            }.build()
    }

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            CacheDataSource
                .Factory()
                .setCache(downloadCache)
                .setUpstreamDataSourceFactory(
                    CacheDataSource
                        .Factory()
                        .setCache(playerCache)
                        .setUpstreamDataSourceFactory(
                            OkHttpDataSource.Factory(mediaOkHttpClient),
                        )
                        .setCacheWriteDataSinkFactory(null)
                        .setFlags(FLAG_IGNORE_CACHE_ON_ERROR),
                )
                .setFlags(FLAG_IGNORE_CACHE_ON_ERROR),
        ) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            if (dataSpec.length >= 0 && downloadCache.isCached(mediaId, dataSpec.position, dataSpec.length)) {
                return@Factory dataSpec
            }

            val cachedUrl = songUrlCache[mediaId]
            if (cachedUrl != null && cachedUrl.second > System.currentTimeMillis()) {
                return@Factory dataSpec.withUri(cachedUrl.first.toUri())
            }

            val playbackData = runBlocking(Dispatchers.IO) {
                val remainingMs = cooldownUntilMs - System.currentTimeMillis()
                if (remainingMs > 0) delay(remainingMs)

                // Downloads are kept forever and played offline many times: always fetch the best stream,
                // regardless of the streaming quality / metered-network settings (which could yield 128k AAC).
                YTPlayerUtils.playerResponseForPlayback(
                    mediaId,
                    audioQuality = AudioQuality.HIGHEST,
                    preferredStreamClient = PlayerStreamClient.ANDROID_VR,
                    connectivityManager = connectivityManager,
                    networkMetered = false,
                    avoidCodecs = avoidStreamCodecs,
                )
            }.getOrThrow()

            val format = playbackData.format

            database.query {
                upsert(
                    FormatEntity(
                        id = mediaId,
                        itag = format.itag,
                        mimeType = format.mimeType.split(";")[0],
                        codecs = format.mimeType.substringAfter("codecs=", "").removeSurrounding("\""),
                        bitrate = format.bitrate,
                        sampleRate = format.audioSampleRate,
                        contentLength = format.contentLength ?: C.LENGTH_UNSET.toLong(),
                        loudnessDb = playbackData.audioConfig?.loudnessDb,
                        perceptualLoudnessDb = playbackData.audioConfig?.perceptualLoudnessDb,
                        playbackUrl = playbackData.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                    ),
                )

                val now = LocalDateTime.now()
                val existing = getSongByIdBlocking(mediaId)?.song

                val updatedSong = if (existing != null) {
                    if (existing.dateDownload == null) existing.copy(dateDownload = now) else existing
                } else {
                    SongEntity(
                        id = mediaId,
                        title = playbackData.videoDetails?.title ?: "Unknown",
                        duration = playbackData.videoDetails?.lengthSeconds?.toIntOrNull() ?: 0,
                        thumbnailUrl = playbackData.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url,
                        dateDownload = now
                    )
                }

                upsert(updatedSong)
            }

            val totalLength = format.contentLength ?: C.LENGTH_UNSET.toLong()
            val streamUrlWithRange = if (totalLength > 0) {
                "${playbackData.streamUrl}&range=0-$totalLength"
            } else {
                playbackData.streamUrl
            }

            songUrlCache[mediaId] = streamUrlWithRange to (System.currentTimeMillis() + (60 * 60 * 1000L))
            dataSpec.withUri(streamUrlWithRange.toUri())
        }

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            downloadExecutor
        ).apply {
            maxParallelDownloads = currentMaxParallelDownloads
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                        val id = download.request.id
                        downloads.update { it - id } // previously left behind as a stale "removing" entry
                        // Quality upgrade: old copy is gone, now fetch it again (always best quality).
                        pendingUpgrades.remove(id)?.let { downloadManager.addDownload(it) }
                    }

                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        if (download.state == Download.STATE_FAILED) {
                            registerThrottleSignal(finalException)
                            // The cached stream link is probably expired/blocked: a retry must resolve a fresh one.
                            songUrlCache.remove(download.request.id)
                            scheduleRetry(download)
                        } else if (download.state == Download.STATE_COMPLETED) {
                            clearThrottleSignal()
                            songUrlCache.remove(download.request.id)
                            retryAttempts.remove(download.request.id)
                            learnFromDownload(download.request.id)
                        }

                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }
                    }
                }
            )
        }

    init {
        // "Download on Wi-Fi only": DownloadManager pauses queued downloads until an unmetered network is back.
        retryScope.launch {
            context.dataStore.data.map { it[DownloadWifiOnlyKey] ?: false }.distinctUntilChanged().collect { wifiOnly ->
                downloadManager.requirements = Requirements(
                    if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK,
                )
            }
        }
        retryScope.launch {
            context.dataStore.data.map { it[SmartDownloadsKey] ?: false }.distinctUntilChanged()
                .collect { SmartDownloadWorker.schedule(context, it) }
        }
        CoroutineScope(Dispatchers.IO).launch {
            val result = mutableMapOf<String, Download>()
            val cursor = downloadManager.downloadIndex.getDownloads()
            while (cursor.moveToNext()) {
                result[cursor.download.request.id] = cursor.download
            }
            downloads.value = result
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    private fun completedIds(): List<String> =
        downloads.value.filterValues { it.state == Download.STATE_COMPLETED }.keys.toList()

    /** Downloads not saved in the best format (mostly from before downloads ignored the streaming quality). */
    suspend fun lowQualityDownloadIds(): List<String> = withContext(Dispatchers.IO) {
        val done = completedIds()
        val formats = done.chunked(900).flatMap { database.formatsByIds(it) }.associateBy { it.id }
        // Unknown format: leave it alone rather than re-download blindly.
        done.filter { id -> formats[id]?.let { it.itag !in BEST_AUDIO_ITAGS } == true }
    }

    /** Re-download in best quality. The old copy must be removed first: re-adding would just reuse its bytes. */
    fun upgradeDownloads(ids: List<String>) {
        ids.forEach { id ->
            val download = downloads.value[id] ?: return@forEach
            pendingUpgrades[id] = download.request
            upgradedIds += id
            androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(appContext, ExoDownloadService::class.java, id, false)
        }
    }

    /** Downloaded more than [days] ago and not played since. */
    suspend fun staleDownloadIds(days: Int = 90): List<String> = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        val playedSince = database.songIdsPlayedSince(cutoff).toHashSet()
        downloads.value.values
            .filter { it.state == Download.STATE_COMPLETED && it.updateTimeMs < cutoff && it.request.id !in playedSince }
            .map { it.request.id }
    }

    fun removeDownloads(ids: List<String>) = ids.forEach {
        androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(appContext, ExoDownloadService::class.java, it, false)
    }

    /**
     * Smart offline mix: keep the [limit] songs you're most likely to love downloaded.
     * Only ever removes songs it added itself, and never ones you've liked since.
     */
    suspend fun runSmartDownloads(limit: Int) = withContext(Dispatchers.IO) {
        val ours = smartPrefs.getStringSet(SMART_IDS_KEY, emptySet()).orEmpty()
        val candidates = database.songsWithInterest().filter { !it.song.isLocal }
        val byId = candidates.associateBy { it.id }
        val target = candidates
            .map { it to taste.model.score(it.id, it.artists.map { a -> a.id }) }
            .filter { it.second > SMART_MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first.id }
            .toSet()
        val present = downloads.value.filterValues { it.state != Download.STATE_FAILED }.keys
        val toRemove = ours.filter { it !in target && byId[it]?.song?.liked != true }
        val toAdd = target.filter { it !in present }
        removeDownloads(toRemove)
        toAdd.forEach { id ->
            ExoDownloadService.download(appContext, id, (byId[id]?.song?.title ?: id))
        }
        smartPrefs.edit().putStringSet(SMART_IDS_KEY, (ours - toRemove.toSet()) + toAdd).apply()
    }

    /** Auto-retry a failed download twice (after 30 s, then 2 min) instead of leaving it failed until tapped. */
    private fun scheduleRetry(download: Download) {
        val id = download.request.id
        val attempt = (retryAttempts[id] ?: 0) + 1
        if (attempt > MAX_AUTO_RETRIES) {
            retryAttempts.remove(id)
            return
        }
        retryAttempts[id] = attempt
        retryScope.launch {
            delay(if (attempt == 1) 30_000L else 120_000L)
            // Only if it's still failed: the user may have removed or retried it meanwhile.
            if (downloads.value[id]?.state == Download.STATE_FAILED) downloadManager.addDownload(download.request)
        }
    }

    /** A download is the strongest "I love this" signal: teach the smart recommender. */
    private fun learnFromDownload(songId: String) {
        // Only YOUR downloads teach taste: smart picks would reinforce themselves, upgrades aren't new interest.
        if (songId in smartPrefs.getStringSet(SMART_IDS_KEY, emptySet()).orEmpty() || songId in upgradedIds) return
        CoroutineScope(Dispatchers.IO).launch {
            val artists = runCatching { database.getSongByIdBlocking(songId)?.artists }.getOrNull().orEmpty()
            taste.feedback(songId, artists.map { it.id }, TasteModel.DOWNLOADED)
        }
    }

    private fun deviceSupportsMimeType(mimeType: String): Boolean {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
        }.getOrDefault(false)
    }

    private fun registerThrottleSignal(exception: Throwable?) {
        val nextStrikeCount =
            if (exception == null || isProbablyThrottleSignal(exception)) {
                consecutiveThrottleSignals.incrementAndGet()
            } else {
                consecutiveThrottleSignals.updateAndGet { strikes -> maxOf(1, strikes) }
            }

        val reducedParallelDownloads =
            when {
                nextStrikeCount >= 4 -> MIN_PARALLEL_DOWNLOADS
                nextStrikeCount >= 2 -> DEFAULT_MAX_PARALLEL_DOWNLOADS - 1
                else -> currentMaxParallelDownloads
            }.coerceIn(MIN_PARALLEL_DOWNLOADS, DEFAULT_MAX_PARALLEL_DOWNLOADS)

        val cooldownMs =
            when {
                nextStrikeCount >= 4 -> LONG_COOLDOWN_MS
                nextStrikeCount >= 2 -> SHORT_COOLDOWN_MS
                else -> 0L
            }

        if (reducedParallelDownloads != currentMaxParallelDownloads) {
            currentMaxParallelDownloads = reducedParallelDownloads
            downloadManager.maxParallelDownloads = reducedParallelDownloads
        }

        if (cooldownMs > 0) {
            cooldownUntilMs = maxOf(cooldownUntilMs, System.currentTimeMillis() + cooldownMs)
        }
    }

    private fun clearThrottleSignal() {
        val remainingStrikes = consecutiveThrottleSignals.updateAndGet { strikes ->
            if (strikes > 0) strikes - 1 else 0
        }

        if (remainingStrikes == 0 && currentMaxParallelDownloads != DEFAULT_MAX_PARALLEL_DOWNLOADS) {
            currentMaxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
            downloadManager.maxParallelDownloads = DEFAULT_MAX_PARALLEL_DOWNLOADS
        }
    }

    private fun isProbablyThrottleSignal(exception: Throwable): Boolean {
        val message = buildString {
            append(exception.message.orEmpty())
            exception.cause?.message?.let {
                if (isNotBlank()) append(' ')
                append(it)
            }
        }.lowercase()

        return listOf(
            "429", "403", "quota", "rate", "too many",
            "temporarily unavailable", "timed out", "timeout",
            "unavailable", "reset by peer"
        ).any(message::contains)
    }

    companion object {
        private const val DEFAULT_MAX_PARALLEL_DOWNLOADS = 6
        private const val MIN_PARALLEL_DOWNLOADS = 1
        private const val SHORT_COOLDOWN_MS = 2_500L
        private const val LONG_COOLDOWN_MS = 8_000L
        private const val MAX_AUTO_RETRIES = 2
        /** 251 = Opus ~160k (best without Premium), 141 = AAC 256k, 774 = Opus 256k (Premium). */
        private val BEST_AUDIO_ITAGS = setOf(251, 141, 774)
        private const val SMART_IDS_KEY = "ids"
        private const val SMART_MIN_SCORE = 0.65
    }
}
