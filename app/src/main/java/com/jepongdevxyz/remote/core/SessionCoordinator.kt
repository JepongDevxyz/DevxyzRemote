package com.jepongdevxyz.remote.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.jepongdevxyz.remote.data.DeviceEntity
import com.jepongdevxyz.remote.data.MessageEntity
import com.jepongdevxyz.remote.data.SessionEntity
import com.jepongdevxyz.remote.data.SessionRepository
import com.jepongdevxyz.remote.service.CaptureService
import com.jepongdevxyz.remote.service.ControlBridge
import com.jepongdevxyz.remote.service.FileTransferManager
import com.jepongdevxyz.remote.service.FirebaseSignaling
import com.jepongdevxyz.remote.service.LoopbackSignaling
import com.jepongdevxyz.remote.service.RtcManager
import com.jepongdevxyz.remote.service.Signaling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.SurfaceViewRenderer

/**
 * Owns the entire session lifecycle: signaling, WebRTC, capture, control
 * routing, chat persistence and file transfers. Single process-wide object;
 * the UI only observes [state]/[rttMs]/[peerScreen]/[fileTransfers] and calls
 * the plain functions below.
 */
object SessionCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    private val _peerScreen = MutableStateFlow<Pair<Int, Int>?>(null)
    val peerScreen: StateFlow<Pair<Int, Int>?> = _peerScreen.asStateFlow()

    private val fileTx = FileTransferManager(
        send = { activeRtc?.sendFileData(it) ?: false },
        scope = scope
    )
    val fileTransfers: StateFlow<List<FileTransferProgress>> = fileTx.transfers

    // ---- plumbing ----

    private data class PendingOffer(val from: String, val fromName: String, val sdp: String)

    private var appCtx: Context? = null
    private var repository: SessionRepository? = null

    /** Firebase signaling for real two-phone calls; never touched by loopback. */
    private var onlineSignaling: Signaling? = null
    private val signalingMutex = Mutex()

    private var activeRtc: RtcManager? = null
    private var sharingRtc: RtcManager? = null
    private var loopbackRtcB: RtcManager? = null
    private var rttJob: Job? = null

    private var currentPeerId: String = ""
    private var currentSessionId: Long? = null
    private var sessionStartMs: Long = 0L
    private var lastGranted: Set<RemotePermission> = emptySet()
    private var pendingOffer: PendingOffer? = null
    private var pendingLoopbackCapture: Boolean = false
    private var dialTimeoutJob: Job? = null
    private var dialFallbackJob: Job? = null

    private fun ensureInit(context: Context): Context {
        Prefs.init(context)
        val app = context.applicationContext
        if (appCtx == null) {
            appCtx = app
            repository = SessionRepository.get(app)
            fileTx.appContext = app
            scope.launch { Prefs.prime(app) }
        }
        return app
    }

    /**
     * Connects Firebase signaling so incoming calls can arrive. Safe to call
     * repeatedly. Worker 1 should call this from Application.onCreate (or the
     * main screen) — without it, this device can dial out but never receive.
     * No-op when Firebase isn't bundled.
     */
    fun ensureOnline(context: Context) {
        scope.launch {
            val app = ensureInit(context)
            ensureSignalingLocked(app)
        }
    }

    private suspend fun ensureSignalingLocked(app: Context): Signaling? = signalingMutex.withLock {
        onlineSignaling?.let { return@withLock it }
        if (!Backend.hasFirebase(app)) return@withLock null
        val localId = DeviceIdManager.getDeviceId(app)
        val deviceName = try {
            Prefs.deviceName.first()
        } catch (e: Exception) {
            Build.MODEL
        }
        val sig = try {
            FirebaseSignaling(deviceName)
        } catch (e: Exception) {
            // Firebase classes are bundled but not initialized (no
            // google-services.json) — signaling simply isn't available.
            return@withLock null
        }
        sig.setListener(masterListener())
        sig.connect(localId)
        onlineSignaling = sig
        sig
    }

    private fun masterListener() = object : Signaling.Listener {
        override fun onIncomingOffer(from: String, fromName: String, sdp: String) {
            if (_state.value is SessionState.Idle) {
                pendingOffer = PendingOffer(from, fromName, sdp)
                _state.value = SessionState.Incoming(from, fromName)
                scope.launch(Dispatchers.IO) {
                    try {
                        repository?.upsertDevice(
                            DeviceEntity(from, fromName.ifBlank { from }, System.currentTimeMillis())
                        )
                    } catch (e: Exception) {
                    }
                }
            } else {
                // Busy — politely decline so the caller doesn't hang.
                try {
                    onlineSignaling?.sendAnswer(from, "")
                } catch (e: Exception) {
                }
            }
        }

        override fun onAnswer(sdp: String) {
            val st = _state.value
            if (st !is SessionState.Dialing) return
            if (sdp.isBlank()) {
                failDial("Peer declined the call")
                return
            }
            val rtc = activeRtc ?: return
            scope.launch {
                try {
                    rtc.setRemoteAnswer(sdp)
                } catch (e: Exception) {
                    failDial("Failed to establish the connection")
                }
            }
        }

        override fun onIce(candidate: String) {
            activeRtc?.addIce(candidate)
        }

        override fun onPeerGone() {
            when (_state.value) {
                is SessionState.Active -> endSession()
                is SessionState.Dialing -> failDial("Peer went offline")
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------ outgoing

    /**
     * Dials [peerId]. Fails fast with [SessionState.Failed] when the id is
     * malformed or Firebase signaling isn't available in this build.
     */
    fun startOutgoing(context: Context, peerId: String) {
        scope.launch {
            val app = ensureInit(context)
            val target = DeviceIdManager.formatInput(peerId)
            if (!DeviceIdManager.isValidDeviceId(target)) {
                _state.value = SessionState.Failed("Invalid device ID — it looks like DEVXYZ-XXXX-XXXX.")
                return@launch
            }
            val sig = ensureSignalingLocked(app)
            if (sig == null) {
                _state.value =
                    SessionState.Failed("Signaling unavailable — this build was installed without Firebase configured.")
                return@launch
            }
            val localId = DeviceIdManager.getDeviceId(app)
            if (target == localId) {
                _state.value = SessionState.Failed("That's your own device ID.")
                return@launch
            }
            if (_state.value is SessionState.Active || _state.value is SessionState.Dialing) {
                endSessionInternal()
            }
            currentPeerId = target
            val rtc = RtcManager(app)
            activeRtc = rtc
            sharingRtc = null
            wireRtc(rtc, sendIce = { cand ->
                try {
                    sig.sendIce(target, cand)
                } catch (e: Exception) {
                }
            })
            _state.value = SessionState.Dialing(target)
            try {
                val offer = rtc.createOffer()
                sig.sendOffer(target, offer)
            } catch (e: Exception) {
                failDial("Failed to create offer: ${e.message}")
                return@launch
            }
            dialTimeoutJob?.cancel()
            dialTimeoutJob = scope.launch {
                delay(45_000)
                if (_state.value is SessionState.Dialing) {
                    failDial("No answer — the other device may be offline.")
                }
            }
        }
    }

    /** Declines the pending incoming call and returns to idle. */
    fun declineIncoming() {
        val pending = pendingOffer
        pendingOffer = null
        if (pending != null) {
            try {
                // Empty answer = decline; the caller surfaces it immediately.
                onlineSignaling?.sendAnswer(pending.from, "")
            } catch (e: Exception) {
            }
        }
        if (_state.value is SessionState.Incoming) {
            _state.value = SessionState.Idle
        }
    }

    /**
     * Accepts the pending incoming call: starts screen capture, answers the
     * WebRTC offer and goes [SessionState.Active] once connected.
     *
     * @param resultCode result code from the MediaProjection permission intent
     * @param data result data from the MediaProjection permission intent
     * @param granted permissions this session grants to the controller
     */
    fun acceptIncoming(context: Context, resultCode: Int, data: Intent?, granted: Set<RemotePermission>) {
        scope.launch {
            val app = ensureInit(context)
            // Atomic check-and-clear: a second tap while the first is still
            // running must not create a second RtcManager.
            val pending = synchronized(this@SessionCoordinator) {
                val p = pendingOffer
                pendingOffer = null
                p
            } ?: run {
                _state.value = SessionState.Failed("The incoming call expired.")
                return@launch
            }
            if (resultCode != Activity.RESULT_OK || data == null) {
                _state.value = SessionState.Failed("Screen capture permission was denied.")
                pendingOffer = null
                try {
                    onlineSignaling?.sendAnswer(pending.from, "")
                } catch (e: Exception) {
                }
                return@launch
            }
            val sig = ensureSignalingLocked(app) ?: run {
                _state.value =
                    SessionState.Failed("Signaling unavailable — this build was installed without Firebase configured.")
                return@launch
            }
            lastGranted = granted
            currentPeerId = pending.from
            endRtcOnly()
            val rtc = RtcManager(app)
            activeRtc = rtc
            sharingRtc = rtc
            wireRtc(rtc, sendIce = { cand ->
                try {
                    sig.sendIce(pending.from, cand)
                } catch (e: Exception) {
                }
            })
            val (w, h, fps) = captureDimsFor(app)
            CaptureService.captureWidth = w
            CaptureService.captureHeight = h
            CaptureService.captureFps = fps
            // The video track MUST exist before createAnswer so the SDP
            // includes the video m-line (otherwise the controller gets black).
            rtc.prepareVideoTrack()
            try {
                val answer = rtc.createAnswer(pending.sdp)
                sig.sendAnswer(pending.from, answer)
            } catch (e: Exception) {
                _state.value = SessionState.Failed("Failed to answer: ${e.message}")
                return@launch
            }
            // CaptureService pulls the sharing RtcManager via getSharingRtc().
            try { CaptureService.start(app, resultCode, data) } catch (e: Exception) { android.util.Log.e("SessionCoordinator", "Failed to start CaptureService", e) }
            // Active is set in onRtcConnected() once the PeerConnection is up.
        }
    }

    // ------------------------------------------------------------ teardown

    /** Hangs up: sends bye, closes the peer connection, stops capture. */
    fun endSession() {
        scope.launch { endSessionInternal() }
    }

    private suspend fun endSessionInternal() {
        dialTimeoutJob?.cancel()
        dialFallbackJob?.cancel()
        try {
            activeRtc?.sendControl(Protocol.bye())
        } catch (e: Exception) {
        }
        if (_state.value is SessionState.Dialing) {
            // Never connected: tell the callee to stop ringing (empty answer).
            try {
                onlineSignaling?.sendAnswer(currentPeerId, "")
            } catch (e: Exception) {
            }
        }
        fileTx.failAll()
        pendingOffer = null
        pendingLoopbackCapture = false
        try {
            val sid = currentSessionId
            if (sid != null && sid != 0L && sessionStartMs > 0) {
                val sec = ((System.currentTimeMillis() - sessionStartMs) / 1000).coerceAtLeast(0)
                repository?.updateSessionDuration(sid, sec)
            }
        } catch (e: Exception) {
        }
        endRtcOnly()
        try {
            appCtx?.let { CaptureService.stop(it) }
        } catch (e: Exception) {
        }
        currentSessionId = null
        sessionStartMs = 0L
        currentPeerId = ""
        lastGranted = emptySet()
        _peerScreen.value = null
        _rttMs.value = 0L
        _state.value = SessionState.Idle
    }

    private fun endRtcOnly() {
        rttJob?.cancel()
        rttJob = null
        try {
            activeRtc?.close()
        } catch (e: Exception) {
        }
        activeRtc = null
        sharingRtc = null
        try {
            loopbackRtcB?.close()
        } catch (e: Exception) {
        }
        loopbackRtcB = null
    }

    private fun failDial(reason: String) {
        if (_state.value is SessionState.Failed) return
        dialTimeoutJob?.cancel()
        dialFallbackJob?.cancel()
        endRtcOnly()
        currentPeerId = ""
        pendingLoopbackCapture = false
        _state.value = SessionState.Failed(reason)
    }

    // ------------------------------------------------------------ in-session

    fun attachRemoteRenderer(view: SurfaceViewRenderer) {
        try {
            activeRtc?.attachRenderer(view)
        } catch (e: Exception) {
        }
    }

    fun detachRemoteRenderer() {
        try {
            activeRtc?.detachRenderer()
        } catch (e: Exception) {
        }
    }

    /** Sends a Protocol-built input message (controller -> controlled). */
    fun sendInput(json: String) {
        val st = _state.value
        if (st !is SessionState.Active || st.role != SessionRole.CONTROLLER) return
        activeRtc?.sendControl(json)
    }

    /** Sends a chat message and stores it locally as mine=true. */
    fun sendChat(text: String) {
        val st = _state.value
        if (st !is SessionState.Active) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        val sid = currentSessionId ?: return
        val ts = System.currentTimeMillis()
        activeRtc?.sendControl(Protocol.chat(clean, ts))
        scope.launch(Dispatchers.IO) {
            try {
                repository?.insertMessage(MessageEntity(sessionId = sid, text = clean, ts = ts, mine = true))
            } catch (e: Exception) {
            }
        }
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        try {
            activeRtc?.setMicrophoneEnabled(enabled)
        } catch (e: Exception) {
        }
    }

    /**
     * True only while we are the CONTROLLED side of a live session whose
     * granted set includes CONTROL. Checked by the control path before every
     * gesture/text/clipboard action.
     */
    fun canControlNow(): Boolean {
        val st = _state.value
        return st is SessionState.Active &&
            st.role == SessionRole.CONTROLLED &&
            RemotePermission.CONTROL in st.granted
    }

    /** The RtcManager currently sharing its screen (for CaptureService). */
    internal fun getSharingRtc(): RtcManager? = sharingRtc

    // ------------------------------------------------------------ loopback

    /**
     * Self-test on a single phone: wires two RtcManagers through
     * [LoopbackSignaling] (real SDP/ICE exchange, real capture -> encode ->
     * decode -> render once [onLoopbackCaptureResult] fires).
     *
     * The UI must launch the MediaProjection permission intent and forward
     * its result to [onLoopbackCaptureResult]; the state becomes Active
     * immediately with role CONTROLLER.
     */
    fun startLoopbackTest(context: Context) {
        // Arm synchronously BEFORE the coroutine starts: onLoopbackCaptureResult
        // may arrive while the coroutine is still setting up (no delay-guessing).
        pendingLoopbackCapture = true
        scope.launch {
            val app = ensureInit(context)
            endSessionInternal()
            // endSessionInternal cleared the flag; re-arm now that setup runs.
            pendingLoopbackCapture = true

            val localId = DeviceIdManager.getDeviceId(app)
            val deviceName = try {
                Prefs.deviceName.first()
            } catch (e: Exception) {
                Build.MODEL
            }

            val sigA = LoopbackSignaling()
            val sigB = LoopbackSignaling()
            sigA.localName = deviceName
            sigB.localName = "This device (loopback)"
            sigA.other = sigB
            sigB.other = sigA

            val rtcA = try { RtcManager(app) } catch (t: Throwable) { android.util.Log.e("SessionCoordinator", "RtcManager A failed", t); _state.value = SessionState.Failed("WebRTC init failed: " + (t.message ?: "unknown")); return@launch } // controller side
            val rtcB = try { RtcManager(app) } catch (t: Throwable) { android.util.Log.e("SessionCoordinator", "RtcManager B failed", t); _state.value = SessionState.Failed("WebRTC init failed: " + (t.message ?: "unknown")); return@launch } // controlled side (shares screen)
            activeRtc = rtcA
            sharingRtc = rtcB
            loopbackRtcB = rtcB
            currentPeerId = localId

            wireRtc(rtcA, sendIce = { cand ->
                try {
                    sigA.sendIce(localId, cand)
                } catch (e: Exception) {
                }
            })
            wireRtc(
                rtcB,
                sendIce = { cand ->
                    try {
                        sigB.sendIce(localId, cand)
                    } catch (e: Exception) {
                    }
                },
                // Controlled half announces its screen as soon as the
                // connection is up (hello -> peerScreen on the A side).
                onConnected = { scope.launch { sendHelloWithRetry(rtcB) } },
                collectRtt = false
            )

            sigA.setListener(object : Signaling.Listener {
                override fun onIncomingOffer(from: String, fromName: String, sdp: String) {}
                override fun onAnswer(sdp: String) {
                    scope.launch {
                        try {
                            rtcA.setRemoteAnswer(sdp)
                        } catch (e: Exception) {
                        }
                    }
                }

                override fun onIce(candidate: String) {
                    rtcA.addIce(candidate)
                }

                override fun onPeerGone() {}
            })
            sigB.setListener(object : Signaling.Listener {
                override fun onIncomingOffer(from: String, fromName: String, sdp: String) {
                    scope.launch {
                        try {
                            val answer = rtcB.createAnswer(sdp)
                            sigB.sendAnswer(from, answer)
                        } catch (e: Exception) {
                        }
                    }
                }

                override fun onAnswer(sdp: String) {}
                override fun onIce(candidate: String) {
                    rtcB.addIce(candidate)
                }

                override fun onPeerGone() {}
            })

            val sid = insertSessionRow(localId, "This device (loopback)", "loopback")
            currentSessionId = sid
            sessionStartMs = System.currentTimeMillis()
            _state.value = SessionState.Active(
                sessionId = sid,
                role = SessionRole.CONTROLLER,
                peerId = localId,
                peerName = "This device (loopback)",
                granted = RemotePermission.entries.toSet()
            )

            val (w, h, fps) = captureDimsFor(app)
            CaptureService.captureWidth = w
            CaptureService.captureHeight = h
            CaptureService.captureFps = fps

            sigA.connect(localId)
            sigB.connect(localId)
            pendingLoopbackCapture = true
            // The sharing side's video track MUST exist before the offer so
            // the SDP includes the video m-line (otherwise black screen).
            rtcB.prepareVideoTrack()
            try {
                val offer = rtcA.createOffer()
                sigA.sendOffer(localId, offer)
            } catch (e: Exception) {
                failDial("Loopback failed: ${e.message}")
            }
        }
    }

    /**
     * Forward the MediaProjection permission result here (from Worker 1's
     * Profile screen) to finish [startLoopbackTest]: starts CaptureService,
     * which feeds the loopback's sharing RtcManager.
     */
    fun onLoopbackCaptureResult(resultCode: Int, data: Intent?) {
        if (!pendingLoopbackCapture) return
        pendingLoopbackCapture = false
        val app = appCtx ?: return
        if (resultCode != Activity.RESULT_OK || data == null) {
            scope.launch {
                endSessionInternal()
                _state.value = SessionState.Failed("Screen capture permission was denied.")
            }
            return
        }
        try { CaptureService.start(app, resultCode, data) } catch (e: Exception) { android.util.Log.e("SessionCoordinator", "Failed to start CaptureService", e) }
    }

    // ------------------------------------------------------------ files

    /**
     * Starts an outgoing file transfer. Returns the transfer id, or null
     * when there is no active session (or FILES wasn't granted).
     */
    fun sendFile(context: Context, uri: Uri, name: String, size: Long, mime: String): String? {
        val st = _state.value
        if (st !is SessionState.Active) return null
        if (RemotePermission.FILES !in st.granted) return null
        ensureInit(context)
        fileTx.appContext = context.applicationContext
        return try {
            fileTx.sendFile(context.applicationContext, uri, name, size, mime)
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ internals

    private fun wireRtc(
        rtc: RtcManager,
        sendIce: (String) -> Unit,
        onConnected: () -> Unit = { onRtcConnected() },
        collectRtt: Boolean = true
    ) {
        rtc.onIceCandidate = { cand ->
            try {
                sendIce(cand)
            } catch (e: Exception) {
            }
        }
        rtc.onDataChannelMessage = { _, text -> handleDataMessage(text) }
        rtc.onConnected = onConnected
        rtc.onConnectionLost = { onRtcLost() }
        if (collectRtt) {
            rttJob?.cancel()
            rttJob = scope.launch { rtc.rttMs.collect { _rttMs.value = it } }
        }
    }

    private fun onRtcConnected() {
        when (val st = _state.value) {
            is SessionState.Incoming -> {
                // Controlled side: announce the screen, then go Active.
                val rtc = activeRtc ?: return
                val granted = lastGranted
                scope.launch {
                    sendHelloWithRetry(rtc)
                    val sid = insertSessionRow(st.peerId, st.peerName, "incoming")
                    currentSessionId = sid
                    sessionStartMs = System.currentTimeMillis()
                    _state.value = SessionState.Active(
                        sessionId = sid,
                        role = SessionRole.CONTROLLED,
                        peerId = st.peerId,
                        peerName = st.peerName,
                        granted = granted
                    )
                }
            }
            is SessionState.Dialing -> {
                // Controller side: the controlled half sends hello on connect.
                // Fallback so we never hang in Dialing if hello is lost.
                dialFallbackJob?.cancel()
                dialFallbackJob = scope.launch {
                    delay(6_000)
                    val s = _state.value
                    if (s is SessionState.Dialing) {
                        completeOutgoingActive(s.peerId, s.peerId)
                    }
                }
            }
            else -> {}
        }
    }

    private fun onRtcLost() {
        when (_state.value) {
            is SessionState.Active -> endSession()
            is SessionState.Dialing -> failDial("Connection lost")
            else -> {}
        }
    }

    private fun completeOutgoingActive(peerId: String, peerName: String) {
        if (_state.value !is SessionState.Dialing) return
        dialTimeoutJob?.cancel()
        dialFallbackJob?.cancel()
        scope.launch(Dispatchers.IO) {
            val sid = insertSessionRow(peerId, peerName, "outgoing")
            currentSessionId = sid
            sessionStartMs = System.currentTimeMillis()
            try {
                repository?.upsertDevice(DeviceEntity(peerId, peerName, System.currentTimeMillis()))
            } catch (e: Exception) {
            }
            _state.value = SessionState.Active(
                sessionId = sid,
                role = SessionRole.CONTROLLER,
                peerId = peerId,
                peerName = peerName,
                granted = RemotePermission.entries.toSet()
            )
        }
    }

    private suspend fun sendHelloWithRetry(rtc: RtcManager) {
        val app = appCtx ?: return
        val metrics = app.resources.displayMetrics
        val name = try {
            Prefs.deviceName.first()
        } catch (e: Exception) {
            Build.MODEL
        }
        val hello = Protocol.hello(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, name)
        repeat(6) {
            if (rtc.sendControl(hello)) return
            delay(500)
        }
    }

    private suspend fun insertSessionRow(peerId: String, peerName: String, direction: String): Long {
        return try {
            repository?.insertSession(
                SessionEntity(
                    peerId = peerId,
                    peerName = peerName.ifBlank { peerId },
                    startedAt = System.currentTimeMillis(),
                    durationSec = 0L,
                    direction = direction
                )
            ) ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** Capture geometry scaled from the display to the quality setting. */
    private suspend fun captureDimsFor(app: Context): Triple<Int, Int, Int> {
        val quality = withTimeoutOrNull(2000) {
            try {
                Prefs.quality.first()
            } catch (e: Exception) {
                Prefs.cachedQuality
            }
        } ?: Prefs.cachedQuality
        val m = app.resources.displayMetrics
        val targetShort = when (quality) {
            "720p" -> 720
            else -> 1080
        }
        val short = minOf(m.widthPixels, m.heightPixels).coerceAtLeast(1)
        val scale = targetShort.toFloat() / short.toFloat()
        val w = ((m.widthPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
        val h = ((m.heightPixels * scale).toInt() / 2 * 2).coerceAtLeast(2)
        return Triple(w, h, 30)
    }

    /** Routes one incoming "devxyz" DataChannel message. */
    private fun handleDataMessage(text: String) {
        val msg = try {
            Protocol.parse(text)
        } catch (e: Exception) {
            return
        }
        when (msg["t"] as? String) {
            "hello" -> {
                val w = (msg["w"] as? Number)?.toInt()
                val h = (msg["h"] as? Number)?.toInt()
                if (w != null && h != null && w > 0 && h > 0) {
                    _peerScreen.value = w to h
                    val st = _state.value
                    if (st is SessionState.Dialing) {
                        val name = (msg["name"] as? String).orEmpty().ifBlank { st.peerId }
                        completeOutgoingActive(st.peerId, name)
                    }
                }
            }
            "chat" -> {
                val v = msg["v"] as? String ?: return
                val ts = (msg["ts"] as? Number)?.toLong() ?: System.currentTimeMillis()
                val sid = currentSessionId ?: return
                scope.launch(Dispatchers.IO) {
                    try {
                        repository?.insertMessage(MessageEntity(sessionId = sid, text = v, ts = ts, mine = false))
                    } catch (e: Exception) {
                    }
                }
            }
            "fmeta", "fchunk", "fack" -> fileTx.onMessage(text)
            "tap", "swipe", "scroll", "text", "action", "clip" -> {
                if (canControlNow()) {
                    ControlBridge.dispatch?.invoke(msg)
                }
            }
            "bye" -> endSession()
        }
    }
}
