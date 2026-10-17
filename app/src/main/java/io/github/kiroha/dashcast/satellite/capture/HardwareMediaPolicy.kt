package io.github.kiroha.dashcast.satellite.capture

import org.webrtc.*

/** The current interop contract is hardware H.264 Baseline 3.1, including the pre-encoded test. */
internal class H264OnlyEncoderFactory(private val delegate: VideoEncoderFactory) : VideoEncoderFactory {
    private fun supported(info: VideoCodecInfo) = info.name == "H264" && H264BaselineContract.permitsLevel31(info.params)
    override fun getSupportedCodecs() = delegate.supportedCodecs.filter(::supported).toTypedArray()
    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = null
    override fun getImplementations() = delegate.implementations.filter(::supported).toTypedArray()
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = if (supported(info)) delegate.createEncoder(info) else null
}

/** A send-only companion must not install an unused software receive/decode path. */
internal object ReceiveNothingDecoderFactory : VideoDecoderFactory {
    override fun getSupportedCodecs() = emptyArray<VideoCodecInfo>()
    override fun createDecoder(info: VideoCodecInfo): VideoDecoder? = null
}
