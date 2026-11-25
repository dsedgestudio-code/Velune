/*
 * Velune - by Nikhil
 * Spectra - Listen Together rebuild
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.together

import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface TogetherServerEvent {
    data class JoinRequested(val participant: TogetherParticipant) : TogetherServerEvent
    data class ParticipantJoined(val participant: TogetherParticipant) : TogetherServerEvent
    data class ParticipantLeft(val participantId: String, val reason: String?) : TogetherServerEvent
    data class ControlRequested(val request: ControlRequest) : TogetherServerEvent
    data class AddTrackRequested(val request: AddTrackRequest) : TogetherServerEvent
    data class ReactionReceived(val reaction: Reaction) : TogetherServerEvent
    data class SuggestionReceived(val suggestion: SuggestTrack, val byName: String) : TogetherServerEvent
    data class VoteReceived(val vote: VoteSuggestion) : TogetherServerEvent
}

/**
 * The room, run on the host's phone. Guests reach it over any [TogetherConnection]: the LAN WebSocket
 * started by [startLan], or the online relay (handed to [accept]). The protocol is the same on both.
 */
class TogetherServer(
    private val scope: CoroutineScope,
    val sessionId: String,
    private val sessionKey: String,
    private val hostClientId: String,
    private val hostDisplayName: String,
    initialSettings: TogetherRoomSettings,
) {
    private val mutex = Mutex()
    private var settings: TogetherRoomSettings = initialSettings
    private var engine: EmbeddedServer<*, *>? = null
    private val bannedClientIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var lastParticipants: List<TogetherParticipant> = emptyList()

    private class Guest(
        val participantId: String,
        val clientId: String,
        val name: String,
        val connection: TogetherConnection,
        @Volatile var pending: Boolean,
        val watching: Boolean = false,
    )

    private val guests = ConcurrentHashMap<String, Guest>()

    var onEvent: ((TogetherServerEvent) -> Unit)? = null

    fun currentParticipants(): List<TogetherParticipant> = lastParticipants

    suspend fun currentSettings(): TogetherRoomSettings = mutex.withLock { settings }

    /** Starts the LAN endpoint on a free port and returns it, or null if the network refused. */
    suspend fun startLan(): Int? = mutex.withLock {
        engine?.let { return@withLock it.engine.resolvedConnectors().firstOrNull()?.port }
        runCatching {
            val server = embeddedServer(CIO, port = 0, host = "0.0.0.0") {
                install(WebSockets)
                routing {
                    webSocket("/together") { accept(WebSocketConnection(this, this)) }
                }
            }.also { it.start(wait = false) }
            engine = server
            server.engine.resolvedConnectors().firstOrNull()?.port
        }.getOrNull()
    }

    suspend fun stop() {
        val eng = mutex.withLock { engine.also { engine = null } }
        guests.values.forEach { runCatching { it.connection.close("Session ended") } }
        guests.clear()
        eng?.let { runCatching { it.stop(500, 1500) } }
    }

    suspend fun updateSettings(newSettings: TogetherRoomSettings) {
        mutex.withLock { settings = newSettings }
    }

    suspend fun approveParticipant(participantId: String, approved: Boolean) {
        val guest = guests[participantId] ?: return
        if (!guest.pending) return
        guest.connection.send(encode(JoinDecision(sessionId = sessionId, participantId = participantId, approved = approved)))
        if (!approved) {
            remove(guest, "Not approved")
            return
        }
        guest.pending = false
        onEvent?.invoke(TogetherServerEvent.ParticipantJoined(guest.toParticipant()))
    }

    suspend fun kickParticipant(participantId: String, reason: String?) {
        val guest = guests[participantId] ?: return
        guest.connection.send(encode(KickParticipant(sessionId = sessionId, participantId = participantId, reason = reason)))
        remove(guest, reason ?: "Removed")
    }

    suspend fun banParticipant(participantId: String, reason: String?) {
        val guest = guests[participantId] ?: return
        bannedClientIds += guest.clientId
        guest.connection.send(encode(BanParticipant(sessionId = sessionId, participantId = participantId, reason = reason)))
        remove(guest, reason ?: "Banned")
    }

    suspend fun broadcastRoomState(state: TogetherRoomState) {
        val snapshotSettings = mutex.withLock { settings }
        val participantList = buildList {
            add(TogetherParticipant(id = state.hostId, name = hostDisplayName, isHost = true, clientId = hostClientId))
            addAll(guests.values.sortedBy { it.name.lowercase() }.map { it.toParticipant() })
        }
        lastParticipants = participantList
        val full = state.copy(participants = participantList, settings = snapshotSettings)
        // Someone waiting for approval sees who is here, but not what is playing.
        val waiting = full.copy(queue = emptyList(), queueHash = "", currentIndex = 0, isPlaying = false, positionMs = 0L)
        val fullText = encode(RoomStateMessage(full))
        val waitingText = encode(RoomStateMessage(waiting))
        guests.values.forEach { it.connection.send(if (it.pending) waitingText else fullText) }
    }

    /** Runs one guest from hello to goodbye. Returns when the link ends. */
    suspend fun accept(connection: TogetherConnection) {
        val hello = withTimeoutOrNull(10_000) { connection.incoming.receiveCatching().getOrNull() }
            ?.let { runCatching { TogetherJson.json.decodeFromString(TogetherMessage.serializer(), it) }.getOrNull() }
            as? ClientHello
        val refusal = when {
            hello == null -> "Handshake required"
            hello.protocolVersion != TogetherProtocolVersion -> "Update Spectra to join this session"
            hello.sessionId != sessionId || hello.sessionKey != sessionKey -> "Invalid session"
            hello.clientId in bannedClientIds -> "You can't join this session"
            hello.watchOnly && !mutex.withLock { settings.allowWatchers } -> "This host doesn't allow watching"
            else -> null
        }
        if (refusal != null) {
            connection.send(encode(ServerError(sessionId = hello?.sessionId, message = refusal)))
            connection.close(refusal)
            return
        }
        hello!!

        // A rejoin from the same install replaces its old, probably dead, link.
        guests.values.filter { it.clientId == hello.clientId }.forEach { remove(it, "Reconnected") }

        val currentSettings = mutex.withLock { settings }
        val guest = Guest(
            participantId = UUID.randomUUID().toString(),
            clientId = hello.clientId,
            name = hello.displayName.trim().ifBlank { "Guest" }.take(40),
            connection = connection,
            pending = currentSettings.requireHostApprovalToJoin,
            watching = hello.watchOnly,
        )
        guests[guest.participantId] = guest
        connection.send(
            encode(
                ServerWelcome(
                    protocolVersion = TogetherProtocolVersion,
                    sessionId = sessionId,
                    participantId = guest.participantId,
                    role = ServerRole.GUEST,
                    isPending = guest.pending,
                    settings = currentSettings,
                    hostClientId = hostClientId,
                    hostName = hostDisplayName,
                ),
            ),
        )
        onEvent?.invoke(
            if (guest.pending) TogetherServerEvent.JoinRequested(guest.toParticipant())
            else TogetherServerEvent.ParticipantJoined(guest.toParticipant()),
        )

        try {
            for (text in connection.incoming) {
                val message = runCatching { TogetherJson.json.decodeFromString(TogetherMessage.serializer(), text) }.getOrNull() ?: continue
                when (message) {
                    is HeartbeatPing -> connection.send(
                        encode(
                            HeartbeatPong(
                                sessionId = sessionId,
                                pingId = message.pingId,
                                clientElapsedRealtimeMs = message.clientElapsedRealtimeMs,
                                serverElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime(),
                            ),
                        ),
                    )
                    is ControlRequest -> if (!guest.pending) onEvent?.invoke(TogetherServerEvent.ControlRequested(message.copy(participantId = guest.participantId)))
                    is AddTrackRequest -> if (!guest.pending) onEvent?.invoke(TogetherServerEvent.AddTrackRequested(message.copy(participantId = guest.participantId)))
                    is Reaction -> if (!guest.pending) {
                        val named = message.copy(participantId = guest.participantId, name = guest.name, emoji = message.emoji.take(8))
                        broadcast(named, except = guest.participantId)
                        onEvent?.invoke(TogetherServerEvent.ReactionReceived(named))
                    }
                    is SuggestTrack -> if (!guest.pending) onEvent?.invoke(TogetherServerEvent.SuggestionReceived(message.copy(participantId = guest.participantId), guest.name))
                    is VoteSuggestion -> if (!guest.pending) onEvent?.invoke(TogetherServerEvent.VoteReceived(message.copy(participantId = guest.participantId)))
                    is ClientLeave -> break
                    else -> Unit
                }
            }
        } finally {
            if (guests.remove(guest.participantId) != null) {
                onEvent?.invoke(TogetherServerEvent.ParticipantLeft(guest.participantId, "Left"))
            }
            runCatching { connection.close("Left") }
        }
    }

    /** Sends to everyone admitted to the room (not those still waiting for approval). */
    suspend fun broadcast(message: TogetherMessage, except: String? = null) {
        val text = encode(message)
        guests.values.filter { !it.pending && it.participantId != except }.forEach { runCatching { it.connection.send(text) } }
    }

    private suspend fun remove(guest: Guest, reason: String) {
        if (guests.remove(guest.participantId) == null) return
        runCatching { guest.connection.close(reason) }
        onEvent?.invoke(TogetherServerEvent.ParticipantLeft(guest.participantId, reason))
    }

    private fun Guest.toParticipant() = TogetherParticipant(
        id = participantId,
        name = name,
        isHost = false,
        isPending = pending,
        clientId = clientId,
        watching = watching,
    )

    private fun encode(message: TogetherMessage): String =
        TogetherJson.json.encodeToString(TogetherMessage.serializer(), message)
}
