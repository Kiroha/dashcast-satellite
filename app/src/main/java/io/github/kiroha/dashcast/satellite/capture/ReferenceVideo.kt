package io.github.kiroha.dashcast.satellite.capture

import android.content.res.AssetManager
import android.os.SystemClock
import org.webrtc.*
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Original platform timestamps only; cached forceFrame callbacks can never mint reference frames. */
internal class FreshReferenceTexture {
    private var newest = Long.MIN_VALUE
    fun accept(timestamp: Long): Boolean {
        if (timestamp <= newest) return false
        newest = timestamp
        return true
    }
}

/** Immutable session limits, shared by all replacement peers. Deadlines never establish freshness. */
internal class ReferenceSession(val consentAt: Long, private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private var mediaAt: Long? = null
    private var lastOutput: Long? = null
    private var lastTimestamp: Long? = null
    private var outputs = 0
    @Synchronized fun mediaStarted() { if (mediaAt == null) mediaAt = clock() }
    @Synchronized fun remaining(): Long = minOf(consentAt + 60_000, mediaAt?.plus(15_000) ?: Long.MAX_VALUE) - clock()
    @Synchronized fun take(timestamp: Long): Boolean {
        val now = clock()
        if (remaining() <= 0 || mediaAt == null || outputs >= 75 || timestamp <= 0 ||
            lastTimestamp?.let { timestamp <= it } == true || lastOutput?.let { now - it < 200 } == true) return false
        lastOutput = now; lastTimestamp = timestamp; outputs++
        return true
    }
}

/** Two immutable direct buffers loaded/hashed on the service worker, never on a codec callback. */
internal class ReferenceAssets private constructor(private val buffers: List<ByteBuffer>) {
    private val busy = BooleanArray(2)
    private var next = 0
    internal data class Lease(val buffer: ByteBuffer, val used: () -> Unit, val release: () -> Unit)
    @Synchronized fun acquire(): Lease? {
        val index = next
        if (busy[index]) return null
        busy[index] = true
        var released = false
        return Lease(buffers[index].duplicate(), { synchronized(this) { next = 1 - index } }) { synchronized(this) {
            if (!released) { released = true; busy[index] = false }
        } }
    }
    companion object {
        const val WIDTH = 960
        const val HEIGHT = 452
        private val hashes = listOf("7bb80c5f57b87e8052dff8ca488c42bb1eee771d878dac21718b32927c09745e", "111138fe7f68e367a3695bfad97613ed5dfac15afd5173abc75e525138201c51")
        fun load(assets: AssetManager) = fromBytes(hashes.indices.map { index ->
            assets.open("video_reference/pattern-$index.h264").use { stream ->
                val buffer = ByteArray(32769)
                var count = 0
                while (count < buffer.size) {
                    val read = stream.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    require(read > 0); count += read
                }
                require(count in 1201..32768); buffer.copyOf(count)
            }
        })
        internal fun fromBytes(values: List<ByteArray>): ReferenceAssets {
            require(values.size == 2)
            return ReferenceAssets(values.mapIndexed { index, bytes ->
                require(bytes.size in 1201..32768)
                require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == hashes[index])
                ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            })
        }
    }
}

/** Pre-encoded synthetic input, not a software or hardware encoder. Native WebRTC still packetizes. */
internal class ReferenceEncoderFactory(private val assets: ReferenceAssets, private val session: ReferenceSession,
    private val eligible: () -> Boolean) : VideoEncoderFactory {
    override fun getSupportedCodecs() = arrayOf(VideoCodecInfo("H264", mapOf(
        "profile-level-id" to "42e01f", "packetization-mode" to "1", "level-asymmetry-allowed" to "1"), emptyList()))
    override fun getImplementations() = supportedCodecs
    override fun getEncoderSelector(): VideoEncoderFactory.VideoEncoderSelector? = null
    override fun createEncoder(info: VideoCodecInfo): VideoEncoder? =
        if (info.name.equals("H264", true) && H264BaselineContract.permitsLevel31(info.params) && eligible()) ReferenceEncoder(assets, session, eligible) else null
}

internal class ReferenceEncoder(private val assets: ReferenceAssets, private val session: ReferenceSession,
    private val eligible: () -> Boolean) : VideoEncoder {
    private var callback: VideoEncoder.Callback? = null
    private var generation = 0L
    override fun createNative(environment: Long) = 0L
    override fun isHardwareEncoder() = false
    @Synchronized override fun initEncode(settings: VideoEncoder.Settings, callback: VideoEncoder.Callback): VideoCodecStatus {
        generation++; this.callback = callback
        return VideoCodecStatus.OK
    }
    @Synchronized override fun release(): VideoCodecStatus { generation++; callback = null; return VideoCodecStatus.OK }
    override fun encode(frame: VideoFrame, info: VideoEncoder.EncodeInfo): VideoCodecStatus {
        val token = synchronized(this) { generation }
        if (info.frameTypes.isEmpty() || info.frameTypes.any { it == EncodedImage.FrameType.EmptyFrame } || !eligible()) return VideoCodecStatus.OK
        val lease = assets.acquire() ?: return VideoCodecStatus.OK
        var image: EncodedImage? = null
        try {
            // Run the external predicate first, then recheck the local generation at the final callback.
            if (!eligible() || session.remaining() <= 0) return VideoCodecStatus.OK
            synchronized(this) {
                val target = callback ?: return VideoCodecStatus.OK
                if (generation != token || !eligible() || generation != token || !session.take(frame.timestampNs)) return VideoCodecStatus.OK
                lease.used()
                image = EncodedImage.builder().setBuffer(lease.buffer, lease.release)
                    .setEncodedWidth(ReferenceAssets.WIDTH).setEncodedHeight(ReferenceAssets.HEIGHT)
                    .setCaptureTimeNs(frame.timestampNs).setRotation(0)
                    .setFrameType(EncodedImage.FrameType.VideoFrameKey).createEncodedImage()
                // Builder/rate accounting may race revocation. This is the final boundary, not encode entry.
                if (session.remaining() <= 0 || !eligible() || generation != token || callback !== target) return VideoCodecStatus.OK
                target.onEncodedFrame(image!!, VideoEncoder.CodecSpecificInfo())
            }
        } finally { if (image == null) lease.release() else image!!.release() }
        return VideoCodecStatus.OK
    }
    override fun setRateAllocation(allocation: VideoEncoder.BitrateAllocation, framerate: Int) = VideoCodecStatus.OK
    override fun setRates(parameters: VideoEncoder.RateControlParameters) = VideoCodecStatus.OK
    override fun getScalingSettings() = VideoEncoder.ScalingSettings.OFF
    override fun getResolutionBitrateLimits() = emptyArray<VideoEncoder.ResolutionBitrateLimits>()
    override fun getImplementationName() = "Synthetic H264 reference"
    override fun getEncoderInfo() = VideoEncoder.EncoderInfo(1, false)
}
