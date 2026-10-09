/*
 * Distance helpers and explicit maneuver phrases adapted from DashCast, MIT.
 * Copyright (c) 2026 Cedric Carre. See docs/PARSER_PROVENANCE.md.
 */
package io.github.kiroha.dashcast.satellite.navigation

import java.util.Locale

interface NavigationAdapter {
    fun accepts(packageName: String): Boolean
    fun parse(notification: NavigationNotification): ParseResult
}

/** Resource names can carry Maps maneuvers. Raster/URI icons are deliberately not recognized. */
class MapsAdapter : NavigationAdapter {
    override fun accepts(packageName: String): Boolean = packageName in PACKAGES

    override fun parse(notification: NavigationNotification): ParseResult =
        if (!accepts(notification.packageName)) ParseResult.Unsupported
        else ConservativeGuidanceParser.parse(notification, acceptMapsResourceNames = true)

    companion object {
        val PACKAGES: Set<String> = setOf(
            "com.google.android.apps.maps",
            "app.revanced.android.apps.maps",
            "app.morphe.android.apps.maps",
        )
    }
}

/** Conditional support: ABRP must actually expose explicit text guidance; Maps icon names do not apply. */
class AbrpAdapter : NavigationAdapter {
    override fun accepts(packageName: String): Boolean = packageName == PACKAGE

    override fun parse(notification: NavigationNotification): ParseResult =
        if (!accepts(notification.packageName)) ParseResult.Unsupported
        else ConservativeGuidanceParser.parse(notification, acceptMapsResourceNames = false)

    companion object { const val PACKAGE = "com.iternio.abrpapp" }
}

internal object ConservativeGuidanceParser {
    private val distancePrefix = Regex(
        "^(?:(?:in|dans|after|après|nach)\\s+)?\\d+[.,]?\\d*[\\s\\u00a0]*(?:km|км|كم|mi|ft|yd|mt|m|м|م)(?![\\p{L}\\p{N}])[\\s,;:—–-]*",
        RegexOption.IGNORE_CASE,
    )
    // Only explicit phrases are carried over; generic 'continue', 'destination', 'merge' and
    // guessed roundabout/exit/U-turn handedness from the vehicle parser are intentionally absent.
    private val phrases = listOf(
        "make a u-turn right" to "uturn_right", "make a u-turn left" to "uturn_left",
        "u-turn right" to "uturn_right", "u-turn left" to "uturn_left",
        "faites demi-tour à droite" to "uturn_right", "faites demi-tour à gauche" to "uturn_left",
        "turn sharply right" to "sharp_right", "turn sharply left" to "sharp_left",
        "sharp right" to "sharp_right", "sharp left" to "sharp_left",
        "virez fortement à droite" to "sharp_right", "virez fortement à gauche" to "sharp_left",
        "scharf rechts" to "sharp_right", "scharf links" to "sharp_left",
        "turn slightly right" to "slight_right", "turn slightly left" to "slight_left",
        "slight right" to "slight_right", "slight left" to "slight_left",
        "keep right" to "slight_right", "keep left" to "slight_left",
        "restez à droite" to "slight_right", "restez à gauche" to "slight_left",
        "tournez légèrement à droite" to "slight_right", "tournez légèrement à gauche" to "slight_left",
        "légèrement à droite" to "slight_right", "légèrement à gauche" to "slight_left",
        "halbrechts" to "slight_right", "halblinks" to "slight_left",
        "turn right" to "right", "turn left" to "left",
        "tournez à droite" to "right", "tournez à gauche" to "left",
        "rechts abbiegen" to "right", "links abbiegen" to "left",
        "continue straight" to "straight", "straight ahead" to "straight",
        "continuez tout droit" to "straight", "tout droit" to "straight", "geradeaus" to "straight",
        "you have arrived" to "destination", "you've arrived" to "destination",
        "vous êtes arrivé" to "destination", "sie haben ihr ziel erreicht" to "destination",
    )
    private val phrasePatterns = phrases.map { (phrase, maneuver) ->
        Regex("^${Regex.escape(phrase)}(?![\\p{L}\\p{N}])") to maneuver
    }
    private val multipleInstructions = Regex(
        "(?:\\bthen\\b|\\bpuis\\b|\\bdann\\b|\\band\\b|\\bet\\b|[;\\n]).*(?:turn |tournez |abbiegen|straight|tout droit)",
        RegexOption.IGNORE_CASE,
    )
    private val roundaboutWords = Regex("roundabout|rond-point|kreisverkehr", RegexOption.IGNORE_CASE)
    private val unsupportedEvent = Regex(
        "^(?:take the exit|exit|prenez la sortie|sortie|ausfahrt|merge|rejoignez|tollbooth|péage|tunnel)(?![\\p{L}\\p{N}])",
    )
    private val unavailableSource = Regex(
        "searching for gps|gps signal lost|recalculating|recalcul du trajet|signal gps perdu|navigation paused",
        RegexOption.IGNORE_CASE,
    )
    private val resourceNames = listOf(
        "u_turn_right" to "uturn_right", "u_turn_left" to "uturn_left",
        "uturn_right" to "uturn_right", "uturn_left" to "uturn_left",
        "slight_right" to "slight_right", "slight_left" to "slight_left",
        "sharp_right" to "sharp_right", "sharp_left" to "sharp_left",
        "turn_right" to "right", "turn_left" to "left",
        "arrow_right" to "right", "arrow_left" to "left",
        "straight" to "straight", "destination" to "destination", "arrive" to "destination",
    )

