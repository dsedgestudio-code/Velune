/*
 * Velune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package com.nikhil.yt.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TogetherCoreTest {
    @Test
    fun code_isGeneratedValid_andSurvivesHowPeopleTypeIt() {
        val code = TogetherCode.generate()
        assertEquals(TogetherCode.LENGTH, code.length)
        assertEquals(code, TogetherCode.normalize(code))
        assertEquals(code, TogetherCode.normalize(TogetherCode.display(code).lowercase()))
        assertEquals("0123ABCD", TogetherCode.normalize("oi23-abcd")) // O->0, I->1
        assertNull(TogetherCode.normalize("ABC"))
        assertNull(TogetherCode.normalize("ABCDEFGU")) // U is not in the alphabet
    }

    @Test
    fun code_parsesLinksAndPlainText() {
        assertEquals("ABCD2345", TogetherCode.parse(TogetherCode.link("ABCD2345")))
        assertEquals("ABCD2345", TogetherCode.parse("velune://together?code=abcd-2345"))
        assertEquals("ABCD2345", TogetherCode.parse("  abcd 2345 "))
        assertNull(TogetherCode.parse("https://example.com/together?code=ABCD2345"))
    }

    @Test
    fun secret_isDeterministic_andSealsOnlyForTheSameCode() {
        val a = TogetherSecret.fromCode("ABCD2345")
        val b = TogetherSecret.fromCode("ABCD2345")
        val other = TogetherSecret.fromCode("ABCD2346")
        assertEquals(a.roomId, b.roomId)
        assertEquals(a.lanKey, b.lanKey)
        assertNotEquals(a.roomId, other.roomId)

        val sealed = a.seal("hello".toByteArray())
        assertEquals("hello", String(b.open(sealed)!!))
        assertNull(other.open(sealed))
        assertNull(a.open(sealed.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }))
    }

    @Test
    fun mqttPacket_encodesRemainingLengthAsVarint() {
        assertEquals(listOf(0x30, 0x00), MqttRelay.packet(0x30, ByteArray(0)).map { it.toInt() and 0xFF })
        val big = MqttRelay.packet(0x30, ByteArray(321))
        assertEquals(0xC1, big[1].toInt() and 0xFF) // 321 = 0x41 | continuation
        assertEquals(0x02, big[2].toInt() and 0xFF)
        assertEquals(3 + 321, big.size)
    }

    @Test
    fun protocol_serialization_roundTrip() {
        val hello =
            ClientHello(
                protocolVersion = TogetherProtocolVersion,
                sessionId = "sid",
                sessionKey = "key",
                clientId = "cid",
                displayName = "Alice",
            )
        val json = TogetherJson.json.encodeToString(TogetherMessage.serializer(), hello)
        val decoded = TogetherJson.json.decodeFromString(TogetherMessage.serializer(), json)
        assertTrue(decoded is ClientHello)
        assertEquals(hello, decoded)
    }

    @Test
    fun protocol_seekToTrack_roundTrip() {
        val message: TogetherMessage =
            ControlRequest(
                sessionId = "sid",
                participantId = "pid",
                action = ControlAction.SeekToTrack(trackId = "track123", positionMs = 456L),
            )
        val json = TogetherJson.json.encodeToString(TogetherMessage.serializer(), message)
        val decoded = TogetherJson.json.decodeFromString(TogetherMessage.serializer(), json)
        assertTrue(decoded is ControlRequest)
        val req = decoded as ControlRequest
        assertTrue(req.action is ControlAction.SeekToTrack)
        val action = req.action as ControlAction.SeekToTrack
        assertEquals("track123", action.trackId)
        assertEquals(456L, action.positionMs)
    }

    @Test
    fun clock_estimates_offset_and_rtt() {
        val clock = TogetherClock()
        val snapshot =
            clock.onPong(
                sentAtElapsedMs = 1000L,
                receivedAtElapsedMs = 1200L,
                serverElapsedMs = 1150L,
            )
        assertTrue(snapshot.estimatedRttMs >= 0L)
    }
}
