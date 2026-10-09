/*
 * Adapted from DashCast MapNotificationListenerService.kt.
 * Copyright (c) 2026 Cedric Carre. MIT; see docs/PARSER_PROVENANCE.md and PARSER_UPSTREAM_LICENSE.txt.
 * Pinned upstream: 48e8f30344d513967e7d065de1ef369c92a89b23.
 */
package io.github.kiroha.dashcast.satellite.navigation

import java.util.Locale
import kotlin.math.roundToInt

/** Small pure subset of the proven parser, with bounds and Arabic kilometre conversion fixed. */
internal object NavTextParsers {
    // Unicode lookarounds preserve unit boundaries on both Android and desktop JVMs.
    private val distance = Regex(
        "(?<![\\p{L}\\p{N}.,+−-])(\\d+[.,]?\\d*)[\\s\\u00A0]*(km|км|كم|mi|ft|yd|mt|m|м|م)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val hours = Regex(
        "(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:h|hr|hrs|hour|hours|ч|ч\\.|ساعة|س)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val minutes = Regex(
        "(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:min|mins|мин|دقيقة|د)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val roundaboutExit = Regex(
        "(?:(\\d+)(?:st|nd|rd|th|er|e|ème)?[\\s\\u00A0]+(?:exit|sortie)|(?:exit|sortie)[\\s\\u00A0]+(\\d+))",
        RegexOption.IGNORE_CASE,
    )
    private val road = Regex(
        "(?:onto|on|sur|vers)\\s+(.+?)(?:\\s+in\\s+\\d|\\s+dans\\s+\\d|$)",
        RegexOption.IGNORE_CASE,
    )

    fun normalizeDigits(value: String): String {
        var output: StringBuilder? = null
        for (index in value.indices) {
            val character = value[index]
            val digit = when (character) {
                in '٠'..'٩' -> character - '٠'
                in '۰'..'۹' -> character - '۰'
                else -> continue
            }
            if (output == null) output = StringBuilder(value)
            output.setCharAt(index, '0' + digit)
        }
        return output?.toString() ?: value
    }

    fun meters(raw: String, maximum: Int = 1_000_000): Int? {
        val match = distance.find(normalizeDigits(raw)) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val factor = when (match.groupValues[2].lowercase(Locale.ROOT)) {
            "km", "км", "كم" -> 1_000.0
            "mi" -> 1_609.344
            "ft" -> 0.3048
            "yd" -> 0.9144
            else -> 1.0
        }
        val converted = value * factor
        if (!converted.isFinite() || converted < 0.0 || converted > maximum.toDouble()) return null
        return converted.roundToInt()
    }

    fun remainingSeconds(raw: String): Int? {
        val normalized = normalizeDigits(raw)
        val hourMatch = hours.find(normalized)
        val minuteMatch = minutes.find(normalized)
        if (hourMatch == null && minuteMatch == null) return null
        val hour = hourMatch?.groupValues?.get(1)?.toLongOrNull() ?: if (hourMatch == null) 0L else return null
        val minute = minuteMatch?.groupValues?.get(1)?.toLongOrNull() ?: if (minuteMatch == null) 0L else return null
        if (hour !in 0..168 || minute !in 0..10_080) return null
        return (hour * 3600L + minute * 60L).takeIf { it in 0..604_800 }?.toInt()
    }

    fun roundaboutExit(raw: String): Int? {
        val match = roundaboutExit.find(normalizeDigits(raw)) ?: return null
        return (match.groups[1]?.value ?: match.groups[2]?.value)
            ?.toIntOrNull()?.takeIf { it in 1..10 }
    }

    fun roadName(raw: String): String? = road.find(raw)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 160 && it.none(Char::isISOControl) }
}
