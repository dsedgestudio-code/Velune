package com.nikhil.yt.together

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * The smallest MQTT 3.1.1 client that Listen Together needs: connect, subscribe, publish and keep-alive,
 * all at QoS 0 over a WebSocket. Public brokers relay the (already encrypted) messages; nothing is stored.
 */
class MqttRelay(
    val url: String,
    private val scope: CoroutineScope,
) {
    data class Message(val topic: String, val payload: ByteArray)

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 256)
    val messages: SharedFlow<Message> = _messages

    /** Completes once, when the connection is gone for any reason. */
    val closed = CompletableDeferred<Unit>()

    private var socket: WebSocket? = null
    private val connAck = CompletableDeferred<Boolean>()
    private var buffer = ByteArray(0)
    private var packetId = 1
    private var keepAliveJob: Job? = null

    /** Runs [block] for each message until this relay closes. */
    fun onMessage(block: suspend (Message) -> Unit): Job = scope.launch {
        val collector = launch { messages.collect { block(it) } }
        closed.await()
        collector.cancel()
    }

    suspend fun connect(clientId: String, timeoutMs: Long = 8_000) {
        val request = Request.Builder().url(url).header("Sec-WebSocket-Protocol", "mqtt").build()
        socket = client.newWebSocket(request, Listener()).also {
            it.send(packet(0x10, connectBody(clientId)).toByteString()) // queued until the socket opens
        }
        val accepted = try {
            withTimeout(timeoutMs) { connAck.await() }
        } catch (t: Throwable) {
            close()
            throw t
        }
        if (!accepted) {
            close()
            error("Relay refused the connection")
        }
        keepAliveJob = scope.launch {
            while (!closed.isCompleted) {
                delay(KEEP_ALIVE_SEC * 1000L / 2)
                socket?.send(byteArrayOf(0xC0.toByte(), 0).toByteString())
            }
        }
    }

    fun subscribe(topic: String) {
        val body = ByteArrayOutputStream().apply {
            writeShort(nextPacketId())
            writeString(topic)
            write(0) // QoS 0
        }.toByteArray()
        socket?.send(packet(0x82, body).toByteString())
    }

    fun publish(topic: String, payload: ByteArray): Boolean {
        val body = ByteArrayOutputStream().apply {
            writeString(topic)
            write(payload)
        }.toByteArray()
        return socket?.send(packet(0x30, body).toByteString()) == true
    }

    fun close() {
        keepAliveJob?.cancel()
        socket?.let { ws ->
            ws.send(byteArrayOf(0xE0.toByte(), 0).toByteString())
            ws.close(1000, null)
        }
        socket = null
        if (!connAck.isCompleted) connAck.complete(false)
        closed.complete(Unit)
    }

    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            buffer += bytes.toByteArray()
            while (true) {
                val packet = takePacket() ?: break
                handle(packet.first, packet.second)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = close()

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!connAck.isCompleted) connAck.completeExceptionally(t)
            close()
        }
    }

    private fun handle(header: Int, body: ByteArray) {
        when (header shr 4) {
            2 -> connAck.complete(body.size >= 2 && body[1].toInt() == 0) // CONNACK, return code 0 = accepted
            3 -> { // PUBLISH (QoS 0: no packet id)
                if (body.size < 2) return
                val topicLength = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
                if (body.size < 2 + topicLength) return
                val topic = String(body, 2, topicLength, Charsets.UTF_8)
                val qos = (header shr 1) and 0x03
                val start = 2 + topicLength + if (qos > 0) 2 else 0
                _messages.tryEmit(Message(topic, body.copyOfRange(start, body.size)))
            }
            else -> Unit // SUBACK, PINGRESP
        }
    }

    /** Pops one complete packet (header byte, body) off the buffer, or null if more bytes are needed. */
    private fun takePacket(): Pair<Int, ByteArray>? {
        if (buffer.size < 2) return null
        var multiplier = 1
        var length = 0
        var index = 1
        while (true) {
            if (index >= buffer.size) return null
            val digit = buffer[index++].toInt() and 0xFF
            length += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) { buffer = ByteArray(0); return null }
        }
        if (buffer.size < index + length) return null
        val header = buffer[0].toInt() and 0xFF
        val body = buffer.copyOfRange(index, index + length)
        buffer = buffer.copyOfRange(index + length, buffer.size)
        return header to body
    }

    private fun nextPacketId(): Int = packetId.also { packetId = if (it == 0xFFFF) 1 else it + 1 }

    private fun connectBody(clientId: String) = ByteArrayOutputStream().apply {
        writeString("MQTT")
        write(4) // protocol level 3.1.1
        write(0x02) // clean session
        writeShort(KEEP_ALIVE_SEC)
        writeString(clientId)
    }.toByteArray()

    companion object {
        private const val KEEP_ALIVE_SEC = 30

        /** Free public brokers, tried in order. The host listens on all of them so a guest can reach it on any. */
        val BROKERS = listOf(
            "wss://broker.emqx.io:8084/mqtt",
            "wss://broker.hivemq.com:8884/mqtt",
        )

        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        internal fun packet(header: Int, body: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(header)
            var length = body.size
            do {
                var digit = length % 128
                length /= 128
                if (length > 0) digit = digit or 0x80
                out.write(digit)
            } while (length > 0)
            out.write(body)
            return out.toByteArray()
        }

        private fun ByteArrayOutputStream.writeShort(value: Int) {
            write((value shr 8) and 0xFF)
            write(value and 0xFF)
        }

        private fun ByteArrayOutputStream.writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeShort(bytes.size)
            write(bytes)
        }
    }
}
