package com.jepongdevxyz.remote.service

/**
 * Transport for WebRTC session descriptions and ICE candidates.
 *
 * Implementations: [FirebaseSignaling] (real two-phone sessions over
 * Firebase Realtime Database) and [LoopbackSignaling] (in-process, used by
 * the loopback self-test so one phone exercises the full pipeline).
 */
interface Signaling {

    /** Start listening for incoming offers addressed to [localId]. */
    fun connect(localId: String)

    /** Stop listening and release presence. */
    fun disconnect()

    /** Send an SDP offer to [to] (a device id). */
    fun sendOffer(to: String, sdp: String)

    /**
     * Send an SDP answer to [to]. An empty [sdp] means "declined" — the
     * caller surfaces that as a failed dial instead of hanging.
     */
    fun sendAnswer(to: String, sdp: String)

    /**
     * Send one ICE candidate to [to], formatted as
     * "sdpMid|sdpMLineIndex|sdp" (see RtcManager.addIce).
     */
    fun sendIce(to: String, candidate: String)

    fun setListener(l: Listener?)

    interface Listener {
        fun onIncomingOffer(from: String, fromName: String, sdp: String)
        fun onAnswer(sdp: String)
        fun onIce(candidate: String)
        fun onPeerGone()
    }
}
