package io.github.kiroha.dashcast.satellite.capture

import android.content.Context
import android.media.projection.MediaProjection
import org.json.JSONObject
import org.webrtc.*
import java.io.Closeable
import java.util.UUID

/** All methods/callbacks are serialized by the capture service worker, never the UI thread. */
internal class NativeVideoSender(
    private val context: Context,
    private val link: VideoLink,
    private val post: (() -> Unit) -> Unit,
    private val failed: (String) -> Unit,
) : Closeable {
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
    fun start(projection: MediaProjection, width: Int, height: Int, density: Int) {
        this.projection = projection
        initializeNative(context)
        NetworkMonitor.getInstance().setNetworkChangeDetectorFactory(LanNetworkDetector.factory(link.lan))
        val base = EglBase.create().also { egl = it }
        val encoder = HardwareVideoEncoderFactory(base.eglBaseContext, false, false)
        require(encoder.supportedCodecs.isNotEmpty()) { "hardware_encoder_unavailable" }
        val options = PeerConnectionFactory.Options().apply {
            networkIgnoreMask = PeerConnectionFactory.Options.ADAPTER_TYPE_CELLULAR or PeerConnectionFactory.Options.ADAPTER_TYPE_VPN or
                PeerConnectionFactory.Options.ADAPTER_TYPE_LOOPBACK or PeerConnectionFactory.Options.ADAPTER_TYPE_UNKNOWN
        }
        factory = PeerConnectionFactory.builder().setOptions(options).setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(SoftwareVideoDecoderFactory()).createPeerConnectionFactory()
        helper = SurfaceTextureHelper.create("dashsat-texture", base.eglBaseContext) ?: error("texture_unavailable")
        source = factory!!.createVideoSource(true).apply { adaptOutputFormat(VideoPolicy.WIDTH, VideoPolicy.HEIGHT, VideoPolicy.FPS) }
        track = factory!!.createVideoTrack("dashsat-video", source!!)
        capture = ProjectionTextureCapturer(projection, helper!!, source!!.capturerObserver, density,
            stopped = { code -> post { if (!closed) failed(code) } },
            resized = { w, h -> source?.adaptOutputFormat(w, h, VideoPolicy.FPS) }
        )
        capture!!.start(width, height)
        negotiate()
    }
    private fun negotiate() {
        if (closed || !link.viewerReady || VideoControlBridge.link !== link) return
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
                post { if (owns(current) && state == PeerConnection.PeerConnectionState.CONNECTED) VideoState.status = "connected_waiting_frames" }
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
        frameWatchdog = EncodedFrameWatchdog(System.nanoTime())
        connection.createOffer(sdpObserver(current, created = { description ->
            connection.setLocalDescription(sdpObserver(current, applied = {
                val sdp = VideoPolicy.sdp(connection.localDescription.description, link.lan.addresses)
                link.send(JSONObject().put("type", "video.offer").put("negotiation", current.id).put("sdp", sdp)) { sent ->
                    post { if (owns(current)) {
                        if (sent) current.offerSent { send(it, current) } else failed("control_unavailable")
                    } }
                }
            }), description)
        }), MediaConstraints())
    }
    fun signal(message: JSONObject) {
        val current = attempt ?: return
        val type = message.optString("type")
        if (type == "video.closed") { failed("viewer_closed"); return }
        if (message.optString("negotiation") != current.id || !owns(current)) return
        try {
            when (type) {
                "video.answer" -> {
                    val sdp = VideoPolicy.sdp(message.getString("sdp"), receiverHost = link.lan.receiverHost)
                    peer!!.setRemoteDescription(sdpObserver(current, applied = {
                        current.answerApplied { applyIce(it, current) }
                    }), SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
                "video.ice" -> {
                    val candidate = VideoPolicy.receiverCandidate(message.getString("candidate"), link.lan.receiverHost)
                        ?: return // Discard other interfaces, IPv6, public and relay candidates.
                    message.put("candidate", candidate)
                    require(message.getString("sdpMid").length <= 64)
                    require(message.get("sdpMLineIndex") is Int && message.getInt("sdpMLineIndex") in 0..8)
                    if (!current.remoteIce(message.toString()) { applyIce(it, current) }) failed("signalling_overflow")
                }
                "video.error" -> {
                    // The waiting viewer remains available after an error; no new ready event is needed.
                    if (++retries <= 2) negotiate() else failed("receiver_video_failed")
                }
            }
        } catch (_: Exception) { failed("video_protocol_failed") }
    }
    private fun applyIce(raw: String, current: VideoNegotiation) {
        if (!owns(current)) return
        val message = JSONObject(raw)
        if (peer?.addIceCandidate(IceCandidate(message.getString("sdpMid"), message.getInt("sdpMLineIndex"),
            message.getString("candidate"))) != true) failed("ice_failed")
    }
    private fun send(raw: String, current: VideoNegotiation) {
        link.send(JSONObject(raw)) { success -> post { if (owns(current) && !success) failed("control_unavailable") } }
    }
    private fun owns(value: VideoNegotiation) = !closed && attempt === value && !value.closed && VideoControlBridge.link === link
    private fun sdpObserver(current: VideoNegotiation, created: (SessionDescription) -> Unit = {}, applied: () -> Unit = {}) =
        object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) { post { if (owns(current)) runGuarded { created(description) } } }
            override fun onSetSuccess() { post { if (owns(current)) runGuarded(applied) } }
            override fun onCreateFailure(error: String) { post { if (owns(current)) failed("negotiation_failed") } }
            override fun onSetFailure(error: String) { post { if (owns(current)) failed("negotiation_failed") } }
        }
    private fun runGuarded(action: () -> Unit) { try { action() } catch (_: Exception) { failed("negotiation_failed") } }
    fun collectStats() {
        frameWatchdog?.failure(System.nanoTime())?.let { failed(it); return }
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
                VideoState.status == "connected_waiting_frames") VideoState.status = "streaming"
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
