/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



@file:Suppress("DEPRECATION")

package com.nikhil.yt.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.SQLException
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.os.Binder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.EVENT_POSITION_DISCONTINUITY
import androidx.media3.common.Player.EVENT_TIMELINE_CHANGED
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_IDLE
import androidx.media3.common.Timeline
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlaybackStats
import androidx.media3.exoplayer.analytics.PlaybackStatsListener
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.nikhil.yt.MainActivity
import com.nikhil.yt.R
import com.nikhil.yt.constants.AudioCrossfadeDurationKey
import com.nikhil.yt.constants.AudioNormalizationKey
import com.nikhil.yt.constants.ImmersiveAudioKey
import com.nikhil.yt.constants.AudioOffload
import com.nikhil.yt.constants.AudioQualityKey
import com.nikhil.yt.constants.AutoDownloadOnLikeKey
import com.nikhil.yt.constants.AutoLoadMoreKey
import com.nikhil.yt.constants.AutoSkipNextOnErrorKey
import com.nikhil.yt.constants.AutoStartOnBluetoothKey
import com.nikhil.yt.constants.DiscordTokenKey
import com.nikhil.yt.constants.EnableDiscordRPCKey
import com.nikhil.yt.constants.EnableLastFMScrobblingKey
import com.nikhil.yt.constants.HideExplicitKey
import com.nikhil.yt.constants.HideVideoKey
import com.nikhil.yt.constants.HistoryDuration
import com.nikhil.yt.constants.InnerTubeCookieKey
import com.nikhil.yt.constants.LastFMUseNowPlaying
import com.nikhil.yt.constants.ListenBrainzEnabledKey
import com.nikhil.yt.constants.ListenBrainzTokenKey
import com.nikhil.yt.constants.MaxSongCacheSizeKey
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleLike
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleRepeatMode
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleShuffle
import com.nikhil.yt.constants.MediaSessionConstants.CommandToggleStartRadio
import com.nikhil.yt.constants.PauseListenHistoryKey
import com.nikhil.yt.constants.PauseOnDeviceMuteKey
import com.nikhil.yt.constants.PermanentShuffleKey
import com.nikhil.yt.constants.PersistentQueueKey
import com.nikhil.yt.constants.PlayerStreamClient
import com.nikhil.yt.constants.PlayerStreamClientKey
import com.nikhil.yt.constants.PlayerVolumeKey
import com.nikhil.yt.constants.RepeatModeKey
import com.nikhil.yt.constants.ScrobbleDelayPercentKey
import com.nikhil.yt.constants.ScrobbleDelaySecondsKey
import com.nikhil.yt.constants.ScrobbleMinSongDurationKey
import com.nikhil.yt.constants.ShowLyricsKey
import com.nikhil.yt.constants.SkipSilenceKey
import com.nikhil.yt.constants.SmartTrimmerKey
import com.nikhil.yt.constants.StopMusicOnTaskClearKey
import com.nikhil.yt.constants.YtmSyncKey
import com.nikhil.yt.db.MusicDatabase
import com.nikhil.yt.db.entities.AlbumEntity
import com.nikhil.yt.db.entities.ArtistEntity
import com.nikhil.yt.db.entities.Event
import com.nikhil.yt.db.entities.FormatEntity
import com.nikhil.yt.db.entities.LyricsEntity
import com.nikhil.yt.db.entities.RelatedSongMap
import com.nikhil.yt.db.entities.Song
import com.nikhil.yt.db.entities.SongEntity
import com.nikhil.yt.di.DownloadCache
import com.nikhil.yt.di.PlayerCache
import com.nikhil.yt.extensions.SilentHandler
import com.nikhil.yt.extensions.collect
import com.nikhil.yt.extensions.collectLatest
import com.nikhil.yt.extensions.currentMetadata
import com.nikhil.yt.extensions.directorySizeBytes
import com.nikhil.yt.extensions.findNextMediaItemById
import com.nikhil.yt.extensions.mediaItems
import com.nikhil.yt.extensions.metadata
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.extensions.toPersistQueue
import com.nikhil.yt.extensions.toQueue
import com.nikhil.yt.innertube.YouTube
import com.nikhil.yt.innertube.models.SongItem
import com.nikhil.yt.innertube.models.WatchEndpoint
import com.nikhil.yt.lastfm.LastFM
import com.nikhil.yt.lyrics.LyricsHelper
import com.nikhil.yt.lyrics.LyricsPreloadManager
import com.nikhil.yt.models.PersistPlayerState
import com.nikhil.yt.models.PersistQueue
import com.nikhil.yt.models.toMediaMetadata
import com.nikhil.yt.playback.queues.EmptyQueue
import com.nikhil.yt.playback.queues.Queue
import com.nikhil.yt.playback.queues.YouTubeQueue
import com.nikhil.yt.playback.queues.filterExplicit
import com.nikhil.yt.playback.queues.filterVideo
import com.nikhil.yt.ui.screens.settings.DiscordPresenceManager
import com.nikhil.yt.ui.screens.settings.ListenBrainzManager
import com.nikhil.yt.utils.CoilBitmapLoader
import com.nikhil.yt.utils.DiscordRPC
import com.nikhil.yt.utils.NetworkConnectivityObserver
import com.nikhil.yt.utils.StreamClientUtils
import com.nikhil.yt.utils.SyncUtils
import com.nikhil.yt.utils.YTPlayerUtils
import com.nikhil.yt.utils.dataStore
import com.nikhil.yt.utils.enumPreference
import com.nikhil.yt.utils.get
import com.nikhil.yt.utils.getAsync
import com.nikhil.yt.utils.getPresenceIntervalMillis
import com.nikhil.yt.utils.reportException
import com.nikhil.yt.ui.widget.updateVeluneWidgetState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@AndroidEntryPoint
class MusicService :
    MediaLibraryService(),
    Player.Listener,
    PlaybackStatsListener.Callback {
    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var lyricsHelper: LyricsHelper

    @Inject
    lateinit var forYouEngine: com.nikhil.yt.utils.ForYouSuggestionEngine

    @Inject
    lateinit var taste: TasteStore

    @Inject
    lateinit var syncUtils: SyncUtils

    @Inject
    lateinit var mediaLibrarySessionCallback: MediaLibrarySessionCallback

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var lastAudioFocusState = AudioManager.AUDIOFOCUS_NONE
    private var wasPlayingBeforeAudioFocusLoss = false
    private var pauseOnDeviceMuteEnabled = false
    private var wasAutoPausedByDeviceMute = false
    private var hasAudioFocus = false
    private var autoStartOnBluetoothEnabled = false
    private var bluetoothReceiverRegistered = false

    private var scopeJob = Job()
    private var scope = CoroutineScope(Dispatchers.Main + scopeJob)
    private var ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
    private val binder = MusicBinder()

    private lateinit var connectivityManager: ConnectivityManager
    lateinit var connectivityObserver: NetworkConnectivityObserver
    val waitingForNetworkConnection = MutableStateFlow(false)
    private val isNetworkConnected = MutableStateFlow(false)

    private val audioQuality by enumPreference(
        this,
        AudioQualityKey,
        com.nikhil.yt.constants.AudioQuality.AUTO
    )
    private val preferredStreamClient by enumPreference(
        this,
        PlayerStreamClientKey,
        PlayerStreamClient.IOS
    )
    private val playbackUrlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    private val streamRecoveryState = ConcurrentHashMap<String, Pair<Int, Long>>()
    @Volatile
    private var pendingStreamRefreshValidationMediaId: String? = null
    @Volatile
    private var refreshValidatedPlayingMediaId: String? = null
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

                val clientParam = request.url.queryParameter("c")?.trim().orEmpty()

                val userAgent = StreamClientUtils.resolveUserAgent(clientParam)
                val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)

                val builder = request.newBuilder().header("User-Agent", userAgent)
                originReferer.origin?.let { builder.header("Origin", it) }
                originReferer.referer?.let { builder.header("Referer", it) }

                chain.proceed(builder.build())
            }.build()
    }

    private var currentQueue: Queue = EmptyQueue
    var queueTitle: String? = null
    private val persistentStateLock = Any()
    @Volatile
    private var suppressAutoPlayback = false
    private var lastPresenceToken: String? = null
    @Volatile
    private var lastPresenceUpdateTime = 0L

    val currentMediaMetadata = MutableStateFlow<com.nikhil.yt.models.MediaMetadata?>(null)
    val queueRestoreCompleted = MutableStateFlow(false)
    private val currentSong =
        currentMediaMetadata
            .flatMapLatest { mediaMetadata ->
                database.song(mediaMetadata?.id)
            }.flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Lazily, null)
    private val currentFormat =
        currentMediaMetadata.flatMapLatest { mediaMetadata ->
            database.format(mediaMetadata?.id)
        }.flowOn(Dispatchers.IO)

    private val normalizeGainDb = MutableStateFlow(0f)
    private val boostGainDb = MutableStateFlow(0f)
    private val loudnessProcessor = LoudnessProcessor()
    private val overlapLoudnessProcessor = LoudnessProcessor() // crossfade player's own chain
    private var outputRouteWatcher: OutputRouteWatcher? = null
    private val immersiveProcessor = ImmersiveProcessor()
    private val overlapImmersiveProcessor = ImmersiveProcessor()
    var playerVolume = MutableStateFlow(1f)
    private val audioFocusVolumeFactor = MutableStateFlow(1f)
    private val playbackFadeFactor = MutableStateFlow(1f)
    private val resumeFadeFactor = MutableStateFlow(1f)
    private val skipMemory by lazy { RecentMemory(this, "adaptive_shuffle_skips", AdaptiveShuffle.SKIP_COOLDOWN_MS) }
    /** Songs that have actually been the current song since this shuffle started. */
    private val playedThisShuffle = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val heardMemory by lazy { RecentMemory(this, "adaptive_shuffle_heard", AdaptiveShuffle.HEARD_COOLDOWN_MS) }
    private var adaptiveShuffleJob: Job? = null
    private var repeatTimerJob: Job? = null
    private var focusRampJob: Job? = null
    private var resumeFadeJob: Job? = null

    /** Eased volume ramp; abrupt volume jumps (ducking, resume) are what make playback feel cheap. */
    private fun rampVolume(flow: MutableStateFlow<Float>, target: Float, durationMs: Long): Job =
        scope.launch {
            val start = flow.value
            val steps = (durationMs / 16L).coerceAtLeast(1L)
            for (i in 1..steps) {
                val t = i.toFloat() / steps
                flow.value = start + (target - start) * (1f - (1f - t) * (1f - t)) // ease-out
                delay(16L)
            }
            flow.value = target
        }

    private fun rampFocusVolume(target: Float) {
        focusRampJob?.cancel()
        focusRampJob = rampVolume(audioFocusVolumeFactor, target, if (target < audioFocusVolumeFactor.value) 300L else 600L)
    }
    private val crossfadeDurationMs = MutableStateFlow(0)
    private val audioNormalizationEnabled = MutableStateFlow(true)
    private var crossfadeAudio: CrossfadeAudio? = null
    private var lyricsPreloadManager: LyricsPreloadManager? = null

    lateinit var sleepTimer: SleepTimer

    @Inject
    @PlayerCache
    lateinit var playerCache: Cache

    @Inject
    @DownloadCache
    lateinit var downloadCache: Cache

    lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaLibrarySession

    val audioEffects by lazy {
        AudioEffectsController(this, dataStore, boostGainDb) { headroom ->
            loudnessProcessor.headroomDb = headroom
            overlapLoudnessProcessor.headroomDb = headroom
        }
    }

    private var discordRpc: DiscordRPC? = null
    private var lastDiscordUpdateTime = 0L

    private var scrobbleManager: com.nikhil.yt.utils.ScrobbleManager? = null

    val automixItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val automixLoading = MutableStateFlow(false)
    val automixError = MutableStateFlow<String?>(null)
    private var automixJob: Job? = null
    private var automixSeedMediaId: String? = null

    val autoAddedMediaIds: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    private var consecutivePlaybackErr = 0

    @Volatile
    private var hasCalledStartForeground = false

    /** Listen Together: the session lives in the controller; the service lends it the player. */
    val together: com.nikhil.yt.together.TogetherController by lazy {
        com.nikhil.yt.together.TogetherController(
            context = this,
            dataStore = dataStore,
            player = { player },
            playNext = ::playNext,
            addToQueue = ::addToQueue,
            replaceQueue = ::replaceQueueForTogether,
        )
    }
    val togetherSessionState get() = together.state

    private fun replaceQueueForTogether(items: List<MediaItem>, startIndex: Int, positionMs: Long) {
        suppressAutoPlayback = false
        currentQueue = com.nikhil.yt.playback.queues.ListQueue(
            title = getString(R.string.music_player),
            items = items,
            startIndex = startIndex,
            position = positionMs,
        )
        queueTitle = null
        player.setMediaItems(items, startIndex, positionMs)
        player.prepare()
    }

    private fun ensureStartedAsForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (hasCalledStartForeground) return

        val notification =
            try {
                val contentIntent =
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )

                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_spectra_ghost)
                    .setContentTitle(getString(R.string.music_player))
                    .setContentText(getString(R.string.app_name))
                    .setContentIntent(contentIntent)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            } catch (e: Exception) {
                reportException(e)
                return
            }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            hasCalledStartForeground = true
        } catch (e: Exception) {
            reportException(e)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureScopesActive()
        together.start()
        outputRouteWatcher = OutputRouteWatcher(this) { profile ->
            loudnessProcessor.outputProfile = profile
            overlapLoudnessProcessor.outputProfile = profile
        }.also { it.start() }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm?.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.music_player),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        } catch (e: Exception) {
            reportException(e)
        }

        ensureStartedAsForeground()

        
        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(createMediaSourceFactory())
                .setRenderersFactory(createRenderersFactory(immersiveProcessor, loudnessProcessor))
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    false,
                ).setSeekBackIncrementMs(5000)
                .setSeekForwardIncrementMs(5000)
                .setDeviceVolumeControlEnabled(true)
                .build()
                .apply {
                    addListener(this@MusicService)
                    sleepTimer = SleepTimer(scope, this)
                    addListener(sleepTimer)
                    addAnalyticsListener(PlaybackStatsListener(false, this@MusicService))
                }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupAudioFocusRequest()

        mediaLibrarySessionCallback.apply {
            toggleLike = ::toggleLike
            toggleStartRadio = ::toggleStartRadio
            toggleLibrary = ::toggleLibrary
        }
        mediaSession =
            MediaLibrarySession
                .Builder(this, player, mediaLibrarySessionCallback)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                ).setBitmapLoader(CoilBitmapLoader(this, scope))
                .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { NOTIFICATION_ID },
                CHANNEL_ID,
                R.string.music_player
            ).apply {
                setSmallIcon(R.drawable.ic_spectra_ghost)
            }
        )
        
        updateNotification()
        player.repeatMode = REPEAT_MODE_OFF

        val sessionToken = SessionToken(this, ComponentName(this, MusicService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())
        scope.launch(Dispatchers.IO) {
            val prefs = dataStore.data.first()
            val repeatMode = prefs[RepeatModeKey] ?: REPEAT_MODE_OFF
            val volume = (prefs[PlayerVolumeKey] ?: 1f).coerceIn(0f, 1f)
            val offload = prefs[AudioOffload] ?: false
            withContext(Dispatchers.Main) {
                player.repeatMode = repeatMode
                playerVolume.value = volume
                updateAudioOffload(offload)
            }
        }

        connectivityManager = getSystemService()!!
        connectivityObserver = NetworkConnectivityObserver(this)

        scope.launch {
            connectivityObserver.networkStatus.collect { isConnected ->
                isNetworkConnected.value = isConnected
                if (isConnected && waitingForNetworkConnection.value) {
                    // Simple auto-play logic like OuterTune
                    waitingForNetworkConnection.value = false
                    if (player.currentMediaItem != null && player.playWhenReady) {
                        player.prepare()
                        player.play()
                    }
                }
            }
        }

        combine(playerVolume, audioFocusVolumeFactor, playbackFadeFactor, resumeFadeFactor) { playerVolume, audioFocusVolumeFactor, playbackFadeFactor, resumeFadeFactor ->
            playerVolume * audioFocusVolumeFactor * playbackFadeFactor * resumeFadeFactor
        }.collectLatest(scope) { finalVolume ->
            player.volume = finalVolume
        }

        // Normalization + boost run as real gain in LoudnessProcessor (with limiter), not via player.volume,
        // because player.volume is capped at 1.0 and could only ever make songs quieter.
        combine(normalizeGainDb, boostGainDb) { norm, boost -> norm + boost }
            .collectLatest(scope) { loudnessProcessor.gainDb = it }

        dataStore.data
            .map { it[ImmersiveAudioKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                immersiveProcessor.enabled = it
                overlapImmersiveProcessor.enabled = it
            }

        playerVolume.debounce(1000).collect(ioScope) { volume ->
            dataStore.edit { settings ->
                settings[PlayerVolumeKey] = volume
            }
        }

        currentSong.debounce(300).collect(scope) { song ->
            updateNotification()
            if (song != null && player.playWhenReady && player.playbackState == Player.STATE_READY) {
                ensurePresenceManager()
            } else {
                discordRpc?.closeRPC()
            }
        }

        combine(
            currentMediaMetadata.distinctUntilChangedBy { it?.id },
            dataStore.data.map { it[ShowLyricsKey] ?: false }.distinctUntilChanged(),
        ) { mediaMetadata, showLyrics ->
            mediaMetadata to showLyrics
        }.collectLatest(ioScope) { (mediaMetadata, showLyrics) ->
            if (showLyrics && mediaMetadata != null && database.lyrics(mediaMetadata.id)
                    .first() == null
            ) {
                val lyrics = lyricsHelper.getLyrics(mediaMetadata)
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = lyrics,
                        ),
                    )
                }
            }
        }

        dataStore.data
            .map { it[SkipSilenceKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                player.skipSilenceEnabled = it
            }

        dataStore.data
            .map { it[PauseOnDeviceMuteKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                pauseOnDeviceMuteEnabled = enabled
                if (!enabled) {
                    wasAutoPausedByDeviceMute = false
                } else {
                    handleDeviceMuteStateChanged()
                }
            }

        dataStore.data
            .map { it[AutoStartOnBluetoothKey] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                autoStartOnBluetoothEnabled = enabled
                if (enabled) {
                    registerBluetoothReceiver()
                } else {
                    unregisterBluetoothReceiver()
                }
            }

        dataStore.data
            .map { it[AudioOffload] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) { enabled ->
                updateAudioOffload(enabled)
                if (enabled) {
                    val skipSilenceEnabled = dataStore.get(SkipSilenceKey, false)
                    if (skipSilenceEnabled) {
                        dataStore.edit { it[SkipSilenceKey] = false }
                        player.skipSilenceEnabled = false
                    }
                    val crossfadeSeconds = dataStore.get(AudioCrossfadeDurationKey, 0)
                    if (crossfadeSeconds != 0) {
                        dataStore.edit { it[AudioCrossfadeDurationKey] = 0 }
                    }
                }
            }
        
        dataStore.data
            .map { (it[AudioCrossfadeDurationKey] ?: 0) * 1000 }
            .distinctUntilChanged()
            .collectLatest(scope) {
                crossfadeDurationMs.value = it
            }

        crossfadeAudio =
            CrossfadeAudio(
                player = player,
                database = database,
                crossfadeDurationMs = crossfadeDurationMs,
                playbackFadeFactor = playbackFadeFactor,
                playerVolume = playerVolume,
                audioFocusVolumeFactor = audioFocusVolumeFactor,
                audioNormalizationEnabled = audioNormalizationEnabled,
                boostGainDb = boostGainDb,
                overlapLoudness = overlapLoudnessProcessor,
                overlapPlayerFactory = {
                    ExoPlayer
                        .Builder(this)
                        .setMediaSourceFactory(createMediaSourceFactory())
                        .setRenderersFactory(createRenderersFactory(overlapImmersiveProcessor, overlapLoudnessProcessor))
                        .setHandleAudioBecomingNoisy(false)
                        .setWakeMode(C.WAKE_MODE_NETWORK)
                        .setAudioAttributes(
                            AudioAttributes
                                .Builder()
                                .setUsage(C.USAGE_MEDIA)
                                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                                .build(),
                            false,
                        ).setSeekBackIncrementMs(10000)
                        .setSeekForwardIncrementMs(10000)
                        .build()
                },
            ).also { it.start(scope) }

        lyricsPreloadManager = LyricsPreloadManager(
            context = this,
            database = database,
            networkConnectivity = connectivityObserver,
        )

        dataStore.data
            .map(AudioEffectsController::settingsFrom)
            .distinctUntilChanged()
            .collectLatest(scope) { settings -> audioEffects.applySettings(settings) }

        // Learn each song's real loudness shape for the waveform while it plays.
        scope.launch {
            AudioLevelMeter.level.collect { level ->
                val duration = player.duration
                if (player.isPlaying && duration > 0) {
                    WaveformStore.record(player.currentPosition.toFloat() / duration, level)
                }
            }
        }

        combine(
            currentFormat,
            dataStore.data
                .map { it[AudioNormalizationKey] ?: true }
                .distinctUntilChanged(),
        ) { format, normalizeAudio ->
            format to normalizeAudio
        }.collectLatest(scope) { (format, normalizeAudio) ->
            audioNormalizationEnabled.value = normalizeAudio
            Timber.tag("AudioNormalization").d("Audio normalization enabled: $normalizeAudio")
            Timber.tag("AudioNormalization").d("Format loudnessDb: ${format?.loudnessDb}, perceptualLoudnessDb: ${format?.perceptualLoudnessDb}")
            
            normalizeGainDb.value =
                if (normalizeAudio) {
                    val loudness = format?.loudnessDb ?: format?.perceptualLoudnessDb
                    if (loudness != null) {
                        LoudnessProcessor.normalizationGainDb(loudness.toFloat())
                            .also { Timber.tag("AudioNormalization").i("Applying normalization gain: $it dB (loudness: $loudness)") }
                    } else {
                        Timber.tag("AudioNormalization").w("Normalization enabled but no loudness data available - no normalization applied")
                        0f
                    }
                } else {
                    0f
                }
        }

        dataStore.data
            .map { it[DiscordTokenKey] to (it[EnableDiscordRPCKey] ?: true) }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(scope) { (key, enabled) ->
                val newRpc =
                    withContext(Dispatchers.IO) {
                        if (!key.isNullOrBlank() && enabled) {
                            runCatching { DiscordRPC(this@MusicService, key) }
                                .onFailure { Timber.tag("MusicService").e(it, "failed to create DiscordRPC client") }
                                .getOrNull()
                        } else {
                            null
                        }
                    }

                try {
                    if (discordRpc?.isRpcRunning() == true) {
                        withContext(Dispatchers.IO) { discordRpc?.closeRPC() }
                    }
                } catch (_: Exception) {}
                discordRpc = newRpc

                if (discordRpc != null) {
                    if (player.playbackState == Player.STATE_READY && player.playWhenReady) {
                        currentSong.value?.let {
                            ensurePresenceManager()
                        }
                    }
                } else {
                    try { DiscordPresenceManager.stop() } catch (_: Exception) {}
                }
            }

        dataStore.data
            .map { prefs ->
                (prefs[SmartTrimmerKey] ?: false) to (prefs[MaxSongCacheSizeKey] ?: 1024)
            }
            .debounce(300)
            .distinctUntilChanged()
            .collectLatest(ioScope) { (enabled, maxSongCacheSizeMb) ->
                if (!enabled) return@collectLatest
                if (maxSongCacheSizeMb <= 0 || maxSongCacheSizeMb == -1) return@collectLatest
                val bytesPerMb = 1024L * 1024L
                val safeSizeMb = maxSongCacheSizeMb.toLong().coerceAtMost(Long.MAX_VALUE / bytesPerMb)
                val limitBytes = safeSizeMb * bytesPerMb
                trimPlayerCacheToBytes(limitBytes)
            }

        dataStore.data
            .map { it[EnableLastFMScrobblingKey] ?: false }
            .debounce(300)
            .distinctUntilChanged()
            .collect(scope) { enabled ->
                if (enabled && scrobbleManager == null) {
                    val delayPercent = dataStore.get(ScrobbleDelayPercentKey, LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT)
                    val minSongDuration = dataStore.get(ScrobbleMinSongDurationKey, LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION)
                    val delaySeconds = dataStore.get(ScrobbleDelaySecondsKey, LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS)
                    
                    scrobbleManager = com.nikhil.yt.utils.ScrobbleManager(
                        ioScope,
                        minSongDuration = minSongDuration,
                        scrobbleDelayPercent = delayPercent,
                        scrobbleDelaySeconds = delaySeconds
                    )
                    scrobbleManager?.useNowPlaying = dataStore.get(LastFMUseNowPlaying, false)
                } else if (!enabled && scrobbleManager != null) {
                    scrobbleManager?.destroy()
                    scrobbleManager = null
                }
            }

        dataStore.data
            .map { it[LastFMUseNowPlaying] ?: false }
            .distinctUntilChanged()
            .collectLatest(scope) {
                scrobbleManager?.useNowPlaying = it
            }

        dataStore.data
            .map { prefs ->
                Triple(
                    prefs[ScrobbleDelayPercentKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_PERCENT,
                    prefs[ScrobbleMinSongDurationKey] ?: LastFM.DEFAULT_SCROBBLE_MIN_SONG_DURATION,
                    prefs[ScrobbleDelaySecondsKey] ?: LastFM.DEFAULT_SCROBBLE_DELAY_SECONDS
                )
            }
            .distinctUntilChanged()
            .collect(scope) { (delayPercent, minSongDuration, delaySeconds) ->
                scrobbleManager?.let {
                    it.scrobbleDelayPercent = delayPercent
                    it.minSongDuration = minSongDuration
                    it.scrobbleDelaySeconds = delaySeconds
                }
            }

        scope.launch(Dispatchers.IO) {
            if (dataStore.get(PersistentQueueKey, true)) {
                readPersistentObject<PersistQueue>(PERSISTENT_QUEUE_FILE)
                    ?.let { persistedQueue ->
                    val restoredQueue = persistedQueue.toQueue()
                    withContext(Dispatchers.Main) {
                        playQueue(
                            queue = restoredQueue,
                            playWhenReady = false,
                        )
                    }
                }
                readPersistentObject<PersistQueue>(PERSISTENT_AUTOMIX_FILE)
                    ?.let { persistedAutomix ->
                    val items = persistedAutomix.items.map { it.toMediaItem() }
                    withContext(Dispatchers.Main) {
                        automixItems.value = items
                        automixSeedMediaId = player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
                    }
                }
                
                readPersistentObject<PersistPlayerState>(PERSISTENT_PLAYER_STATE_FILE)
                    ?.let { playerState ->
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        player.repeatMode = playerState.repeatMode
                        player.shuffleModeEnabled = playerState.shuffleModeEnabled
                        playerVolume.value = playerState.volume
                        
                        if (playerState.currentMediaItemIndex < player.mediaItemCount) {
                            player.seekTo(playerState.currentMediaItemIndex, playerState.currentPosition)
                        }
                        
                        currentMediaMetadata.value = player.currentMetadata
                        updateNotification()
                    }
                }
            }
            withContext(Dispatchers.Main) {
                queueRestoreCompleted.value = true
            }
        }

        scope.launch {
            while (isActive) {
                val interval = if (player.isPlaying) 10.seconds else 30.seconds
                delay(interval)
                val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
                if (shouldSave) {
                    saveQueueToDisk()
                }
            }
        }

    }

    private fun ensureScopesActive() {
        if (!scopeJob.isActive) {
            scopeJob = Job()
        }
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + scopeJob)
        }
        if (!ioScope.isActive) {
            ioScope = CoroutineScope(Dispatchers.IO + scopeJob)
        }
    }

    private fun ensurePresenceManager() {
        if (DiscordPresenceManager.isRunning() && lastPresenceToken != null) return

        scope.launch {
            if (!dataStore.get(EnableDiscordRPCKey, true)) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("Discord RPC disabled → stopping presence manager")
                    try { DiscordPresenceManager.stop() } catch (_: Exception) {}
                    lastPresenceToken = null
                }
                return@launch
            }

            val key: String = dataStore.get(DiscordTokenKey, "")
            if (key.isNullOrBlank()) {
                if (DiscordPresenceManager.isRunning()) {
                    Timber.tag("MusicService").d("No Discord token → stopping presence manager")
                    try { DiscordPresenceManager.stop() } catch (_: Exception) {}
                    lastPresenceToken = null
                }
                return@launch
            }

            if (DiscordPresenceManager.isRunning() && lastPresenceToken == key) {
                // try {
                //     if (DiscordPresenceManager.restart()) {
                //         Timber.tag("MusicService").d("Presence manager restarted with same token")
                //     }
                // } catch (ex: Exception) {
                //     Timber.tag("MusicService").e(ex, "Failed to restart presence manager")
                // }
                return@launch
            }

            try {
                DiscordPresenceManager.stop()
                DiscordPresenceManager.start(
                    context = this@MusicService,
                    token = key,
                    songProvider = { player.currentMetadata?.let { createTransientSongFromMedia(it) } ?: currentSong.value },
                    positionProvider = { player.currentPosition },
                    isPausedProvider = { !player.isPlaying },
                    intervalProvider = { getPresenceIntervalMillis(this@MusicService) }
                )
                Timber.tag("MusicService").d("Presence manager started with token=$key")
                lastPresenceToken = key
            } catch (ex: Exception) {
                Timber.tag("MusicService").e(ex, "Failed to start presence manager")
            }
        }
    }

    private fun canUpdatePresence(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(this) {
            return if (now - lastPresenceUpdateTime > MIN_PRESENCE_UPDATE_INTERVAL) {
                lastPresenceUpdateTime = now
                true
            } else false
        }
    }

    private fun setupAudioFocusRequest() {
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                handleAudioFocusChange(focusChange)
            }
            .setAcceptsDelayedFocusGain(true)
            .build()
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                rampFocusVolume(1f)

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                rampFocusVolume(1f)
                wasPlayingBeforeAudioFocusLoss = false

                if (player.isPlaying) {
                    player.pause()
                }

                abandonAudioFocus()

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                rampFocusVolume(1f)
                wasPlayingBeforeAudioFocusLoss = player.isPlaying

                if (player.isPlaying) {
                    player.pause()
                }

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                hasAudioFocus = false

                wasPlayingBeforeAudioFocusLoss = player.isPlaying

                rampFocusVolume(0.25f)

                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT -> {

                hasAudioFocus = true
                rampFocusVolume(1f)

                if (wasPlayingBeforeAudioFocusLoss) {
                    player.play()
                    wasPlayingBeforeAudioFocusLoss = false
                }
        
                lastAudioFocusState = focusChange
            }

            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> {
                hasAudioFocus = true
                rampFocusVolume(1f)

                lastAudioFocusState = focusChange
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
    
        audioFocusRequest?.let { request ->
            val result = audioManager.requestAudioFocus(request)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            return hasAudioFocus
        }
        return false
    }

    private fun abandonAudioFocus() {
        if (hasAudioFocus) {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
                hasAudioFocus = false
            }
        }
    }

    fun hasAudioFocusForPlayback(): Boolean {
        return hasAudioFocus
    }

    private fun isDeviceMutedNow(): Boolean {
        return player.isDeviceMuted || player.deviceVolume <= 0
    }

    private fun isTogetherGuestSession(): Boolean = together.isGuest

    private fun handleDeviceMuteStateChanged() {
        if (!pauseOnDeviceMuteEnabled || isTogetherGuestSession()) {
            wasAutoPausedByDeviceMute = false
            return
        }

        if (isDeviceMutedNow()) {
            val canPauseNow =
                player.currentMediaItem != null &&
                    player.playWhenReady &&
                    player.playbackState != Player.STATE_IDLE &&
                    player.playbackState != Player.STATE_ENDED

            if (canPauseNow) {
                player.pause()
                wasAutoPausedByDeviceMute = true
            }
            return
        }

        if (!wasAutoPausedByDeviceMute) return

        wasAutoPausedByDeviceMute = false
        val canResumeNow =
            player.currentMediaItem != null &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        if (canResumeNow) {
            player.play()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
            if (!autoStartOnBluetoothEnabled) return

            val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return

            val isAudioDevice = try {
                val majorClass = device.bluetoothClass?.majorDeviceClass
                majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO ||
                    majorClass == BluetoothClass.Device.Major.WEARABLE
            } catch (_: SecurityException) {
                true
            }

            if (!isAudioDevice) return

            scope.launch {
                delay(1500)
                handleBluetoothAutoStart()
            }
        }
    }

    private fun handleBluetoothAutoStart() {
        if (isTogetherGuestSession()) return

        if (player.currentMediaItem != null &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
        ) {
            if (!player.playWhenReady) {
                player.play()
            }
            return
        }

        if (player.mediaItemCount > 0) {
            player.prepare()
            player.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun registerBluetoothReceiver() {
        if (bluetoothReceiverRegistered) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return

        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, filter)
        }
        bluetoothReceiverRegistered = true
    }

    private fun unregisterBluetoothReceiver() {
        if (!bluetoothReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        bluetoothReceiverRegistered = false
    }

    private fun waitOnNetworkError() {
        waitingForNetworkConnection.value = true
    }

    private fun skipOnError() {
        /**
         * Auto skip to the next media item on error.
         *
         * To prevent a "runaway diesel engine" scenario, force the user to take action after
         * too many errors come up too quickly. Pause to show player "stopped" state
         */
        consecutivePlaybackErr += 2
        val nextWindowIndex = player.nextMediaItemIndex

        if (consecutivePlaybackErr <= MAX_CONSECUTIVE_ERR && nextWindowIndex != C.INDEX_UNSET) {
            player.seekTo(nextWindowIndex, C.TIME_UNSET)
            player.prepare()
            player.play()
            return
        }

        player.pause()
        consecutivePlaybackErr = 0
    }

    private fun stopOnError() {
        player.pause()
    }

    private fun updateNotification() {
        try {
            val customLayout = listOf(
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            if (currentSong.value?.song?.liked == true) {
                                R.string.action_remove_like
                            } else {
                                R.string.action_like
                            },
                        ),
                    )
                    .setIconResId(if (currentSong.value?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border)
                    .setSessionCommand(CommandToggleLike)
                    .setEnabled(currentSong.value != null)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(
                        getString(
                            when (player.repeatMode) {
                                REPEAT_MODE_OFF -> R.string.repeat_mode_off
                                REPEAT_MODE_ONE -> R.string.repeat_mode_one
                                REPEAT_MODE_ALL -> R.string.repeat_mode_all
                                else -> R.string.repeat_mode_off
                            },
                        ),
                    ).setIconResId(
                        when (player.repeatMode) {
                            REPEAT_MODE_OFF -> R.drawable.repeat
                            REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                            REPEAT_MODE_ALL -> R.drawable.repeat_on
                            else -> R.drawable.repeat
                        },
                    ).setSessionCommand(CommandToggleRepeatMode)
                    .build(),
                CommandButton
                    .Builder()
                    .setDisplayName(getString(if (player.shuffleModeEnabled) R.string.action_shuffle_off else R.string.action_shuffle_on))
                    .setIconResId(if (player.shuffleModeEnabled) R.drawable.shuffle_on else R.drawable.shuffle)
                    .setSessionCommand(CommandToggleShuffle)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName(getString(R.string.start_radio))
                    .setIconResId(R.drawable.radio)
                    .setSessionCommand(CommandToggleStartRadio)
                    .setEnabled(currentSong.value != null)
                    .build(),
            )
            mediaSession.setCustomLayout(customLayout)
        } catch (e: Exception) {
            reportException(e)
        }
    }

    fun refreshPlaybackNotification() {
        updateNotification()
        runCatching { super.onUpdateNotification(mediaSession, player.isPlaying) }
            .onFailure { reportException(it) }
    }

    private suspend fun recoverSong(
        mediaId: String,
        playbackData: YTPlayerUtils.PlaybackData? = null
    ) {
        val song = database.song(mediaId).first()
        val mediaMetadata = withContext(Dispatchers.Main) {
            player.findNextMediaItemById(mediaId)?.metadata
        } ?: return
        val duration = song?.song?.duration?.takeIf { it != -1 }
            ?: mediaMetadata.duration.takeIf { it != -1 }
            ?: (playbackData?.videoDetails ?: YTPlayerUtils.playerResponseForMetadata(mediaId)
                .getOrNull()?.videoDetails)?.lengthSeconds?.toInt()
            ?: -1
        database.query {
            if (song == null) insert(mediaMetadata.copy(duration = duration))
            else if (song.song.duration == -1) update(song.song.copy(duration = duration))
        }
        if (!database.hasRelatedSongs(mediaId)) {
            val relatedEndpoint =
                YouTube.next(WatchEndpoint(videoId = mediaId)).getOrNull()?.relatedEndpoint
                    ?: return
            val relatedPage = YouTube.related(relatedEndpoint).getOrNull() ?: return
            database.query {
                relatedPage.songs
                    .map(SongItem::toMediaMetadata)
                    .onEach(::insert)
                    .map {
                        RelatedSongMap(
                            songId = mediaId,
                            relatedSongId = it.id
                        )
                    }
                    .forEach(::insert)
            }
        }
    }

    fun playQueue(
        queue: Queue,
        playWhenReady: Boolean = true,
    ) {
        if (together.isGuest && !together.isApplyingRemote) {
            ensureScopesActive()
            scope.launch(SilentHandler) {
                val initialStatus = withContext(Dispatchers.IO) {
                    queue.getInitialStatus()
                        .filterExplicit(dataStore.get(HideExplicitKey, false))
                        .filterVideo(dataStore.get(HideVideoKey, false))
                }
                val target = initialStatus.items.getOrNull(initialStatus.mediaItemIndex) ?: queue.preloadItem?.toMediaItem()
                if (target != null) together.requestPlayNow(target, initialStatus.position, playWhenReady)
            }
            return
        }
        ensureScopesActive()
        suppressAutoPlayback = false
        currentQueue = queue
        queueTitle = null
        val permanentShuffle = dataStore.get(PermanentShuffleKey, false)
        val shufflePlay = queue.shuffle
        if (!permanentShuffle && !shufflePlay) {
            player.shuffleModeEnabled = false
        }
        
        clearAutomix()
        automixSeedMediaId = null
        autoAddedMediaIds.clear()
        if (queue.preloadItem != null) {
            player.setMediaItem(queue.preloadItem!!.toMediaItem())
            player.prepare()
            player.playWhenReady = playWhenReady
        }
        scope.launch(SilentHandler) {
            var initialStatus =
                withContext(Dispatchers.IO) {
                    queue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
                }
            if (shufflePlay && initialStatus.items.isNotEmpty()) {
                // Smart first song; the rest of the order is applied once items are set (shuffle mode is on).
                val tracks = adaptiveTracks(initialStatus.items.map { it.mediaId })
                initialStatus = initialStatus.copy(mediaItemIndex = AdaptiveShuffle.order(tracks, -1)[0], position = 0L)
                player.shuffleModeEnabled = true
            }
            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }
            if (initialStatus.items.isEmpty()) return@launch
            if (queue.preloadItem != null) {
                player.addMediaItems(
                    0,
                    initialStatus.items.subList(0, initialStatus.mediaItemIndex)
                )
                player.addMediaItems(
                    initialStatus.items.subList(
                        initialStatus.mediaItemIndex + 1,
                        initialStatus.items.size
                    )
                )
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }
            } else {
                val items = initialStatus.items
                val index = initialStatus.mediaItemIndex

                val windowStart = (index - 20).coerceAtLeast(0)
                val windowEnd = (index + 50).coerceAtMost(items.size)
                
                val initialChunk = items.subList(windowStart, windowEnd)
                val relativeIndex = index - windowStart
                
                player.setMediaItems(
                    initialChunk,
                    if (relativeIndex > 0) relativeIndex else 0,
                    initialStatus.position,
                )
                player.prepare()
                player.playWhenReady = playWhenReady
                if (player.shuffleModeEnabled) {
                    applyCurrentFirstShuffleOrder()
                }

                if (items.size > initialChunk.size) {
                    scope.launch(SilentHandler) {
                        try {
                            delay(2000)
                            if (!isActive) return@launch

                            if (windowStart > 0) {
                                val startChunk = items.subList(0, windowStart)
                                player.addMediaItems(0, startChunk)
                            }

                            if (windowEnd < items.size) {
                                val endChunk = items.subList(windowEnd, items.size)
                                player.addMediaItems(endChunk)
                            }

                            if (player.shuffleModeEnabled) {
                                applyCurrentFirstShuffleOrder(keepHistory = true)
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Failed to load deferred queue items")
                        }
                    }
                }
            }
        }
    }

    /**
     * Smart shuffle (see [AdaptiveShuffle]). Applies an instant order from the skip/heard memory so shuffle is never
     * delayed, then refines it with play counts and download dates from the database.
     * [keepHistory]: the queue only grew (e.g. the rest of a big list loaded) - songs already played this
     * shuffle stay played instead of being reshuffled back into the upcoming songs.
     */
    private fun applyCurrentFirstShuffleOrder(keepHistory: Boolean = false) {
        val count = player.mediaItemCount
        if (count <= 1) return
        val ids = List(count) { player.getMediaItemAt(it).mediaId }
        if (!keepHistory) {
            playedThisShuffle.clear()
            player.currentMediaItem?.mediaId?.let(playedThisShuffle::add)
        }
        setShuffleOrder(ids.map(::memoryTrack), keepHistory)

        adaptiveShuffleJob?.cancel()
        adaptiveShuffleJob = scope.launch(SilentHandler) {
            val tracks = adaptiveTracks(ids)
            // Queue changed while we were reading the database: a newer call will handle it.
            if (!player.shuffleModeEnabled || player.mediaItemCount != count ||
                List(count) { player.getMediaItemAt(it).mediaId } != ids
            ) return@launch
            setShuffleOrder(tracks, keepHistory = true) // history is now our own order from just above
        }
    }

    private fun memoryTrack(id: String) = AdaptiveShuffle.Track(
        skippedRecently = skipMemory.recentAt(id) != null,
        heardAt = heardMemory.recentAt(id),
    )

    private fun setShuffleOrder(tracks: List<AdaptiveShuffle.Track>, keepHistory: Boolean) {
        val currentIndex = player.currentMediaItemIndex.coerceIn(0, tracks.size - 1)
        val history = if (keepHistory) shuffleHistory(currentIndex) else emptyList()
        val order = AdaptiveShuffle.order(tracks, currentIndex, history = history)
        player.setShuffleOrder(DefaultShuffleOrder(order, System.currentTimeMillis()))
    }

    /** Songs before the current one in the active shuffle order (= already played this shuffle), oldest first. */
    private fun shuffleHistory(currentIndex: Int): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty || !player.shuffleModeEnabled) return emptyList()
        val out = ArrayList<Int>()
        var i = timeline.getPreviousWindowIndex(currentIndex, REPEAT_MODE_OFF, true)
        while (i != C.INDEX_UNSET && out.size < timeline.windowCount) {
            // Only songs really played: items inserted later land at random spots, some before the current one.
            if (timeline.getWindow(i, Timeline.Window()).mediaItem.mediaId in playedThisShuffle) out.add(i)
            i = timeline.getPreviousWindowIndex(i, REPEAT_MODE_OFF, true)
        }
        return out.asReversed()
    }

    private suspend fun adaptiveTracks(ids: List<String>): List<AdaptiveShuffle.Track> = withContext(Dispatchers.IO) {
        // chunked: SQLite caps bound parameters at 999
        val songs = ids.distinct().chunked(900).flatMap { database.getSongsByIds(it) }.associate { it.id to it.song }
        val downloaded = runCatching { downloadCache.keys }.getOrDefault(emptySet())
        val now = LocalDateTime.now()
        ids.map { id ->
            val song = songs[id]
            AdaptiveShuffle.Track(
                plays = song?.takeIf { it.duration > 0 }?.let { it.totalPlayTime / (it.duration * 1000f) } ?: 0f,
                // dateDownload defaults to "now" for every DB row, so only trust it for songs actually downloaded.
                downloadedDaysAgo = song?.dateDownload?.takeIf { id in downloaded }
                    ?.let { java.time.Duration.between(it, now).toMinutes() / 1440f },
                skippedRecently = skipMemory.recentAt(id) != null,
                heardAt = heardMemory.recentAt(id),
            )
        }
    }

    fun startRadioSeamlessly() {
        if (together.isGuest && !together.isApplyingRemote) {
            together.notice(getString(R.string.together_host_controls_playback))
            return
        }
        suppressAutoPlayback = false
        val currentMediaMetadata = player.currentMetadata ?: return

        val currentIndex = player.currentMediaItemIndex
        val currentMediaId = currentMediaMetadata.id

        scope.launch(SilentHandler) {
            val radioQueue = YouTubeQueue(
                endpoint = WatchEndpoint(videoId = currentMediaId)
            )
            val initialStatus = withContext(Dispatchers.IO) {
                radioQueue.getInitialStatus().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
            }

            if (initialStatus.title != null) {
                queueTitle = initialStatus.title
            }

            val radioItems = initialStatus.items.filter { item ->
                item.mediaId != currentMediaId
            }
            
            if (radioItems.isNotEmpty()) {
                val itemCount = player.mediaItemCount
                
                if (itemCount > currentIndex + 1) {
                    player.removeMediaItems(currentIndex + 1, itemCount)
                }
                
                player.addMediaItems(currentIndex + 1, radioItems)
            }

            currentQueue = radioQueue
        }
    }

    fun getAutomixAlbum(albumId: String) {
        scope.launch(Dispatchers.IO + SilentHandler) {
            YouTube
                .album(albumId)
                .onSuccess {
                    getAutomix(it.album.playlistId)
                }
        }
    }

    fun getAutomix(playlistId: String) {
        if (dataStore.get(AutoLoadMoreKey, true) && 
            player.repeatMode == REPEAT_MODE_OFF) {
            scope.launch(Dispatchers.IO + SilentHandler) {
                val seedAtRequest =
                    withContext(Dispatchers.Main) {
                        player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
                    }
                YouTube
                    .next(WatchEndpoint(playlistId = playlistId))
                    .onSuccess {
                        YouTube
                            .next(WatchEndpoint(playlistId = it.endpoint.playlistId))
                            .onSuccess {
                                val mediaItems = it.items.map { song -> song.toMediaItem() }
                                withContext(Dispatchers.Main) {
                                    val currentSeed =
                                        player.currentMetadata?.id?.trim()?.takeIf { it.isNotBlank() }
                                    if (seedAtRequest != null && currentSeed != seedAtRequest) return@withContext
                                    automixItems.value = mediaItems
                                    automixSeedMediaId = currentSeed
                                }
                            }
                    }
            }
        }
    }

    fun addToQueueAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        addToQueue(listOf(item))
    }

    fun playNextAutomix(
        item: MediaItem,
        position: Int,
    ) {
        automixItems.value =
            automixItems.value.toMutableList().apply {
                removeAt(position)
            }
        playNext(listOf(item))
    }

    fun clearAutomix() {
        automixJob?.cancel()
        automixJob = null
        automixItems.value = emptyList()
        automixLoading.value = false
        automixError.value = null
        automixSeedMediaId = null
    }

    private fun refreshAutomixForCurrentMedia(force: Boolean) {
        if (!dataStore.get(AutoLoadMoreKey, true)) return
        if (player.repeatMode != REPEAT_MODE_OFF) return
        if (suppressAutoPlayback) return
        if (player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return

        val currentMeta = player.currentMetadata ?: return
        val seedMediaId = currentMeta.id.trim().ifBlank { return }

        if (!force && automixSeedMediaId == seedMediaId && automixItems.value.isNotEmpty() && automixJob?.isActive == true) return

        automixJob?.cancel()
        automixJob = null
        automixItems.value = emptyList()
        automixLoading.value = true
        automixError.value = null
        automixSeedMediaId = seedMediaId

        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)

        automixJob = scope.launch {
            try {
                val nextResult = withContext(Dispatchers.IO) {
                    YouTube.next(WatchEndpoint(videoId = seedMediaId))
                }

                nextResult
                    .onSuccess { result ->
                        if (automixSeedMediaId != seedMediaId) {
                            automixLoading.value = false
                            return@onSuccess
                        }

                        val queueIds =
                            (0 until player.mediaItemCount)
                                .map { player.getMediaItemAt(it).mediaId }
                                .toSet()

                        val fromNext =
                            result.items
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val relatedCandidates =
                            result.relatedEndpoint
                                ?.let { endpoint ->
                                    withContext(Dispatchers.IO) { YouTube.related(endpoint) }
                                        .getOrNull()
                                        ?.songs
                                        .orEmpty()
                                }
                                .orEmpty()

                        val related =
                            relatedCandidates
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val poolBase =
                            (fromNext + related)
                                .asSequence()
                                .distinctBy { it.mediaId }
                                .take(50)
                                .toList()

                        val pool =
                            if (poolBase.size >= 25 || result.endpoint.playlistId.isNullOrBlank()) {
                                poolBase
                            } else {
                                val playlistId = result.endpoint.playlistId
                                val extra =
                                    withContext(Dispatchers.IO) {
                                        YouTube.next(WatchEndpoint(playlistId = playlistId))
                                    }.getOrNull()
                                        ?.items
                                        .orEmpty()
                                        .map { it.toMediaItem() }
                                        .filter { it.mediaId !in queueIds }
                                        .filterExplicit(hideExplicit)
                                        .filterVideo(hideVideo)

                                (poolBase + extra)
                                    .asSequence()
                                    .distinctBy { it.mediaId }
                                    .take(75)
                                    .toList()
                            }

                        // Smart feed: also pull songs related to what you've enjoyed most this session,
                        // then rank everything by your taste (with exploration + artist variety).
                        val moodSeed = taste.model.sessionSeed()?.takeIf { it != seedMediaId }
                        val moodCandidates =
                            moodSeed?.let { s ->
                                withContext(Dispatchers.IO) {
                                    YouTube.next(WatchEndpoint(videoId = s)).getOrNull()?.relatedEndpoint
                                        ?.let { YouTube.related(it).getOrNull()?.songs }
                                }
                            }.orEmpty()
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)
                                .take(25)
                        val smartPool = taste.rank((pool + moodCandidates).distinctBy { it.mediaId })

                        if (automixSeedMediaId != seedMediaId) {
                            automixLoading.value = false
                            return@onSuccess
                        }

                        automixItems.value = smartPool
                        if (smartPool.isEmpty()) {
                            automixError.value = getString(R.string.error_no_similar_songs)
                        }
                        automixLoading.value = false
                    }
                    .onFailure { throwable ->
                        if (automixSeedMediaId == seedMediaId) {
                            automixLoading.value = false
                            automixError.value =
                                throwable.localizedMessage ?: getString(R.string.error_automix_failed)
                        }
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (automixSeedMediaId == seedMediaId) {
                    automixLoading.value = false
                    automixError.value = e.localizedMessage ?: getString(R.string.error_automix_failed)
                }
            }
        }
    }

    fun onInfiniteQueueDisabled() {
        automixJob?.cancel()
        automixJob = null
        automixLoading.value = false
        automixError.value = null
        val currentIndex = player.currentMediaItemIndex
        val idsToRemove = synchronized(autoAddedMediaIds) { autoAddedMediaIds.toSet() }
        if (idsToRemove.isEmpty()) {
            clearAutomix()
            return
        }
        for (i in player.mediaItemCount - 1 downTo 0) {
            if (i == currentIndex) continue
            val item = player.getMediaItemAt(i)
            if (item.mediaId in idsToRemove) {
                player.removeMediaItem(i)
            }
        }
        autoAddedMediaIds.clear()
        clearAutomix()
    }

    fun onInfiniteQueueEnabled() {
        val currentMeta = player.currentMetadata
        if (currentMeta == null) {
            automixError.value = getString(R.string.error_no_song_playing)
            return
        }

        automixJob?.cancel()
        automixLoading.value = true
        automixError.value = null
        automixItems.value = emptyList()
        automixSeedMediaId = currentMeta.id.trim().ifBlank { null }

        val hideExplicit = dataStore.get(HideExplicitKey, false)
        val hideVideo = dataStore.get(HideVideoKey, false)

        automixJob = scope.launch {
            try {
                val nextResult = withContext(Dispatchers.IO) {
                    YouTube.next(WatchEndpoint(videoId = currentMeta.id))
                }

                nextResult
                    .onSuccess { result ->
                        if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) {
                            automixLoading.value = false
                            return@onSuccess
                        }
                        val initialQueueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val filteredFromNext =
                            result.items
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in initialQueueIds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val addedNow = ArrayList<MediaItem>(32)

                        if (filteredFromNext.isNotEmpty()) {
                            val toAdd = filteredFromNext.take(25)
                            player.addMediaItems(toAdd)
                            toAdd.forEach { autoAddedMediaIds.add(it.mediaId) }
                            addedNow.addAll(toAdd)
                        }

                        val queueIdsAfterNext = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val relatedCandidates =
                            result.relatedEndpoint?.let { relatedEndpoint ->
                                withContext(Dispatchers.IO) {
                                    YouTube.related(relatedEndpoint)
                                }.getOrNull()?.songs.orEmpty()
                            }.orEmpty()

                        val filteredRelated =
                            relatedCandidates
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIdsAfterNext }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        if (addedNow.isEmpty() && filteredRelated.isNotEmpty()) {
                            val toAdd = filteredRelated.take(25)
                            player.addMediaItems(toAdd)
                            toAdd.forEach { autoAddedMediaIds.add(it.mediaId) }
                            addedNow.addAll(toAdd)
                        }

                        val queueIdsAfterAdds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                        val playlistId = result.endpoint.playlistId
                        val automixCandidates =
                            if (playlistId.isNullOrBlank()) {
                                emptyList()
                            } else {
                                withContext(Dispatchers.IO) {
                                    YouTube.next(WatchEndpoint(playlistId = playlistId))
                                }.getOrNull()?.items.orEmpty()
                            }

                        val filteredAutomix =
                            automixCandidates
                                .map { it.toMediaItem() }
                                .filter { it.mediaId !in queueIdsAfterAdds }
                                .filterExplicit(hideExplicit)
                                .filterVideo(hideVideo)

                        val addedIds = addedNow.map { it.mediaId }.toSet()
                        val pool =
                            (filteredFromNext + filteredRelated + filteredAutomix)
                                .asSequence()
                                .distinctBy { it.mediaId }
                                .filter { it.mediaId !in addedIds }
                                .take(75)
                                .toList()

                        automixItems.value = pool

                        if (addedNow.isEmpty() && pool.isEmpty()) {
                            automixError.value = getString(R.string.error_no_similar_songs)
                        }
                        automixLoading.value = false
                    }
                    .onFailure { throwable ->
                        automixLoading.value = false
                        automixError.value = throwable.localizedMessage ?: getString(R.string.error_automix_failed)
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                automixLoading.value = false
                automixError.value = e.localizedMessage ?: getString(R.string.error_automix_failed)
            }
        }
    }

    fun stopAndClearPlayback() {
        suppressAutoPlayback = true
        clearAutomix()
        currentQueue = EmptyQueue
        queueTitle = null
        clearStreamRefreshGuards()
        waitingForNetworkConnection.value = false
        currentMediaMetadata.value = null
        player.playWhenReady = false
        player.stop()
        player.clearMediaItems()
        abandonAudioFocus()
        audioEffects.close()
        consecutivePlaybackErr = 0
    }

    fun playNext(items: List<MediaItem>) {
        if (together.isGuest) {
            together.requestAddTracks(items, com.nikhil.yt.together.AddTrackMode.PLAY_NEXT)
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(
            if (player.mediaItemCount == 0) 0 else player.currentMediaItemIndex + 1,
            items
        )
        player.prepare()
    }

    fun addToQueue(items: List<MediaItem>) {
        if (together.isGuest) {
            together.requestAddTracks(items, com.nikhil.yt.together.AddTrackMode.ADD_TO_QUEUE)
            return
        }
        suppressAutoPlayback = false
        player.addMediaItems(items)
        player.prepare()
    }

    private fun toggleLibrary() {
        database.query {
            currentSong.value?.let {
                update(it.song.toggleLibrary())
            }
        }
    }

    fun toggleLike() {
         database.query {
             currentSong.value?.let {
                 val song = it.song.toggleLike()
                 update(song)
                 syncUtils.likeSong(song)

                 if (dataStore.get(AutoDownloadOnLikeKey, false) && song.liked) {
                     ExoDownloadService.download(this@MusicService, song.id, song.title)
                 }
             }
         }
     }

    fun toggleStartRadio() {
        startRadioSeamlessly()
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaItem?.mediaId?.let(playedThisShuffle::add)
    super.onMediaItemTransition(mediaItem, reason)

    clearStreamRefreshGuards(
        mediaItem?.mediaId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: player.currentMediaItem?.mediaId
    )

    crossfadeAudio?.onMediaItemTransition(mediaItem, reason)
    WaveformStore.startSong(this, mediaItem?.mediaId)

    val currentIndex = player.currentMediaItemIndex
    val queue = player.mediaItems.mapNotNull { it.metadata }
    if (queue.isNotEmpty()) {
        lyricsPreloadManager?.onSongChanged(currentIndex, queue)
    }

    // Resolve the next song's stream now, so the data source finds it in playbackUrlCache and starts instantly.
    if (player.hasNextMediaItem()) {
        val nextId = player.getMediaItemAt(player.nextMediaItemIndex).mediaId
        val alreadyFresh = playbackUrlCache[nextId]?.second?.let { it > System.currentTimeMillis() } == true
        if (!alreadyFresh && downloadCache.getCachedSpans(nextId).isEmpty()) {
            scope.launch(SilentHandler + Dispatchers.IO) {
                runCatching { resolveAndCacheStream(nextId) }
            }
        }
    }

    if (together.isGuest && reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
        together.onGuestSeekedTo(
            index = player.currentMediaItemIndex.coerceAtLeast(0),
            trackId = (mediaItem?.metadata ?: player.currentMetadata)?.id,
            positionMs = player.currentPosition.coerceAtLeast(0L),
        )
    }

    val timelineEmpty = player.currentTimeline.isEmpty || player.mediaItemCount == 0 || player.currentMediaItem == null
    currentMediaMetadata.value = if (timelineEmpty) null else (mediaItem?.metadata ?: player.currentMetadata)

    scrobbleManager?.onSongStop()

    if (!timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.repeatMode == REPEAT_MODE_OFF
    ) {
        val isNearEndWithoutPaging =
            player.mediaItemCount - player.currentMediaItemIndex <= 3 && !currentQueue.hasNextPage()

        if (!isNearEndWithoutPaging) {
            val force =
                reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED

            val currentId = (mediaItem?.metadata ?: player.currentMetadata)?.id?.trim().orEmpty()
            if (force || (currentId.isNotBlank() && automixSeedMediaId != currentId)) {
                refreshAutomixForCurrentMedia(force = force)
            }
        }
    }

    if (!suppressAutoPlayback &&
        !timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.mediaItemCount - player.currentMediaItemIndex <= 5 &&
        currentQueue.hasNextPage() &&
        player.repeatMode == REPEAT_MODE_OFF
    ) {
        scope.launch(SilentHandler) {
            val mediaItems =
                currentQueue.nextPage().filterExplicit(dataStore.get(HideExplicitKey, false)).filterVideo(dataStore.get(HideVideoKey, false))
            if (player.playbackState != STATE_IDLE) {
                player.addMediaItems(mediaItems.drop(1))
            } else {
                scope.launch { discordRpc?.stopActivity() }
            }
        }
    }
    
    if (!suppressAutoPlayback &&
        !timelineEmpty &&
        dataStore.get(AutoLoadMoreKey, true) &&
        reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT &&
        player.repeatMode == REPEAT_MODE_OFF &&
        player.mediaItemCount - player.currentMediaItemIndex <= 3 &&
        !currentQueue.hasNextPage()
    ) {
        scope.launch(SilentHandler) {
            if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@launch
            val queueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
            val currentMediaMetadata = player.currentMetadata
            val currentMediaId = currentMediaMetadata?.id?.trim().orEmpty()
            val existingSeed = automixSeedMediaId?.trim().orEmpty()
            val existingAutomix =
                if (currentMediaId.isNotBlank() && existingSeed == currentMediaId) {
                    automixItems.value
                } else {
                    if (automixItems.value.isNotEmpty()) {
                        clearAutomix()
                    }
                    emptyList()
                }
            if (existingAutomix.isNotEmpty()) {
                val filteredAutomix = existingAutomix.filter { it.mediaId !in queueIds }
                if (filteredAutomix.isNotEmpty()) {
                    player.addMediaItems(filteredAutomix)
                    filteredAutomix.forEach { autoAddedMediaIds.add(it.mediaId) }
                }
                clearAutomix()
            } else {
                if (currentMediaMetadata != null) {
                    refreshAutomixForCurrentMedia(force = true)
                }
            }
        }
    }

    if (player.playWhenReady && player.playbackState == Player.STATE_READY) {
        scrobbleManager?.onSongStart(player.currentMetadata, duration = player.duration)
    }

    scope.launch {
        val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
        if (shouldSave) {
            saveQueueToDisk()
        }
    }
    ensurePresenceManager()
}

    override fun onPlaybackStateChanged(@Player.State playbackState: Int) {
    super.onPlaybackStateChanged(playbackState)

    val activeMediaId = player.currentMediaItem?.mediaId
    clearStreamRefreshGuards(activeMediaId)
    if (
        playbackState == Player.STATE_READY &&
        player.playWhenReady &&
        player.isPlaying &&
        activeMediaId != null &&
        pendingStreamRefreshValidationMediaId == activeMediaId
    ) {
        refreshValidatedPlayingMediaId = activeMediaId
        pendingStreamRefreshValidationMediaId = null
        streamRecoveryState.remove(activeMediaId)
        Timber.tag("MusicService").i("Stream refresh validated and playback resumed for $activeMediaId")
    }

    scope.launch {
        val shouldSave = withContext(Dispatchers.IO) { dataStore.get(PersistentQueueKey, true) }
        if (shouldSave) {
            saveQueueToDisk()
        }
    }

    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
        crossfadeAudio?.stop(resetMainFade = true)
        scrobbleManager?.onSongStop()
    }

    if (!suppressAutoPlayback &&
        playbackState == Player.STATE_ENDED &&
        dataStore.get(AutoLoadMoreKey, true) &&
        player.repeatMode == REPEAT_MODE_OFF &&
        player.currentMediaItem != null
    ) {
        scope.launch(SilentHandler) {
            if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@launch
            val lastMediaMetadata = player.currentMetadata
            val existingAutomix = automixItems.value
            if (existingAutomix.isNotEmpty()) {
                val filteredAutomix = existingAutomix.filter { it.mediaId != lastMediaMetadata?.id }
                if (filteredAutomix.isNotEmpty()) {
                    autoAddedMediaIds.clear()
                    player.setMediaItems(filteredAutomix, 0, 0)
                    player.prepare()
                    player.play()
                    filteredAutomix.forEach { autoAddedMediaIds.add(it.mediaId) }
                }
                clearAutomix()
            } else {
                if (lastMediaMetadata != null) {
                    withContext(Dispatchers.IO) {
                        YouTube.next(WatchEndpoint(videoId = lastMediaMetadata.id))
                    }.onSuccess { nextResult ->
                        if (suppressAutoPlayback || player.playbackState == STATE_IDLE || player.mediaItemCount == 0) return@onSuccess
                        val hideExplicit = dataStore.get(HideExplicitKey, false)
                        val hideVideo = dataStore.get(HideVideoKey, false)
                        val radioItems = nextResult.items
                            .map { it.toMediaItem() }
                            .filter { it.mediaId != lastMediaMetadata.id }
                            .filterExplicit(hideExplicit)
                            .filterVideo(hideVideo)

                        if (radioItems.isNotEmpty()) {
                            autoAddedMediaIds.clear()
                            player.setMediaItems(radioItems, 0, 0)
                            player.prepare()
                            player.play()
                            radioItems.forEach { autoAddedMediaIds.add(it.mediaId) }

                            withContext(Dispatchers.IO) {
                                YouTube.next(WatchEndpoint(playlistId = nextResult.endpoint.playlistId))
                            }.onSuccess { automixResult ->
                                if (suppressAutoPlayback || player.playbackState == STATE_IDLE) return@onSuccess
                                automixItems.value = automixResult.items
                                    .map { it.toMediaItem() }
                                    .filter { it.mediaId != lastMediaMetadata.id }
                                    .filterExplicit(hideExplicit)
                                    .filterVideo(hideVideo)
                            }
                        }
                    }
                }
            }
        }
    }

    ensurePresenceManager()
    scope.launch {
        try {
            val token = withContext(Dispatchers.IO) { dataStore.get(DiscordTokenKey, "") }
            if (token.isNotBlank() && DiscordPresenceManager.isRunning()) {
                val mediaId = player.currentMediaItem?.mediaId
                val song = if (mediaId != null) withContext(Dispatchers.IO) { database.song(mediaId).first() } else null
                val finalSong = song ?: player.currentMetadata?.let { createTransientSongFromMedia(it) }

                if (canUpdatePresence()) {
                    val success = withContext(Dispatchers.IO) {
                        DiscordPresenceManager.updateNow(
                            context = this@MusicService,
                            token = token,
                            song = finalSong,
                            positionMs = player.currentPosition,
                            isPaused = !player.playWhenReady,
                        )
                    }
                    if (!success) {
                        Timber.tag("MusicService").w("immediate presence update returned false — attempting restart")
                        if (DiscordPresenceManager.isRunning()) {
                            try {
                                if (DiscordPresenceManager.restart()) {
                                    Timber.tag("MusicService").d("presence manager restarted after failed update")
                                }
                            } catch (ex: Exception) {
                                Timber.tag("MusicService").e(ex, "restart after failed presence update threw")
                            }
                        }
                    }

                    try {
                        val lbEnabled = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzEnabledKey, false) }
                        val lbToken = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzTokenKey, "") }
                        if (lbEnabled && !lbToken.isNullOrBlank()) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, finalSong, player.currentPosition)
                                } catch (ie: Exception) {
                                    Timber.tag("MusicService").v(ie, "ListenBrainz playing_now submit failed")
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Timber.tag("MusicService").v(e, "immediate presence update failed")
        }
    }
}


    override fun onEvents(player: Player, events: Player.Events) {
    if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && this.player.playWhenReady) {
        // Soft 250 ms fade-in on play/resume instead of starting at full level.
        resumeFadeJob?.cancel()
        resumeFadeFactor.value = 0f
        this.player.volume = 0f // apply now, before the AudioTrack starts, not on the next collector pass
        resumeFadeJob = rampVolume(resumeFadeFactor, 1f, 250L)
    }
    if (together.isGuest && events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
        together.onGuestPlayWhenReadyChanged(this.player.playWhenReady)
    }
    if (events.contains(Player.EVENT_DEVICE_VOLUME_CHANGED)) {
        handleDeviceMuteStateChanged()
    }
    if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED) && isDeviceMutedNow() && this.player.playWhenReady) {
        wasAutoPausedByDeviceMute = false
    }
    if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
        (this.player.playbackState == Player.STATE_IDLE || this.player.playbackState == Player.STATE_ENDED)
    ) {
        wasAutoPausedByDeviceMute = false
    }
    if (events.contains(Player.EVENT_AUDIO_SESSION_ID)) {
        audioEffects.onAudioSessionChanged(this.player.audioSessionId)
    }
    if (events.containsAny(
            Player.EVENT_PLAYBACK_STATE_CHANGED,
            Player.EVENT_PLAY_WHEN_READY_CHANGED
        )
    ) {
        val isBufferingOrReady =
            player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_READY
        if (isBufferingOrReady && player.playWhenReady) {
            val focusGranted = requestAudioFocus()
            if (focusGranted) audioEffects.open(player.audioSessionId)
        } else {
            audioEffects.close()
        }
    }

       if (events.containsAny(EVENT_TIMELINE_CHANGED, EVENT_POSITION_DISCONTINUITY)) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                try {
                    val token = dataStore.get(DiscordTokenKey, "")
                    if (token.isNotBlank() && DiscordPresenceManager.isRunning()) {
                        val mediaId = player.currentMediaItem?.mediaId
                        val song = if (mediaId != null) withContext(Dispatchers.IO) { database.song(mediaId).first() } else null
                        val finalSong = song ?: player.currentMetadata?.let { createTransientSongFromMedia(it) }

                        if (canUpdatePresence()) {
                            val success = DiscordPresenceManager.updateNow(
                                context = this@MusicService,
                                token = token,
                                song = finalSong,
                                positionMs = player.currentPosition,
                                isPaused = !player.isPlaying,
                            )
                            if (!success) {
                                Timber.tag("MusicService").w("transition immediate presence update failed — attempting restart")
                                try { DiscordPresenceManager.stop(); DiscordPresenceManager.start(this@MusicService, dataStore.get(DiscordTokenKey, ""), { song }, { player.currentPosition }, { !player.isPlaying }, { getPresenceIntervalMillis(this@MusicService) }) } catch (_: Exception) {}
                            }
                            try {
                                val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)
                                val lbToken = dataStore.get(ListenBrainzTokenKey, "")
                                if (lbEnabled && !lbToken.isNullOrBlank()) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, finalSong, player.currentPosition)
                                        } catch (ie: Exception) {
                                            Timber.tag("MusicService").v(ie, "ListenBrainz playing_now submit failed on transition")
                                        }
                                    }
                                }

                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "immediate presence update failed on transition")
                }
            }
        }

        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                currentMediaMetadata.value = player.currentMetadata
            }
            val currentMediaId = player.currentMediaItem?.mediaId
            val currentMetadata = player.currentMetadata
            val currentPosition = player.currentPosition
            val isPlaying = player.isPlaying

            updateVeluneWidgetState(
                context = this@MusicService,
                title = currentMetadata?.title ?: "Not Playing",
                artist = currentMetadata?.artists?.joinToString(", ") { it.name } ?: "Spectra",
                isPlaying = isPlaying,
                thumbnailUrl = currentMetadata?.thumbnailUrl
            )





            scope.launch {
                try {
                    val token = withContext(Dispatchers.IO) { dataStore.get(DiscordTokenKey, "") }
                    if (token.isNotBlank() && DiscordPresenceManager.isRunning()) {
                        val song = if (currentMediaId != null) withContext(Dispatchers.IO) { database.song(currentMediaId).first() } else null
                        val finalSong = song ?: currentMetadata?.let { createTransientSongFromMedia(it) }

                        if (canUpdatePresence()) {
                            val success = withContext(Dispatchers.IO) {
                                DiscordPresenceManager.updateNow(
                                    context = this@MusicService,
                                    token = token,
                                    song = finalSong,
                                    positionMs = currentPosition,
                                    isPaused = !isPlaying,
                                )
                            }
                            if (!success) {
                                Timber.tag("MusicService").w("isPlaying/mediaTransition immediate presence update failed — restarting manager")
                                if (DiscordPresenceManager.isRunning()) {
                                    try { DiscordPresenceManager.stop(); DiscordPresenceManager.restart() } catch (_: Exception) {}
                                }
                            }
                            try {
                                val lbEnabled = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzEnabledKey, false) }
                                val lbToken = withContext(Dispatchers.IO) { dataStore.get(ListenBrainzTokenKey, "") }
                                if (lbEnabled && !lbToken.isNullOrBlank()) {
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            ListenBrainzManager.submitPlayingNow(this@MusicService, lbToken, finalSong, currentPosition)
                                        } catch (ie: Exception) {
                                            Timber.tag("MusicService").v(ie, "ListenBrainz playing_now submit failed for isPlaying/mediaTransition")
                                        }
                                    }
                                }

                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag("MusicService").v(e, "immediate presence update failed for isPlaying/mediaTransition")
                }
            }
        }

   if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED)) {
        ensurePresenceManager()
        scrobbleManager?.onPlayerStateChanged(player.isPlaying, player.currentMetadata, duration = player.duration)
    } else if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
        ensurePresenceManager()
    } else {
        ensurePresenceManager()
    }
  }


    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val skipped = oldPosition.mediaItem ?: return
        // Taste learning: the song played to its end (same index again = repeat-one).
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
            heardMemory.record(skipped.mediaId)
            taste.feedback(
                skipped,
                if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) TasteModel.REPEATED else TasteModel.FINISHED,
            )
            return
        }
        // Smart shuffle: a forward skip before the halfway point means "not now" -> cool the song down for 4 h.
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        val oldIndex = oldPosition.mediaItemIndex
        if (oldIndex == newPosition.mediaItemIndex) return // seek within the song / crossfade handoff
        val timeline = player.currentTimeline
        if (oldIndex >= timeline.windowCount) return
        val forward = timeline.getNextWindowIndex(oldIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled) ==
            newPosition.mediaItemIndex
        if (!forward) return
        val durationMs = timeline.getWindow(oldIndex, Timeline.Window()).durationMs
        val earlySkip = if (durationMs != C.TIME_UNSET && durationMs > 0) {
            oldPosition.positionMs < durationMs / 2
        } else {
            oldPosition.positionMs < 30_000L
        }
        if (earlySkip) {
            taste.feedback(
                skipped,
                if (oldPosition.positionMs < 15_000L) TasteModel.SKIPPED_INSTANTLY else TasteModel.SKIPPED_EARLY,
            )
            skipMemory.record(skipped.mediaId)
            scope.launch(Dispatchers.IO + SilentHandler) { forYouEngine.recordSkip(skipped.mediaId) } // teaches For You
        } else {
            heardMemory.record(skipped.mediaId) // listened past halfway: counts as heard
        }
    }

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
        updateNotification()
        if (together.isGuest) {
            together.onGuestShuffleChanged(shuffleModeEnabled)
            return
        }
        if (shuffleModeEnabled) {
            applyCurrentFirstShuffleOrder()
        }

        scope.launch {
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
    }

    /** Keep repeat-one on for [minutes], then switch repeat off so the song finishes and the queue moves on. */
    fun repeatCurrentFor(minutes: Int) {
        repeatTimerJob?.cancel()
        repeatTimerJob = scope.launch {
            delay(minutes * 60_000L)
            if (player.repeatMode == REPEAT_MODE_ONE) player.repeatMode = REPEAT_MODE_OFF
        }
    }

    override fun onRepeatModeChanged(repeatMode: Int) {
        if (repeatMode != REPEAT_MODE_ONE) repeatTimerJob?.cancel() // user changed it: timer no longer applies
        updateNotification()
        if (together.isGuest) {
            if (!together.isApplyingRemote) together.requestControl(com.nikhil.yt.together.ControlAction.SetRepeatMode(repeatMode))
            return
        }
        scope.launch {
            dataStore.edit { settings ->
                settings[RepeatModeKey] = repeatMode
            }
        }

        scope.launch {
            if (dataStore.get(PersistentQueueKey, true)) {
                saveQueueToDisk()
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        val isConnectionError = (error.cause?.cause is PlaybackException) &&
                (error.cause?.cause as PlaybackException).errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

        if (!isNetworkConnected.value || isConnectionError) {
            waitOnNetworkError()
            return
        }

        val currentMediaId = player.currentMediaItem?.mediaId
        val httpStatusCode = error.httpStatusCodeOrNull()

        if (currentMediaId != null && YTPlayerUtils.isBotDetectionException(error)) {
            if (markAndCheckRecoveryAllowance(currentMediaId)) {
                Timber.tag("MusicService").w(
                    "Bot detection error for $currentMediaId — clearing caches and retrying with fresh stream"
                )
                YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
                playbackUrlCache.remove(currentMediaId)
                pendingStreamRefreshValidationMediaId = currentMediaId
                player.prepare()
                player.playWhenReady = true
                return
            }
        }

        val shouldAttemptStreamRefresh =
            currentMediaId != null && (
                error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
                    // "no playable stream" from YTPlayerUtils: often transient, one fresh resolve usually works
                    error.errorCode == PlaybackException.ERROR_CODE_REMOTE_ERROR ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                    httpStatusCode in setOf(403, 404, 410, 416, 429, 500, 502, 503)
                )

        if (currentMediaId != null && error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
            scope.launch(Dispatchers.IO) {
                runCatching { downloadCache.removeResource(currentMediaId) }
                runCatching { playerCache.removeResource(currentMediaId) }
            }
        }

        if (shouldAttemptStreamRefresh && currentMediaId != null && shouldSkipRedundantStreamRefresh(currentMediaId)) {
            Timber.tag("MusicService").w(
                "Skipping redundant stream refresh for $currentMediaId after validated recovery; resuming playback without URL refresh"
            )
            player.prepare()
            player.playWhenReady = true
            return
        }

        if (shouldAttemptStreamRefresh && currentMediaId != null && markAndCheckRecoveryAllowance(currentMediaId)) {
            val failingStreamClientKey =
                playbackUrlCache[currentMediaId]
                    ?.first
                    ?.toHttpUrlOrNull()
                    ?.queryParameter("c")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            Timber.tag("MusicService").w(
                "Attempting stream refresh for $currentMediaId (http=$httpStatusCode, code=${error.errorCode}, client=${failingStreamClientKey ?: "unknown"})"
            )
            YTPlayerUtils.markStreamClientFailed(currentMediaId, failingStreamClientKey, httpStatusCode)
            YTPlayerUtils.markPreferredClientFailed(currentMediaId, preferredStreamClient, httpStatusCode)
            YTPlayerUtils.invalidateCachedStreamUrls(currentMediaId)
            playbackUrlCache.remove(currentMediaId)
            pendingStreamRefreshValidationMediaId = currentMediaId
            player.prepare()
            player.playWhenReady = true
            return
        }

        val skipSilenceCurrentlyEnabled = dataStore.get(SkipSilenceKey, false)
        val causeText = (error.cause?.stackTraceToString() ?: error.stackTraceToString()).lowercase()
        val looksLikeSilenceProcessor = skipSilenceCurrentlyEnabled && (
            "silenceskippingaudioprocessor" in causeText || "silence" in causeText
        )

        if (looksLikeSilenceProcessor) {
            scope.launch {
                try {
                    dataStore.edit { settings ->
                        settings[SkipSilenceKey] = false
                    }
                    player.skipSilenceEnabled = false
                    val currentPos = player.currentPosition
                    val targetPos = min(currentPos + 1500L, if (player.duration > 0) player.duration - 1000L else currentPos + 1500L)
                    player.seekTo(targetPos)
                    player.prepare()
                    player.play()
                    return@launch
                } catch (t: Throwable) {
                    Timber.tag("MusicService").e(t, "failed to recover from silence-skipper error")
                }
                if (dataStore.get(AutoSkipNextOnErrorKey, true)) {
                    skipOnError()
                } else {
                    stopOnError()
                }
            }

            return
        }

        if (dataStore.get(AutoSkipNextOnErrorKey, true)) {
            skipOnError()
        } else {
            stopOnError()
        }
    }

    private suspend fun trimPlayerCacheToBytes(limitBytes: Long) {
        if (limitBytes <= 0L) return

        withContext(Dispatchers.IO) {
            val cacheDir = filesDir.resolve("exoplayer")
            val currentSpace = runCatching { playerCache.cacheSpace }.getOrNull() ?: 0L
            var totalBytes = if (currentSpace > 0L) currentSpace else cacheDir.directorySizeBytes()
            if (totalBytes <= limitBytes) return@withContext

            data class Candidate(
                val key: String,
                val lastTouchTimestamp: Long,
                val sizeBytes: Long,
            )

            val candidates =
                runCatching {
                    playerCache.keys.mapNotNull { key ->
                        runCatching {
                            val spans = playerCache.getCachedSpans(key)
                            if (spans.isEmpty()) return@runCatching null
                            val oldestTouch = spans.minOf { it.lastTouchTimestamp }
                            val sizeBytes = spans.sumOf { it.length }
                            Candidate(key = key, lastTouchTimestamp = oldestTouch, sizeBytes = sizeBytes)
                        }.getOrNull()
                    }.sortedBy { it.lastTouchTimestamp }
                }.getOrNull().orEmpty()

            for (candidate in candidates) {
                if (totalBytes <= limitBytes) break
                val removedSize = candidate.sizeBytes.coerceAtLeast(0L)
                runCatching { playerCache.removeResource(candidate.key) }
                totalBytes -= removedSize
            }
        }
    }

    private fun createCacheDataSource(): CacheDataSource.Factory =
        CacheDataSource
            .Factory()
            .setCache(downloadCache)
            .setUpstreamDataSourceFactory(
                CacheDataSource
                    .Factory()
                    .setCache(playerCache)
                    .setUpstreamDataSourceFactory(
                        DefaultDataSource.Factory(
                            this,
                            OkHttpDataSource.Factory(
                                mediaOkHttpClient,
                            ),
                        ),
                    )
                    .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)
            ).setCacheWriteDataSinkFactory(null)
            .setFlags(FLAG_IGNORE_CACHE_ON_ERROR)

    private fun createDataSourceFactory(): DataSource.Factory {
        return ResolvingDataSource.Factory(createCacheDataSource()) { dataSpec ->
            val mediaId = dataSpec.key ?: error("No media id")

            val requiredCachedLength =
                if (dataSpec.length >= 0) {
                    dataSpec.length
                } else {
                    val contentLength =
                        runBlocking(Dispatchers.IO) {
                            database.format(mediaId).first()?.contentLength
                        } ?: runCatching {
                            downloadCache
                                .getContentMetadata(mediaId)
                                .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                        }.getOrNull()?.takeIf { it > 0L } ?: runCatching {
                            playerCache
                                .getContentMetadata(mediaId)
                                .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
                        }.getOrNull()?.takeIf { it > 0L }

                    contentLength?.let { nonNullContentLength ->
                        (nonNullContentLength - dataSpec.position).takeIf { it > 0L }
                    }
                }

            if (requiredCachedLength != null) {
                val isFullyCached =
                    downloadCache.isCached(mediaId, dataSpec.position, requiredCachedLength) ||
                        playerCache.isCached(mediaId, dataSpec.position, requiredCachedLength)
                if (isFullyCached) {
                    scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                    return@Factory dataSpec
                }
            }

            playbackUrlCache[mediaId]?.takeIf { it.second > System.currentTimeMillis() }?.let {
                scope.launch(Dispatchers.IO) { recoverSong(mediaId) }
                val length = if (dataSpec.length >= 0) minOf(dataSpec.length, CHUNK_LENGTH) else CHUNK_LENGTH
                return@Factory dataSpec.withUri(it.first.toUri()).subrange(dataSpec.uriPositionOffset, length)
            }

            val streamUrl = runBlocking(Dispatchers.IO) {
                runCatching { resolveAndCacheStream(mediaId) }
            }.getOrElse { throwable ->
                when (throwable) {
                    is PlaybackException -> throw throwable

                    is java.net.ConnectException, is java.net.UnknownHostException -> {
                        throw PlaybackException(
                            getString(R.string.error_no_internet),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                        )
                    }

                    is java.net.SocketTimeoutException -> {
                        throw PlaybackException(
                            getString(R.string.error_timeout),
                            throwable,
                            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                        )
                    }

                    else -> throw PlaybackException(
                        getString(R.string.error_unknown),
                        throwable,
                        PlaybackException.ERROR_CODE_REMOTE_ERROR
                    )
                }
            }

            val length = if (dataSpec.length >= 0) minOf(dataSpec.length, CHUNK_LENGTH) else CHUNK_LENGTH
            dataSpec.withUri(streamUrl.toUri()).subrange(dataSpec.uriPositionOffset, length)
        }
    }

    /** The single place a stream is resolved: used for playback and for preloading the next song. */
    private suspend fun resolveAndCacheStream(mediaId: String): String {
        val playback = YTPlayerUtils.playerResponseForPlayback(
            mediaId,
            audioQuality = audioQuality,
            connectivityManager = connectivityManager,
            preferredStreamClient = preferredStreamClient,
            avoidCodecs = avoidStreamCodecs,
        ).getOrThrow()
        val format = playback.format
        val loudnessDb = playback.audioConfig?.loudnessDb
        val perceptualLoudnessDb = playback.audioConfig?.perceptualLoudnessDb
        if (loudnessDb == null && perceptualLoudnessDb == null) {
            Timber.tag("AudioNormalization").w("No loudness data available from YouTube for video: $mediaId")
        }
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
                    loudnessDb = loudnessDb,
                    perceptualLoudnessDb = perceptualLoudnessDb,
                    playbackUrl = playback.playbackTracking?.videostatsPlaybackUrl?.baseUrl
                )
            )
        }
        scope.launch(Dispatchers.IO) { recoverSong(mediaId, playback) }
        playbackUrlCache[mediaId] =
            playback.streamUrl to System.currentTimeMillis() + (playback.streamExpiresInSeconds * 1000L)
        return playback.streamUrl
    }

    fun retryCurrentFromFreshStream() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        clearStreamRefreshGuards(mediaId)
        YTPlayerUtils.invalidateCachedStreamUrls(mediaId)
        playbackUrlCache.remove(mediaId)
        pendingStreamRefreshValidationMediaId = mediaId
        player.prepare()
        player.playWhenReady = true
    }

    private fun PlaybackException.httpStatusCodeOrNull(): Int? {
        var t: Throwable? = cause
        while (t != null) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode
            t = t.cause
        }
        return null
    }

    private fun markAndCheckRecoveryAllowance(mediaId: String): Boolean {
        val now = System.currentTimeMillis()
        val (count, lastAt) = streamRecoveryState[mediaId] ?: (0 to 0L)
        val nextCount = if (now - lastAt > 45_000L) 1 else count + 1
        if (nextCount > 2) return false
        streamRecoveryState[mediaId] = nextCount to now
        return true
    }

    private fun shouldSkipRedundantStreamRefresh(mediaId: String): Boolean {
        if (refreshValidatedPlayingMediaId != mediaId) return false
        val expiresAt = playbackUrlCache[mediaId]?.second ?: return false
        if (expiresAt <= System.currentTimeMillis()) {
            refreshValidatedPlayingMediaId = null
            return false
        }
        return true
    }

    private fun clearStreamRefreshGuards(activeMediaId: String? = null) {
        val normalizedActiveMediaId = activeMediaId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedActiveMediaId == null || refreshValidatedPlayingMediaId != normalizedActiveMediaId) {
            refreshValidatedPlayingMediaId = null
        }
        if (normalizedActiveMediaId == null || pendingStreamRefreshValidationMediaId != normalizedActiveMediaId) {
            pendingStreamRefreshValidationMediaId = null
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

    private fun createMediaSourceFactory() =
        DefaultMediaSourceFactory(
            createDataSourceFactory(),
            ExtractorsFactory {
                arrayOf(Mp4Extractor(), FragmentedMp4Extractor(), MatroskaExtractor())
            },
        )

    private fun updateAudioOffload(enabled: Boolean) {
        val mode =
            if (enabled) androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
            else androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setAudioOffloadPreferences(
                androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(mode)
                    .build()
            )
            .build()
    }

    private fun createRenderersFactory(immersive: ImmersiveProcessor, loudness: LoudnessProcessor) =
        object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ) = DefaultAudioSink
                .Builder(this@MusicService)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessorChain(
                    DefaultAudioSink.DefaultAudioProcessorChain(
                        immersive, // tone + space first, so the limiter in `loudness` catches its peaks
                        loudness,
                        SilenceSkippingAudioProcessor(
                            1_500_000L,
                            0.35f,
                            500_000L,
                            10,
                            150.toShort(),
                        ),
                        SonicAudioProcessor(),
                    ),
                ).build()
        }

    override fun onPlaybackStatsReady(
        eventTime: AnalyticsListener.EventTime,
        playbackStats: PlaybackStats,
    ) {
        val mediaItem = eventTime.timeline.getWindow(eventTime.windowIndex, Timeline.Window()).mediaItem

        if (playbackStats.totalPlayTimeMs >= (
                dataStore[HistoryDuration]?.times(1000f)
                    ?: 30000f
            ) &&
            !dataStore.get(PauseListenHistoryKey, false)
        ) {
            database.query {
                incrementTotalPlayTime(mediaItem.mediaId, playbackStats.totalPlayTimeMs)
                try {
                    insert(
                        Event(
                            songId = mediaItem.mediaId,
                            timestamp = LocalDateTime.now(),
                            playTime = playbackStats.totalPlayTimeMs,
                        ),
                    )
                } catch (_: SQLException) {
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val song = database.song(mediaItem.mediaId).first()
                        ?: return@launch

                    val lbEnabled = dataStore.get(ListenBrainzEnabledKey, false)
                    val lbToken = dataStore.get(ListenBrainzTokenKey, "")
                    if (lbEnabled && !lbToken.isNullOrBlank()) {
                        val endMs = System.currentTimeMillis()
                        val startMs = endMs - playbackStats.totalPlayTimeMs
                        try {
                            ListenBrainzManager.submitFinished(this@MusicService, lbToken, song, startMs, endMs)
                        } catch (ie: Exception) {
                            Timber.tag("MusicService").v(ie, "ListenBrainz finished submit failed")
                        }
                    }
                } catch (_: Exception) {
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                runCatching { registerRemoteListeningHistory(mediaItem.mediaId) }
            }
        }
    }

    private suspend fun registerRemoteListeningHistory(mediaId: String) {
        if (!isRemoteHistorySyncAllowed()) return

        val attemptedUrls = LinkedHashSet<String>()

        suspend fun registerTrackingUrl(url: String): Boolean {
            attemptedUrls += url
            return YouTube.registerPlayback(playbackTracking = url)
                .onFailure {
                    reportException(it)
                }.isSuccess
        }

        val cachedPlaybackUrl = database.format(mediaId).first()?.playbackUrl
        val cachedSuccess = cachedPlaybackUrl?.let { registerTrackingUrl(it) } == true
        if (cachedSuccess) return

        val playbackTracking =
            YTPlayerUtils.playerResponseForMetadata(mediaId, null)
                .getOrNull()
                ?.playbackTracking
                ?: return

        for (
            trackingUrl in listOfNotNull(
                playbackTracking.videostatsPlaybackUrl?.baseUrl,
                playbackTracking.videostatsWatchtimeUrl?.baseUrl,
            )
        ) {
            if (trackingUrl.isBlank() || trackingUrl in attemptedUrls) continue
            registerTrackingUrl(trackingUrl)
        }
    }

    private suspend fun isRemoteHistorySyncAllowed(): Boolean {
        if (!dataStore.getAsync(YtmSyncKey, true)) return false
        val cookie = dataStore.getAsync(InnerTubeCookieKey, "")
        return cookie.isNotBlank() && cookie.contains("SAPISID")
    }

    private fun createTransientSongFromMedia(media: com.nikhil.yt.models.MediaMetadata): Song {
        val songEntity = SongEntity(
            id = media.id,
            title = media.title,
            duration = media.duration,
            thumbnailUrl = media.thumbnailUrl,
            albumId = media.album?.id,
            albumName = media.album?.title,
            explicit = media.explicit,
        )

        val artists = media.artists.map { artist ->
            ArtistEntity(
                id = artist.id ?: "LA_unknown_${artist.name}",
                name = artist.name,
                thumbnailUrl = if (!artist.thumbnailUrl.isNullOrBlank()) artist.thumbnailUrl else media.thumbnailUrl,
            )
        }

        val album = media.album?.let { alb ->
            AlbumEntity(
                id = alb.id,
                playlistId = null,
                title = alb.title,
                year = null,
                thumbnailUrl = media.thumbnailUrl,
                themeColor = null,
                songCount = 1,
                duration = media.duration,
            )
        }

        return Song(
            song = songEntity,
            artists = artists,
            album = album,
            format = null,
        )
    }

    private inline fun <reified T> readPersistentObject(fileName: String): T? {
        val persistentFile = filesDir.resolve(fileName)
        if (!persistentFile.exists() || !persistentFile.isFile) return null

        return synchronized(persistentStateLock) {
            runCatching {
                persistentFile.inputStream().use { fis ->
                    ObjectInputStream(fis).use { input ->
                        input.readObject() as? T
                    }
                }
            }.onFailure {
                Timber.tag("MusicService").w(it, "Failed to read persistent file: $fileName")
                runCatching { persistentFile.delete() }
            }.getOrNull()
        }
    }

    private fun writePersistentObject(fileName: String, payload: Serializable) {
        val persistentFile = filesDir.resolve(fileName)
        val tempFile = filesDir.resolve("$fileName.tmp")

        synchronized(persistentStateLock) {
            runCatching {
                FileOutputStream(tempFile).use { fos ->
                    ObjectOutputStream(fos).use { output ->
                        output.writeObject(payload)
                        output.flush()
                        // Must sync before use{} closes the stream: syncing a closed fd threw, so no save ever succeeded.
                        fos.fd.sync()
                    }
                }

                // rename() atomically replaces the old file on Linux; deleting first could lose both on a crash.
                if (!tempFile.renameTo(persistentFile)) {
                    error("Could not atomically move $fileName")
                }
            }.onFailure {
                runCatching { tempFile.delete() }
                reportException(it)
            }
        }
    }

    private suspend fun saveQueueToDisk() {
        if (currentQueue == EmptyQueue) return

        val mediaItemsSnapshot = player.mediaItems.mapNotNull { it.metadata }
        if (mediaItemsSnapshot.isEmpty()) return

        val currentMediaItemIndex = player.currentMediaItemIndex
        val currentPosition = player.currentPosition
        val automixSnapshot = automixItems.value.mapNotNull { it.metadata }
        val playWhenReady = player.playWhenReady
        val repeatMode = player.repeatMode
        val shuffleModeEnabled = player.shuffleModeEnabled
        val volume = playerVolume.value
        val playbackState = player.playbackState

        withContext(Dispatchers.IO) {
            val persistQueue = currentQueue.toPersistQueue(
                title = queueTitle,
                items = mediaItemsSnapshot,
                mediaItemIndex = currentMediaItemIndex,
                position = currentPosition
            )
            
            val persistAutomix =
                PersistQueue(
                    title = "automix",
                    items = automixSnapshot,
                    mediaItemIndex = 0,
                    position = 0,
                )

            val persistPlayerState = PersistPlayerState(
                playWhenReady = playWhenReady,
                repeatMode = repeatMode,
                shuffleModeEnabled = shuffleModeEnabled,
                volume = volume,
                currentPosition = currentPosition,
                currentMediaItemIndex = currentMediaItemIndex, // Redundant but part of data class
                playbackState = playbackState
            )
            
            writePersistentObject(PERSISTENT_QUEUE_FILE, persistQueue)
            writePersistentObject(PERSISTENT_AUTOMIX_FILE, persistAutomix)
            writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, persistPlayerState)
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        unregisterBluetoothReceiver()
        runCatching { together.release() }
        runCatching { outputRouteWatcher?.stop() }
        try {
            DiscordPresenceManager.stop()
        } catch (_: Exception) {}
        try {
            discordRpc?.closeRPC()
        } catch (_: Exception) {}
        discordRpc = null
        try {
            connectivityObserver.unregister()
        } catch (_: Exception) {}
        abandonAudioFocus()
        try {
            audioEffects.destroy()
        } catch (_: Exception) {}
        WaveformStore.save(this)
        try {
            if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                val mediaItemsSnapshot = player.mediaItems.mapNotNull { it.metadata }
                val currentMediaItemIndex = player.currentMediaItemIndex
                val currentPosition = player.currentPosition
                val automixSnapshot = automixItems.value.mapNotNull { it.metadata }
                val repeatMode = player.repeatMode
                val shuffleModeEnabled = player.shuffleModeEnabled
                val volume = playerVolume.value
                val playbackState = player.playbackState
                val playWhenReady = player.playWhenReady
                runBlocking(Dispatchers.IO) {
                    val persistQueue = currentQueue.toPersistQueue(
                        title = queueTitle,
                        items = mediaItemsSnapshot,
                        mediaItemIndex = currentMediaItemIndex,
                        position = currentPosition
                    )
                    val persistAutomix = PersistQueue(
                        title = "automix",
                        items = automixSnapshot,
                        mediaItemIndex = 0,
                        position = 0,
                    )
                    val persistPlayerState = PersistPlayerState(
                        playWhenReady = playWhenReady,
                        repeatMode = repeatMode,
                        shuffleModeEnabled = shuffleModeEnabled,
                        volume = volume,
                        currentPosition = currentPosition,
                        currentMediaItemIndex = currentMediaItemIndex,
                        playbackState = playbackState
                    )

                    writePersistentObject(PERSISTENT_QUEUE_FILE, persistQueue)
                    writePersistentObject(PERSISTENT_AUTOMIX_FILE, persistAutomix)
                    writePersistentObject(PERSISTENT_PLAYER_STATE_FILE, persistPlayerState)
                }
            }
        } catch (_: Exception) {}
        try {
            mediaSession.release()
        } catch (_: Exception) {}
        try {
            crossfadeAudio?.release()
            crossfadeAudio = null
        } catch (_: Exception) {}
        try {
            player.removeListener(this)
            player.removeListener(sleepTimer)
            player.release()
        } catch (_: Exception) {}
        scopeJob.cancel()
    }

    override fun onBind(intent: Intent?): android.os.IBinder? {
        val result = super.onBind(intent) ?: binder
        if (player.mediaItemCount > 0 && player.currentMediaItem != null) {
            currentMediaMetadata.value = player.currentMetadata
            scope.launch {
                delay(50)
                updateNotification()
            }
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            scope.launch {
                try { discordRpc?.stopActivity() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        try {
            if (discordRpc?.isRpcRunning() == true) {
                try { discordRpc?.closeRPC() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        discordRpc = null
        try { DiscordPresenceManager.stop() } catch (_: Exception) {}
        lastPresenceToken = null

        val stopMusicOnTaskClearEnabled = dataStore.get(StopMusicOnTaskClearKey, false)

        try {
            val state = togetherSessionState.value
            val isHostSessionActive =
                state is com.nikhil.yt.together.TogetherSessionState.Hosting

            val isPlaybackInactive = player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0

            if (shouldStopServiceOnTaskRemoved(stopMusicOnTaskClearEnabled, isHostSessionActive, isPlaybackInactive)) {
                if (isHostSessionActive && isPlaybackInactive) {
                    runCatching { together.leave() }
                    stopSelf()
                    return
                }

                if (stopMusicOnTaskClearEnabled) {
                    if (dataStore.get(PersistentQueueKey, true) && player.mediaItemCount > 0) {
                        runBlocking { saveQueueToDisk() }
                    }
                    runCatching { stopAndClearPlayback() }
                    runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                        } else {
                            stopForeground(true)
                        }
                    }
                    stopSelf()
                    return
                }
            }
        } catch (_: Exception) {}
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureStartedAsForeground()

        when (intent?.action) {
            "com.nikhil.yt.ACTION_PREV" -> {
                if (player.hasPreviousMediaItem()) {
                    player.seekToPrevious()
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            "com.nikhil.yt.ACTION_NEXT" -> {
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                    player.prepare()
                    player.playWhenReady = true
                }
            }
            "com.nikhil.yt.ACTION_PLAY_PAUSE" -> {
                if (player.isPlaying) {
                    player.pause()
                } else {
                    player.play()
                }
            }
            "com.nikhil.yt.ACTION_REWIND" -> {
                // Jumps back exactly 10,000 milliseconds (10 seconds)
                val newPos = (player.currentPosition - 10000).coerceAtLeast(0)
                player.seekTo(newPos)
            }
            "com.nikhil.yt.ACTION_FORWARD" -> {
                // Jumps forward 10 seconds, but won't go past the end of the song
                val newPos = (player.currentPosition + 10000).coerceAtMost(player.duration)
                player.seekTo(newPos)
            }
        }

        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }


    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (startInForegroundRequired) ensureStartedAsForeground()
        runCatching { super.onUpdateNotification(session, startInForegroundRequired) }
            .onFailure { reportException(it) }
    }

    inner class MusicBinder : Binder() {
        val service: MusicService
            get() = this@MusicService
    }

    companion object {
        internal fun shouldStopServiceOnTaskRemoved(
            stopMusicOnTaskClearEnabled: Boolean,
            isHostSessionActive: Boolean,
            isPlaybackInactive: Boolean,
        ): Boolean = (isHostSessionActive && isPlaybackInactive) || stopMusicOnTaskClearEnabled

        const val ROOT = "root"
        const val SONG = "song"
        const val ARTIST = "artist"
        const val ALBUM = "album"
        const val PLAYLIST = "playlist"

        const val CHANNEL_ID = "music_channel_01"
        const val NOTIFICATION_ID = 888
        const val ERROR_CODE_NO_STREAM = 1000001
        const val CHUNK_LENGTH = 512 * 1024L
        const val PERSISTENT_QUEUE_FILE = "persistent_queue.data"
        const val PERSISTENT_AUTOMIX_FILE = "persistent_automix.data"
        const val PERSISTENT_PLAYER_STATE_FILE = "persistent_player_state.data"
        const val MAX_CONSECUTIVE_ERR = 5
        const val MIN_PRESENCE_UPDATE_INTERVAL = 20_000L
    }
}
