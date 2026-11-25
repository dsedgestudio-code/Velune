/*
 * Velune - by Nikhil
 * Nikhil
 * Licensed Under GPL-3.0
 */

package com.nikhil.yt.together

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
@Immutable
data class TogetherTrack(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val durationSec: Int = -1,
    val thumbnailUrl: String? = null,
)

@Serializable
@Immutable
data class TogetherParticipant(
    val id: String,
    val name: String,
    val isHost: Boolean = false,
    val isPending: Boolean = false,
    val isConnected: Boolean = true,
    /** Stable per-install id; lets people save each other after listening together. */
    val clientId: String? = null,
)

@Serializable
@Immutable
data class TogetherRoomSettings(
    val allowGuestsToAddTracks: Boolean = true,
    val allowGuestsToControlPlayback: Boolean = false,
    val requireHostApprovalToJoin: Boolean = false,
)

@Serializable
@Immutable
data class TogetherRoomState(
    val sessionId: String,
    val hostId: String,
    val participants: List<TogetherParticipant> = emptyList(),
    val settings: TogetherRoomSettings = TogetherRoomSettings(),
    val queue: List<TogetherTrack> = emptyList(),
    val queueHash: String = "",
    val currentIndex: Int = 0,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val sentAtElapsedRealtimeMs: Long = 0L,
)

@Immutable
sealed class TogetherRole {
    data object Host : TogetherRole()
    data object Guest : TogetherRole()
}

@Immutable
sealed class TogetherSessionState {
    data object Idle : TogetherSessionState()

    data object Starting : TogetherSessionState()

    /** Hosting under one [code] that works on this Wi-Fi ([lan]) and from anywhere ([online]). */
    data class Hosting(
        val code: String,
        val sessionId: String,
        val lan: Boolean,
        val online: Boolean,
        val settings: TogetherRoomSettings,
        val roomState: TogetherRoomState?,
    ) : TogetherSessionState()

    data class Joining(val code: String) : TogetherSessionState()

    data class Joined(
        val role: TogetherRole,
        val sessionId: String,
        val selfParticipantId: String,
        val roomState: TogetherRoomState,
        val hostName: String? = null,
        val hostClientId: String? = null,
        val overLan: Boolean = false,
        val isPending: Boolean = false,
    ) : TogetherSessionState()

    data class Error(val message: String) : TogetherSessionState()
}

/** An invite from a saved person, waiting for a yes or no. */
@Immutable
data class TogetherInvite(
    val person: SavedPerson,
    val code: String,
)
