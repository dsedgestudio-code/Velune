package com.nikhil.yt.together

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** Someone you listened with and chose to keep, under the name you gave them ("sweetheart"). */
@Serializable
data class SavedPerson(
    val clientId: String,
    val nickname: String,
    /** The name they used themselves, shown under the nickname. */
    val name: String = "",
)

object SavedPeopleCodec {
    private val serializer = ListSerializer(SavedPerson.serializer())
    fun decode(raw: String?): List<SavedPerson> =
        raw?.takeIf { it.isNotBlank() }?.let { runCatching { TogetherJson.json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    fun encode(people: List<SavedPerson>): String = TogetherJson.json.encodeToString(serializer, people)
}

/** Messages between two installs' private inboxes. Each is sealed with the recipient's inbox key. */
@Serializable
sealed interface InboxMessage {
    val fromClientId: String

    @Serializable
    @kotlinx.serialization.SerialName("invite")
    data class Invite(override val fromClientId: String, val fromName: String, val code: String) : InboxMessage

    @Serializable
    @kotlinx.serialization.SerialName("invite_reply")
    data class Reply(override val fromClientId: String, val fromName: String, val accepted: Boolean) : InboxMessage
}

/**
 * Direct invites between saved people, with consent on both ends: an invite only shows if it comes from
 * someone you saved, and it always asks before joining. The inbox listens only while saved people exist.
 */
class TogetherInbox(
    private val scope: CoroutineScope,
    private val selfClientId: String,
) {
    private val _received = MutableSharedFlow<InboxMessage>(extraBufferCapacity = 16)
    val received: SharedFlow<InboxMessage> = _received

    private val selfSecret by lazy { TogetherSecret.inbox(selfClientId) }
    private val relays = java.util.concurrent.CopyOnWriteArrayList<MqttRelay>()
    private var listenJob: kotlinx.coroutines.Job? = null
    @Volatile private var allowed: Set<String> = emptySet()

    /** Listen for the given people; stops listening when the list is empty. */
    fun listenFor(people: List<SavedPerson>) {
        allowed = people.map { it.clientId }.toSet()
        if (allowed.isEmpty()) { stop(); return }
        if (listenJob != null) return
        listenJob = scope.launch {
            for (url in MqttRelay.BROKERS) {
                val relay = MqttRelay(url, scope)
                if (runCatching { relay.connect("$selfClientId-i-${RelayConnection.newPeerId().take(6)}") }.isFailure) continue
                relay.subscribe(RelayTopics.inbox(selfSecret.roomId))
                relay.onMessage { message ->
                    val plain = selfSecret.open(message.payload) ?: return@onMessage
                    val inbox = runCatching {
                        TogetherJson.json.decodeFromString(InboxMessage.serializer(), String(plain))
                    }.getOrNull() ?: return@onMessage
                    if (inbox.fromClientId in allowed) _received.emit(inbox)
                }
                relays += relay
            }
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        relays.forEach { it.close() }
        relays.clear()
    }

    /** Delivers to the person's inbox on every broker we can reach (they may be listening on either). */
    suspend fun send(to: SavedPerson, message: InboxMessage): Boolean {
        val secret = TogetherSecret.inbox(to.clientId)
        val sealed = secret.seal(TogetherJson.json.encodeToString(InboxMessage.serializer(), message).toByteArray())
        var delivered = false
        for (url in MqttRelay.BROKERS) {
            val relay = MqttRelay(url, scope)
            if (runCatching { relay.connect("$selfClientId-o-${RelayConnection.newPeerId().take(6)}") }.isFailure) continue
            delivered = relay.publish(RelayTopics.inbox(secret.roomId), sealed) || delivered
            kotlinx.coroutines.delay(300) // let the frame leave before closing
            relay.close()
        }
        return delivered
    }
}
