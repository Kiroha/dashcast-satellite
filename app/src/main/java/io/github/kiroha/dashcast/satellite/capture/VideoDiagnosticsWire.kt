package io.github.kiroha.dashcast.satellite.capture

import io.github.kiroha.dashcast.satellite.transport.NavigationWire
import org.json.JSONObject

/** Optional diagnostics schema 1. This boundary has no free-form strings or identifiers. */
internal object VideoDiagnosticsWire {
    const val MAX_BYTES = 16_384
    const val MAX_ORDINAL = 1_000_000L
    const val MAX_AGE_MS = 86_400_000L
    val states = setOf("stopped", "starting", "waiting_viewer", "negotiating", "connected_waiting_frames", "streaming",
        "control_unavailable", "receiver_address_unsupported", "capture_unavailable", "consent_required", "native_unavailable",
        "capture_revoked", "capture_locked", "capture_paused", "capture_hidden", "media_connection_lost", "signalling_overflow",
        "negotiation_failed", "video_protocol_failed", "receiver_video_failed", "ice_failed", "media_connection_timeout",
        "encoder_stalled", "stats_timeout", "other_failure")
    fun supported(welcome: JSONObject): Boolean = welcome.opt("videoDiagnostics").let {
        (it is Int || it is Long) && (it as Number).toLong() == 1L
    }
    fun detailsSupported(welcome: JSONObject): Boolean = supported(welcome) && welcome.opt("videoDiagnosticsDetails").let {
        (it is Int || it is Long) && (it as Number).toLong() == 1L
    }
    fun validate(json: JSONObject) {
        require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        keys(json, setOf("type", "schema", "request", "snapshotAgeMs", "appVersionCode", "state", "captureBusy", "share", "counts", "recent") + if (json.has("details")) setOf("details") else emptySet())
        require(json.get("type") == "diagnostics.video")
        NavigationWire.integer(json, "schema", 1, 1)
        NavigationWire.integer(json, "request", 1, MAX_ORDINAL)
        NavigationWire.integer(json, "snapshotAgeMs", 0, MAX_AGE_MS)
        NavigationWire.integer(json, "appVersionCode", 1, MAX_ORDINAL)
        require(json.get("state") in states && json.get("captureBusy") is Boolean)
        val share = NavigationWire.integer(json, "share", 0, MAX_ORDINAL)
        if (json.has("details")) validateDetails(json.getJSONObject("details"), share)
        val events = VideoDiagnostics.Event.entries.map { it.name.lowercase() }.toSet()
        val causes = VideoDiagnostics.Cause.entries.map { it.name.lowercase() }.toSet()
        val counts = json.getJSONObject("counts")
        require(counts.length() <= 32)
        counts.keys().forEach { key -> require(key in events); NavigationWire.integer(counts, key, 1, MAX_ORDINAL) }
        val recent = json.getJSONArray("recent")
        require(recent.length() <= 32)
        var previousAge = 0L
        repeat(recent.length()) {
            val entry = recent.getJSONObject(it)
            keys(entry, setOf("event", "cause", "share", "ageMs"))
            require(entry.get("event") in events && entry.get("cause") in causes)
            NavigationWire.integer(entry, "share", 0, share)
            previousAge = NavigationWire.integer(entry, "ageMs", previousAge, MAX_AGE_MS)
        }
    }
    private val senderCounters = listOf("bytesSent", "packetsSent", "framesEncoded", "keyFramesEncoded", "pliCount", "nackCount", "firCount")
    private fun validateDetails(json: JSONObject, share: Long) {
        keys(json, setOf("schema", "attempts"))
        NavigationWire.integer(json, "schema", 1, 1)
        val rows = json.getJSONArray("attempts"); require(rows.length() <= 4)
        var previousAttempt = MAX_ORDINAL + 1
        (0 until rows.length()).forEach { index ->
            val row = rows.getJSONObject(index)
            keys(row, setOf("attempt", "share", "ageMs", "sampleAgeMs", "ended", "codec") + senderCounters)
            val attempt = NavigationWire.integer(row, "attempt", 1, previousAttempt - 1)
            previousAttempt = attempt
            val session = NavigationWire.integer(row, "share", 0, share)
            val age = NavigationWire.integer(row, "ageMs", 0, MAX_AGE_MS)
            fun nullable(key: String, max: Long): Long? = if (row.get(key) === JSONObject.NULL) null else NavigationWire.integer(row, key, 0, max)
            val sampleAge = nullable("sampleAgeMs", age)
            val ended = row.get("ended"); require(ended is Boolean)
            val codec = row.get("codec"); require(codec === JSONObject.NULL || codec in setOf("h264", "vp8", "vp9", "av1"))
            val counters = senderCounters.associateWith { nullable(it, 9_000_000_000_000L) }
            val frames = counters["framesEncoded"]; val keys = counters["keyFramesEncoded"]
            require(frames == null || keys == null || keys <= frames)
            require(sampleAge != null || (codec === JSONObject.NULL && counters.values.all { it == null }))

        }
    }
    private fun keys(json: JSONObject, expected: Set<String>) { require(json.keys().asSequence().toSet() == expected) }
}
