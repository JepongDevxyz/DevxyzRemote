package com.jepongdevxyz.remote.service

/**
 * In-process signaling for the loopback self-test: two [Signaling.Listener]
 * halves (a/b) cross-linked via [other]. Offers, answers and ICE candidates
 * are delivered synchronously to the peer half — no network involved.
 */
class LoopbackSignaling : Signaling {

    /** The peer half of the loopback pair; set before connect(). */
    var other: LoopbackSignaling? = null

    /** Display name reported as fromName on outgoing offers. */
    var localName: String = ""

    private var localId: String = ""
    private var listener: Signaling.Listener? = null

    override fun connect(localId: String) {
        this.localId = localId
    }

    override fun disconnect() {
        listener = null
    }

    override fun sendOffer(to: String, sdp: String) {
        other?.listener?.onIncomingOffer(localId, localName, sdp)
    }

    override fun sendAnswer(to: String, sdp: String) {
        other?.listener?.onAnswer(sdp)
    }

    override fun sendIce(to: String, candidate: String) {
        other?.listener?.onIce(candidate)
    }

    override fun setListener(l: Signaling.Listener?) {
        listener = l
    }
}
