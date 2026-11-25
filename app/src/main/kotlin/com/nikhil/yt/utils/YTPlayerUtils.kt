/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */



package com.nikhil.yt.utils

import android.net.ConnectivityManager
import androidx.media3.common.PlaybackException
import com.nikhil.yt.constants.AudioQuality
import com.nikhil.yt.constants.PlayerStreamClient
import com.nikhil.yt.innertube.pages.NewPipeUtils
import com.nikhil.yt.innertube.YouTube
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import com.nikhil.yt.innertube.models.YouTubeClient
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.nikhil.yt.innertube.models.response.PlayerResponse
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_UNPLUGGED
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.IOS_MUSIC
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.MOBILE
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.TVHTML5
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.VISIONOS
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB
import com.nikhil.yt.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val FAILED_CLIENT_BACKOFF_MS = 10 * 60 * 1000L

    @Volatile private var streamClientPair: Pair<java.net.Proxy?, OkHttpClient>? = null

    private fun currentStreamClient(): OkHttpClient {
        val current = YouTube.streamProxy
        streamClientPair?.let { (proxy, client) ->
            if (proxy == current) return client
        }
        val client = OkHttpClient.Builder()
            .proxy(current)
            .build()
        streamClientPair = current to client
        return client
    }
    /**
     * The main client is used for metadata and initial streams.
     * Do not use other clients for this because it can result in inconsistent metadata.
     * For example other clients can have different normalization targets (loudnessDb).
     *
     * [com.nikhil.yt.innertube.models.YouTubeClient.WEB_REMIX] should be preferred here because currently it is the only client which provides:
     * - the correct metadata (like loudnessDb)
     * - premium formats
     */
    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX
    /**
     * Clients used for fallback streams in case the streams of the main client do not work.
     * Removed after a live probe (2026-11-24) failed them on every song regardless of session:
     * IOS / IPADOS / ANDROID_CREATOR (HTTP 4xx), ANDROID_TESTSUITE ("not available"),
     * TVHTML5_SIMPLY_EMBEDDED_PLAYER ("YouTube is no longer supported in this app").
     */
    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_NO_AUTH,
        ANDROID_MUSIC,
        MOBILE,
        ANDROID_VR_1_61_48,
        ANDROID_VR_1_43_32,
        ANDROID_UNPLUGGED,
        IOS_MUSIC,
        VISIONOS,
        TVHTML5,
        WEB,
        WEB_CREATOR,
        WEB_REMIX
    )

    /** How many upcoming clients' player responses are fetched in parallel while one is being validated. */
    private const val CLIENT_LOOKAHEAD = 2
    private data class CachedStreamUrl(
        val url: String,
        val expiresAtMs: Long,
    )

    private val streamUrlCache = ConcurrentHashMap<String, CachedStreamUrl>()
    private val failedStreamClientsUntil = ConcurrentHashMap<String, Long>()

    fun invalidateCachedStreamUrls(videoId: String) {
        val prefix = "$videoId:"
        streamUrlCache.keys.removeIf { it.startsWith(prefix) }
    }

    fun markStreamClientFailed(videoId: String, clientKey: String?, httpStatusCode: Int?) {
        if (httpStatusCode != 403 && httpStatusCode != 429) return
        val normalizedClientKey = normalizeStreamClientKey(clientKey)
        if (normalizedClientKey.isEmpty()) return
        failedStreamClientsUntil[normalizedClientKey] =
            System.currentTimeMillis() + FAILED_CLIENT_BACKOFF_MS
    }

    private fun isStreamClientTemporarilyBlocked(videoId: String, clientKey: String?): Boolean {
        val normalizedClientKey = normalizeStreamClientKey(clientKey)
        if (normalizedClientKey.isEmpty()) return false

        val until = failedStreamClientsUntil[normalizedClientKey] ?: return false
        if (until <= System.currentTimeMillis()) {
            failedStreamClientsUntil.remove(normalizedClientKey)
            return false
        }
        return true
    }


    fun markPreferredClientFailed(videoId: String, client: PlayerStreamClient, httpStatusCode: Int?) {
        markStreamClientFailed(videoId, client.name, httpStatusCode)
    }
    private fun normalizeStreamClientKey(clientKey: String?): String {
        return clientKey?.trim()?.takeIf { it.isNotBlank() }?.uppercase(Locale.US).orEmpty()
    }

    private fun buildFailedClientKey(videoId: String, clientKey: String): String {
        return "$videoId:${normalizeStreamClientKey(clientKey)}"
    }

    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient = PlayerStreamClient.ANDROID_VR,
        // if provided, this preference overrides ConnectivityManager.isActiveNetworkMetered
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
    ): Result<PlaybackData> = runCatching {
        val attempts =
            when (audioQuality) {
                AudioQuality.HIGHEST -> listOf(AudioQuality.HIGHEST, AudioQuality.HIGH)
                AudioQuality.AUTO -> listOf(AudioQuality.AUTO, AudioQuality.HIGH)
                else -> listOf(audioQuality)
            }.distinct()

        var lastError: Throwable? = null
        for (attempt in attempts) {
            val attemptResult =
                runCatching {
                    playerResponseForPlaybackOnce(
                        videoId = videoId,
                        playlistId = playlistId,
                        audioQuality = attempt,
                        connectivityManager = connectivityManager,
                        preferredStreamClient = preferredStreamClient,
                        networkMetered = networkMetered,
                        avoidCodecs = avoidCodecs,
                    )
                }
            if (attemptResult.isSuccess) return@runCatching attemptResult.getOrThrow()
            lastError = attemptResult.exceptionOrNull()
        }
        throw lastError ?: IllegalStateException("Failed to resolve stream")
    }

    private suspend fun playerResponseForPlaybackOnce(
        videoId: String,
        playlistId: String?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        preferredStreamClient: PlayerStreamClient,
        networkMetered: Boolean?,
        avoidCodecs: Set<String>,
    ): PlaybackData {
        Timber.tag(logTag).i("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).v("Signature timestamp: $signatureTimestamp")

        val isLoggedIn = YouTube.cookie != null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        Timber.tag(logTag).v("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"} (sessionId=${sessionId.orEmpty()})")

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null

        val orderedFallbackClients =
            (
                    if (isLoggedIn) {
                        STREAM_FALLBACK_CLIENTS.filter { it.loginSupported } + STREAM_FALLBACK_CLIENTS.filterNot { it.loginSupported }
                    } else {
                        STREAM_FALLBACK_CLIENTS.toList()
                    }
                    ).distinct()

        val preferredYouTubeClient =
            when (preferredStreamClient) {
                // Was aliased to IOS, but IOS 19.29.1 now gets HTTP 400 "Precondition check failed" on every request (tested 2026-11-23).
                PlayerStreamClient.ANDROID_VR -> ANDROID_VR_NO_AUTH
                PlayerStreamClient.WEB_REMIX -> WEB_REMIX
                PlayerStreamClient.IOS -> ANDROID_VR_NO_AUTH // IOS 19.29.1 is rejected (HTTP 400) on every request
                PlayerStreamClient.MOBILE -> ANDROID_MUSIC
                PlayerStreamClient.TVHTML5 -> TVHTML5 // the embedded-player variant is shut down by YouTube
                PlayerStreamClient.ANDROID_MUSIC -> ANDROID_MUSIC
            }

        val metadataClient =
            preferredYouTubeClient.takeIf { preferredStreamClient == PlayerStreamClient.ANDROID_VR } ?: MAIN_CLIENT

        Timber.tag(logTag).i("Fetching metadata response using client: ${metadataClient.clientName}")
        val metadataPlayerResponse = runCatching {
            YouTube.player(videoId, playlistId, metadataClient, signatureTimestamp).getOrThrow()}.getOrNull()
        val audioConfig = metadataPlayerResponse?.playerConfig?.audioConfig
        val videoDetails = metadataPlayerResponse?.videoDetails
        val playbackTracking = metadataPlayerResponse?.playbackTracking
        val expectedDurationMs = videoDetails?.lengthSeconds?.toLongOrNull()?.takeIf { it > 0 }?.times(1000L)

        val streamClients =
            buildList {
                add(preferredYouTubeClient)
                addAll(orderedFallbackClients)
                if (preferredYouTubeClient != MAIN_CLIENT) add(MAIN_CLIENT)
            }.distinct()
            .filter { it == MAIN_CLIENT || !it.loginRequired || isLoggedIn }
            .sortedBy { client ->
                // Deprioritize (not drop) recently failed clients: the block is global, so dropping them
                // could leave zero clients and fail every song until the backoff expires.
                val blocked = isStreamClientTemporarilyBlocked(videoId, client.clientName)
                if (blocked) {
                    Timber.tag(logTag).w("Temporarily deprioritized stream client for $videoId: ${client.clientName}")
                }
                blocked
            }

        val botDetectedClients = mutableSetOf<String>()

        // Each client's response is requested ahead of time (CLIENT_LOOKAHEAD), but still evaluated in priority order.
        val lookaheadScope = CoroutineScope(currentCoroutineContext() + SupervisorJob(currentCoroutineContext()[Job]))
        val responses = streamClients.map { client ->
            lookaheadScope.async(start = CoroutineStart.LAZY) {
                if (client == metadataClient) metadataPlayerResponse
                else YouTube.player(videoId, playlistId, client, signatureTimestamp).getOrNull()
            }
        }

        try {
        for ((index, client) in streamClients.withIndex()) {
            responses.subList(index, minOf(index + 1 + CLIENT_LOOKAHEAD, responses.size)).forEach { it.start() }
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            streamPlayerResponse = null

            Timber.tag(logTag).v(
                "Trying ${if (client == MAIN_CLIENT) "MAIN_CLIENT" else "fallback client"} ${index + 1}/${streamClients.size}: ${client.clientName}"
            )

            streamPlayerResponse = responses[index].await()

            if (streamPlayerResponse == null) continue

            if (streamPlayerResponse.playabilityStatus.status != "OK") {
                val reason = streamPlayerResponse.playabilityStatus.reason.orEmpty()
                val isBotDetection = isBotDetectionError(reason)
                Timber.tag(logTag).w(
                    "Player response status not OK: ${streamPlayerResponse.playabilityStatus.status}, reason: $reason, botDetection: $isBotDetection"
                )
                // No global backoff here: YouTube's bot check is per video (tested: same client + IP, one song OK,
                // the next "not a bot"), so penalizing the client would only slow down every other song.
                if (isBotDetection) botDetectedClients.add(client.clientName)
                continue
            }

            val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
            val candidates =
                selectAudioFormatCandidates(
                    streamPlayerResponse,
                    audioQuality,
                    isMetered,
                    avoidCodecs = avoidCodecs,
                )

            if (candidates.isEmpty()) continue

            var selectedFormat: PlayerResponse.StreamingData.Format? = null
            var selectedUrl: String? = null

            for (candidate in candidates.asSequence().take(6)) {
                if (isLoggedIn && expectedDurationMs != null && isLikelyPreview(candidate, expectedDurationMs)) continue
                val cacheKey = buildCacheKey(videoId, candidate.itag)
                val cached = streamUrlCache[cacheKey]
                val candidateUrl =
                    if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
                        cached.url
                    } else {
                        findUrlOrNull(candidate, videoId, client)
                    } ?: continue
                selectedFormat = candidate
                selectedUrl = candidateUrl
                break
            }

            if (selectedFormat == null || selectedUrl == null) continue

            format = selectedFormat
            streamUrl = selectedUrl
            streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds

            if (streamExpiresInSeconds == null) {
                streamPlayerResponse = null
                continue
            }

            Timber.tag(logTag).i("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")
            Timber.tag(logTag).v("Stream expires in: $streamExpiresInSeconds seconds")

            val valid = validateStatus(streamUrl, client.userAgent)
            if (valid) {
                Timber.tag(logTag).i("Stream validated successfully with client: ${client.clientName}")
                break
            }

            Timber.tag(logTag).w("Stream validation failed with client: ${client.clientName}, trying next fallback")
            format = null
            streamUrl = null
            streamExpiresInSeconds = null
            streamPlayerResponse = null
        }
        } finally {
            // Don't keep fetching (or wait for) clients we no longer need.
            lookaheadScope.cancel()
        }

        if (streamPlayerResponse == null) {
            if (botDetectedClients.isNotEmpty()) {
                Timber.tag(logTag).e("Bot detection triggered on clients: $botDetectedClients - all clients failed")
                throw PlaybackException(
                    "[E-2000] Bot detection — sign in or try again later (clients: ${botDetectedClients.joinToString()})",
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR
                )
            }
            Timber.tag(logTag).e("Bad stream player response - all clients failed for $videoId")
            throw PlaybackException(
                "[E-3001] No playable stream found — all ${streamClients.size} clients failed for this song",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            throw PlaybackException(
                "[E-3002] Blocked: ${errorReason ?: "unknown reason"}",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time for $videoId")
            throw PlaybackException(
                "[E-3003] Stream expired before playback could start",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (format == null) {
            val available = streamPlayerResponse.streamingData?.adaptiveFormats?.filter { it.isAudio }?.size ?: 0
            Timber.tag(logTag).e("Could not find suitable format for quality: $audioQuality. Available audio formats: $available")
            throw PlaybackException(
                "[E-3004] No compatible audio format (quality: $audioQuality, available: $available)",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url for format: ${format.mimeType}, itag: ${format.itag}")
            throw PlaybackException(
                "[E-3005] Stream URL unavailable (format: ${format.itag})",
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        Timber.tag(logTag).i("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")

        streamUrlCache[buildCacheKey(videoId, format.itag)] =
            CachedStreamUrl(
                url = streamUrl,
                expiresAtMs = System.currentTimeMillis() + (streamExpiresInSeconds * 1000L),
            )

        return PlaybackData(
            // Metadata client can fail (e.g. WEB_REMIX "page needs to be reloaded") - then take loudness etc. from
            // the stream response, or normalization silently stops working.
            audioConfig ?: streamPlayerResponse.playerConfig?.audioConfig,
            videoDetails ?: streamPlayerResponse.videoDetails,
            playbackTracking ?: streamPlayerResponse.playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).i("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = MAIN_CLIENT)
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        // optional override from user preference; if non-null, use this instead of ConnectivityManager
        networkMetered: Boolean? = null,
        avoidCodecs: Set<String> = emptySet(),
    ): PlayerResponse.StreamingData.Format? {
        val isMetered = networkMetered ?: connectivityManager.isActiveNetworkMetered
        return selectAudioFormatCandidates(
            playerResponse,
            audioQuality,
            isMetered,
            avoidCodecs = avoidCodecs,
        ).firstOrNull()
    }

    internal fun selectAudioFormatCandidates(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        networkMetered: Boolean,
        avoidCodecs: Set<String> = emptySet(),
    ): List<PlayerResponse.StreamingData.Format> {
        Timber.tag(logTag).i("Finding format with audioQuality: $audioQuality, network metered: $networkMetered")

        val audioFormats =
            playerResponse.streamingData?.adaptiveFormats
                ?.asSequence()
                ?.filter { it.isAudio && it.bitrate > 0 }
                ?.filter { it.url != null || it.signatureCipher != null || it.cipher != null }
                ?.filter { format ->
                    val codec = extractCodec(format.mimeType)?.lowercase()
                    codec == null || codec !in avoidCodecs
                }
                ?.toList()
                .orEmpty()

        if (audioFormats.isEmpty()) return emptyList()

        val effectiveQuality =
            when (audioQuality) {
                AudioQuality.AUTO -> if (networkMetered) AudioQuality.HIGH else AudioQuality.HIGHEST
                else -> audioQuality
            }

        val targetBitrateBps =
            when (effectiveQuality) {
                AudioQuality.LOW -> 70_000
                AudioQuality.HIGH -> 160_000
                AudioQuality.HIGHEST -> 320_000
                AudioQuality.AUTO -> null
            }

        val preferHigher =
            compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                .thenByDescending { it.bitrate }
                .thenByDescending { codecRank(extractCodec(it.mimeType)) }
                .thenByDescending { it.audioSampleRate ?: 0 }

        val preferLowerAboveTarget =
            compareByDescending<PlayerResponse.StreamingData.Format> { it.url != null }
                .thenBy { it.bitrate }
                .thenByDescending { codecRank(extractCodec(it.mimeType)) }
                .thenByDescending { it.audioSampleRate ?: 0 }

        val candidates =
            if (targetBitrateBps == null) {
                audioFormats.sortedWith(preferHigher)
            } else {
                val belowOrEqual = audioFormats.filter { it.bitrate <= targetBitrateBps }
                if (belowOrEqual.isNotEmpty()) {
                    belowOrEqual.sortedWith(preferHigher)
                } else {
                    val aboveOrEqual = audioFormats.filter { it.bitrate >= targetBitrateBps }
                    if (aboveOrEqual.isNotEmpty()) aboveOrEqual.sortedWith(preferLowerAboveTarget)
                    else audioFormats.sortedWith(preferHigher)
                }
            }

        Timber.tag(logTag)
            .v(
                "Available audio formats: ${
                    candidates.take(12).map {
                        val codec = extractCodec(it.mimeType)
                        val direct = if (it.url != null) "direct" else "cipher"
                        "${it.mimeType} ($direct, codec=${codec ?: "unknown"}) @ ${it.bitrate}bps"
                    }
                }"
            )

        return candidates
    }

    private fun extractCodec(mimeType: String): String? {
        val match = Regex("""codecs="([^"]+)"""").find(mimeType) ?: return null
        return match.groupValues.getOrNull(1)?.split(",")?.firstOrNull()?.trim()
    }

    private fun codecRank(codec: String?): Int =
        when {
            codec.isNullOrBlank() -> 0
            codec.contains("opus", ignoreCase = true) -> 3
            codec.contains("mp4a", ignoreCase = true) -> 2
            else -> 1
        }
    private fun isLikelyPreview(
        format: PlayerResponse.StreamingData.Format,
        expectedDurationMs: Long,
    ): Boolean {
        val approx = format.approxDurationMs?.toLongOrNull() ?: return false
        if (expectedDurationMs < 90_000L) return false
        return approx in 1L..(minOf(90_000L, (expectedDurationMs * 9L) / 10L))
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String, userAgent: String): Boolean {
        Timber.tag(logTag).v("Validating stream URL status")
        try {
            val httpUrl = url.toHttpUrlOrNull()
            val clientParam = httpUrl?.queryParameter("c")?.trim().orEmpty()

            val resolvedUserAgent = StreamClientUtils.resolveUserAgent(clientParam).ifEmpty { userAgent }
            val originReferer = StreamClientUtils.resolveOriginReferer(clientParam)

            val probeRanges =
                if (StreamClientUtils.isWebClient(clientParam)) {
                    listOf("bytes=0-0", "bytes=262144-262145", "bytes=1048576-1048577")
                } else {
                    listOf("bytes=0-0")
                }

            for (range in probeRanges) {
                val rangeRequest =
                    okhttp3.Request.Builder()
                        .get()
                        .header("User-Agent", resolvedUserAgent)
                        .header("Range", range)
                        .apply {
                            originReferer.origin?.let { header("Origin", it) }
                            originReferer.referer?.let { header("Referer", it) }
                        }.url(url)
                        .build()

                val code = currentStreamClient().newCall(rangeRequest).execute().use { response -> response.code }
                if (code == 403) return false
                if (code !in 200..399 && code != 416) return false
            }

            return true
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    /**
     * Wrapper around the [NewPipeUtils.getSignatureTimestamp] function which reports exceptions
     */
    private fun getSignatureTimestampOrNull(
        videoId: String
    ): Int? {
        Timber.tag(logTag).i("Getting signature timestamp for videoId: $videoId")
        return NewPipeUtils.getSignatureTimestamp(videoId)
            .onSuccess { Timber.tag(logTag).i("Signature timestamp obtained: $it") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get signature timestamp")
                reportException(it)
            }
            .getOrNull()
    }
    /**
     * Wrapper around the [NewPipeUtils.getStreamUrl] function which reports exceptions.
     * Also patches cver to match the client version.
     */
    private fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        client: YouTubeClient? = null,
    ): String? {
        Timber.tag(logTag).i("Finding stream URL for format: ${format.mimeType}, videoId: $videoId")
        var url = NewPipeUtils.getStreamUrl(format, videoId, client)
            .onSuccess { Timber.tag(logTag).i("Stream URL obtained successfully") }
            .onFailure {
                Timber.tag(logTag).e(it, "Failed to get stream URL")
                reportException(it)
            }
            .getOrNull() ?: return null

        // Patch cver in the URL to match the client we actually used
        if (client != null) {
            url = StreamClientUtils.patchClientVersion(url, client.clientVersion)
        }

        return url
    }

    private fun buildCacheKey(videoId: String, itag: Int): String {
        return "$videoId:$itag"
    }

    private fun isBotDetectionError(reason: String): Boolean {
        val lower = reason.lowercase(Locale.US)
        // No bare "sign in": it also matches per-video reasons like "Sign in to confirm your age",
        // which would wrongly block the client for every song.
        return "bot" in lower ||
                "confirm" in lower && "not a" in lower ||
                "verify" in lower && "human" in lower
    }

    fun isBotDetectionException(error: PlaybackException): Boolean {
        val message = error.message.orEmpty()
        if (isBotDetectionError(message)) return true
        var cause: Throwable? = error.cause
        while (cause != null) {
            if (isBotDetectionError(cause.message.orEmpty())) return true
            cause = cause.cause
        }
        return false
    }
}
