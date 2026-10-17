package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*

class HardwareMediaPolicyTest {
    private val baseline = VideoCodecInfo("H264", mapOf("profile-level-id" to "42e01f", "packetization-mode" to "1", "level-asymmetry-allowed" to "1"), emptyList())
    @Test fun `only guarded H264 Baseline is advertised and unsupported codecs cannot allocate an encoder`() {
        val other = arrayOf(VideoCodecInfo("VP8", emptyMap(), emptyList()), VideoCodecInfo("H265", emptyMap(), emptyList()),
            VideoCodecInfo("H264", baseline.params + ("profile-level-id" to "64001f"), emptyList()))
        var creates = 0
        val policy = H264OnlyEncoderFactory(object : VideoEncoderFactory {
            override fun getSupportedCodecs() = arrayOf(baseline, *other)
            override fun getImplementations() = supportedCodecs
            override fun getEncoderSelector() = object : VideoEncoderFactory.VideoEncoderSelector {
                override fun onCurrentEncoder(codec: VideoCodecInfo) = Unit
                override fun onAvailableBitrate(bitrate: Int) = other[0]
                override fun onEncoderBroken() = other[0]
            }
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder? { assertSame(baseline, info); creates++; return null }
        })
        assertNull(policy.encoderSelector)
        assertArrayEquals(arrayOf(baseline), policy.supportedCodecs)
        assertArrayEquals(arrayOf(baseline), policy.implementations)
        other.forEach { assertNull(policy.createEncoder(it)) }
        assertEquals(0, creates)
        policy.createEncoder(baseline); assertEquals(1, creates)
    }
    @Test fun `send-only companion exposes no decoder or software fallback`() {
        assertTrue(ReceiveNothingDecoderFactory.supportedCodecs.isEmpty())
        for (codec in listOf(baseline, VideoCodecInfo("VP8", emptyMap(), emptyList()))) assertNull(ReceiveNothingDecoderFactory.createDecoder(codec))
    }
}
