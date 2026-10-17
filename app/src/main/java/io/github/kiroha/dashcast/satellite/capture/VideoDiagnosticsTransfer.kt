package io.github.kiroha.dashcast.satellite.capture

import android.os.SystemClock
import android.content.Context
import org.json.JSONObject

/** One explicit latest-value request in RAM, owned by one authenticated control generation. */
object VideoDiagnosticsTransfer {
    enum class Availability { DISCONNECTED, UNSUPPORTED, AVAILABLE }
    enum class Result { IDLE, SENDING, RECEIVED, REJECTED, FAILED, TIMEOUT }
    internal class Session(private val clock: () -> Long = SystemClock::elapsedRealtime) {
        @Volatile var availability = Availability.DISCONNECTED
            private set
        @Volatile var result = Result.IDLE
            private set
        private var detailsSupported = false
        private var h264Supported = false
        private var generation = -1L
        private var request = 0L
        private var pending: String? = null
        private var requestedAt = 0L
        private var sent = false
        @Synchronized fun connected(owner: Long, supported: Boolean, details: Boolean = false, h264: Boolean = false) {
            detailsSupported = supported && details
            h264Supported = detailsSupported && h264
            if (result == Result.SENDING) result = Result.FAILED
            generation = owner; request = 0; pending = null; sent = false
            availability = if (supported) Availability.AVAILABLE else Availability.UNSUPPORTED
        }
        @Synchronized fun disconnected(owner: Long) {
            if (generation != owner) return
            if (result == Result.SENDING) result = Result.FAILED
            detailsSupported = false; h264Supported = false
            generation = -1; pending = null; sent = false; availability = Availability.DISCONNECTED
        }
        @Synchronized fun send(details: (() -> JSONObject)? = null, h264Details: (() -> JSONObject)? = null, snapshot: (Long) -> JSONObject): Boolean {
            if (availability != Availability.AVAILABLE) return false
            if (request >= VideoDiagnosticsWire.MAX_ORDINAL) { result = Result.FAILED; return false }
            val json = try { snapshot(++request).also {
                // Capability and complete snapshot belong to this authenticated generation.
                if (detailsSupported && details != null) it.put("details", if (h264Supported && h264Details != null) h264Details() else details()) else it.remove("details")
                VideoDiagnosticsWire.validate(it)
            } }
                catch (_: Exception) { pending = null; sent = false; result = Result.FAILED; return false }
            pending = json.toString(); requestedAt = clock(); sent = false; result = Result.SENDING
            return true
        }
        @Synchronized fun next(owner: Long): String? {
            if (owner != generation) return null
            expire(owner)
            val text = pending ?: return null
            pending = null; sent = true
            return JSONObject(text).put("snapshotAgeMs", (clock() - requestedAt).coerceIn(0, VideoDiagnosticsWire.MAX_AGE_MS)).toString()
        }
        @Synchronized fun expire(owner: Long) {
            if (owner == generation && result == Result.SENDING && clock() - requestedAt >= 5_000) {
                pending = null; sent = false; result = Result.TIMEOUT
            }
        }
        @Synchronized fun acknowledge(owner: Long, json: JSONObject) {
            expire(owner)
            if (owner != generation || result != Result.SENDING || !sent) return
            try {
                if (json.keys().asSequence().toSet() != setOf("type", "schema", "request", "status") ||
                    json.get("type") != "diagnostics.video.ack" ||
                    io.github.kiroha.dashcast.satellite.transport.NavigationWire.integer(json, "schema", 1, 1) != 1L ||
                    io.github.kiroha.dashcast.satellite.transport.NavigationWire.integer(json, "request", 1, VideoDiagnosticsWire.MAX_ORDINAL) != request) return
                val status = json.get("status")
                if (status !in setOf("accepted", "rejected", "unsupported")) return
                result = if (status == "accepted") Result.RECEIVED else Result.REJECTED
                sent = false
            } catch (_: Exception) { /* Diagnostics must never break control or guidance. */ }
        }
    }
    private val session = Session()
    val availability get() = session.availability
    val result get() = session.result
    fun send(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }
            .getOrDefault(1).coerceIn(1, VideoDiagnosticsWire.MAX_ORDINAL).toInt()
        return session.send(details = { VideoDiagnostics.sender.snapshot() }, h264Details = { VideoDiagnostics.sender.snapshot(true) }) { VideoDiagnostics.snapshot(it, version) }
    }
    internal fun connected(owner: Long, supported: Boolean, details: Boolean = false, h264: Boolean = false) = session.connected(owner, supported, details, h264)
    internal fun disconnected(owner: Long) = session.disconnected(owner)
    internal fun next(owner: Long) = session.next(owner)
    internal fun expire(owner: Long) = session.expire(owner)
    internal fun acknowledge(owner: Long, json: JSONObject) = session.acknowledge(owner, json)
}
