package io.github.kiroha.dashcast.satellite.capture

import java.nio.ByteBuffer

/** The hardware path supports the advertised Constrained Baseline contract through level 3.1. */
internal object H264BaselineContract {
    private val levels = setOf(10, 11, 12, 13, 20, 21, 22, 30, 31, 32, 40, 41, 42, 50, 51, 52)
    fun constrained(profile: Int, flags: Int): Boolean = flags and 0x0f == 0 && when (profile) {
        66 -> flags and 0x40 != 0
        77 -> flags and 0x80 != 0
        88 -> flags and 0xc0 == 0xc0
        else -> false
    }
    fun output(profile: Int, flags: Int, level: Int) =
        constrained(profile, flags) && level in levels && level <= 31

    /** A lower negotiated level is refused, even when level asymmetry is enabled. */
    fun permitsLevel31(params: Map<String, String>): Boolean {
        if (params["packetization-mode"] != "1" ||
            (params["level-asymmetry-allowed"] ?: "0") !in setOf("0", "1")) return false
        val value = params["profile-level-id"] ?: return false
        if (!value.matches(Regex("[0-9a-fA-F]{6}"))) return false
        val profile = value.substring(0, 2).toInt(16)
        val flags = value.substring(2, 4).toInt(16)
        val level = value.substring(4, 6).toInt(16)
        return constrained(profile, flags) && level in levels && level >= 31
    }
}

/**
 * A lifetime compatibility gate, independent of the finite diagnostic/recovery budgets.
 * Reads a small Annex B prefix through the first VCL NAL; never retains or changes media memory.
 * This checks fixed SPS profile bytes, not complete SPS syntax or decoded image validity.
 */
internal class H264ProfileGuard(private val eligible: () -> Boolean, private val unsupported: () -> Unit) {
    private var generation: Any? = null
    private var validated = false
    private var failed = false

    @Synchronized fun begin(): Any = Any().also { generation = it; validated = false }
    @Synchronized fun retire(owner: Any?) { if (generation === owner) generation = null }

    fun allow(owner: Any, buffer: ByteBuffer): Boolean {
        if (!eligible()) return false
        synchronized(this) { if (generation !== owner || failed) return false }
        // No policy/history monitor is held while reading an encoder-owned buffer.
        val result = try { inspect(buffer) } catch (_: Exception) { Result.INVALID }
        // A predicate may observe revocation/reinitialization. Check the generation after it returns.
        if (!eligible()) return false
        var report = false
        val allowed = synchronized(this) {
            if (generation !== owner || failed) return false
            if (result == Result.INVALID || (result == Result.NO_SPS && !validated)) {
                failed = true; report = true; false
            } else {
                if (result == Result.VALID_SPS) validated = true
                true
            }
        }
        // The sink only latches/posts a scalar failure. It must never tear down native resources here.
        if (report) unsupported()
        return allowed
    }

    private enum class Result { VALID_SPS, NO_SPS, INVALID }
    private fun inspect(buffer: ByteBuffer): Result {
        val start = buffer.position()
        val end = start + minOf(buffer.remaining(), 4096)
        fun byte(index: Int) = buffer.get(index).toInt() and 255
        fun marker(index: Int): Int = when {
            index + 3 < end && byte(index) == 0 && byte(index + 1) == 0 && byte(index + 2) == 0 && byte(index + 3) == 1 -> 4
            index + 2 < end && byte(index) == 0 && byte(index + 1) == 0 && byte(index + 2) == 1 -> 3
            else -> 0
        }
        var offset = start
        while (offset < end && byte(offset) == 0 && marker(offset) == 0) offset++
        var prefix = marker(offset)
        if (prefix == 0) return Result.INVALID
        var sps = false
        repeat(32) {
            val header = offset + prefix
            if (header >= end) return Result.INVALID
            val value = byte(header)
            val type = value and 31
            if (value and 128 != 0 || type !in 1..15) return Result.INVALID
            // SPS/PPS precede slices in the SDK's Annex B access unit. Do not scan picture payloads.
            if (type in 1..5) return if (sps) Result.VALID_SPS else Result.NO_SPS
            if (type == 7) {
                if (header + 3 >= end || !H264BaselineContract.output(byte(header + 1), byte(header + 2), byte(header + 3)))
                    return Result.INVALID
                sps = true
            }
            offset = header + 1
            while (offset < end && marker(offset) == 0) offset++
            if (offset == end) return if (end == buffer.limit() && sps) Result.VALID_SPS else Result.INVALID
            prefix = marker(offset)
        }
        return Result.INVALID // Bounded work even for pathological tiny NAL sequences.
    }
}
