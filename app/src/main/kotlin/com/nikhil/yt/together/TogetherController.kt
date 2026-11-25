package com.nikhil.yt.together

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.nikhil.yt.MainActivity
import com.nikhil.yt.R
import com.nikhil.yt.constants.TogetherClientIdKey
import com.nikhil.yt.extensions.metadata
import com.nikhil.yt.extensions.toMediaItem
import com.nikhil.yt.models.MediaMetadata
import com.nikhil.yt.utils.getAsync
import com.nikhil.yt.utils.md5
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Listen Together, end to end: hosting, joining, keeping guests in sync, saved people and invites.
 * MusicService owns the player; this owns the session and talks to the player through the callbacks.
 */
class TogetherController(
    private val context: Context,
    private val dataStore: DataStore<Preferences>,
    private val player: () -> ExoPlayer,
    private val playNext: (List<MediaItem>) -> Unit,
    private val addToQueue: (List<MediaItem>) -> Unit,
    /** Replace the whole queue with the host's, as one change. */
    private val replaceQueue: (items: List<MediaItem>, startIndex: Int, positionMs: Long) -> Unit,
) {
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<TogetherSessionState>(TogetherSessionState.Idle)
    val state: StateFlow<TogetherSessionState> = _state.asStateFlow()

    private val _people = MutableStateFlow<List<SavedPerson>>(emptyList())
    val people: StateFlow<List<SavedPerson>> = _people.asStateFlow()

    private val _invite = MutableStateFlow<TogetherInvite?>(null)
    /** An invite waiting for the user's answer. */
    val invite: StateFlow<TogetherInvite?> = _invite.asStateFlow()

    private val _reactions = MutableSharedFlow<TogetherReaction>(extraBufferCapacity = 16)
    /** Emoji reactions to float up on the player, from anyone in the session (including you). */
    val reactions: SharedFlow<TogetherReaction> = _reactions.asSharedFlow()

    private val _recap = MutableStateFlow<TogetherRecap?>(null)
    /** The session that just ended, for the recap card. */
    val recap: StateFlow<TogetherRecap?> = _recap.asStateFlow()

    private val nsd = TogetherNsd(context)
    private var clientId: String = ""
    private var inbox: TogetherInbox? = null

    // Host side
    private var server: TogetherServer? = null
    private var relayHost: RelayHost? = null
    private var broadcastJob: Job? = null
    private var hostDisplayName = ""
    @Volatile private var suggestionList: List<TogetherSuggestion> = emptyList()
    private var log: SessionLog? = null

    /** What happened in this session, for the recap. */
    private class SessionLog {
        val startedAt = SystemClock.elapsedRealtime()
        val people = LinkedHashSet<String>()
        val tracks = LinkedHashMap<String, TogetherTrack>()
        val reactionsByTrack = HashMap<String, Int>()
        var reactions = 0
        var currentTrackId: String? = null

        fun saw(state: TogetherRoomState, selfId: String) {
            state.participants.filter { !it.isPending && it.id != selfId }.forEach { people += it.name }
            val track = state.queue.getOrNull(state.currentIndex) ?: return
            currentTrackId = track.id
            if (state.isPlaying) tracks.putIfAbsent(track.id, track)
        }

        fun reacted() {
            reactions++
            currentTrackId?.let { reactionsByTrack[it] = (reactionsByTrack[it] ?: 0) + 1 }
        }

        fun recap(): TogetherRecap? = tracks.takeIf { it.isNotEmpty() }?.let {
            TogetherRecap(
                people = people.toList(),
                tracks = it.values.toList(),
                reactions = reactions,
                topTrack = reactionsByTrack.maxByOrNull { e -> e.value }?.key?.let(it::get),
                minutes = ((SystemClock.elapsedRealtime() - startedAt) / 60_000L).toInt(),
            )
        }
    }

    // Guest side
    private var client: TogetherClient? = null
    private var clientEventsJob: Job? = null
    private var heartbeatJob: Job? = null
    private var clock: TogetherClock? = null
    private var selfParticipantId: String? = null
    private var lastAppliedSentAt = 0L
    private var applyingRemote = false
    private var suppressEchoUntil = 0L
    private var lastRemotePlayWhenReady: Boolean? = null
    private var lastRemoteIndex = -1
    private var lastSentAction: ControlAction? = null
    private var lastSentActionAt = 0L
    private var pendingControl: PendingControl? = null

    private var lastNoticeKey: String? = null
    private var lastNoticeAt = 0L

    private data class PendingControl(
        val desiredIsPlaying: Boolean? = null,
        val desiredIndex: Int? = null,
        val desiredTrackId: String? = null,
        val requestedAt: Long,
        val expiresAt: Long,
    )

    /** A guest whose player follows the host. A watcher (ghost mode) is not: their player stays their own. */
    val isGuest: Boolean
        get() = (_state.value as? TogetherSessionState.Joined)?.let { it.role is TogetherRole.Guest && !it.watching } == true

    /** True while a host state is being applied, so the player's own callbacks aren't echoed back. */
    val isApplyingRemote: Boolean get() = applyingRemote

    fun start() {
        ioScope.launch {
            clientId = loadClientId()
            val people = SavedPeopleCodec.decode(dataStore.getAsync(PeopleKey))
            _people.value = people
            inbox = TogetherInbox(ioScope, clientId).also { box ->
                box.listenFor(people)
                ioScope.launch { box.received.collect(::onInbox) }
            }
        }
    }

    fun release() {
        inbox?.stop()
        runBlocking { withTimeoutOrNull(1_500) { stopInternal() } }
        ioScope.cancel()
        mainScope.cancel()
    }

    // ---- Hosting ----

    fun startHosting(displayName: String, settings: TogetherRoomSettings, then: (suspend (code: String) -> Unit)? = null) {
        _state.value = TogetherSessionState.Starting
        ioScope.launch {
            stopInternal()
            val code = TogetherCode.generate()
            val secret = withContext(Dispatchers.Default) { TogetherSecret.fromCode(code) }
            val hostName = displayName.trim().ifBlank { context.getString(R.string.app_name) }
            val room = TogetherServer(ioScope, secret.roomId, secret.lanKey, ensureClientId(), hostName, settings)
            room.onEvent = { event -> ioScope.launch { onHostEvent(event, room) } }

            val port = room.startLan()
            port?.let { nsd.advertise(secret.roomId, it) }
            val relay = RelayHost(secret, ioScope) { connection -> room.accept(connection) }
            val online = relay.start(ensureClientId())
            if (port == null && !online) {
                room.stop()
                relay.stop()
                _state.value = TogetherSessionState.Error(context.getString(R.string.together_start_failed))
                return@launch
            }
            server = room
            relayHost = relay
            hostDisplayName = hostName
            suggestionList = emptyList()
            log = SessionLog()
            _state.value = TogetherSessionState.Hosting(code, secret.roomId, lan = port != null, online = online, settings = settings, roomState = null)
            broadcastJob = ioScope.launch { broadcastLoop(room, secret.roomId) }
            then?.invoke(code)
        }
    }

    /**
     * Sends room state when something a guest would notice changed, and at least every few seconds
     * as a heartbeat (online guests treat silence as the host leaving). Not every tick: the relay is shared.
     */
    private suspend fun broadcastLoop(room: TogetherServer, sessionId: String) {
        var lastSignature = ""
        var lastSentAt = 0L
        var lastSentPosition = 0L
        var lastSentPlaying = false
        while (server === room) {
            val state = buildRoomState(sessionId)
            val now = SystemClock.elapsedRealtime()
            val participants = room.currentParticipants()
            val settings = room.currentSettings()
            val signature = listOf(
                state.queueHash, state.currentIndex, state.isPlaying, state.repeatMode, state.shuffleEnabled,
                participants.joinToString { "${it.id}:${it.isPending}" }, settings,
                state.suggestions.joinToString { "${it.track.id}:${it.voterIds.size}" },
            ).joinToString("|")
            val expectedPosition = lastSentPosition + if (lastSentPlaying) now - lastSentAt else 0L
            val jumped = abs(state.positionMs - expectedPosition) > 1_500
            if (signature != lastSignature || jumped || now - lastSentAt > 3_000) {
                room.broadcastRoomState(state)
                lastSignature = signature
                lastSentAt = now
                lastSentPosition = state.positionMs
                lastSentPlaying = state.isPlaying
            }
            log?.saw(state.copy(participants = participants), HOST_ID)
            (_state.value as? TogetherSessionState.Hosting)?.takeIf { it.sessionId == sessionId }?.let { hosting ->
                _state.value = hosting.copy(settings = settings, roomState = state.copy(participants = participants, settings = settings))
            }
            delay(500)
        }
    }

    fun updateSettings(settings: TogetherRoomSettings) {
        val room = server ?: return
        ioScope.launch { room.updateSettings(settings) }
    }

    fun approve(participantId: String, approved: Boolean) {
        val room = server ?: return
        ioScope.launch { room.approveParticipant(participantId, approved) }
    }

    fun kick(participantId: String) {
        val room = server ?: return
        ioScope.launch { room.kickParticipant(participantId, null) }
    }

    fun ban(participantId: String) {
        val room = server ?: return
        ioScope.launch { room.banParticipant(participantId, null) }
    }

    private suspend fun onHostEvent(event: TogetherServerEvent, room: TogetherServer) {
        when (event) {
            is TogetherServerEvent.ControlRequested ->
                if (room.currentSettings().allowGuestsToControlPlayback) applyHostControl(event.request.action)
            is TogetherServerEvent.ReactionReceived -> receivedReaction(TogetherReaction(event.reaction.name, event.reaction.emoji))
            is TogetherServerEvent.SuggestionReceived ->
                addVote(event.suggestion.track, event.byName, event.suggestion.participantId, room)
            is TogetherServerEvent.VoteReceived ->
                suggestionList.firstOrNull { it.track.id == event.vote.trackId }?.let { addVote(it.track, it.byName, event.vote.participantId, room) }
            is TogetherServerEvent.AddTrackRequested ->
                if (!room.currentSettings().allowGuestsToAddTracks) {
                    // Older guests just send the song: treat it as a suggestion.
                    val name = room.currentParticipants().firstOrNull { it.id == event.request.participantId }?.name.orEmpty()
                    addVote(event.request.track, name, event.request.participantId, room)
                } else {
                    val item = event.request.track.toMediaMetadata().toMediaItem()
                    withContext(Dispatchers.Main) {
                        when (event.request.mode) {
                            AddTrackMode.PLAY_NEXT -> playNext(listOf(item))
                            AddTrackMode.ADD_TO_QUEUE -> addToQueue(listOf(item))
                        }
                    }
                }
            is TogetherServerEvent.JoinRequested ->
                notice(context.getString(R.string.together_wants_to_join, event.participant.name))
            is TogetherServerEvent.ParticipantJoined ->
                notice(context.getString(R.string.together_joined_notice, event.participant.name))
            is TogetherServerEvent.ParticipantLeft -> Unit
        }
    }

    private suspend fun applyHostControl(action: ControlAction) = withContext(Dispatchers.Main) {
        val player = player()
        when (action) {
            ControlAction.Play -> if (!player.playWhenReady) { player.prepare(); player.playWhenReady = true }
            ControlAction.Pause -> player.playWhenReady = false
            is ControlAction.SeekTo -> player.seekTo(action.positionMs.coerceAtLeast(0L))
            ControlAction.SkipNext -> if (player.hasNextMediaItem()) { player.seekToNext(); player.playWhenReady = true }
            ControlAction.SkipPrevious -> if (player.hasPreviousMediaItem()) { player.seekToPrevious(); player.playWhenReady = true }
            is ControlAction.SeekToTrack -> {
                val index = (0 until player.mediaItemCount).firstOrNull { i ->
                    val item = player.getMediaItemAt(i)
                    item.mediaId == action.trackId || item.metadata?.id == action.trackId
                }
                if (index != null) player.seekTo(index, action.positionMs.coerceAtLeast(0L))
            }
            is ControlAction.SeekToIndex ->
                if (action.index in 0 until player.mediaItemCount) player.seekTo(action.index, action.positionMs.coerceAtLeast(0L))
            is ControlAction.SetRepeatMode -> player.repeatMode = action.repeatMode
            is ControlAction.SetShuffleEnabled -> player.shuffleModeEnabled = action.shuffleEnabled
        }
        player.prepare()
    }

    private suspend fun buildRoomState(sessionId: String): TogetherRoomState = withContext(Dispatchers.Main) {
        val player = player()
        val tracks = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).metadata?.toTrack() }
        TogetherRoomState(
            sessionId = sessionId,
            hostId = HOST_ID,
            queue = tracks,
            queueHash = md5(tracks.joinToString("|") { it.id }),
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0),
            isPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            repeatMode = player.repeatMode,
            shuffleEnabled = player.shuffleModeEnabled,
            sentAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            suggestions = suggestionList,
        )
    }

    // ---- Reactions and suggestions ----

    fun react(emoji: String) {
        val room = server
        val joined = _state.value as? TogetherSessionState.Joined
        when {
            room != null -> ioScope.launch { room.broadcast(Reaction(room.sessionId, HOST_ID, hostDisplayName, emoji)) }
            joined != null && !joined.isPending -> client?.react(emoji)
            else -> return
        }
        receivedReaction(TogetherReaction(context.getString(R.string.together_you), emoji, mine = true))
    }

    private fun receivedReaction(reaction: TogetherReaction) {
        log?.reacted()
        _reactions.tryEmit(reaction)
    }

    /** Votes for a suggested song (the host's vote counts like anyone's). */
    fun vote(trackId: String) {
        val room = server
        if (room != null) {
            suggestionList.firstOrNull { it.track.id == trackId }?.let { ioScope.launch { addVote(it.track, it.byName, HOST_ID, room) } }
        } else {
            client?.vote(trackId)
        }
    }

    /** Host only: play a suggestion next now, or drop it. */
    fun decideSuggestion(trackId: String, accept: Boolean) {
        val suggestion = suggestionList.firstOrNull { it.track.id == trackId } ?: return
        suggestionList = suggestionList - suggestion
        if (accept) mainScope.launch { playNext(listOf(suggestion.track.toMediaMetadata().toMediaItem())) }
    }

    private suspend fun addVote(track: TogetherTrack, byName: String, voterId: String, room: TogetherServer) {
        val existing = suggestionList.firstOrNull { it.track.id == track.id }
        val updated = existing?.copy(voterIds = (existing.voterIds + voterId).distinct())
            ?: TogetherSuggestion(track, byName, listOf(voterId))
        val roomSize = room.currentParticipants().count { !it.isPending }.coerceAtLeast(1)
        if (updated.voterIds.size * 2 > roomSize) {
            suggestionList = suggestionList.filterNot { it.track.id == track.id }
            withContext(Dispatchers.Main) { playNext(listOf(track.toMediaMetadata().toMediaItem())) }
            notice(context.getString(R.string.together_vote_passed, track.title))
        } else {
            suggestionList = if (existing == null) suggestionList + updated else suggestionList.map { if (it === existing) updated else it }
        }
    }

    // ---- Joining ----

    /** Joins with a code, a pasted link or a scanned QR. There is no auto-reconnect: a new session needs a new join. */
    fun join(raw: String, displayName: String, watchOnly: Boolean = false) {
        val code = TogetherCode.parse(raw)
        if (code == null) {
            _state.value = TogetherSessionState.Error(context.getString(R.string.invalid_code))
            return
        }
        _state.value = TogetherSessionState.Joining(code)
        ioScope.launch {
            stopInternal()
            val secret = withContext(Dispatchers.Default) { TogetherSecret.fromCode(code) }
            val name = displayName.trim().ifBlank { context.getString(R.string.together_role_guest) }
            val guest = TogetherClient(ioScope, ensureClientId(), nsd)
            client = guest
            clock = TogetherClock()
            clientEventsJob = ioScope.launch { guest.events.collect { onClientEvent(it, secret.roomId) } }

            if (!guest.connect(secret, name, watchOnly)) {
                fail(context.getString(R.string.together_no_connection))
                return@launch
            }
            val welcome = guest.awaitWelcome()
            if (welcome == null && client === guest) {
                fail(guest.lastIssue ?: context.getString(R.string.together_no_session_found))
            }
        }
    }

    private suspend fun onClientEvent(event: TogetherClientEvent, sessionId: String) {
        when (event) {
            is TogetherClientEvent.Welcome -> {
                selfParticipantId = event.welcome.participantId
                _state.value = TogetherSessionState.Joined(
                    role = TogetherRole.Guest,
                    sessionId = sessionId,
                    selfParticipantId = event.welcome.participantId,
                    roomState = TogetherRoomState(sessionId = sessionId, hostId = HOST_ID, settings = event.welcome.settings),
                    hostName = event.welcome.hostName,
                    hostClientId = event.welcome.hostClientId,
                    overLan = event.overLan,
                    isPending = event.welcome.isPending,
                    watching = client?.watchOnly == true,
                )
                log = SessionLog()
                startHeartbeat()
            }
            is TogetherClientEvent.RoomState -> applyRemoteRoomState(event.state)
            is TogetherClientEvent.ReactionReceived -> receivedReaction(TogetherReaction(event.reaction.name, event.reaction.emoji))
            is TogetherClientEvent.JoinDecision ->
                if (event.decision.approved) {
                    (_state.value as? TogetherSessionState.Joined)?.let { _state.value = it.copy(isPending = false) }
                } else {
                    fail(context.getString(R.string.not_allowed))
                }
            is TogetherClientEvent.Removed -> fail(event.reason)
            is TogetherClientEvent.ServerIssue -> Unit // surfaced through awaitWelcome / lastIssue
            is TogetherClientEvent.HeartbeatPong -> clock?.onPong(
                sentAtElapsedMs = event.pong.clientElapsedRealtimeMs,
                receivedAtElapsedMs = event.receivedAtElapsedRealtimeMs,
                serverElapsedMs = event.pong.serverElapsedRealtimeMs,
            )
            TogetherClientEvent.Disconnected ->
                if (_state.value is TogetherSessionState.Joined) fail(context.getString(R.string.together_host_left_session))
        }
    }

    private fun startHeartbeat() {
        val guest = client ?: return
        heartbeatJob?.cancel()
        heartbeatJob = ioScope.launch {
            var pingId = 0L
            while (client === guest) {
                guest.sendHeartbeat(pingId++, SystemClock.elapsedRealtime())
                delay(2_000)
            }
        }
    }

    private suspend fun applyRemoteRoomState(state: TogetherRoomState) {
        val pid = selfParticipantId ?: return
        val now = SystemClock.elapsedRealtime()
        log?.saw(state, pid)
        (_state.value as? TogetherSessionState.Joined)?.takeIf { it.watching }?.let { watcher ->
            _state.value = watcher.copy(roomState = state) // ghost mode: follow along on screen only
            return
        }

        // While our own request is in flight, don't let an older host state undo it on screen.
        pendingControl?.let { pending ->
            val currentTrackId = state.queue.getOrNull(state.currentIndex)?.id
            val mismatch = (pending.desiredIsPlaying != null && state.isPlaying != pending.desiredIsPlaying) ||
                (pending.desiredIndex != null && state.currentIndex != pending.desiredIndex) ||
                (pending.desiredTrackId != null && currentTrackId != pending.desiredTrackId)
            if (now < pending.expiresAt && mismatch) return
            if (now >= pending.expiresAt && mismatch && (pending.desiredIndex != null || pending.desiredTrackId != null)) {
                notice(context.getString(R.string.together_song_change_failed))
            }
            pendingControl = null
        }

        val sentAt = state.sentAtElapsedRealtimeMs
        if (sentAt in 1..lastAppliedSentAt) return

        // Host clock -> our clock, then add the time the message spent travelling.
        val offset = clock?.snapshot()?.estimatedOffsetMs ?: 0L
        val travelled = (now - (sentAt - offset)).coerceIn(0L, 5_000L)
        val target = if (state.isPlaying) state.positionMs + travelled else state.positionMs

        withContext(Dispatchers.Main) {
            val player = player()
            applyingRemote = true
            suppressEchoUntil = SystemClock.elapsedRealtime() + 450L
            try {
                val localIds = (0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).let { it.metadata?.id ?: it.mediaId } }
                val localHash = if (localIds.isEmpty()) "" else md5(localIds.joinToString("|"))
                if (state.queue.isNotEmpty() && state.queueHash != localHash) {
                    val startIndex = state.currentIndex.coerceIn(0, state.queue.lastIndex)
                    replaceQueue(state.queue.map { it.toMediaMetadata().toMediaItem() }, startIndex, target)
                    player.repeatMode = state.repeatMode
                    player.shuffleModeEnabled = state.shuffleEnabled
                    player.playWhenReady = state.isPlaying
                    lastRemoteIndex = startIndex
                } else if (player.mediaItemCount > 0) {
                    val index = state.currentIndex.coerceIn(0, player.mediaItemCount - 1)
                    if (index != player.currentMediaItemIndex) {
                        player.seekTo(index, target)
                    } else {
                        val drift = abs(player.currentPosition - target)
                        if (drift > if (state.isPlaying) DRIFT_TOLERANCE_MS else 200L) player.seekTo(target)
                    }
                    if (player.repeatMode != state.repeatMode) player.repeatMode = state.repeatMode
                    if (player.shuffleModeEnabled != state.shuffleEnabled) player.shuffleModeEnabled = state.shuffleEnabled
                    if (player.playWhenReady != state.isPlaying) player.playWhenReady = state.isPlaying
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    lastRemoteIndex = index
                }
                lastRemotePlayWhenReady = state.isPlaying
                lastAppliedSentAt = sentAt
                val joined = _state.value as? TogetherSessionState.Joined
                if (joined != null && joined.selfParticipantId == pid) _state.value = joined.copy(roomState = state)
            } finally {
                applyingRemote = false
            }
        }
    }

    // ---- Guest requests (from the player's own controls) ----

    /** Asks the host to act. Returns false (and tells the user) when the host doesn't allow it. */
    fun requestControl(action: ControlAction): Boolean {
        val joined = _state.value as? TogetherSessionState.Joined ?: return false
        val guest = client ?: return false
        if (!joined.roomState.settings.allowGuestsToControlPlayback) {
            notice(context.getString(R.string.together_host_controls_playback), key = "control-disabled")
            ioScope.launch { applyRemoteRoomState(joined.roomState.copy(sentAtElapsedRealtimeMs = 0L)) }
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (action == lastSentAction && now - lastSentActionAt < 350L) return true
        lastSentAction = action
        lastSentActionAt = now
        val expires = now + if (joined.overLan) 2_000L else 5_000L
        pendingControl = when (action) {
            ControlAction.Play -> PendingControl(desiredIsPlaying = true, requestedAt = now, expiresAt = expires)
            ControlAction.Pause -> PendingControl(desiredIsPlaying = false, requestedAt = now, expiresAt = expires)
            is ControlAction.SeekToIndex -> PendingControl(desiredIndex = action.index, requestedAt = now, expiresAt = expires)
            is ControlAction.SeekToTrack -> PendingControl(desiredTrackId = action.trackId, requestedAt = now, expiresAt = expires)
            else -> pendingControl
        }
        guest.requestControl(action)
        return true
    }

    fun requestAddTracks(items: List<MediaItem>, mode: AddTrackMode) {
        val joined = _state.value as? TogetherSessionState.Joined ?: return
        val tracks = items.mapNotNull { it.metadata?.toTrack() }
        if (!joined.roomState.settings.allowGuestsToAddTracks) {
            tracks.forEach { client?.suggest(it) }
            notice(context.getString(R.string.together_suggested), key = "suggested")
            return
        }
        // Play-next inserts one after another right after the current song, so send them last-first.
        (if (mode == AddTrackMode.PLAY_NEXT) tracks.asReversed() else tracks).forEach { client?.requestAddTrack(it, mode) }
    }

    /** A guest picked a song to play now: jump to it if the host has it, else ask to add it first. */
    fun requestPlayNow(item: MediaItem, positionMs: Long, playWhenReady: Boolean) {
        val joined = _state.value as? TogetherSessionState.Joined ?: return
        val track = item.metadata?.toTrack() ?: return
        val ops = TogetherGuestPlaybackPlanner.planPlayTrackNow(joined.roomState, track, positionMs, playWhenReady)
        if (ops.isEmpty()) {
            notice(context.getString(R.string.together_host_controls_playback), key = "play-disabled")
            return
        }
        notice(context.getString(R.string.together_requesting_song_change), key = "play-request")
        ops.forEach { op ->
            when (op) {
                is TogetherGuestOp.Control -> requestControl(op.action)
                is TogetherGuestOp.AddTrack -> client?.requestAddTrack(op.track, op.mode)
            }
        }
    }

    /** Player callbacks: forward a guest's own seek / play-pause to the host, ignoring our own echoes. */
    fun onGuestSeekedTo(index: Int, trackId: String?, positionMs: Long) {
        if (applyingRemote || (SystemClock.elapsedRealtime() < suppressEchoUntil && lastRemoteIndex == index)) return
        requestControl(
            if (trackId.isNullOrBlank()) ControlAction.SeekToIndex(index, positionMs)
            else ControlAction.SeekToTrack(trackId, positionMs),
        )
    }

    fun onGuestPlayWhenReadyChanged(playWhenReady: Boolean) {
        if (applyingRemote || (SystemClock.elapsedRealtime() < suppressEchoUntil && lastRemotePlayWhenReady == playWhenReady)) return
        requestControl(if (playWhenReady) ControlAction.Play else ControlAction.Pause)
    }

    fun onGuestShuffleChanged(enabled: Boolean) {
        if (!applyingRemote) requestControl(ControlAction.SetShuffleEnabled(enabled))
    }

    // ---- Leaving ----

    fun leave() {
        _state.value = TogetherSessionState.Idle
        ioScope.launch { stopInternal() }
    }

    /** Clears an error or finished session back to the start screen. */
    fun dismissError() {
        if (_state.value is TogetherSessionState.Error) _state.value = TogetherSessionState.Idle
    }

    private suspend fun fail(message: String) {
        _state.value = TogetherSessionState.Error(message)
        stopInternal()
    }

    fun dismissRecap() {
        _recap.value = null
    }

    private suspend fun stopInternal() {
        log?.recap()?.let { _recap.value = it }
        log = null
        suggestionList = emptyList()
        broadcastJob?.cancel(); broadcastJob = null
        clientEventsJob?.cancel(); clientEventsJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        nsd.stopAdvertising()
        runCatching { client?.disconnect() }
        client = null
        runCatching { server?.stop() }
        server = null
        relayHost?.stop()
        relayHost = null
        clock = null
        selfParticipantId = null
        lastAppliedSentAt = 0L
        applyingRemote = false
        suppressEchoUntil = 0L
        lastRemotePlayWhenReady = null
        lastRemoteIndex = -1
        lastSentAction = null
        pendingControl = null
    }

    // ---- Saved people and invites ----

    fun savePerson(clientId: String, nickname: String, name: String) {
        if (clientId.isBlank() || clientId == this.clientId) return
        updatePeople { people ->
            people.filterNot { it.clientId == clientId } + SavedPerson(clientId, nickname.trim().ifBlank { name }, name)
        }
    }

    fun renamePerson(clientId: String, nickname: String) = updatePeople { people ->
        people.map { if (it.clientId == clientId) it.copy(nickname = nickname.trim().ifBlank { it.name }) else it }
    }

    fun forgetPerson(clientId: String) = updatePeople { people -> people.filterNot { it.clientId == clientId } }

    /** Invites a saved person, starting a session first if we're not hosting one. */
    fun invite(person: SavedPerson, displayName: String, settings: TogetherRoomSettings) {
        val hosting = _state.value as? TogetherSessionState.Hosting
        if (hosting != null) {
            ioScope.launch { sendInvite(person, hosting.code, displayName) }
        } else {
            startHosting(displayName, settings) { code -> sendInvite(person, code, displayName) }
        }
    }

    private suspend fun sendInvite(person: SavedPerson, code: String, displayName: String) {
        val sent = inbox?.send(person, InboxMessage.Invite(ensureClientId(), displayName.ifBlank { "Spectra" }, code)) == true
        notice(context.getString(if (sent) R.string.together_invite_sent else R.string.together_no_connection, person.nickname))
    }

    fun answerInvite(accept: Boolean, displayName: String) {
        val pending = _invite.value ?: return
        _invite.value = null
        NotificationManagerCompat.from(context).cancel(INVITE_NOTIFICATION_ID)
        ioScope.launch {
            inbox?.send(pending.person, InboxMessage.Reply(ensureClientId(), displayName.ifBlank { "Spectra" }, accept))
        }
        if (accept) join(pending.code, displayName)
    }

    private suspend fun onInbox(message: InboxMessage) {
        val person = _people.value.firstOrNull { it.clientId == message.fromClientId } ?: return
        when (message) {
            is InboxMessage.Invite -> {
                _invite.value = TogetherInvite(person, message.code)
                showInviteNotification(person)
            }
            is InboxMessage.Reply -> notice(
                context.getString(if (message.accepted) R.string.together_invite_accepted else R.string.together_invite_declined, person.nickname),
            )
        }
    }

    private fun showInviteNotification(person: SavedPerson) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(INVITE_CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(INVITE_CHANNEL, context.getString(R.string.together_invites), NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, INVITE_CHANNEL)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(context.getString(R.string.together_invite_title, person.nickname))
            .setContentText(context.getString(R.string.together_invite_body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(INVITE_NOTIFICATION_ID, notification) }
    }

    private fun updatePeople(change: (List<SavedPerson>) -> List<SavedPerson>) {
        val updated = change(_people.value)
        _people.value = updated
        inbox?.listenFor(updated)
        ioScope.launch { dataStore.edit { it[PeopleKey] = SavedPeopleCodec.encode(updated) } }
    }

    // ---- Helpers ----

    private suspend fun ensureClientId(): String = clientId.ifBlank { loadClientId().also { clientId = it } }

    private suspend fun loadClientId(): String {
        dataStore.getAsync(TogetherClientIdKey)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = java.util.UUID.randomUUID().toString()
        dataStore.edit { it[TogetherClientIdKey] = generated }
        return generated
    }

    fun notice(message: String, key: String? = null) {
        val now = SystemClock.elapsedRealtime()
        val normalized = key ?: message
        if (normalized == lastNoticeKey && now - lastNoticeAt < 1_200L) return
        lastNoticeKey = normalized
        lastNoticeAt = now
        mainScope.launch { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }

    private fun MediaMetadata.toTrack() = TogetherTrack(
        id = id,
        title = title,
        artists = artists.map { it.name },
        durationSec = duration,
        thumbnailUrl = thumbnailUrl,
    )

    private fun TogetherTrack.toMediaMetadata() = MediaMetadata(
        id = id,
        title = title,
        artists = artists.map { MediaMetadata.Artist(id = null, name = it) },
        duration = durationSec,
        thumbnailUrl = thumbnailUrl,
        album = null,
        setVideoId = null,
        explicit = false,
        liked = false,
        likedDate = null,
        inLibrary = null,
    )

    private companion object {
        const val HOST_ID = "host"
        const val DRIFT_TOLERANCE_MS = 1_500L
        const val INVITE_CHANNEL = "together_invites"
        const val INVITE_NOTIFICATION_ID = 0x5EC7
        val PeopleKey = stringPreferencesKey("together_saved_people")
    }
}
