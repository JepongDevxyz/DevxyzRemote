package com.jepongdevxyz.remote.service

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns one WebRTC PeerConnection: hardware H.264 encode/decode, two
 * DataChannels ("devxyz" for control/chat/meta, "devxyz-file" for file
 * chunks), an audio track for the voice call, and an optional screen-capture
 * video track. Internal — SessionCoordinator is the only caller.
 */
internal class RtcManager(private val appContext: Context) {

    /** (channelLabel, text) for every incoming DataChannel message. */
    var onDataChannelMessage: ((channelLabel: String, text: String) -> Unit)? = null

    /** Fired when the PeerConnection reaches CONNECTED. */
    var onConnected: (() -> Unit)? = null

    /** Fired on FAILED/CLOSED — the session is dead. */
    var onConnectionLost: (() -> Unit)? = null

    /** Outgoing ICE candidate, formatted "sdpMid|sdpMLineIndex|sdp". */
    var onIceCandidate: ((candidate: String) -> Unit)? = null

    /** Round-trip time in ms, refreshed every 2s from getStats. */
    private val _rttMs = MutableStateFlow(0L)
    val rttMs: StateFlow<Long> = _rttMs.asStateFlow()

    /** EGL context for renderers and capturers. */
    val eglContext: EglBase.Context get() = eglBase.eglBaseContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val eglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescSet = false

    private var controlDc: DataChannel? = null
    private var fileDc: DataChannel? = null

    private var audioTrack: AudioTrack? = null
    private var videoTrack: VideoTrack? = null
    private var videoSource: VideoSource? = null
    private var captureHelper: SurfaceTextureHelper? = null
    private var capturer: VideoCapturer? = null

    private var renderer: SurfaceViewRenderer? = null
    private var remoteVideoTrack: VideoTrack? = null

    private var statsJob: Job? = null
    private var lastQuality = "1080p"
    private var closed = false

    companion object {
        private val factoryInitialized = AtomicBoolean(false)

        private fun ensureFactoryInitialized(context: Context) {
            if (factoryInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
            }
        }

        /** Reorders the video m-line so H.264 payload types come first. */
        private fun preferH264(sdp: String): String {
            val lines = sdp.lines().toMutableList()
            val mIdx = lines.indexOfFirst { it.startsWith("m=video ") }
            if (mIdx < 0) return sdp
            val h264Pts = lines.mapNotNull { line ->
                Regex("^a=rtpmap:(\\d+) H264/90000").find(line)?.groupValues?.get(1)
            }
            if (h264Pts.isEmpty()) return sdp
            val parts = lines[mIdx].split(" ")
            if (parts.size < 4) return sdp
            val header = parts.take(3)
            val pts = parts.drop(3)
            val reordered = (h264Pts.filter { it in pts } + pts.filter { it !in h264Pts })
            lines[mIdx] = (header + reordered).joinToString(" ")
            return lines.joinToString("\r\n")
        }
    }

