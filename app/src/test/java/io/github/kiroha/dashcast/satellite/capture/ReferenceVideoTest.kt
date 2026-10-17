package io.github.kiroha.dashcast.satellite.capture

import org.junit.Assert.*
import org.junit.Test
import org.webrtc.*
import java.io.File

class ReferenceVideoTest {
    private fun assets(): ReferenceAssets = ReferenceAssets.fromBytes((0..1).map { index ->
        val relative = "src/main/assets/video_reference/pattern-$index.h264"
        (File(relative).takeIf { it.exists() } ?: File("app/$relative")).readBytes()
    })
    private class Buffer : VideoFrame.Buffer {
        override fun getWidth() = 960
        override fun getHeight() = 452
        override fun retain() = Unit
        override fun release() = Unit
        override fun toI420(): VideoFrame.I420Buffer = error("Reference must never inspect pixels")
        override fun cropAndScale(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int): VideoFrame.Buffer = error("No pixel work")
    }
    private val settings = VideoEncoder.Settings(1,960,452,100,5,1,false)
    private val info = VideoEncoder.EncodeInfo(arrayOf(EncodedImage.FrameType.VideoFrameDelta))
    private fun frame(time: Long) = VideoFrame(Buffer(), 0, time)
    @Test fun `deadlines remain absolute across repeated readiness and no frames`() {
        var now = 100L
        val session = ReferenceSession(now) { now }
        assertEquals(60_000, session.remaining())
        now += 40_000; session.mediaStarted()
        now += 14_000; session.mediaStarted()
        assertEquals(1000, session.remaining())
        now += 1000; assertFalse(session.take(1))
        val waiting = ReferenceSession(100) { now }
        now = 60_100; assertEquals(0, waiting.remaining())
        assertFalse(waiting.take(2))
    }
    @Test fun `rate limit has no catchup and budget cannot reset across encoders`() {
        var now = 0L
        val session = ReferenceSession(0) { now }; session.mediaStarted()
        repeat(75) { index ->
            now = index * 200L
            assertTrue(session.take(index + 1L))
            assertFalse(session.take(index + 1000L))
        }
        now = 14999
        assertFalse(session.take(10000))
    }
    @Test fun `cached texture cannot become a new reference through repeat or delivery reset`() {
        val gate = FreshReferenceTexture()
        assertTrue(gate.accept(10))
        repeat(75) { assertFalse(gate.accept(10)) }
        assertTrue(gate.accept(11)); assertFalse(gate.accept(9)); assertFalse(gate.accept(11))
        val availability = CaptureAvailability {}
        availability.texture(11); val epoch = availability.readyEpoch
        availability.visibility(false); availability.visibility(true)
        assertFalse(availability.texture(11)); assertFalse(availability.owns(epoch))
        assertTrue(availability.texture(12)); assertTrue(gate.accept(12))
        assertFalse(availability.owns(epoch))
    }
    @Test fun `factory refuses lower level different profile or packetization`() {
        val factory = ReferenceEncoderFactory(assets(), ReferenceSession(0) { 0 }) { true }
        val good = factory.supportedCodecs.single()
        assertNotNull(factory.createEncoder(good))
        for (params in listOf(good.params + ("profile-level-id" to "42e01e"),
            good.params + ("profile-level-id" to "640c1f"), good.params + ("packetization-mode" to "0"))) {
            assertNull(factory.createEncoder(VideoCodecInfo("H264", params, emptyList())))
        }
        assertFalse(factory.createEncoder(good)!!.isHardwareEncoder)
    }
    @Test fun `native retained images hold both buffers and resume only after effective release`() {
        var now = 0L
        val session = ReferenceSession(0) { now }; session.mediaStarted()
        val encoder = ReferenceEncoder(assets(), session) { true }
        val images = mutableListOf<EncodedImage>()
        encoder.initEncode(settings) { image, _ -> image.retain(); images.add(image) }
        encoder.encode(frame(1), info); now = 200; encoder.encode(frame(2), info)
        assertEquals(2, images.size)
        assertNotEquals(images[0].buffer.remaining(), images[1].buffer.remaining())
        assertEquals(960, images[0].encodedWidth); assertEquals(452, images[0].encodedHeight)
        now = 400; encoder.encode(frame(3), info); assertEquals(2, images.size)
        images[0].release()
        encoder.encode(frame(4), info); assertEquals(3, images.size)
        images[1].release(); images[2].release(); encoder.release()
    }
    @Test fun `callback failure releases leased buffer and original timestamps are preserved`() {
        var now = 0L
        val session = ReferenceSession(0) { now }; session.mediaStarted()
        val encoder = ReferenceEncoder(assets(), session) { true }
        encoder.initEncode(settings) { image, _ -> assertEquals(1000, image.captureTimeNs); error("downstream") }
        try { encoder.encode(frame(1000), info); fail() } catch (_: IllegalStateException) { }
        var calls = 0
        encoder.initEncode(settings) { _, _ -> calls++ }
        now = 200; encoder.encode(frame(1001), info)
        now = 400; encoder.encode(frame(1002), info)
        assertEquals(2, calls)
    }
    @Test fun `final predicate retirement cannot deliver old callback and does not leak slots`() {
        var now = 0L; var checks = 0; var retireAt = Int.MAX_VALUE
        val session = ReferenceSession(0) { now }; session.mediaStarted()
        lateinit var encoder: ReferenceEncoder
        encoder = ReferenceEncoder(assets(), session) {
            checks++; if (checks == retireAt) encoder.release(); true
        }
        var calls = 0
        encoder.initEncode(settings) { _, _ -> calls++ }
        retireAt = 4 // final callback fence, after the image was constructed
        encoder.encode(frame(1), info); assertEquals(0, calls)
        encoder.initEncode(settings) { _, _ -> calls++ }; retireAt = Int.MAX_VALUE
        now = 200; encoder.encode(frame(2), info)
        now = 400; encoder.encode(frame(3), info)
        assertEquals(2, calls)
    }
    @Test fun `deadline advancing during rate accounting blocks final callback`() {
        var now = 0L; var reads = 0; var expireOn = Int.MAX_VALUE
        val session = ReferenceSession(0) { reads++; if (reads == expireOn) now = 15000; now }
        session.mediaStarted()
        val encoder = ReferenceEncoder(assets(), session) { true }
        var calls = 0; encoder.initEncode(settings) { _, _ -> calls++ }
        expireOn = reads + 4
        encoder.encode(frame(1), info)
        assertEquals(0, calls)
    }
    @Test fun `asset mutation fails before creating encoder`() {
        assertThrows(IllegalArgumentException::class.java) { ReferenceAssets.fromBytes(listOf(ByteArray(1300),ByteArray(1300))) }
    }
}
