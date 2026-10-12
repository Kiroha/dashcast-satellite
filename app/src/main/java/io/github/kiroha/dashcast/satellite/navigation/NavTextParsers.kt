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
    private val ordinalWords = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
        "eleventh" to 11,
        "première" to 1, "premiere" to 1, "deuxième" to 2, "deuxieme" to 2,
        "seconde" to 2, "troisième" to 3, "troisieme" to 3,
        "quatrième" to 4, "quatrieme" to 4, "cinquième" to 5, "cinquieme" to 5,
        "sixième" to 6, "sixieme" to 6, "septième" to 7, "septieme" to 7,
        "huitième" to 8, "huitieme" to 8, "neuvième" to 9, "neuvieme" to 9,
        "dixième" to 10, "dixieme" to 10, "onzième" to 11, "onzieme" to 11,
    )
    // Inspect every exit clause, including unknown ordinals and separated signs. A finite
    // dictionary alone would silently turn "twelfth exit" into an absent/unknown exit.
    private val exitToken = "(?:[+−-]?\\d[\\p{L}\\p{N}.,+−-]*|[\\p{L}]+(?:[-'][\\p{L}]+)*|[+−-])"
    private val roundaboutExit = Regex(
        "(?<![\\p{L}\\p{N}.,+−-])(?:($exitToken)[ \\t\\u00A0\\u202F]+)?(?:exit|sortie)(?![\\p{L}\\p{N}])(?:[ \\t\\u00A0\\u202F]+($exitToken))?",
        RegexOption.IGNORE_CASE,
    )
    // Grammar words carry no ordinal evidence. All other adjacent words are unknown evidence,
    // not a license to retain the first valid ordinal from a contradictory multi-exit clause.
    private val exitGrammarWords = setOf("the", "an", "a", "take", "at", "next", "last", "then", "in",
        "onto", "on", "towards", "to", "and", "la", "le", "les", "prendre", "prenez", "au", "à",
        "sur", "vers", "de", "du", "des", "dans", "puis", "et", "prochaine", "suivante", "dernière",
        "derniere")
    private val numericOrdinal = Regex("^(\\d+)(?:st|nd|rd|th|ère|ere|er|ième|ieme|ème|eme|e)?$",
        RegexOption.IGNORE_CASE)
    private val unlistedOrdinal = Regex("(?:[\\p{L}]+(?:ième|ieme|ème|eme|ère|ere)|twelfth|[\\p{L}]+teenth|[\\p{L}]+tieth|hundredth|thousandth|[\\p{L}]+-(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth))")
    private val compoundOrdinalPrefix = Regex(
        "(?<![\\p{L}\\p{N}])(?:one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze|treize|quatorze|quinze|seize|vingt|trente|quarante|cinquante|soixante|cent|mille)(?:[ \\t\\u00A0\\u202F]+(?:and|et))?[ \\t\\u00A0\\u202F]+$",
        RegexOption.IGNORE_CASE)
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

    sealed interface Exit {
        data object Absent : Exit
        data object Invalid : Exit
        data class Known(val number: Int) : Exit
    }

    fun roundaboutExit(raw: String): Int? = (roundaboutExitState(raw) as? Exit.Known)?.number

    fun roundaboutExitState(raw: String): Exit {
        // Repeated title/bigText may agree, but two exits or an out-of-range exit are ambiguous.
        val normalized = normalizeDigits(raw)
        val matches = roundaboutExit.findAll(normalized).toList()
        fun groupingSpace(c: Char) = c == ' ' || c == '\t' || c == '\u00a0' || c == '\u202f'
        // Never extract a valid-looking suffix/prefix from a grouped ordinal such as 1 003.
        // Newlines separate title/text fields and may repeat the same complete instruction.
        if (matches.any { match ->
                compoundOrdinalPrefix.containsMatchIn(normalized.substring(0, match.range.first)) ||
                normalized.substring(0, match.range.first).trimEnd(::groupingSpace).lastOrNull()
                    ?.let { it.isDigit() || it in "+−-" } == true ||
                    normalized.substring(match.range.last + 1).trimStart(::groupingSpace).firstOrNull()?.isDigit() == true
            }) return Exit.Invalid
        val exits = matches.flatMap { match ->
            val tokens = listOfNotNull(match.groups[1]?.value, match.groups[2]?.value)
                .map { it.trimEnd('.', ',').lowercase(Locale.ROOT) }.filterNot { it in exitGrammarWords }
            fun number(token: String): Int? = ordinalWords[token]
                ?: numericOrdinal.matchEntire(token)?.groupValues?.get(1)?.toIntOrNull()
            val hasOrdinal = tokens.any { number(it) != null }
            tokens.mapNotNull { token ->
                number(token) ?: if (!hasOrdinal || token.any { it.isDigit() || it in "+−-" } ||
                    unlistedOrdinal.matches(token) || compoundOrdinalPrefix.matches("$token ")) -1 else null
            }
        }.toList()
        if (exits.isEmpty()) return Exit.Absent
        if (exits.any { it !in 1..10 }) return Exit.Invalid
        return exits.distinct().singleOrNull()?.let { Exit.Known(it) } ?: Exit.Invalid
    }

    fun roadName(raw: String): String? = road.find(raw)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= 160 && it.none(Char::isISOControl) }
}