    init {
        ensureFactoryInitialized(appContext)
        // Hardware encoder/decoder factory; H264 preferred via SDP reorder.
        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    // ------------------------------------------------------------------ SDP

    /** Creates the offer (offerer also creates both DataChannels first). */
    suspend fun createOffer(): String = suspendCancellableCoroutine { cont ->
        try {
            val pc = ensurePeerConnection()
            createLocalDataChannels(pc)
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {
                    pc.setLocalDescription(sdpObserver(
                        onSet = { if (cont.isActive) cont.resume(preferH264(desc.description)) },
                        onSetFail = { e -> if (cont.isActive) cont.resumeWithException(RuntimeException("setLocalDescription failed: $e")) }
                    ), desc)
                }

                override fun onCreateFailure(error: String) {
                    if (cont.isActive) cont.resumeWithException(RuntimeException("createOffer failed: $error"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String) {}
            }, MediaConstraints())
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /** Applies the remote offer, then returns our answer SDP. */
    suspend fun createAnswer(offerSdp: String): String = suspendCancellableCoroutine { cont ->
        try {
            val pc = ensurePeerConnection()
            val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            pc.setRemoteDescription(sdpObserver(
                onSet = {
                    remoteDescSet = true
                    flushPendingIce(pc)
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(desc: SessionDescription) {
                            pc.setLocalDescription(sdpObserver(
                                onSet = { if (cont.isActive) cont.resume(preferH264(desc.description)) },
                                onSetFail = { e -> if (cont.isActive) cont.resumeWithException(RuntimeException("setLocalDescription failed: $e")) }
                            ), desc)
                        }

                        override fun onCreateFailure(error: String) {
                            if (cont.isActive) cont.resumeWithException(RuntimeException("createAnswer failed: $error"))
                        }

                        override fun onSetSuccess() {}
                        override fun onSetFailure(error: String) {}
                    }, MediaConstraints())
                },
                onSetFail = { e -> if (cont.isActive) cont.resumeWithException(RuntimeException("setRemoteDescription(offer) failed: $e")) }
            ), offer)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /** Applies the answer to our earlier offer. */
    suspend fun setRemoteAnswer(sdp: String) = suspendCancellableCoroutine { cont ->
        try {
            val pc = peerConnection
                ?: run {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("no peer connection"))
                    return@suspendCancellableCoroutine
                }
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            pc.setRemoteDescription(sdpObserver(
                onSet = {
                    remoteDescSet = true
                    flushPendingIce(pc)
                    if (cont.isActive) cont.resume(Unit)
                },
                onSetFail = { e -> if (cont.isActive) cont.resumeWithException(RuntimeException("setRemoteDescription(answer) failed: $e")) }
            ), answer)
        } catch (e: Exception) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    /** Adds one "sdpMid|sdpMLineIndex|sdp" candidate (queued until remote SDP is set). */
    fun addIce(candidate: String) {
        val parts = candidate.split("|", limit = 3)
        if (parts.size != 3) return
        val ice = IceCandidate(parts[0], parts[1].toIntOrNull() ?: 0, parts[2])
        val pc = peerConnection
        if (pc == null || !remoteDescSet) {
            synchronized(pendingIce) { pendingIce.add(ice) }
        } else {
            try {
                pc.addIceCandidate(ice)
            } catch (e: Exception) {
            }
        }
    }

    private fun flushPendingIce(pc: PeerConnection) {
        val queued = synchronized(pendingIce) {
            val copy = pendingIce.toList()
            pendingIce.clear()
            copy
        }
        queued.forEach {
            try {
                pc.addIceCandidate(it)
            } catch (e: Exception) {
            }
        }
    }

    private fun sdpObserver(onSet: () -> Unit, onSetFail: (String) -> Unit): SdpObserver =
        object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {}
            override fun onSetSuccess() = onSet()
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) = onSetFail(error)
        }

    // ---------------------------------------------------------- DataChannels

    private fun createLocalDataChannels(pc: PeerConnection) {
        if (controlDc == null) {
            val init = DataChannel.Init().apply { ordered = true }
            controlDc = pc.createDataChannel("devxyz", init)?.also { registerDataChannel(it) }
        }
        if (fileDc == null) {
            val init = DataChannel.Init().apply { ordered = true }
            fileDc = pc.createDataChannel("devxyz-file", init)?.also { registerDataChannel(it) }
        }
    }

    private fun registerDataChannel(dc: DataChannel) {
        when (dc.label()) {
            "devxyz" -> controlDc = dc
            "devxyz-file" -> fileDc = dc
        }
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {}

            override fun onMessage(buffer: DataChannel.Buffer) {
                try {
                    val bytes = ByteArray(buffer.data.remaining())
                    buffer.data.get(bytes)
                    val text = String(bytes, Charsets.UTF_8)
                    onDataChannelMessage?.invoke(dc.label(), text)
                } catch (e: Exception) {
                }
            }
        })
    }

    private fun sendOn(dc: DataChannel?, text: String): Boolean {
        val channel = dc ?: return false
        return try {
            if (channel.state() != DataChannel.State.OPEN) return false
            val bytes = text.toByteArray(Charsets.UTF_8)
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false))
        } catch (e: Exception) {
            false
        }
    }

    /** Sends on the "devxyz" control channel. */
    fun sendControl(text: String): Boolean = sendOn(controlDc, text)

    /** Sends on the "devxyz-file" channel. */
    fun sendFileData(text: String): Boolean = sendOn(fileDc, text)

    // ----------------------------------------------------------------- media

    /** Starts screen capture into a new video track added to the connection. */
    fun startCapture(capturer: VideoCapturer, width: Int, height: Int, fps: Int) {
        stopCapture()
        prepareVideoTrack()
        attachCapturer(capturer, width, height, fps)
    }

    /**
     * Creates the video source + track and adds it to the PeerConnection.
     * MUST be called before createOffer/createAnswer so the SDP includes the
     * video m-line. The track stays black until [attachCapturer] wires a
     * real capturer — no renegotiation needed.
     */
    fun prepareVideoTrack() {
        if (videoTrack != null) return
        try {
            val pc = ensurePeerConnection()
            val helper = SurfaceTextureHelper.create("devxyz-capture", eglBase.eglBaseContext)
            captureHelper = helper
            val source = factory.createVideoSource(false)
            videoSource = source
            videoTrack = factory.createVideoTrack("devxyz-video", source)
            pc.addTrack(videoTrack, listOf("devxyz-screen"))
            applyQualityBitrate()
        } catch (e: Exception) {
        }
    }

    /** True once [prepareVideoTrack] has added the track. */
    val hasVideoTrack: Boolean get() = videoTrack != null

    /**
     * Attaches a capturer to the prepared video source and starts capture.
     * Call after [prepareVideoTrack] (or standalone — it prepares first).
     */
    fun attachCapturer(capturer: VideoCapturer, width: Int, height: Int, fps: Int) {
        try {
            if (videoTrack == null) prepareVideoTrack()
            val source = videoSource ?: return
            val helper = captureHelper ?: return
            try {
                this.capturer?.stopCapture()
            } catch (e: Exception) {
            }
            try {
                this.capturer?.dispose()
            } catch (e: Exception) {
            }
            this.capturer = capturer
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(width, height, fps)
        } catch (e: Exception) {
        }
    }

    fun stopCapture() {
        try {
            capturer?.stopCapture()
        } catch (e: Exception) {
        }
        try {
            capturer?.dispose()
        } catch (e: Exception) {
        }
        capturer = null
        try {
            val track = videoTrack
            if (track != null) {
                val sender = peerConnection?.senders?.firstOrNull { it.track() == track }
                if (sender != null) {
                    peerConnection?.removeTrack(sender)
                }
            }
        } catch (e: Exception) {
        }
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        captureHelper?.dispose()
        captureHelper = null
    }

    fun setMicrophoneEnabled(enabled: Boolean) {
        try {
            audioTrack?.setEnabled(enabled)
        } catch (e: Exception) {
        }
    }

    /** 720p -> ~1.2 Mbps, 1080p -> ~2.5 Mbps, applied to the video sender. */
    fun setQualityMode(quality: String) {
        lastQuality = quality
        applyQualityBitrate()
    }

    private fun applyQualityBitrate() {
        val bitrateBps = when (lastQuality) {
            "720p" -> 1_200_000
            else -> 2_500_000
        }
        try {
            peerConnection?.senders?.forEach { sender ->
                if (sender.track()?.kind() == MediaStreamTrack.VIDEO_TRACK_KIND) {
                    val params = sender.parameters
                    params.encodings.forEach { it.maxBitrateBps = bitrateBps }
                    sender.parameters = params
                }
            }
        } catch (e: Exception) {
        }
    }

    fun attachRenderer(view: SurfaceViewRenderer) {
        try {
            view.init(eglBase.eglBaseContext, null)
            renderer = view
            remoteVideoTrack?.addSink(view)
        } catch (e: Exception) {
        }
    }

    fun detachRenderer() {
        try {
            val view = renderer
            if (view != null) {
                remoteVideoTrack?.removeSink(view)
                view.release()
            }
        } catch (e: Exception) {
        }
        renderer = null
    }

    // ------------------------------------------------------------- lifecycle

    private fun ensurePeerConnection(): PeerConnection {
        peerConnection?.let { return it }
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onRenegotiationNeeded() {}

            override fun onIceCandidate(candidate: IceCandidate) {
                onIceCandidate?.invoke("${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}")
            }

            override fun onDataChannel(dc: DataChannel) {
                registerDataChannel(dc)
            }

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val track = receiver.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    renderer?.let {
                        try {
                            track.addSink(it)
                        } catch (e: Exception) {
                        }
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> onConnected?.invoke()
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> onConnectionLost?.invoke()
                    else -> {}
                }
            }
        }) ?: error("Failed to create PeerConnection")

        // Voice call audio track (enabled by default; toggle via setMicrophoneEnabled).
        try {
            val audioSource = factory.createAudioSource(MediaConstraints())
            audioTrack = factory.createAudioTrack("devxyz-audio", audioSource)
            pc.addTrack(audioTrack, listOf("devxyz-stream"))
        } catch (e: Exception) {
        }

        peerConnection = pc
        startStatsLoop()
        return pc
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(2000)
                try {
                    val pc = peerConnection ?: continue
                    pc.getStats(object : RTCStatsCollectorCallback {
                        override fun onStatsDelivered(report: RTCStatsReport) {
                            extractRttMs(report)?.let { _rttMs.value = it }
                        }
                    })
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun extractRttMs(report: RTCStatsReport): Long? {
        for (stats in report.statsMap.values) {
            if (stats.type != "candidate-pair") continue
            val members = stats.members
            val nominated = members["nominated"] as? Boolean ?: false
            val selected = members["selected"] as? Boolean ?: false
            if (!nominated && !selected) continue
            val rttSec = members["currentRoundTripTime"] as? Double ?: continue
            return (rttSec * 1000).toLong().coerceAtLeast(0)
        }
        return null
    }

    fun close() {
        if (closed) return
        closed = true
        statsJob?.cancel()
        scope.cancel()
        stopCapture()
        try {
            controlDc?.close()
            controlDc?.dispose()
        } catch (e: Exception) {
        }
        try {
            fileDc?.close()
            fileDc?.dispose()
        } catch (e: Exception) {
        }
        controlDc = null
        fileDc = null
        try {
            remoteVideoTrack?.let { track ->
                renderer?.let { track.removeSink(it) }
            }
        } catch (e: Exception) {
        }
        remoteVideoTrack = null
        renderer = null
        try {
            audioTrack?.dispose()
        } catch (e: Exception) {
        }
        audioTrack = null
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (e: Exception) {
        }
        peerConnection = null
        try {
            factory.dispose()
        } catch (e: Exception) {
        }
        try {
            eglBase.release()
        } catch (e: Exception) {
        }
    }
}
