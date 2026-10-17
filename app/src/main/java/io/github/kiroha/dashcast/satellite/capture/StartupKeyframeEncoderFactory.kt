package io.github.kiroha.dashcast.satellite.capture

import org.webrtc.EncodedImage
import org.webrtc.VideoCodecInfo
import org.webrtc.VideoCodecStatus
import org.webrtc.VideoEncoder
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoFrame

/** This factory belongs to one peer, so delayed native creation cannot acquire another peer's ticket. */
internal class StartupKeyframeEncoderFactory(
    private val delegate: VideoEncoderFactory,
    private val ticket: StartupKeyframePolicy,
    private val diagnostics: EncodedH264Diagnostics? = null,
    private val profileUnsupported: (() -> Unit)? = null,
) : VideoEncoderFactory {
    override fun getSupportedCodecs() = delegate.supportedCodecs
    override fun getImplementations() = delegate.implementations
    override fun getEncoderSelector() = delegate.encoderSelector
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (!ticket.ownsSource()) return null // Pre-CONNECTED is valid; revoked ownership is not.
        val encoder = delegate.createEncoder(info) ?: return null
        if (!ticket.ownsSource()) { runCatching { encoder.release() }; return null }
        return if (info.name.equals("H264", true)) StartupKeyframeEncoder(encoder, ticket, diagnostics,
            profileUnsupported?.let { H264ProfileGuard(ticket::ownsSource, it) }) else encoder
    }
}

internal class StartupKeyframeEncoder(
    private val delegate: VideoEncoder,
    private val ticket: StartupKeyframePolicy,
    private val diagnostics: EncodedH264Diagnostics? = null,
    private val profileGuard: H264ProfileGuard? = null,
) : VideoEncoder {
    // Java default methods are explicit: Kotlin interface delegation can select the SDK default
    // rather than a delegate override (including rates, alignment and the native-encoder path).
    override fun createNative(environment: Long) = delegate.createNative(environment)
    override fun isHardwareEncoder() = delegate.isHardwareEncoder
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int) = delegate.setRateAllocation(allocation, framerate)
    override fun setRates(parameters: VideoEncoder.RateControlParameters) = delegate.setRates(parameters)
    override fun getScalingSettings() = delegate.scalingSettings
    override fun getResolutionBitrateLimits() = delegate.resolutionBitrateLimits
    override fun getImplementationName() = delegate.implementationName
    override fun getEncoderInfo() = delegate.encoderInfo
    private val owner = Any()
    private var initialized = false
    private var bound = false
    private var retired = false
    private var profileOwner: Any? = null
    override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        profileGuard?.retire(profileOwner)
        val profileToken = profileGuard?.begin().also { profileOwner = it }
        if (initialized) { ticket.retire(owner); diagnostics?.retire(owner); bound = false; retired = true }
        initialized = true
        if (!retired) diagnostics?.bind(owner)
        val forwarded = if (diagnostics != null || profileGuard != null) VideoEncoder.Callback { image, info ->
            // Optional diagnostics cannot suppress delivery. The independent compatibility gate can.
            try { diagnostics?.inspect(owner, image) } catch (_: Exception) { /* Optional diagnostics only. */ }
            if (profileGuard == null || profileGuard.allow(checkNotNull(profileToken), image.buffer))
                callback.onEncodedFrame(image, info)
        } else callback
        val status = try { delegate.initEncode(settings, forwarded) }
        catch (error: Throwable) { diagnostics?.retire(owner); profileGuard?.retire(profileToken); throw error }
        if (status != VideoCodecStatus.OK) { diagnostics?.retire(owner); profileGuard?.retire(profileToken) }
        if (!retired && !bound && status == VideoCodecStatus.OK) bound = ticket.bind(owner)
        return status
    }
    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val types = info.frameTypes
        val nativeKey = types.any { it == EncodedImage.FrameType.VideoFrameKey }
        val encodable = types.isNotEmpty() && types.all { it != EncodedImage.FrameType.EmptyFrame }
        val request = bound && ticket.request(owner, nativeKey, encodable)
        val forwarded = if (request) VideoEncoder.EncodeInfo(types.copyOf().also {
            it[0] = EncodedImage.FrameType.VideoFrameKey
        }) else info
        return delegate.encode(frame, forwarded)
    }
    override fun release(): VideoCodecStatus {
        profileGuard?.retire(profileOwner)
        ticket.retire(owner); diagnostics?.retire(owner); bound = false; retired = true
        return delegate.release()
    }
}
