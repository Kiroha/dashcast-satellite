package io.github.kiroha.dashcast.satellite.capture

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Latest scalar sample of four local attempts; no native report, signalling or track identity retained. */
internal class VideoSenderDiagnostics(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private data class Attempt(val number: Long, val share: Long, val at: Long, var sampledAt: Long? = null,
        var codec: String? = null, var counters: Map<String, Long?> = emptyMap(), var ended: Boolean = false)
    private val rows = ArrayDeque<Attempt>()
    private var next = 0L
    @Synchronized fun begin(share: Long): Long? {
        if (next >= VideoDiagnosticsWire.MAX_ORDINAL) return null
        rows.lastOrNull()?.ended = true
        if (rows.size == 4) rows.removeFirst()
        return (++next).also { rows.addLast(Attempt(it, share.coerceIn(0, VideoDiagnosticsWire.MAX_ORDINAL), clock())) }
    }
    @Synchronized fun sample(attempt: Long?, values: Map<String, Any>, mime: String?) {
        val row = rows.lastOrNull()?.takeIf { it.number == attempt && !it.ended } ?: return
        fun counter(key: String): Long? {
            val value = (values[key] as? Number)?.toDouble() ?: return null
            return if (value.isFinite() && value >= 0 && value <= 9_000_000_000_000.0 && value % 1.0 == 0.0) value.toLong() else null
        }
        val counters = counterNames.associateWith(::counter).toMutableMap()
        val frames = counters["framesEncoded"]; val keys = counters["keyFramesEncoded"]
        if (frames != null && keys != null && keys > frames) counters["keyFramesEncoded"] = null
        row.counters = counters
        row.codec = when (mime) { "video/H264" -> "h264"; "video/VP8" -> "vp8"; "video/VP9" -> "vp9"; "video/AV1" -> "av1"; else -> null }
        row.sampledAt = clock()
    }
    @Synchronized fun end(attempt: Long?) { rows.lastOrNull()?.takeIf { it.number == attempt }?.ended = true }
    @Synchronized fun snapshot(): JSONObject {
        val now = clock()
        return JSONObject().put("schema", 1).put("attempts", JSONArray(rows.toList().asReversed().map { row ->
            val age = (now - row.at).coerceIn(0, VideoDiagnosticsWire.MAX_AGE_MS)
            JSONObject().put("attempt", row.number).put("share", row.share).put("ageMs", age)
                .put("sampleAgeMs", row.sampledAt?.let { (now - it).coerceIn(0, age) } ?: JSONObject.NULL)
                .put("ended", row.ended).put("codec", row.codec ?: JSONObject.NULL).also { json ->
                    counterNames.forEach { json.put(it, row.counters[it] ?: JSONObject.NULL) }
                }
        }))
    }
    companion object { val counterNames = listOf("bytesSent", "packetsSent", "framesEncoded", "keyFramesEncoded", "pliCount", "nackCount", "firCount") }
}
