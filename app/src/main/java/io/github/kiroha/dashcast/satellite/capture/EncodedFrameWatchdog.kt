package io.github.kiroha.dashcast.satellite.capture

/** Deadlines advance independently of getStats completion or presence of an outbound report. */
internal class EncodedFrameWatchdog(private val startedNs: Long) {
    private var pendingSinceNs: Long? = null
    private var lastFrames = 0L
    private var progressNs: Long? = null
    fun requested(nowNs: Long) { pendingSinceNs = nowNs }
    fun completed() { pendingSinceNs = null }
    fun encoded(frames: Long, nowNs: Long): Boolean {
        if (frames <= lastFrames) return false
        lastFrames = frames; progressNs = nowNs
        return true
    }
    fun failure(nowNs: Long): String? = when {
        pendingSinceNs?.let { nowNs - it > 5_000_000_000L } == true -> "stats_timeout"
        progressNs?.let { nowNs - it > 6_000_000_000L } == true -> "encoder_stalled"
        progressNs == null && nowNs - startedNs > 10_000_000_000L -> "media_connection_timeout"
        else -> null
    }
}
