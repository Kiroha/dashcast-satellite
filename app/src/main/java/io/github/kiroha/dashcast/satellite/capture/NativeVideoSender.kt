package io.github.kiroha.dashcast.satellite.capture

import android.content.Context
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.webrtc.*
import java.util.UUID

/** All methods/callbacks are serialized by the capture service worker, never the UI thread. */
internal class NativeVideoSender(
    private val context: Context,
    private val link: VideoLink,
    private val post: (() -> Unit) -> Unit,
    private val failed: (String) -> Unit,
    private val active: () -> Boolean = { true },
    private val stateChanged: () -> Unit = {},
) : VideoCaptureEngine {
    private var egl: EglBase? = null
    private var helper: SurfaceTextureHelper? = null
    private var factory: PeerConnectionFactory? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var capture: ProjectionTextureCapturer? = null
    private var projection: MediaProjection? = null
    private var peer: PeerConnection? = null
    private var attempt: VideoNegotiation? = null
    private var closed = false
    private var retries = 0
    private var frameWatchdog: EncodedFrameWatchdog? = null
    private var statsPending = false
    private var lastBytes = 0L
    private var lastStatsUs = 0.0
    private var width = 0
    private var height = 0
    private var density = 0
    private val sharingSession = VideoDiagnostics.currentShare()
    private val viewerSession = ViewerCaptureSession(
        initialize = { initializeCapture() },
        resume = { retries = 0; capture?.setDeliveryEnabled(true); track?.setEnabled(true); negotiate() },
        pause = { track?.setEnabled(false); capture?.setDeliveryEnabled(false); disposePeer() },
        waiting = { if (active()) {
            val status = if (capture?.available == false) "capture_paused" else "waiting_viewer"
            if (VideoState.status != status) VideoDiagnostics.record(if (status == "capture_paused")
                VideoDiagnostics.Event.CAPTURE_HIDDEN else VideoDiagnostics.Event.WAITING_VIEWER)
            VideoState.status = status; VideoState.metrics = ""
            stateChanged()
        } },
        isReady = { !closed && active() && link.viewerReady && VideoControlBridge.link === link && capture?.available != false },
    )
    override fun start(projection: MediaProjection, width: Int, height: Int, density: Int) {
        this.projection = projection
        this.width = width; this.height = height; this.density = density
        // No native factory, texture, encoder, ICE or VD until the first visible viewer.
        viewerSession.reconcile()
    }
    override fun viewerChanged(ready: Boolean) {
        // A closed event always fences the old peer, even if a newer ready is already queued.
        if (!ready) viewerSession.viewerClosed() else viewerSession.reconcile()
    }
    private fun initializeCapture() {
        val projection = checkNotNull(projection)
        VideoDiagnostics.record(VideoDiagnostics.Event.NATIVE_INITIALIZING)
        initializeNative(context)
        NetworkMonitor.getInstance().setNetworkChangeDetectorFactory(LanNetworkDetector.factory(link.lan))
        val base = EglBase.create().also { egl = it }
        val encoder = HardwareVideoEncoderFactory(base.eglBaseContext, false, false)
        VideoDiagnostics.record(VideoDiagnostics.Event.ENCODER_CHECK)
        if (encoder.supportedCodecs.isEmpty()) {
            VideoDiagnostics.record(VideoDiagnostics.Event.HARDWARE_UNAVAILABLE)
            error("hardware_encoder_unavailable")
        }
        val options = PeerConnectionFactory.Options().apply {
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or PeerConnectionFactory.Options.ADAPTER_TYPE_VPN or
                PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK or PeerConnectionFactory.Options.ADAPTER_TYPE_UNKNOWN
        }
        factory = PeerConnectionFactory.builder().setOptions(options).setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory()).createPeerConnectionFactory()
        VideoDiagnostics.record(VideoDiagnostics.Event.TEXTURE_INITIALIZING)
        helper = SurfaceTextureHelper.create("dashsat-texture", base.eglBaseContext) ?: error("texture_unavailable")
        source = factory!!.createVideoSource(true).apply { adaptOutputFormat(VideoPolicy.WIDTH, VideoPolicy.HEIGHT, VideoPolicy.FPS) }
        track = factory!!.createVideoTrack("dashsat-video", source!!)
        capture = ProjectionTextureCapturer(projection, helper!!, source!!.capturerObserver, density,
            stopped = { code -> post { if (!closed) failed(code) } },
            resized = { w, h -> source?.adaptOutputFormat(w, h, VideoPolicy.FPS) },
            availabilityChanged = { ready -> post {
                if (!closed && active() && VideoControlBridge.link === link) {
                    // Ordered source loss always fences the old peer, even if a newer texture
                    // is already ready by the time this worker drains its callback queue.
                    if (!ready) viewerSession.viewerClosed() else viewerSession.reconcile()
                }
            } }
        )
        VideoDiagnostics.record(VideoDiagnostics.Event.DISPLAY_INITIALIZING)
        capture!!.start(width, height)
        VideoDiagnostics.record(VideoDiagnostics.Event.CAPTURE_INITIALIZED)
    }
    private fun negotiate() {
        if (closed || !active() || !link.viewerReady || VideoControlBridge.link !== link || capture?.available != true) return
        disposePeer()
        val current = VideoNegotiation(UUID.randomUUID().toString()).also { attempt = it }
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            disableIPv6OnWifi = true; maxIPv6Networks = 0
            enableIceGatheringOnAnyAddressPorts = false
            iceCandidatePoolSize = 0
        }
        val connection = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                post { if (owns(current) && state in setOf(PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.DISCONNECTED)) failed("media_connection_lost") }
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                post { if (owns(current) && state == PeerConnection.PeerConnectionState.CONNECTED) {
                    VideoDiagnostics.record(VideoDiagnostics.Event.MEDIA_CONNECTED)
                    VideoState.status = "connected_waiting_frames"
                } }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) {
                post {
                    if (!owns(current) || !VideoPolicy.localCandidate(candidate.sdp, link.lan.addresses)) return@post
                    val message = JSONObject().put("type", "video.ice").put("negotiation", current.id)
                        .put("candidate", candidate.sdp).put("sdpMid", candidate.sdpMid ?: "0")
                        .put("sdpMLineIndex", candidate.sdpMLineIndex).toString()
                    if (!current.localIce(message) { send(it, current) }) failed("signalling_overflow")
                }
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) { channel.close() }
            override fun onRenegotiationNeeded() = Unit
        }) ?: error("peer_unavailable")
        peer = connection
        connection.setAudioPlayout(false); connection.setAudioRecording(false)
        val transceiver = connection.addTransceiver(track!!,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
        val codecs = factory!!.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO).codecs
        transceiver.setCodecPreferences(codecs.sortedBy { if (it.name.equals("H264", true)) 0 else 1 })
        val params = transceiver.sender.parameters
        params.encodings.forEach { it.maxBitrateBps = VideoPolicy.BITRATE; it.maxFramerate = VideoPolicy.FPS }
        check(transceiver.sender.setParameters(params))
        connection.setBitrate(100_000, 900_000, VideoPolicy.BITRATE)
        VideoState.status = "negotiating"
        VideoDiagnostics.record(VideoDiagnostics.Event.NEGOTIATING)
        frameWatchdog = EncodedFrameWatchdog(System.nanoTime())
        stateChanged()
        connection.createOffer(sdpObserver(current, created = { description ->
            connection.setLocalDescription(sdpObserver(current, applied = {
                val sdp = VideoPolicy.sdp(connection.localDescription.description, link.lan.addresses)
                val completion = VideoSendCompletion(VideoDiagnostics.Event.OFFER_SENT, sharingSession, post,
                    owns = { owns(current) }, sent = { current.offerSent { send(it, current) } },
                    failed = { failed("control_unavailable") })
                link.send(JSONObject().put("type", "video.offer").put("negotiation", current.id).put("sdp", sdp),
                    completion::complete)
            }), description)
        }), MediaConstraints())
    }
    override fun signal(message: JSONObject) {
        val current = attempt ?: return
        val type = message.optString("type")
        if (message.optString("negotiation") != current.id || !owns(current)) return
        try {
            when (type) {
                "video.answer" -> {
                    VideoDiagnostics.record(VideoDiagnostics.Event.ANSWER_RECEIVED)
                    val sdp = VideoPolicy.sdp(message.getString("sdp"), receiverHost = link.lan.receiverHost)
                    peer!!.setRemoteDescription(sdpObserver(current, applied = {
                        VideoDiagnostics.record(VideoDiagnostics.Event.ANSWER_APPLIED)
                        current.answerApplied { applyIce(it, current) }
                    }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
                "video.ice" -> {
                    val candidate = VideoPolicy.receiverCandidate(message.getString("candidate"), link.lan.receiverHost)
                        ?: return // Discard other interfaces, IPv6, public and relay candidates.
                    message.put("candidate", candidate)
                    VideoDiagnostics.record(VideoDiagnostics.Event.ICE_RECEIVED, recent = false)
                    require(message.getString("sdpMid").length <= 64)
                    require(message.get("sdpMLineIndex") is Int && message.getInt("sdpMLineIndex") in 0..8)
                    if (!current.remoteIce(message.toString()) { applyIce(it, current) }) failed("signalling_overflow")
                }
                "video.error" -> {
                    VideoDiagnostics.record(VideoDiagnostics.Event.RECEIVER_ERROR)
                    // The waiting viewer remains available after an error; no new ready event is needed.
                    if (++retries <= 2) negotiate() else failed("receiver_video_failed")
                }
            }
        } catch (error: Exception) { VideoDiagnostics.exception(VideoDiagnostics.Event.NEGOTIATION_FAILED, error); failed("video_protocol_failed") }
    }
    private fun applyIce(raw: String, current: VideoNegotiation) {
        if (!owns(current)) return
        val message = JSONObject(raw)
        if (peer?.addIceCandidate(IceCandidate(message.getString("sdpMid"), message.getInt("sdpMLineIndex"),
            message.getString("candidate"))) != true) failed("ice_failed")
    }
    private fun send(raw: String, current: VideoNegotiation) {
        val completion = VideoSendCompletion(VideoDiagnostics.Event.ICE_SENT, sharingSession, post,
            owns = { owns(current) }, failed = { failed("control_unavailable") })
        link.send(JSONObject(raw), completion::complete)
    }
    private fun owns(value: VideoNegotiation) = !closed && active() && capture?.available == true && link.viewerReady && attempt === value && !value.closed && VideoControlBridge.link === link
    private fun sdpObserver(current: VideoNegotiation, created: (SessionDescription) -> Unit = {}, applied: () -> Unit = {}) =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { post { if (owns(current)) runGuarded { created(description) } } }
            override fun onSetSuccess() { post { if (owns(current)) runGuarded(applied) } }
            override fun onCreateFailure(error: String) { post { if (owns(current)) failed("negotiation_failed") } }
            override fun onSetFailure(error: String) { post { if (owns(current)) failed("negotiation_failed") } }
        }
    private fun runGuarded(action: () -> Unit) { try { action() } catch (error: Exception) {
        VideoDiagnostics.exception(VideoDiagnostics.Event.NEGOTIATION_FAILED, error); failed("negotiation_failed")
    } }
    override val needsStats: Boolean get() = !closed && peer != null && link.viewerReady && capture?.available == true
    override fun collectStats() {
        if (!needsStats || VideoControlBridge.link !== link) { viewerSession.reconcile(); return }
        frameWatchdog?.failure(System.nanoTime())?.let {
            // The source can pause on the texture thread between scheduling and this tick.
            if (!needsStats || VideoControlBridge.link !== link) viewerSession.reconcile() else failed(it)
            return
        }
        val connection = peer ?: return
        val current = attempt ?: return
        if (closed || statsPending) return
        statsPending = true
        frameWatchdog?.requested(System.nanoTime())
        connection.getStats { report -> post {
            if (!owns(current)) return@post
            statsPending = false
            frameWatchdog?.completed()
            val outbound = report.statsMap.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" }
            val values = outbound?.members ?: return@post
            val encoded = (values["framesEncoded"] as? Number)?.toLong() ?: 0L
            if (frameWatchdog?.encoded(encoded, System.nanoTime()) == true &&
                VideoState.status == "connected_waiting_frames") {
                VideoDiagnostics.record(VideoDiagnostics.Event.FRAMES_ENCODED)
                VideoState.status = "streaming"
            }
            val codec = report.statsMap[values["codecId"]]?.members?.get("mimeType") as? String
            val bytes = (values["bytesSent"] as? Number)?.toLong() ?: 0L
            val time = report.timestampUs
            val kbps = if (lastStatsUs > 0 && time > lastStatsUs) ((bytes-lastBytes)*8000/(time-lastStatsUs)).toInt().coerceIn(0,100_000) else 0
            lastBytes = bytes; lastStatsUs = time
            val label = when(codec) { "video/H264" -> "H.264"; "video/VP8" -> "VP8"; "video/VP9" -> "VP9"; "video/AV1" -> "AV1"; else -> "unknown codec" }
            val width = (values["frameWidth"] as? Number)?.toInt()?.coerceIn(0,8192) ?: 0
            val height = (values["frameHeight"] as? Number)?.toInt()?.coerceIn(0,8192) ?: 0
            val fps = (values["framesPerSecond"] as? Number)?.toInt()?.coerceIn(0,120) ?: 0
            VideoState.metrics = "$label · ${width}×${height} · $fps fps · $kbps kbps · hardware encoder"
        } }
    }
    private fun disposePeer() {
        attempt?.let { it.close(); link.endNegotiation(it.id) }; attempt = null
        peer?.let { runCatching { it.close() }; runCatching { it.dispose() } }; peer = null
        statsPending = false; lastStatsUs = 0.0; lastBytes = 0L; frameWatchdog = null
    }
    override fun close() {
        if (closed) return
        closed = true
        viewerSession.close()
        disposePeer()
        if (capture != null) runCatching { capture?.close() } else runCatching { projection?.stop() }
        capture = null; projection = null
        runCatching { track?.dispose() }; track = null
        runCatching { source?.dispose() }; source = null
        runCatching { helper?.dispose() }; helper = null
        runCatching { factory?.dispose() }; factory = null
        runCatching { egl?.release() }; egl = null
    }
    companion object {
        private var initialized = false
        @Synchronized private fun initializeNative(context: Context) {
            if (initialized) return
            // Drop native diagnostics, which can contain addresses, SDP and media metadata.
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setInjectableLogger({ _, _, _ -> }, Logging.Severity.LS_NONE)
                .setEnableInternalTracer(false).createInitializationOptions())
            initialized = true
        }
    }
}
