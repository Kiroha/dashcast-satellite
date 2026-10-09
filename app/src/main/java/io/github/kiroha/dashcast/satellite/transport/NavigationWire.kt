package io.github.kiroha.dashcast.satellite.transport

import io.github.kiroha.dashcast.satellite.navigation.Observation
import org.json.JSONObject

/** Portable protocol v1 boundary. No OEM constants or output implementation belong here. */
internal object NavigationWire {
    const val MAX_AGE_MS = 1_500L
    const val MAX_BYTES = 65_536
    private val maneuvers = setOf("left", "right", "slight_left", "slight_right", "sharp_left", "sharp_right",
        "uturn_left", "uturn_right", "straight", "destination", "roundabout_cw", "roundabout_ccw")

    fun encode(observation: Observation, sequence: Long, nowMs: Long): String? {
        if (nowMs < observation.observedAtElapsedMs) return null
        val age = nowMs - observation.observedAtElapsedMs
        if (age !in 0..MAX_AGE_MS) return null
        val json = JSONObject().put("seq", sequence).put("ageMs", age)
        when (observation) {
            is Observation.Stop -> json.put("type", "navigation.stop")
            is Observation.Valid -> {
                val g = observation.guidance
                json.put("type", "navigation.update").put("maneuver", g.maneuver)
                    .put("distanceMeters", g.distanceMeters)
                g.exit?.let { json.put("exit", it) }
                g.roadName?.let { json.put("roadName", it) }
                g.remainingDistanceMeters?.let { json.put("remainingDistanceMeters", it) }
                g.remainingTimeSeconds?.let { json.put("remainingTimeSeconds", it) }
                g.etaHour?.let { json.put("etaHour", it) }
                g.etaMinute?.let { json.put("etaMinute", it) }
            }
        }
        return try { validate(json); json.toString() } catch (_: Exception) { null }
    }

    /** Runs the pinned receiver fixtures against the sender's boundary as well. */
    fun validate(json: JSONObject) {
        require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        integer(json, "seq", 0, Long.MAX_VALUE)
        integer(json, "ageMs", 0, MAX_AGE_MS)
        if (json.get("type") == "navigation.stop") return
        require(json.get("type") == "navigation.update")
        val maneuver = json.get("maneuver")
        require(maneuver is String && maneuver in maneuvers)
        if (maneuver.startsWith("roundabout_")) integer(json, "exit", 1, 10)
        integer(json, "distanceMeters", 0, 1_000_000)
        val road = if (json.has("roadName")) json.get("roadName") else ""
        require(road is String && road.length <= 160 && road.none { it.isISOControl() })
        optional(json, "remainingDistanceMeters", 0, 10_000_000)
        optional(json, "remainingTimeSeconds", 0, 604_800)
        val hour = optional(json, "etaHour", 0, 23)
        val minute = optional(json, "etaMinute", 0, 59)
        require((hour == null) == (minute == null))
    }

    internal fun integer(json: JSONObject, key: String, min: Long, max: Long): Long {
        val value = json.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it in min..max) }
    }

    private fun optional(json: JSONObject, key: String, min: Long, max: Long): Long? =
        if (!json.has(key) || json.isNull(key)) null else integer(json, key, min, max)
}

/** One source value, one sequence counter. A new connection never makes the source younger. */
internal class LatestGuidance {
    private var current: Observation? = null
    private var revision = 0L
    private var sentRevision = -1L
    private var sequence = 0L

    @Synchronized fun offer(observation: Observation) {
        current = observation
        revision++
    }

    @Synchronized fun next(nowMs: Long): String? {
        val observation = current ?: return null
        if (revision == sentRevision) return null
        sentRevision = revision
        if (sequence == Long.MAX_VALUE) throw IllegalStateException("Sequence exhausted")
        val message = NavigationWire.encode(observation, sequence, nowMs) ?: return null
        sequence++
        return message
    }

    @Synchronized fun newSession() { sequence = 0; sentRevision = -1 }
    @Synchronized fun clear() { current = null; revision = 0; newSession() }
}

internal class ReconnectBackoff {
    private var attempts = 0
    fun nextDelayMs(random: Double = Math.random()): Long {
        val base = (500L shl attempts.coerceAtMost(6)).coerceAtMost(30_000)
        attempts = (attempts + 1).coerceAtMost(7)
        return (base * (0.8 + random.coerceIn(0.0, 1.0) * 0.4)).toLong().coerceIn(400, 30_000)
    }
    fun reset() { attempts = 0 }
}
