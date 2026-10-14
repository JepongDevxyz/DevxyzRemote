package com.jepongdevxyz.remote.service

import android.os.Build
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * Firebase Realtime Database signaling.
 *
 * Tree layout:
 *   /devxyzremote/v1/rooms/{deviceId}/offer   {sdp, from, fromName, ts}
 *   /devxyzremote/v1/rooms/{deviceId}/answer  {sdp, ts}
 *   /devxyzremote/v1/rooms/{deviceId}/ice/{pushId}  "sdpMid|sdpMLineIndex|sdp"
 *   /devxyzremote/v1/rooms/{deviceId}/presence     true (removed on disconnect)
 *
 * All Firebase imports live in this file only. If Firebase isn't bundled,
 * [com.jepongdevxyz.remote.core.Backend.hasFirebase] returns false and this
 * class is never instantiated.
 */
class FirebaseSignaling(
    private val deviceName: String = Build.MODEL
) : Signaling {

    private val rooms: DatabaseReference =
        FirebaseDatabase.getInstance().getReference("devxyzremote/v1/rooms")

    private var localId: String? = null
    private var listener: Signaling.Listener? = null

    private var offerListener: ValueEventListener? = null
    private var iceListener: ChildEventListener? = null
    private var answerListener: ValueEventListener? = null

    private var peerWatchRef: DatabaseReference? = null
    private var peerWatch: ValueEventListener? = null
    private var peerWatchFirstFire = true

    private var lastOfferTs: Long = 0L
    private var offerFirstFire = true
    private var answerFirstFire = true

    override fun connect(localId: String) {
        disconnect()
        this.localId = localId
        val room = rooms.child(localId)

        // Presence: removed automatically when this client disconnects.
        room.child("presence").setValue(true)
        room.child("presence").onDisconnect().removeValue()

        // Incoming offers. The first snapshot is skipped so a stale offer
        // left by a previous run is never re-delivered.
        offerFirstFire = true
        val offerL = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?> ?: return
                val ts = (map["ts"] as? Long) ?: 0L
                if (offerFirstFire) {
                    offerFirstFire = false
                    if (ts > lastOfferTs) lastOfferTs = ts
                    return
                }
                if (ts <= lastOfferTs) return
                lastOfferTs = ts
                val from = map["from"] as? String ?: return
                val fromName = (map["fromName"] as? String).orEmpty().ifBlank { from }
                val sdp = map["sdp"] as? String ?: return
                listener?.onIncomingOffer(from, fromName, sdp)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        offerListener = offerL
        room.child("offer").addValueEventListener(offerL)

        // Incoming ICE trickle. Each child is removed after delivery so a
        // reconnect never replays old candidates.
        iceListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val candidate = snapshot.getValue(String::class.java)
                    ?: (snapshot.value as? String)
                    ?: return
                snapshot.ref.removeValue()
                listener?.onIce(candidate)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }.also { room.child("ice").addChildEventListener(it) }

        // Incoming answer to our offer. Removed after delivery.
        answerFirstFire = true
        val answerL = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (answerFirstFire) {
                    answerFirstFire = false
                    return
                }
                @Suppress("UNCHECKED_CAST")
                val map = snapshot.value as? Map<String, Any?> ?: return
                val sdp = map["sdp"] as? String ?: return
                snapshot.ref.removeValue()
                listener?.onAnswer(sdp)
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        answerListener = answerL
        room.child("answer").addValueEventListener(answerL)
    }

    override fun disconnect() {
        val id = localId
        val room = id?.let { rooms.child(it) }
        offerListener?.let { room?.child("offer")?.removeEventListener(it) }
        iceListener?.let { room?.child("ice")?.removeEventListener(it) }
        answerListener?.let { room?.child("answer")?.removeEventListener(it) }
        peerWatch?.let { peerWatchRef?.removeEventListener(it) }
        peerWatch = null
        peerWatchRef = null
        offerListener = null
        iceListener = null
        answerListener = null
        room?.child("presence")?.removeValue()
        localId = null
        listener = null
    }

    override fun sendOffer(to: String, sdp: String) {
        val id = localId ?: return
        rooms.child(to).child("offer").setValue(
            mapOf(
                "sdp" to sdp,
                "from" to id,
                "fromName" to deviceName,
                "ts" to ServerValue.TIMESTAMP
            )
        )
        watchPeer(to)
    }

    override fun sendAnswer(to: String, sdp: String) {
        rooms.child(to).child("answer").setValue(
            mapOf("sdp" to sdp, "ts" to ServerValue.TIMESTAMP)
        )
        // An empty sdp is a decline — no need to watch for presence then.
        if (sdp.isNotBlank()) watchPeer(to)
    }

    override fun sendIce(to: String, candidate: String) {
        rooms.child(to).child("ice").push().setValue(candidate)
    }

    override fun setListener(l: Signaling.Listener?) {
        listener = l
    }

    /**
     * Fires [Signaling.Listener.onPeerGone] when the peer's presence node
     * disappears (clean disconnect or network drop). Watching starts only
     * once the peer has proven itself online (offered/answered).
     */
    private fun watchPeer(peerId: String) {
        val oldListener = peerWatch
        val oldRef = peerWatchRef
        if (oldListener != null && oldRef != null) {
            oldRef.removeEventListener(oldListener)
        }
        peerWatchFirstFire = true
        val ref = rooms.child(peerId).child("presence")
        val l = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (peerWatchFirstFire) {
                    peerWatchFirstFire = false
                    return
                }
                if (!snapshot.exists()) {
                    listener?.onPeerGone()
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        peerWatchRef = ref
        peerWatch = l
        ref.addValueEventListener(l)
    }
}
