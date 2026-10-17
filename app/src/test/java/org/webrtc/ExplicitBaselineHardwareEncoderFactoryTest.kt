package org.webrtc

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCrypto
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExplicitBaselineHardwareEncoderFactoryTest {
    private val info = VideoCodecInfo("H264", mapOf("profile-level-id" to "42e01f", "packetization-mode" to "1", "level-asymmetry-allowed" to "1"), emptyList())
    private val settings = VideoEncoder.Settings(1, 960, 540, 900, 15, 1, false)
    private class Codec(private val refuse: Boolean = false, private val refusal: RuntimeException = IllegalArgumentException("fixture profile refusal")) : MediaCodecWrapper {
        var configured: MediaFormat? = null; var starts = 0; var stops = 0; var releases = 0
        var dequeues = 0; var lastParameters: Bundle? = null
        override fun configure(format: MediaFormat, surface: Surface?, crypto: MediaCrypto?, flags: Int) {
            configured = format; assertNull(surface); assertNull(crypto); assertEquals(MediaCodec.CONFIGURE_FLAG_ENCODE, flags)
            if (refuse) throw refusal
        }
        override fun start() { starts++ }
        override fun flush() = Unit
        override fun stop() { stops++ }
        override fun release() { releases++ }
        override fun dequeueInputBuffer(timeoutUs: Long): Int { dequeues++; return -1 }
        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) = Unit
        override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int { Thread.sleep(2); return -1 }
        override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit
        override fun getInputFormat() = checkNotNull(configured)
        override fun getOutputFormat() = checkNotNull(configured)
        override fun getOutputFormat(index: Int) = checkNotNull(configured)
        override fun getInputBuffer(index: Int) = ByteBuffer.allocateDirect(8)
        override fun getOutputBuffer(index: Int) = ByteBuffer.allocateDirect(8)
        override fun createInputSurface(): Surface = error("No texture allocation in byte-buffer fixture")
        override fun setParameters(params: Bundle) { lastParameters = params }
        override fun getCodecInfo(): MediaCodecInfo? = null // SDK explicitly supports unavailable optional statistics metadata.
    }
    private class Probe(private val selection: String = "c2.vendor.avc.encoder", private val failRelease: Boolean = false,
        private val throwName: Boolean = false, private val throwRelease: Boolean = false) :
        HardwareVideoEncoder(object : MediaCodecWrapperFactory {
            override fun createByCodecName(name: String): MediaCodecWrapper = error("Probe must never initialize")
        }, selection, VideoCodecMimeType.H264, null, 19, emptyMap(), 3600, 0, BaseBitrateAdjuster(), null) {
        var released = 0
        override fun getImplementationName(): String { if (throwName) throw IllegalStateException("fixture name unavailable"); return super.getImplementationName() }
        override fun release(): VideoCodecStatus { released++; if (throwRelease) throw IllegalStateException("fixture release failed"); return if (failRelease) VideoCodecStatus.ERROR else super.release() }
    }
    private fun delegate(probe: Probe) = object : VideoEncoderFactory {
        override fun createEncoder(info: VideoCodecInfo): VideoEncoder = probe
        override fun getSupportedCodecs() = arrayOf(info)
    }
    private fun factory(probe: Probe, codecs: MutableList<Codec>, reject: () -> Unit = {}, refuseAt: Int = Int.MAX_VALUE,
        refusal: RuntimeException = IllegalArgumentException("fixture profile refusal")) =
        ExplicitBaselineHardwareEncoderFactory(null, reject, delegate(probe), { name ->
            assertEquals(probe.implementationName, name)
            ExplicitBaselineHardwareEncoderFactory.Configuration(null, 19, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }, object : MediaCodecWrapperFactory {
            override fun createByCodecName(name: String): MediaCodecWrapper {
                assertEquals(probe.implementationName, name)
                return Codec(codecs.size == refuseAt, refusal).also(codecs::add)
            }
        })
    private fun checkFormat(format: MediaFormat, width: Int, height: Int, profile: Int = MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline) {
        assertEquals("video/avc", format.getString(MediaFormat.KEY_MIME))
        assertEquals(profile, format.getInteger(MediaFormat.KEY_PROFILE))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31, format.getInteger(MediaFormat.KEY_LEVEL))
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, format.getInteger(MediaFormat.KEY_BITRATE_MODE))
        assertEquals(900_000, format.getInteger(MediaFormat.KEY_BIT_RATE)); assertEquals(15f, format.getFloat(MediaFormat.KEY_FRAME_RATE))
        assertEquals(3600, format.getInteger(MediaFormat.KEY_I_FRAME_INTERVAL)); assertEquals(19, format.getInteger(MediaFormat.KEY_COLOR_FORMAT))
        assertEquals(width, format.getInteger(MediaFormat.KEY_WIDTH)); assertEquals(height, format.getInteger(MediaFormat.KEY_HEIGHT))
    }
    @Test fun `real pinned SDK init and internal resize both reach explicit configure preserving bitrate and color parameters`() {
        val probe = Probe(); val codecs = mutableListOf<Codec>(); var rejected = 0
        val encoder = checkNotNull(factory(probe, codecs, { rejected++ }, refuseAt = 1).createEncoder(info))
        assertEquals(1, probe.released); assertTrue(codecs.isEmpty()) // Probe allocated no MediaCodec.
        assertTrue(encoder is HardwareVideoEncoder); assertEquals(probe.implementationName, encoder.implementationName)
        assertEquals(VideoCodecStatus.OK, encoder.initEncode(settings, VideoEncoder.Callback { _, _ -> fail("No encoded output in fixture") }))
        checkFormat(checkNotNull(codecs[0].configured), 960, 540)
        val frame = VideoFrame(object : VideoFrame.Buffer {
            override fun getWidth() = 640
            override fun getHeight() = 360
            override fun retain() = Unit
            override fun release() = Unit
            override fun toI420(): VideoFrame.I420Buffer = error("No pixel conversion")
            override fun cropAndScale(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int): VideoFrame.Buffer = error("No scaling")
        }, 0, 0)
        try {
            // The SDK internally releases/recreates the codec on dimensions change, without wrapper initEncode.
            assertEquals(VideoCodecStatus.FALLBACK_SOFTWARE, encoder.encode(frame, VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameDelta))))
            checkFormat(checkNotNull(codecs[1].configured), 640, 360)
            assertEquals(1, rejected); assertEquals(1, codecs[0].releases); assertEquals(1, codecs[1].releases)
            assertEquals(0, codecs[1].starts)
        } finally { encoder.release() }
        assertEquals(1, codecs[1].releases) // Init refusal cleanup cannot leak or double release.
    }
    @Test fun `real SDK initialization refusal yields its failure status and releases codec exactly once without software activation`() {
        for (refusal in listOf(IllegalArgumentException("fixture"), IllegalStateException("fixture"), UnsupportedOperationException("fixture"), SecurityException("fixture"))) {
            val codecs = mutableListOf<Codec>(); var rejected = 0
            val encoder = checkNotNull(factory(Probe(), codecs, { rejected++ }, refuseAt = 0, refusal = refusal).createEncoder(info))
            assertEquals(VideoCodecStatus.FALLBACK_SOFTWARE, encoder.initEncode(settings, VideoEncoder.Callback { _, _ -> fail() }))
            encoder.release(); assertEquals(1, rejected); assertEquals(1, codecs.single().releases); assertEquals(0, codecs.single().starts)
        }
        // FALLBACK_SOFTWARE is the SDK's refusal status; this factory contains no software encoder.
    }
    @Test fun `probe release happens on selection failure capability refusal and release refusal with no replacement allocation`() {
        for (mode in 0..4) {
            val probe = Probe(failRelease = mode == 2, throwName = mode == 3, throwRelease = mode == 4); var rejected = 0
            val adapter = ExplicitBaselineHardwareEncoderFactory(null, { rejected++ }, delegate(probe), {
                if (mode == 0) throw IllegalStateException("fixture capabilities unavailable")
                null
            }, object : MediaCodecWrapperFactory {
                override fun createByCodecName(name: String): MediaCodecWrapper = error("No allocation on refusal")
            })
            assertNull(adapter.createEncoder(info)); assertEquals(1, probe.released); assertEquals(1, rejected)
        }
    }
    @Test fun `unsupported negotiated lower level never probes hardware or silently ignores asymmetry`() {
        var allocations = 0; var rejected = 0
        val adapter = ExplicitBaselineHardwareEncoderFactory(null, { rejected++ }, object : VideoEncoderFactory {
            override fun getSupportedCodecs() = arrayOf(info)
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder? { allocations++; return null }
        })
        for (asym in listOf("0", "1")) assertNull(adapter.createEncoder(VideoCodecInfo("H264", info.params +
            mapOf("profile-level-id" to "42e01e", "level-asymmetry-allowed" to asym), emptyList())))
        assertEquals(0, allocations); assertEquals(2, rejected)
    }
    @Test fun `other codecs metadata implementations selectors and exact request identity are delegated`() {
        val probe = Probe(); val support = arrayOf(info); val implementations = arrayOf(VideoCodecInfo("VP8", emptyMap(), emptyList()))
        var received: VideoCodecInfo? = null
        val selector = object : VideoEncoderFactory.VideoEncoderSelector {
            override fun onCurrentEncoder(codec: VideoCodecInfo) = Unit
            override fun onAvailableBitrate(bitrate: Int): VideoCodecInfo? = null
            override fun onEncoderBroken(): VideoCodecInfo? = null
        }
        val adapter = ExplicitBaselineHardwareEncoderFactory(null, { fail() }, object : VideoEncoderFactory {
            override fun getSupportedCodecs() = support
            override fun getImplementations() = implementations
            override fun getEncoderSelector() = selector
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder { received = info; return probe }
        })
        assertSame(support, adapter.supportedCodecs); assertSame(implementations, adapter.implementations); assertSame(selector, adapter.encoderSelector)
        assertSame(probe, adapter.createEncoder(implementations[0])); assertSame(implementations[0], received); assertEquals(0, probe.released)
    }
    @Test fun `profile wrapper applies constrained Baseline to every configure and forwards release only once`() {
        val codec = Codec(); val wrapper = ProfileCodec(codec, MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
        repeat(2) {
            val format = MediaFormat.createVideoFormat("video/avc", 960, 540)
            wrapper.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline, format.getInteger(MediaFormat.KEY_PROFILE))
            assertEquals(MediaCodecInfo.CodecProfileLevel.AVCLevel31, format.getInteger(MediaFormat.KEY_LEVEL))
        }
        wrapper.release(); wrapper.release(); assertEquals(1, codec.releases)
    }
    @Test fun `capability policy prefers declared constrained Baseline then explicit Baseline and refuses insufficient levels`() {
        fun capability(profile: Int, level: Int = MediaCodecInfo.CodecProfileLevel.AVCLevel31) =
            MediaCodecInfo.CodecProfileLevel().also { it.profile = profile; it.level = level }
        val baseline = capability(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        val constrained = capability(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline)
        val high = capability(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
            ExplicitBaselineHardwareEncoderFactory.selectProfile(35, arrayOf(high, baseline, constrained)))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            ExplicitBaselineHardwareEncoderFactory.selectProfile(35, arrayOf(high, baseline)))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
            ExplicitBaselineHardwareEncoderFactory.selectProfile(26, arrayOf(high, baseline, constrained)))
        assertNull(ExplicitBaselineHardwareEncoderFactory.selectProfile(26, arrayOf(constrained)))
        assertNull(ExplicitBaselineHardwareEncoderFactory.selectProfile(35, arrayOf(high)))
        assertNull(ExplicitBaselineHardwareEncoderFactory.selectProfile(35,
            arrayOf(capability(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, MediaCodecInfo.CodecProfileLevel.AVCLevel3))))
    }
}
