package io.github.kiroha.dashcast.satellite.navigation

/** Portable protocol v1 values. No receiver/OEM icon identifiers belong in the companion. */
data class Guidance(
    val maneuver: String,
    val distanceMeters: Int,
    val exit: Int? = null,
    val roadName: String? = null,
    val remainingDistanceMeters: Int? = null,
    val remainingTimeSeconds: Int? = null,
    val etaHour: Int? = null,
    val etaMinute: Int? = null,
) {
    override fun toString(): String = "Guidance(redacted)"
}

sealed interface Observation {
    /** elapsedRealtime at the actual source read, never at enqueue or dispatch time. */
    val observedAtElapsedMs: Long

    data class Valid(
        val guidance: Guidance,
        override val observedAtElapsedMs: Long,
    ) : Observation

    data class Stop(override val observedAtElapsedMs: Long) : Observation
}

enum class SourceStatus { ACTIVE, INACTIVE, UNSUPPORTED, PERMISSION_MISSING, SOURCE_UNAVAILABLE }

enum class NavigationSource { MAPS, ABRP }

/** A current OS observation, not persisted, logged, or retained for timer-based replay. */
data class NavigationNotification(
    val key: String,
    val packageName: String,
    val ongoing: Boolean,
    val navigationCategory: Boolean,
    val postTime: Long = 0,
    val title: String = "",
    val text: String = "",
    val bigText: String = "",
    val subText: String = "",
    val iconResourceName: String? = null,
) {
    override fun toString(): String = "NavigationNotification(redacted)"
}

sealed interface ParseResult {
    data class Valid(val guidance: Guidance) : ParseResult
    data object Unsupported : ParseResult
}

data class SourceObservation(val observation: Observation, val status: SourceStatus)
