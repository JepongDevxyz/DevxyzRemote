package com.jepongdevxyz.remote.core

/** Which side of the session this device plays. */
enum class SessionRole {
    CONTROLLER,
    CONTROLLED
}

/** Permissions the controlled side grants to the controller for one session. */
enum class RemotePermission {
    VIEW,
    CONTROL,
    FILES,
    VOICE
}

/** Lifecycle of a remote session, observed by the UI. */
sealed interface SessionState {
    data object Idle : SessionState
    data class Dialing(val peerId: String) : SessionState
    data class Incoming(val peerId: String, val peerName: String) : SessionState
    data class Active(
        val sessionId: Long,
        val role: SessionRole,
        val peerId: String,
        val peerName: String,
        val granted: Set<RemotePermission>
    ) : SessionState
    data class Failed(val reason: String) : SessionState
}

/** UI-facing snapshot of one file transfer (either direction). */
data class FileTransferProgress(
    val id: String,
    val name: String,
    val size: Long,
    val transferred: Long,
    val done: Boolean,
    val failed: Boolean,
    val incoming: Boolean,
    val savedPath: String?
)
