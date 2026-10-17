package io.github.kiroha.dashcast.satellite.capture

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Latest scalar sample of four local attempts; no native report, signalling or track identity retained. */
internal class VideoSenderDiagnostics(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private data class Attempt(val number: Long, val sourceMode: String, val share: Long, val at: Long, var sampledAt: Long? = null,
        val encoded: MutableList<EncodedH264Diagnostics.Observation> = mutableListOf(),
        var codec: String? = null, var counters: Map<String, Long?> = emptyMap(), var ended: Boolean = false)
    private val rows = ArrayDeque<Attempt>()
    private var next = 0L
    @Synchronized fun begin(share: Long, reference: Boolean = false): Long? {
        if (next >= VideoDiagnosticsWire.MAX_ORDINAL) return null
        rows.lastOrNull()?.ended = true
        if (rows.size == 4) rows.removeFirst()
        return (++next).also { rows.addLast(Attempt(it, if (reference) "reference" else "capture", share.coerceIn(0, VideoDiagnosticsWire.MAX_ORDINAL), clock())) }
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
    @Synchronized fun encoded(attempt: Long?, value: EncodedH264Diagnostics.Observation) {
        val row = rows.lastOrNull()?.takeIf { it.number == attempt && !it.ended } ?: return
        if (row.encoded.size < 4) row.encoded.add(value)
    }
    @Synchronized fun end(attempt: Long?) { rows.lastOrNull()?.takeIf { it.number == attempt }?.ended = true }
    fun snapshot(includeH264: Boolean = false, includeMode: Boolean = false): JSONObject {
        val (now, captured) = synchronized(this) {
            clock() to rows.toList().asReversed().filter { includeMode || it.sourceMode == "capture" }.map { it.copy(encoded = it.encoded.toMutableList(), counters = it.counters.toMap()) }
        }
        // Native callbacks never wait for JSON building/serialization under the history monitor.
        return JSONObject().put("schema", if (includeMode) 3 else if (includeH264) 2 else 1).put("attempts", JSONArray(captured.map { row ->
            val age = (now - row.at).coerceIn(0, VideoDiagnosticsWire.MAX_AGE_MS)
            JSONObject().put("attempt", row.number).put("share", row.share).put("ageMs", age)
                .put("sampleAgeMs", row.sampledAt?.let { (now - it).coerceIn(0, age) } ?: JSONObject.NULL)
                .put("ended", row.ended).put("codec", row.codec ?: JSONObject.NULL).also { json ->
                    counterNames.forEach { json.put(it, row.counters[it] ?: JSONObject.NULL) }
                    if (includeMode) json.put("sourceMode", row.sourceMode)
                    if (includeH264 || includeMode) json.put("h264", JSONArray(row.encoded.map { item ->
                        JSONObject().put("ageMs", (now - item.at).coerceIn(0, age)).put("declaredKey", item.declaredKey)
                            .put("width", item.width ?: JSONObject.NULL).put("height", item.height ?: JSONObject.NULL)
                            .put("framing", item.framing).put("inspection", item.inspection)
                            .put("sps", item.sps ?: JSONObject.NULL).put("pps", item.pps ?: JSONObject.NULL).put("idr", item.idr ?: JSONObject.NULL)
                            .put("profileIdc", item.profileIdc ?: JSONObject.NULL).put("constraintFlags", item.constraintFlags ?: JSONObject.NULL)
                            .put("levelIdc", item.levelIdc ?: JSONObject.NULL)
                    }))
                }
        }))
    }
    companion object { val counterNames = listOf("bytesSent", "packetsSent", "framesEncoded", "keyFramesEncoded", "pliCount", "nackCount", "firCount") }
}
