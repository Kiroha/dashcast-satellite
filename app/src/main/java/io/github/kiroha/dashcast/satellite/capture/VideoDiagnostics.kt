package io.github.kiroha.dashcast.satellite.capture

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Bounded in-memory diagnostics. No content, signalling, device/network identity or raw errors. */
object VideoDiagnostics {
    enum class Event(val label: String) {
        CONTROL_CONNECTED("Control connected"), CONTROL_CLOSED("Control disconnected"),
        VIEWER_READY("DashCast viewer ready"), VIEWER_CLOSED("DashCast viewer hidden"),
        LEGACY_VIEWER_ERROR("Unscoped viewer error ignored; readiness unchanged"),
        CONSENT_REQUESTED("Screen sharing consent requested"), CONSENT_CANCELLED("Screen sharing consent cancelled"),
        SHARE_STARTED("New authorized screen sharing session"), PROJECTION_CREATED("Android capture session created"),
        WAITING_VIEWER("Screen sharing active; waiting for DashCast"),
        NATIVE_INITIALIZING("Initializing video library"), ENCODER_CHECK("Checking hardware video encoder"),
        HARDWARE_UNAVAILABLE("Hardware video encoder unavailable"), TEXTURE_INITIALIZING("Preparing capture texture"),
        DISPLAY_INITIALIZING("Creating capture display"), CAPTURE_INITIALIZED("Capture display ready"),
        NEGOTIATING("Starting video negotiation"), OFFER_SENT("Video offer sent"),
        ANSWER_RECEIVED("Video answer received"), ANSWER_APPLIED("Video answer accepted"),
        ICE_SENT("Local media candidate sent"), ICE_RECEIVED("Receiver media candidate accepted"),
        MEDIA_CONNECTED("Media connection established; image not confirmed"),
        FRAMES_ENCODED("Video encoding started; image not confirmed"),
        RECEIVER_ERROR("Receiver video error"), NEGOTIATION_FAILED("Video negotiation failed"),
        CAPTURE_UNAVAILABLE("Capture setup failed"), NATIVE_UNAVAILABLE("Video library unavailable"),
        CAPTURE_REVOKED("Android ended screen sharing"), CAPTURE_LOCKED("Screen sharing ended: device locked or screen off"),
        CAPTURE_HIDDEN("Android paused or hid the captured app"), CONTROL_UNAVAILABLE("Screen sharing ended: control disconnected"),
        MEDIA_LOST("Media connection lost"), SIGNALLING_OVERFLOW("Video signalling limit reached"),
        CONNECTION_TIMEOUT("Video connection or first encoded frame timed out"),
        ENCODER_STALLED("Video encoding stopped progressing"), STATS_TIMEOUT("Video statistics timed out"),
        STOPPED("Screen sharing stopped"), OTHER_FAILURE("Video sharing failed"),
    }
    enum class Cause { NONE, SECURITY, STATE, ARGUMENT, LINKAGE, OTHER }
    internal class Timeline(private val clock: () -> Long = SystemClock::elapsedRealtime) {
        private data class Entry(val at: Long, val event: Event, val cause: Cause, val share: Long)
        private val entries = ArrayDeque<Entry>()
        private val counts = IntArray(Event.entries.size)
        private var share = 0L
        @Synchronized fun currentShare(): Long = share
        @Synchronized fun record(event: Event, cause: Cause = Cause.NONE, recent: Boolean = true, sharingSession: Long? = null) {
            if (event == Event.SHARE_STARTED && share < Long.MAX_VALUE) share++
            counts[event.ordinal] = (counts[event.ordinal] + 1).coerceAtMost(1_000_000)
            if (recent) {
                if (entries.size == 32) entries.removeFirst()
                entries.addLast(Entry(clock(), event, cause, sharingSession?.coerceAtLeast(0) ?: share))
            }
        }
        @Synchronized fun recent(limit: Int = 8): String {
            val now = clock()
            return entries.toList().asReversed().take(limit.coerceIn(0, 32)).joinToString("\n") {
                "${((now - it.at).coerceAtLeast(0) / 1000)}s ago · " +
                    (if (it.share == 0L) "Before first share" else "Session ${it.share}") + " · ${it.event.label}" +
                    if (it.cause == Cause.NONE) "" else " (${it.cause.name.lowercase()})"
            }
        }
        /** Reconstruct only typed local evidence; never export the human-readable summary. */
        @Synchronized fun snapshot(request: Long, state: String, captureBusy: Boolean, appVersionCode: Int): JSONObject {
            val now = clock()
            val recent = entries.toList().asReversed()
            val selected = (recent.map { it.event } + Event.entries.filter { counts[it.ordinal] > 0 }).distinct().take(32)
            val counters = JSONObject()
            var ageFloor = 0L
            selected.forEach { counters.put(it.name.lowercase(), counts[it.ordinal]) }
            return JSONObject().put("type", "diagnostics.video").put("schema", 1).put("request", request)
                .put("snapshotAgeMs", 0).put("appVersionCode", appVersionCode).put("state", state.takeIf { it in VideoDiagnosticsWire.states } ?: "other_failure")
                .put("captureBusy", captureBusy).put("share", share.coerceAtMost(VideoDiagnosticsWire.MAX_ORDINAL))
                .put("counts", counters).put("recent", JSONArray(recent.map {
                    // Preserve chronological order conservatively if an injected clock regresses.
                    val age = (now - it.at).coerceIn(ageFloor, VideoDiagnosticsWire.MAX_AGE_MS)
                    ageFloor = age
                    JSONObject().put("event", it.event.name.lowercase()).put("cause", it.cause.name.lowercase())
                        .put("share", it.share.coerceIn(0, VideoDiagnosticsWire.MAX_ORDINAL))
                        .put("ageMs", age)
                }))
        }
        @Synchronized fun summary(): String =
            "DashSat video: process-local history, newest first; session numbers separate sharing attempts. " +
                "Earlier encoding does not confirm the current receiver image.\n" +
                Event.entries.filter { counts[it.ordinal] > 0 }
                    .joinToString(" ") { "${it.name.lowercase()}=${counts[it.ordinal]}" } + "\n" + recent(32)
    }
    private val timeline = Timeline()
    fun record(event: Event, cause: Cause = Cause.NONE, recent: Boolean = true, sharingSession: Long? = null) =
        timeline.record(event, cause, recent, sharingSession)
    internal fun currentShare(): Long = timeline.currentShare()
    fun recent(): String = timeline.recent()
    fun summary(): String = timeline.summary()
    internal fun snapshot(request: Long, appVersionCode: Int): JSONObject =
        timeline.snapshot(request, VideoState.status, VideoState.captureBusy, appVersionCode)
    fun exception(event: Event, error: Throwable) = record(event, when (error) {
        is SecurityException -> Cause.SECURITY
        is IllegalStateException -> Cause.STATE
        is IllegalArgumentException -> Cause.ARGUMENT
        is LinkageError -> Cause.LINKAGE
        else -> Cause.OTHER
    })
    fun terminal(code: String) = record(when (code) {
        "stopped" -> Event.STOPPED
        "capture_revoked" -> Event.CAPTURE_REVOKED
        "capture_locked" -> Event.CAPTURE_LOCKED
        "capture_paused", "capture_hidden" -> Event.CAPTURE_HIDDEN
        "control_unavailable" -> Event.CONTROL_UNAVAILABLE
        "capture_unavailable", "consent_required" -> Event.CAPTURE_UNAVAILABLE
        "native_unavailable" -> Event.NATIVE_UNAVAILABLE
        "media_connection_lost" -> Event.MEDIA_LOST
        "signalling_overflow" -> Event.SIGNALLING_OVERFLOW
        "negotiation_failed", "video_protocol_failed", "receiver_video_failed", "ice_failed" -> Event.NEGOTIATION_FAILED
        "media_connection_timeout" -> Event.CONNECTION_TIMEOUT
        "encoder_stalled" -> Event.ENCODER_STALLED
        "stats_timeout" -> Event.STATS_TIMEOUT
        else -> Event.OTHER_FAILURE
    })
}