    fun parse(notification: NavigationNotification, acceptMapsResourceNames: Boolean): ParseResult {
        val lines = listOf(notification.title, notification.text, notification.bigText)
            .flatMap(String::lines).map { NavTextParsers.normalizeDigits(it).trim() }.filter(String::isNotEmpty)
        if (lines.isEmpty() || lines.any(unavailableSource::containsMatchIn)) return ParseResult.Unsupported
        if (lines.any(multipleInstructions::containsMatchIn)) return ParseResult.Unsupported

        val stripped = lines.map { distancePrefix.replaceFirst(it.lowercase(Locale.ROOT), "").trim() }
        if (stripped.any(unsupportedEvent::containsMatchIn)) return ParseResult.Unsupported
        val textDirections = stripped.mapNotNull(::textManeuver).distinct()
        if (textDirections.size > 1) return ParseResult.Unsupported
        val iconDirection = if (acceptMapsResourceNames) resourceManeuver(notification.iconResourceName) else null
        val textDirection = textDirections.singleOrNull()
        if (iconDirection != null && textDirection != null && iconDirection != textDirection) return ParseResult.Unsupported

        val hasRoundabout = lines.any(roundaboutWords::containsMatchIn)
        val maneuver = iconDirection ?: textDirection ?: return ParseResult.Unsupported
        if (hasRoundabout && !maneuver.startsWith("roundabout_")) return ParseResult.Unsupported
        // An unlabelled U-turn must not become a normal left/right merely because of an icon name.
        if (stripped.any { "u-turn" in it || "demi-tour" in it } && !maneuver.startsWith("uturn_")) {
            return ParseResult.Unsupported
        }
        val exit = if (maneuver.startsWith("roundabout_")) {
            lines.firstNotNullOfOrNull(NavTextParsers::roundaboutExit) ?: return ParseResult.Unsupported
        } else null

        // Do not borrow a route-summary distance from subText, nor arbitrary text containing a
        // road number. The distance must be in an explicit instruction or a distance-only field.
        val meters = lines.firstNotNullOfOrNull { line ->
            val strippedLine = distancePrefix.replaceFirst(line.lowercase(Locale.ROOT), "").trim()
            val isDistanceOnly = strippedLine.isEmpty() && distancePrefix.containsMatchIn(line)
            val isInstruction = textManeuver(strippedLine) != null || roundaboutWords.containsMatchIn(line)
            if (isDistanceOnly || isInstruction) NavTextParsers.meters(line) else null
        } ?: return ParseResult.Unsupported

        val summary = NavTextParsers.normalizeDigits(notification.subText)
        val instruction = lines.firstOrNull { textManeuver(distancePrefix.replaceFirst(it.lowercase(Locale.ROOT), "").trim()) != null }
        return ParseResult.Valid(Guidance(
            maneuver = maneuver,
            distanceMeters = meters,
            exit = exit,
            roadName = instruction?.let(NavTextParsers::roadName),
            remainingDistanceMeters = NavTextParsers.meters(summary, 10_000_000),
            remainingTimeSeconds = NavTextParsers.remainingSeconds(summary),
        ))
    }

    private fun textManeuver(text: String): String? =
        phrasePatterns.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second

    private fun resourceManeuver(raw: String?): String? {
        val name = raw?.lowercase(Locale.ROOT) ?: return null
        fun token(value: String): Boolean = Regex("(?:^|_)${Regex.escape(value)}(?:_|$)").containsMatchIn(name)
        if ("roundabout" in name) return when {
            token("roundabout_ccw") -> "roundabout_ccw"
            token("roundabout_cw") -> "roundabout_cw"
            else -> null
        }
        if ("merge" in name || "exit" in name || "ramp" in name || "tunnel" in name || "toll" in name) return null
        if (("u_turn" in name || "uturn" in name) && !token("u_turn_left") && !token("u_turn_right") &&
            !token("uturn_left") && !token("uturn_right")) return null
        return resourceNames.firstOrNull { (resource, _) -> token(resource) }?.second
    }
}

/** Stateless evaluation of each fresh OS snapshot, with explicitly selected source isolation. */
class NavigationSnapshotEvaluator {
    private val maps = MapsAdapter()
    private val abrp = AbrpAdapter()

    fun evaluate(source: NavigationSource, notifications: List<NavigationNotification>, observedAtElapsedMs: Long): SourceObservation {
        val adapter = when (source) { NavigationSource.MAPS -> maps; NavigationSource.ABRP -> abrp }
        val candidate = notifications.asSequence()
            .filter { adapter.accepts(it.packageName) && (it.ongoing || it.navigationCategory) }
            .sortedWith(compareByDescending<NavigationNotification> { it.navigationCategory }.thenByDescending { it.postTime })
            .firstOrNull() ?: return SourceObservation(Observation.Stop(observedAtElapsedMs), SourceStatus.INACTIVE)
        return when (val parsed = adapter.parse(candidate)) {
            is ParseResult.Valid -> SourceObservation(Observation.Valid(parsed.guidance, observedAtElapsedMs), SourceStatus.ACTIVE)
            ParseResult.Unsupported -> SourceObservation(Observation.Stop(observedAtElapsedMs), SourceStatus.UNSUPPORTED)
        }
    }
}
