package com.nikhil.yt.together

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** One guest-to-host link. The room logic is the same whether this runs over LAN or the online relay. */
interface TogetherConnection {
    /** Text messages from the other side; closes when the link ends. */
    val incoming: ReceiveChannel<String>
    suspend fun send(text: String)
    suspend fun close(reason: String)
}

/** LAN: a WebSocket on the local network (host runs the server, guest connects to it). */
class WebSocketConnection(
    private val session: WebSocketSession,
    scope: CoroutineScope,
) : TogetherConnection {
    private val channel = Channel<String>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<String> = channel

    init {
        scope.launch {
            try {
                for (frame in session.incoming) (frame as? Frame.Text)?.let { channel.send(it.readText()) }
            } finally {
                channel.close()
            }
        }
    }

    override suspend fun send(text: String) {
        runCatching { session.send(Frame.Text(text)) }
    }

    override suspend fun close(reason: String) {
        runCatching { session.close(CloseReason(CloseReason.Codes.NORMAL, reason)) }
        channel.close()
    }
}

/** What travels inside the relay's encryption. [bye] ends the link; [peer] identifies a guest to the host. */
@Serializable
internal data class RelayEnvelope(
    val peer: String,
    val text: String? = null,
    val bye: String? = null,
)

internal object RelayTopics {
    fun host(roomId: String) = "spectra/v1/$roomId/h"
    fun peer(roomId: String, peer: String) = "spectra/v1/$roomId/p/$peer"
    fun inbox(inboxId: String) = "spectra/v1/inbox/$inboxId"
}

/**
 * Online, one end of a guest link carried over an [MqttRelay]. Every message is sealed with the session
 * key. A relay has no connection state, so silence is the signal: [IDLE_TIMEOUT_MS] without a message
 * (guests ping every 2 s, the host sends room state every few seconds) closes the link.
 */
internal class RelayConnection(
    private val relay: MqttRelay,
    private val secret: TogetherSecret,
    private val peer: String,
    private val outTopic: String,
    scope: CoroutineScope,
    private val onClosed: () -> Unit = {},
) : TogetherConnection {
    private val channel = Channel<String>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<String> = channel

    @Volatile private var lastHeardAt = System.currentTimeMillis()
    @Volatile private var open = true
    private val watchdog: Job = scope.launch {
        while (open) {
            delay(2_000)
            if (System.currentTimeMillis() - lastHeardAt > IDLE_TIMEOUT_MS) finish()
        }
    }

    init {
        scope.launch {
            relay.closed.await()
            finish()
        }
    }

    fun deliver(envelope: RelayEnvelope) {
        lastHeardAt = System.currentTimeMillis()
        envelope.text?.let { channel.trySend(it) }
        if (envelope.bye != null) finish()
    }

    override suspend fun send(text: String) {
        if (open) relay.publish(outTopic, seal(RelayEnvelope(peer = peer, text = text)))
    }

    override suspend fun close(reason: String) {
        if (open) relay.publish(outTopic, seal(RelayEnvelope(peer = peer, bye = reason)))
        finish()
    }

    private fun finish() {
        if (!open) return
        open = false
        watchdog.cancel()
        channel.close()
        onClosed()
    }

    private fun seal(envelope: RelayEnvelope): ByteArray =
        secret.seal(TogetherJson.json.encodeToString(RelayEnvelope.serializer(), envelope).toByteArray())

    companion object {
        const val IDLE_TIMEOUT_MS = 15_000L

        fun newPeerId(): String = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }

        fun openEnvelope(secret: TogetherSecret, payload: ByteArray): RelayEnvelope? =
            secret.open(payload)?.let { plain ->
                runCatching { TogetherJson.json.decodeFromString(RelayEnvelope.serializer(), String(plain)) }.getOrNull()
            }
    }
}

/**
 * The host's side of the relay: listens on every broker it could reach and turns each new guest
 * into a [TogetherConnection] handed to [onGuest]. Replies go back over the broker the guest used.
 */
internal class RelayHost(
    private val secret: TogetherSecret,
    private val scope: CoroutineScope,
    private val onGuest: suspend (TogetherConnection) -> Unit,
) {
    private val relays = mutableListOf<MqttRelay>()
    private val guests = ConcurrentHashMap<String, RelayConnection>()

    /** True if at least one broker is reachable. */
    suspend fun start(clientId: String): Boolean {
        for (url in MqttRelay.BROKERS) {
            val relay = MqttRelay(url, scope)
            val ok = runCatching { relay.connect("$clientId-h-${RelayConnection.newPeerId().take(6)}") }.isSuccess
            if (!ok) continue
            relay.subscribe(RelayTopics.host(secret.roomId))
            relays += relay
            relay.onMessage { message ->
                    val envelope = RelayConnection.openEnvelope(secret, message.payload) ?: return@onMessage
                    val existing = guests[envelope.peer]
                    if (existing != null) {
                        existing.deliver(envelope)
                    } else if (envelope.bye == null) {
                        val connection = RelayConnection(
                            relay = relay,
                            secret = secret,
                            peer = envelope.peer,
                            outTopic = RelayTopics.peer(secret.roomId, envelope.peer),
                            scope = scope,
                            onClosed = { guests.remove(envelope.peer) },
                        )
                        guests[envelope.peer] = connection
                        connection.deliver(envelope)
                        scope.launch { onGuest(connection) }
                    }
            }
        }
        return relays.isNotEmpty()
    }

    fun stop() {
        relays.forEach { it.close() }
        relays.clear()
        guests.clear()
    }
}

/** A guest's link to the host over the relay. Tries each broker until one connects. */
internal suspend fun connectRelayGuest(
    secret: TogetherSecret,
    clientId: String,
    scope: CoroutineScope,
): TogetherConnection? {
    val peer = RelayConnection.newPeerId()
    for (url in MqttRelay.BROKERS) {
        val relay = MqttRelay(url, scope)
        if (runCatching { relay.connect("$clientId-g-$peer") }.isFailure) continue
        relay.subscribe(RelayTopics.peer(secret.roomId, peer))
        val connection = RelayConnection(
            relay = relay,
            secret = secret,
            peer = peer,
            outTopic = RelayTopics.host(secret.roomId),
            scope = scope,
            onClosed = { relay.close() },
        )
        relay.onMessage { message ->
            RelayConnection.openEnvelope(secret, message.payload)?.let(connection::deliver)
        }
        return connection
    }
    return null
}
