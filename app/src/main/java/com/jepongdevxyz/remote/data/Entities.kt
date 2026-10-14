package com.jepongdevxyz.remote.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One completed (or in-progress) remote session for the history screen. */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val peerName: String,
    val startedAt: Long,
    val durationSec: Long,
    /** "incoming" | "outgoing" | "loopback" */
    val direction: String
)

/** One in-session chat message. */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val ts: Long,
    val mine: Boolean
)

/** A device this user has connected to before (quick-dial list). */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val deviceId: String,
    val name: String,
    val lastSeen: Long
)
