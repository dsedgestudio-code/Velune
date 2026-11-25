package com.nikhil.yt.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey

/** What Spectra has learned about one output (headphones, speaker, the phone itself): how far it can be pushed cleanly. */
@Immutable
@Entity(tableName = "device_sound_profile")
data class DeviceSoundProfile(
    @PrimaryKey val id: String, // "speaker", "bluetooth:AirPods Pro", ...
    val name: String,
    val pushDb: Float = 0f, // extra gain on top of normal levelling, learned
    val listenedMs: Long = 0L,
    val updatedAt: Long = 0L,
)
