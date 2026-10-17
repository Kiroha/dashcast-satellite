package io.github.kiroha.dashcast.satellite.capture

import android.os.SystemClock
import org.webrtc.EncodedImage
import java.nio.ByteBuffer

/** Reads only bounded structural bytes during the native callback. Never retains a frame or buffer. */
internal class EncodedH264Diagnostics(
    private val eligible: () -> Boolean,
    private val observed: (Observation) -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    data class Observation(val at: Long, val declaredKey: Boolean, val width: Int?, val height: Int?,
        val framing: String, val inspection: String, val sps: Boolean?, val pps: Boolean?, val idr: Boolean?,
        val profileIdc: Int?, val constraintFlags: Int?, val levelIdc: Int?)
    @Volatile private var done = false
    @Volatile private var closed = false
    private var started: Long? = null
    private var lastTime = 0L
    private var callbacks = 0
    private var selected = 0
    private var bytesLeft = TOTAL_BYTES
    private var inspecting = false
    private var encoder: Any? = null
    private var retired = false

    @Synchronized fun bind(owner: Any): Boolean {
        if (done || retired || encoder != null || !eligible()) return false
        encoder = owner; return true
    }
    @Synchronized fun retire(owner: Any) { if (encoder === owner) { retired = true; closed = true; done = true } }

    fun close() { closed = true; done = true }
    fun inspect(owner: Any, image: EncodedImage) {
        if (done) return
        val key = image.frameType == EncodedImage.FrameType.VideoFrameKey
        val reservation = synchronized(this) {
            if (done || retired || encoder !== owner) return
            if (!eligible()) { done = true; return }
            val now = clock()
            val start = started ?: now.also { started = it; lastTime = it }
            if (now < lastTime || now - start >= 12_000 || ++callbacks > 180) { done = true; return }
            lastTime = now
            if (inspecting || (selected > 0 && !key)) return
            inspecting = true; selected++
            val limit = minOf(bytesLeft, FRAME_BYTES, image.buffer.remaining())
            bytesLeft -= limit
            now to limit
        }
        // No policy/history monitor is held while reading encoder-owned memory.
        val value = try { inspectBuffer(image.buffer, reservation.second, reservation.first, key,
            image.encodedWidth.takeIf { it in 1..8192 }, image.encodedHeight.takeIf { it in 1..8192 }) }
        catch (_: Exception) { Observation(reservation.first, key, null, null, "unknown", "truncated", null, null, null, null, null, null) }
        val publish = synchronized(this) {
            inspecting = false
            val valid = !done && !retired && encoder === owner && eligible()
            if (selected >= 4 || bytesLeft <= 0 || !eligible()) done = true
            valid
        }
        // The history sink receives a scalar copy outside this collector's monitor.
        if (publish && eligible() && !closed) observed(value)
    }
    companion object {
        private const val FRAME_BYTES = 96 * 1024
        private const val TOTAL_BYTES = 256 * 1024
        /** Absolute reads preserve position, limit, mark and content of direct/read-only native buffers. */
        internal fun inspectBuffer(buffer: ByteBuffer, budget: Int, at: Long, key: Boolean, width: Int?, height: Int?): Observation {
            val start = buffer.position()
            val end = start + minOf(buffer.remaining(), budget.coerceAtLeast(0))
            fun byte(index: Int) = buffer.get(index).toInt() and 255
            fun marker(index: Int): Int = when {
                index + 3 < end && byte(index) == 0 && byte(index + 1) == 0 && byte(index + 2) == 0 && byte(index + 3) == 1 -> 4
                index + 2 < end && byte(index) == 0 && byte(index + 1) == 0 && byte(index + 2) == 1 -> 3
                else -> 0
            }
            var first = start
            while (first < end && byte(first) == 0 && marker(first) == 0) first++
            val annex = marker(first) > 0
            var complete = end == buffer.limit()
            var sps = false; var pps = false; var idr = false
            var profile: Int? = null; var constraints: Int? = null; var level: Int? = null
            fun nal(header: Int, boundary: Int) {
                if (header + 1 >= boundary) { complete = false; return }
                val type = byte(header)
                if (type and 128 != 0 || ((type and 31) !in 1..15 && (type and 31) !in 19..21)) { complete = false; return }
                when (type and 31) {
                    5 -> idr = true
                    7 -> {
                        if (header + 3 >= boundary) { complete = false; return }
                        sps = true
                        // Only the three fixed SPS bytes, never a validated SPS/RBSP or dimensions parser.
                        if (profile == null) { profile = byte(header + 1); constraints = byte(header + 2); level = byte(header + 3) }
                    }
                    8 -> pps = true
                }
            }
            if (annex) {
                var header = first + marker(first)
                var index = header
                while (index < end) {
                    val prefix = marker(index)
                    if (prefix == 0) { index++; continue }
                    nal(header, index)
                    header = index + prefix
                    index = header
                }
                nal(header, end)
            }
            fun presence(value: Boolean): Boolean? = if (annex && value) true else if (annex && complete) false else null
            return Observation(at, key, width, height, if (annex) "annex_b" else "unknown",
                if (complete) "complete" else "truncated", presence(sps), presence(pps), presence(idr), profile, constraints, level)
        }
    }
}
