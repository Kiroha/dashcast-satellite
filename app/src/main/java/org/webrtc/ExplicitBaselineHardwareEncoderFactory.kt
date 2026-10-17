package org.webrtc

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.Surface
import io.github.kiroha.dashcast.satellite.capture.H264BaselineContract
import java.nio.ByteBuffer

/**
 * Narrow adapter to package-private APIs in the SHA-pinned 150.7871.01 Java SDK.
 * The SDK's Baseline branch omits MediaFormat profile/level; some hardware then defaults to High.
 * Reuses the unmodified SDK encoder and its codec selection. No reflection or native/AAR patching.
 */
internal class ExplicitBaselineHardwareEncoderFactory(
    private val sharedContext: EglBase.Context?,
    private val unsupported: () -> Unit,
    private val delegate: VideoEncoderFactory = HardwareVideoEncoderFactory(sharedContext, false, false),
    private val configuration: (String) -> Configuration? = ::configuration,
    private val wrappers: MediaCodecWrapperFactory = MediaCodecWrapperFactoryImpl(),
) : VideoEncoderFactory {
    internal data class Configuration(val surface: Int?, val yuv: Int, val profile: Int)
    override fun getSupportedCodecs() = delegate.supportedCodecs
    override fun getImplementations() = delegate.implementations
    override fun getEncoderSelector() = delegate.encoderSelector

    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? {
        if (!info.name.equals("H264", true)) return delegate.createEncoder(info)
        if (!H264BaselineContract.permitsLevel31(info.params)) { unsupported(); return null }
        val candidate = delegate.createEncoder(info) ?: return null
        var released = false
        val selected = try {
            if (candidate !is HardwareVideoEncoder) null
            else candidate.implementationName.let { name -> configuration(name)?.let { name to it } }
        } catch (_: Exception) { null }
        finally {
            // The upstream candidate has never been initialized: no codec, EGL surface or GL shader.
            released = try { candidate.release() == VideoCodecStatus.OK } catch (_: Exception) { false }
        }
        if (!released || selected == null) { unsupported(); return null }
        val (name, config) = selected
        return HardwareVideoEncoder(ProfileCodecFactory(wrappers, config.profile, unsupported), name, VideoCodecMimeType.H264,
            config.surface, config.yuv, info.params, 3600, 0,
            if (name.startsWith("OMX.Exynos.")) FramerateBitrateAdjuster() else BaseBitrateAdjuster(),
            sharedContext as? EglBase14.Context)
    }

    companion object {
        private fun configuration(name: String): Configuration? {
            val codec = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { it.name == name } ?: return null
            if (!codec.isEncoder || (Build.VERSION.SDK_INT >= 29 && !codec.isHardwareAccelerated)) return null
            val caps = codec.getCapabilitiesForType("video/avc")
            val profile = selectProfile(Build.VERSION.SDK_INT, caps.profileLevels) ?: return null
            val yuv = MediaCodecUtils.selectColorFormat(MediaCodecUtils.ENCODER_COLOR_FORMATS, caps) ?: return null
            return Configuration(MediaCodecUtils.selectColorFormat(MediaCodecUtils.TEXTURE_COLOR_FORMATS, caps), yuv, profile)
        }
        internal fun selectProfile(api: Int, capabilities: Array<MediaCodecInfo.CodecProfileLevel>): Int? {
            val profiles = capabilities.filter { it.level >= MediaCodecInfo.CodecProfileLevel.AVCLevel31 }.map { it.profile }
            return when {
                Build.VERSION.SDK_INT >= 27 && api >= 27 && MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline in profiles ->
                    MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline in profiles -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                else -> null
            }
        }
    }
}

/** Applied on every configure, including the SDK's internal resolution/surface-mode restart. */
internal class ProfileCodecFactory(private val delegate: MediaCodecWrapperFactory, private val profile: Int,
    private val unsupported: () -> Unit = {}) : MediaCodecWrapperFactory {
    override fun createByCodecName(name: String): MediaCodecWrapper = ProfileCodec(delegate.createByCodecName(name), profile, unsupported)
}

internal class ProfileCodec(private val delegate: MediaCodecWrapper, private val profile: Int,
    private val unsupported: () -> Unit = {}) : MediaCodecWrapper {
    private var released = false
    override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
        try {
            require(format.getString(MediaFormat.KEY_MIME) == "video/avc")
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            delegate.configure(format, surface, crypto, flags)
        } catch (error: Exception) {
            // The pinned SDK has no output thread yet and otherwise drops this codec on refusal.
            try { release() } catch (_: Exception) { /* Preserve the configuration refusal as the cause. */ }
            unsupported()
            // JNI's pinned encoder translates these two exception types into its ordinary refusal status.
            if (error is IllegalArgumentException || error is IllegalStateException) throw error
            throw IllegalStateException("Hardware H264 configuration failed", error)
        }
    }
    override fun start() = delegate.start()
    override fun flush() = delegate.flush()
    override fun stop() = delegate.stop()
    override fun release() { if (!released) { released = true; delegate.release() } }
    override fun dequeueInputBuffer(timeoutUs: Long) = delegate.dequeueInputBuffer(timeoutUs)
    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) =
        delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    override fun dequeueOutputBuffer(info: android.media.MediaCodec.BufferInfo, timeoutUs: Long) = delegate.dequeueOutputBuffer(info, timeoutUs)
    override fun releaseOutputBuffer(index: Int, render: Boolean) = delegate.releaseOutputBuffer(index, render)
    override fun getInputFormat(): MediaFormat? = delegate.inputFormat
    override fun getOutputFormat(): MediaFormat? = delegate.outputFormat
    override fun getOutputFormat(index: Int): MediaFormat? = delegate.getOutputFormat(index)
    override fun getInputBuffer(index: Int): ByteBuffer? = delegate.getInputBuffer(index)
    override fun getOutputBuffer(index: Int): ByteBuffer? = delegate.getOutputBuffer(index)
    override fun createInputSurface(): Surface? = delegate.createInputSurface()
    override fun setParameters(params: Bundle) = delegate.setParameters(params)
    override fun getCodecInfo(): MediaCodecInfo? = delegate.codecInfo
}
