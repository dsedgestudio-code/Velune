/*
 * Velune - by Nikhil
 * Spectra - Listen Together rebuild
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.together

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

sealed interface TogetherClientEvent {
    data class Welcome(val welcome: ServerWelcome, val overLan: Boolean) : TogetherClientEvent
    data class RoomState(val state: TogetherRoomState) : TogetherClientEvent
    data class JoinDecision(val decision: com.nikhil.yt.together.JoinDecision) : TogetherClientEvent
    data class Removed(val reason: String) : TogetherClientEvent
    data class ServerIssue(val message: String, val code: String? = null) : TogetherClientEvent
    data class HeartbeatPong(
        val pong: com.nikhil.yt.together.HeartbeatPong,
        val receivedAtElapsedRealtimeMs: Long,
    ) : TogetherClientEvent
    data object Disconnected : TogetherClientEvent
}

/** A guest's side of a session: finds the host (same Wi-Fi first, then online) and speaks the protocol. */
class TogetherClient(
    externalScope: CoroutineScope,
    private val clientId: String,
    private val nsd: TogetherNsd,
) {
    private val scope = CoroutineScope(externalScope.coroutineContext + SupervisorJob())
    private val _events = MutableSharedFlow<TogetherClientEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TogetherClientEvent> = _events.asSharedFlow()

    private var connection: TogetherConnection? = null
    private var loop: Job? = null
    private var sessionId: String? = null
    private var selfParticipantId: String? = null

    /** The host's last refusal (bad code, banned, old version), for the join error message. */
    @Volatile var lastIssue: String? = null
        private set

    /** False when no host answered on this network or online. */
    suspend fun connect(secret: TogetherSecret, displayName: String): Boolean {
        val lan = nsd.find(secret.roomId)?.let { (host, port) ->
            runCatching { WebSocketConnection(http.webSocketSession("ws://$host:$port/together"), scope) }.getOrNull()
        }
        val link = lan ?: connectRelayGuest(secret, clientId, scope) ?: return false
        connection = link
        sessionId = secret.roomId
        link.send(
            encode(
                ClientHello(
                    protocolVersion = TogetherProtocolVersion,
                    sessionId = secret.roomId,
                    sessionKey = secret.lanKey,
                    clientId = clientId,
                    displayName = displayName.trim().ifBlank { "Guest" },
                ),
            ),
        )
        loop = scope.launch { run(link, overLan = lan != null) }
        return true
    }

    private val welcome = CompletableDeferred<ServerWelcome?>()

    /** The host's welcome; null if nobody answered in time (no session with that code) or it refused. */
    suspend fun awaitWelcome(timeoutMs: Long = 12_000): ServerWelcome? = withTimeoutOrNull(timeoutMs) { welcome.await() }

    suspend fun disconnect() {
        val link = connection
        val pid = selfParticipantId
        val sid = sessionId
        if (link != null && pid != null && sid != null) runCatching { link.send(encode(ClientLeave(sessionId = sid, participantId = pid))) }
        runCatching { link?.close("Left") }
        loop?.cancel()
        connection = null
        selfParticipantId = null
        scope.cancel()
    }

    fun requestControl(action: ControlAction) = sendAsParticipant { sid, pid ->
        ControlRequest(sessionId = sid, participantId = pid, action = action)
    }

    fun requestAddTrack(track: TogetherTrack, mode: AddTrackMode) = sendAsParticipant { sid, pid ->
        AddTrackRequest(sessionId = sid, participantId = pid, track = track, mode = mode)
    }

    fun sendHeartbeat(pingId: Long, clientElapsedRealtimeMs: Long) {
        val sid = sessionId ?: return
        scope.launch { connection?.send(encode(HeartbeatPing(sessionId = sid, pingId = pingId, clientElapsedRealtimeMs = clientElapsedRealtimeMs))) }
    }

    private fun sendAsParticipant(build: (String, String) -> TogetherMessage) {
        val sid = sessionId ?: return
        val pid = selfParticipantId ?: return
        scope.launch { connection?.send(encode(build(sid, pid))) }
    }

    private suspend fun run(link: TogetherConnection, overLan: Boolean) {
        try {
            for (text in link.incoming) {
                val message = runCatching { TogetherJson.json.decodeFromString(TogetherMessage.serializer(), text) }.getOrNull() ?: continue
                when (message) {
                    is ServerWelcome -> {
                        selfParticipantId = message.participantId
                        welcome.complete(message)
                        _events.emit(TogetherClientEvent.Welcome(message, overLan))
                    }
                    is RoomStateMessage -> _events.emit(TogetherClientEvent.RoomState(message.state))
                    is com.nikhil.yt.together.JoinDecision ->
                        if (message.participantId == selfParticipantId) _events.emit(TogetherClientEvent.JoinDecision(message))
                    is KickParticipant ->
                        if (message.participantId == selfParticipantId) { _events.emit(TogetherClientEvent.Removed(message.reason ?: "Removed by the host")); break }
                    is BanParticipant ->
                        if (message.participantId == selfParticipantId) { _events.emit(TogetherClientEvent.Removed(message.reason ?: "Removed by the host")); break }
                    is HeartbeatPong -> _events.emit(TogetherClientEvent.HeartbeatPong(message, android.os.SystemClock.elapsedRealtime()))
                    is ServerError -> welcome.complete(null).also { lastIssue = message.message }.let { _events.emit(TogetherClientEvent.ServerIssue(message.message, message.code)) }
                    else -> Unit
                }
            }
        } finally {
            welcome.complete(null)
            _events.tryEmit(TogetherClientEvent.Disconnected)
        }
    }

    private fun encode(message: TogetherMessage): String =
        TogetherJson.json.encodeToString(TogetherMessage.serializer(), message)

    private companion object {
        val http by lazy {
            HttpClient(OkHttp) {
                engine { config { connectTimeout(4, TimeUnit.SECONDS); pingInterval(20, TimeUnit.SECONDS) } }
                install(WebSockets)
            }
        }
    }
}
