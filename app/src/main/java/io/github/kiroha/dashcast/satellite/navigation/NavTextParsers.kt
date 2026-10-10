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
    // Capture the whole number before validating it, including malformed grouping. Otherwise a
    // failed match at "1" in "1 00 m" could silently retry at "00" and report zero metres.
    const val DISTANCE_NUMBER_PATTERN = "[+−-]?\\d[\\d., \\u00A0\\u202F]*"
    // Unicode lookarounds preserve unit boundaries on both Android and desktop JVMs.
    private val distance = Regex(
        "(?<![\\p{L}\\p{N}.,+−-])($DISTANCE_NUMBER_PATTERN)[\\s\\u00A0\\u202F]*(km|км|كم|mi|ft|yd|mt|m|м|م)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val plainNumber = Regex("\\d+(?:[.,]\\d+)?")
    private val spaceGroupedNumber = Regex("\\d{1,3}(?:[ \\u00A0\\u202F]\\d{3})+(?:[.,]\\d+)?")
    private val ambiguousPunctuationGrouping = Regex("\\d{1,3}[.,]\\d{3}")
    private val hours = Regex(
        "(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:h|hr|hrs|hour|hours|ч|ч\\.|ساعة|س)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val minutes = Regex(
        "(?<![\\p{L}\\p{N}])(\\d+)[\\s\\u00A0]*(?:min|mins|мин|دقيقة|د)(?![\\p{L}\\p{N}])",
        RegexOption.IGNORE_CASE,
    )
    private val roundaboutExit = Regex(
        "(?<![\\p{L}\\p{N}.,+−-])(?:([+−-]?\\d+(?:[.,]\\d+)*)(?:st|nd|rd|th|er|e|ème)?[\\s\\u00A0]+(?:exit|sortie)(?![\\p{L}\\p{N}])|(?:exit|sortie)[\\s\\u00A0]+([+−-]?\\d[\\p{L}\\p{N}.,+−-]*))",
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
        val normalized = normalizeDigits(raw)
        val match = distance.find(normalized) ?: return null
        // A separator outside the supported grouping spaces must not expose a numeric suffix.
        val prefix = normalized.substring(0, match.range.first).trimEnd()
        val before = prefix.lastOrNull()
        if (before != null && (before.isDigit() || before in "+−-" ||
                before in ".," && prefix.dropLast(1).lastOrNull()?.isDigit() == true)) return null
        val number = match.groupValues[1].trim()
        if (!plainNumber.matches(number) && !spaceGroupedNumber.matches(number)) return null
        // Without the source locale, "1,000" and "1.000" could mean one or one thousand.
        if (ambiguousPunctuationGrouping.matches(number)) return null
        val value = number.filterNot { it == ' ' || it == '\u00A0' || it == '\u202F' }
            .replace(',', '.').toDoubleOrNull() ?: return null
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
        // Repeated title/bigText may agree, but two exits or an out-of-range exit are ambiguous.
        val normalized = normalizeDigits(raw)
        val matches = roundaboutExit.findAll(normalized).toList()
        fun groupingSpace(c: Char) = c == ' ' || c == '\t' || c == '\u00a0' || c == '\u202f'
        // Never extract a valid-looking suffix/prefix from a grouped ordinal such as 1 003.
        // Newlines separate title/text fields and may repeat the same complete instruction.
        if (matches.any { match ->
                normalized.substring(0, match.range.first).trimEnd(::groupingSpace).lastOrNull()?.isDigit() == true ||
                    normalized.substring(match.range.last + 1).trimStart(::groupingSpace).firstOrNull()?.isDigit() == true
            }) return null
        val exits = matches.map { match ->
            (match.groups[1]?.value ?: match.groups[2]?.value)?.trimEnd('.', ',')?.toIntOrNull()
        }.toList()
        if (exits.isEmpty() || exits.any { it == null || it !in 1..10 }) return null
        return exits.distinct().singleOrNull()
    }

    fun roadName(raw: String): String? = road.find(raw)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 160 && it.none(Char::isISOControl) }
}
