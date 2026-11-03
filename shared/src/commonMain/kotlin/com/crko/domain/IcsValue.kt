package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Content lines every component shares, read once by `VEVENT`, `VTODO` and `VJOURNAL` parsers.
 * `handle` returns false for a line the component has to interpret itself.
 */
internal class CommonFields {
    var uid: String? = null
    var summary = ""
    var description = ""
    var url = ""
    val categories = mutableListOf<String>()
    val attachments = mutableListOf<Attachment>()
    var created: String? = null
    var lastModified: String? = null
    var sequence = 0
    var dtStamp: String? = null
    val textParameters = mutableMapOf<String, String>()

    fun handle(line: ContentLine): Boolean {
        when (line.name) {
            "UID" -> uid = unescapeText(line.value).trim().takeIf { it.isNotEmpty() }
            "SUMMARY" -> {
                summary = unescapeText(line.value).trim()
                captureTextParameter(line)
            }
            "DESCRIPTION" -> {
                description = unescapeText(line.value).trim()
                captureTextParameter(line)
            }
            "URL" -> {
                url = unescapeText(line.value).trim()
                captureTextParameter(line)
            }
            "CATEGORIES" -> {
                categories += splitList(line.value).map(::unescapeText).filter { it.isNotBlank() }
                captureTextParameter(line)
            }
            "ATTACH" -> attachments += readAttachment(line)
            "CREATED" -> created = parseIcsInstant(line.value) ?: line.value.trim()
            "LAST-MODIFIED" -> lastModified = parseIcsInstant(line.value) ?: line.value.trim()
            "SEQUENCE" -> sequence = line.value.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            "DTSTAMP" -> dtStamp = parseIcsInstant(line.value) ?: line.value.trim()
            else -> return false
        }
        return true
    }

    /** Remembers the parameters of the TEXT property [line] carries, for the export to re-apply. */
    fun captureTextParameter(line: ContentLine) {
        textParametersOf(line)?.let { textParameters[line.name] = it }
    }
}

/**
 * Parameters of a TEXT property, rebuilt for the export to splice behind the property name.
 * `ENCODING` and `CHARSET` only served the decode (the value is now plain text) and must not be
 * written back next to it.
 */
private fun textParametersOf(line: ContentLine): String? =
    line.params.filterKeys { it != "ENCODING" && it != "CHARSET" }
        .takeIf { it.isNotEmpty() }
        ?.entries
        ?.joinToString(";") { (name, value) -> "$name=${icsParameterValue(value)}" }

/** Parameter prefix stored for [name], ready to splice behind the property name on export. */
internal fun Map<String, String>.textPropertyParameters(name: String): String =
    this[name]?.let { ";$it" }.orEmpty()

internal fun parseTime(
    line: ContentLine,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
    defaultZone: IcsZone? = null,
): IcsTime? {
    val text = line.value.trim()
    if (text.isEmpty()) return null
    val valueType = line.params["VALUE"]?.uppercase()
    if (valueType == "PERIOD") return parsePeriod(line, text, zones, warnings, defaultZone)
    val digitsOnly = text.length == 8 && text.all { it.isDigit() }
    if (digitsOnly) {
        val date = parseDate(text) ?: run {
            warnings += IcsWarning.InvalidValue
            return null
        }
        return IcsTime.AllDay(date)
    }
    if (valueType == "DATE") {
        warnings += IcsWarning.InvalidValue
        return null
    }
    val utc = text.endsWith("Z", ignoreCase = true)
    val body = if (utc) text.dropLast(1) else text
    if (body.length < 13 || body[8] != 'T') {
        warnings += IcsWarning.InvalidValue
        return null
    }
    val date = parseDate(body.substring(0, 8)) ?: run {
        warnings += IcsWarning.InvalidValue
        return null
    }
    val hour = body.substring(9, 11).toIntOrNull() ?: return null
    val minute = body.substring(11, 13).toIntOrNull() ?: return null
    val second = if (body.length >= 15) body.substring(13, 15).toIntOrNull() ?: 0 else 0
    if (minute !in 0..59 || second !in 0..60 || hour !in 0..24) {
        warnings += IcsWarning.InvalidValue
        return null
    }
    if (hour == 24) {
        if (minute != 0 || second != 0) {
            warnings += IcsWarning.InvalidValue
            return null
        }
        val nextDay = date.plusDays(1)
        val zone = resolveLineZone(utc, line.params["TZID"], zones, warnings, defaultZone)
        return IcsTime.DateTime(LocalDateTime(nextDay, LocalTime(0, 0)), zone, text)
    }
    // RFC 5545 allows a leap second (a value of 60) at the end of a minute; the model has no 60th
    // second, so the value rolls into the next minute, which names the same instant.
    val secondsOfDay = hour * 3600 + minute * 60 + second
    val extraDays = secondsOfDay / (24 * 60 * 60)
    val daySeconds = secondsOfDay % (24 * 60 * 60)
    val local = LocalDateTime(
        if (extraDays > 0) date.plusDays(extraDays) else date,
        LocalTime(daySeconds / 3600, daySeconds % 3600 / 60, daySeconds % 60),
    )
    val zone = resolveLineZone(utc, line.params["TZID"], zones, warnings, defaultZone)
    return IcsTime.DateTime(local, zone, text)
}

/**
 * Parses `start/end` and `start/duration` periods. The occurrence keeps its start; the length is
 * carried so an RDATE instance can end at its own time instead of inheriting the series duration.
 */
internal fun parsePeriod(
    line: ContentLine,
    text: String,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
    defaultZone: IcsZone?,
): IcsTime? {
    val slash = text.indexOf('/')
    if (slash <= 0 || slash == text.lastIndex) {
        warnings += IcsWarning.InvalidValue
        return null
    }
    val startText = text.substring(0, slash).trim()
    val endText = text.substring(slash + 1).trim()
    val start = parseTime(
        ContentLine(line.name, line.params - "VALUE", startText, line.raw),
        zones,
        warnings,
        defaultZone,
    ) as? IcsTime.DateTime ?: return null
    val duration = if (endText.startsWith("P", ignoreCase = true) || endText.startsWith("+P", ignoreCase = true)) {
        parseSignedDurationSeconds(endText)
    } else {
        val end = parseTime(
            ContentLine(line.name, line.params - "VALUE", endText, line.raw),
            zones,
            warnings,
            defaultZone,
        ) as? IcsTime.DateTime ?: return null
        end.zone.toInstant(end.value).epochSeconds - start.zone.toInstant(start.value).epochSeconds
    }
    return IcsTime.DateTime(
        value = start.value,
        zone = start.zone,
        source = startText,
        periodSeconds = duration?.takeIf { it > 0L } ?: run {
            warnings += IcsWarning.InvalidValue
            null
        },
    )
}

private fun resolveLineZone(
    utc: Boolean,
    tzid: String?,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
    defaultZone: IcsZone?,
): IcsZone {
    if (utc) return IcsZone.Utc
    if (tzid == null) return defaultZone ?: IcsZone.Floating
    val id = tzid.trim().removePrefix("/")
    zones[id]?.let { return IcsZone.Custom(it) }
    return resolvePlatformZone(id) ?: run {
        warnings += IcsWarning.UnresolvedTimeZone
        IcsZone.Floating
    }
}

/** Parts of `RRULE` this model understands; anything else keeps the original text in [RepeatRule.raw]. */
private val KnownRuleParts = setOf(
    "FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS",
    "BYYEARDAY", "BYWEEKNO", "WKST", "BYHOUR", "BYMINUTE", "BYSECOND",
)

internal fun parseRule(
    value: String,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
    defaultZone: IcsZone? = null,
    startIsDate: Boolean = false,
): RepeatRule? {
    val parts = value.split(';').mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) null else part.substring(0, equals).trim().uppercase() to part.substring(equals + 1).trim()
    }.toMap()
    val rawFrequency = parts["FREQ"]?.uppercase() ?: return null
    val frequency = when (rawFrequency) {
        "DAILY" -> RepeatFrequency.Daily
        "WEEKLY" -> RepeatFrequency.Weekly
        "MONTHLY" -> RepeatFrequency.Monthly
        "YEARLY" -> RepeatFrequency.Yearly
        else -> {
            // HOURLY/MINUTELY/SECONDLY and private values cannot be expanded by this model; the
            // caller stores the property text so an edit round trip does not drop it.
            warnings += IcsWarning.UnsupportedRecurrence
            return null
        }
    }
    val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val byDay = parts["BYDAY"].orEmpty().split(',').mapNotNull { day ->
        val code = day.trim().uppercase().takeLast(2)
        val weekday = weekdayCodes.indexOf(code)
        if (weekday < 0) return@mapNotNull null
        val ordinalText = day.trim().uppercase().dropLast(2)
        val ordinal = ordinalText.toIntOrNull() ?: 0
        ordinal to weekday
    }
    var lossy = (parts.keys - KnownRuleParts).isNotEmpty()
    // RFC 5545 only gives an ordinal in BYDAY a meaning for MONTHLY and YEARLY rules. Elsewhere the
    // ordinal is dropped (so the plain days still expand and export), and the user is warned.
    val effectiveByDay = if (frequency == RepeatFrequency.Daily || frequency == RepeatFrequency.Weekly) {
        if (byDay.any { it.first != 0 }) warnings += IcsWarning.UnsupportedRecurrence
        byDay.filter { it.first == 0 }
    } else {
        byDay
    }
    val byHour = intList(parts["BYHOUR"]).filter { it in 0..23 }
    val byMinute = intList(parts["BYMINUTE"]).filter { it in 0..59 }
    val bySecond = intList(parts["BYSECOND"]).filter { it in 0..59 }
    if (startIsDate && (byHour.isNotEmpty() || byMinute.isNotEmpty() || bySecond.isNotEmpty())) {
        warnings += IcsWarning.UnsupportedRecurrence
        lossy = true
    }
    return RepeatRule(
        frequency = frequency,
        interval = interval,
        count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 },
        until = parts["UNTIL"]?.let { parseUntil(it, zones, warnings, defaultZone) },
        byDay = effectiveByDay.map { (ordinal, weekday) -> OrdinalWeekday(ordinal, weekday) },
        byMonthDay = intList(parts["BYMONTHDAY"]),
        byMonth = intList(parts["BYMONTH"]).filter { it in 1..12 },
        bySetPos = intList(parts["BYSETPOS"]),
        byYearDay = intList(parts["BYYEARDAY"]),
        byWeekNo = intList(parts["BYWEEKNO"]),
        weekStart = parts["WKST"]?.uppercase()?.let { weekdayCodes.indexOf(it) }?.takeIf { it >= 0 },
        byHour = byHour,
        byMinute = byMinute,
        bySecond = bySecond,
        raw = value.takeIf { lossy },
    )
}

private fun intList(value: String?): List<Int> =
    value.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()

private fun parseUntil(
    value: String,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
    defaultZone: IcsZone?,
): IcsTimeValue? {
    val utc = value.endsWith("Z", ignoreCase = true)
    val line = ContentLine("UNTIL", if (utc) emptyMap() else mapOf("VALUE" to "DATE-TIME"), value, value)
    // A date-only UNTIL is written without VALUE=DATE by some clients; parseTime handles it too.
    return parseTime(line, zones, warnings, defaultZone)?.toValue()
}

/**
 * `DURATION` value in seconds. RFC 5545 durations are second-precise (`PT30S`, `-PT1H30M30S`), so
 * this is the granularity every caller that keeps a [Duration] needs; [parseDurationMinutes] rounds
 * it for the few whole-minute values the model stores.
 */
internal fun parseDurationSeconds(value: String): Long? {
    val text = value.trim().uppercase()
    if (!text.startsWith("P")) return null
    var total = 0.0
    var number = 0.0
    var fraction = 0.0
    var fractionScale = 0.0
    var inTime = false
    var sawDigit = false
    text.drop(1).forEach { ch ->
        when {
            ch == 'T' -> inTime = true
            ch.isDigit() -> {
                sawDigit = true
                if (fractionScale > 0.0) {
                    fractionScale /= 10
                    fraction += (ch - '0') * fractionScale
                } else {
                    number = number * 10 + (ch - '0')
                }
            }
            // A fractional component is not part of RFC 5545, but weighing the digits by their
            // position beats misreading them as whole units ("PT1.5S" used to read as five seconds).
            ch == '.' || ch == ',' -> fractionScale = 1.0
            else -> {
                total += (number + fraction) * when (ch) {
                    'W' -> 7.0 * 24 * 3600
                    'D' -> 24.0 * 3600
                    'H' -> if (inTime) 3600.0 else 0.0
                    'M' -> if (inTime) 60.0 else 0.0
                    'S' -> if (inTime) 1.0 else 0.0
                    else -> 0.0
                }
                number = 0.0
                fraction = 0.0
                fractionScale = 0.0
            }
        }
    }
    return if (sawDigit) kotlin.math.floor(total).toLong() else null
}

internal fun parseSignedDurationSeconds(value: String): Long? {
    val trimmed = value.trim()
    val sign = if (trimmed.startsWith('-')) -1L else 1L
    val body = trimmed.removePrefix("-").removePrefix("+")
    val seconds = parseDurationSeconds(body) ?: return null
    return sign * seconds
}

/** Whole minutes of a duration, for the calendar-level values that are stored in minutes. */
internal fun parseDurationMinutes(value: String): Int? = parseDurationSeconds(value)?.let { (it / 60).toInt() }

private fun parseDate(text: String): LocalDate? {
    if (text.length < 8) return null
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(4, 6).toIntOrNull() ?: return null
    val day = text.substring(6, 8).toIntOrNull() ?: return null
    return runCatching { LocalDate(year, month, day) }.getOrNull()
}

private fun parseDateText(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

internal fun splitList(value: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    value.forEach { ch ->
        when {
            escaped -> {
                current.append(ch)
                escaped = false
            }
            ch == '\\' -> {
                current.append(ch)
                escaped = true
            }
            ch == ',' -> {
                result += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
    }
    result += current.toString()
    return result.filter { it.isNotEmpty() }
}

private fun IcsTime.localDateTime(deviceZone: TimeZone): LocalDateTime = when (this) {
    is IcsTime.AllDay -> LocalDateTime(date, LocalTime(0, 0))
    is IcsTime.DateTime -> when (val z = zone) {
        IcsZone.Floating -> value
        IcsZone.Utc -> value.toInstant(TimeZone.UTC).toLocalDateTime(deviceZone)
        is IcsZone.Platform -> value.toInstant(z.zone).toLocalDateTime(deviceZone)
        is IcsZone.Custom -> z.zone.toInstant(value).toLocalDateTime(deviceZone)
    }
}

/** Date the calendar file itself names, independent of the device zone. */
internal fun IcsTime.localWallDate(): LocalDate = when (this) {
    is IcsTime.AllDay -> date
    is IcsTime.DateTime -> value.date
}

internal fun IcsZone.toInstant(local: LocalDateTime): Instant = when (this) {
    IcsZone.Floating -> local.toInstant(TimeZone.UTC)
    IcsZone.Utc -> local.toInstant(TimeZone.UTC)
    is IcsZone.Platform -> local.toInstant(zone)
    is IcsZone.Custom -> zone.toInstant(local)
}

private fun LocalDateTime.plusMinutes(minutes: Int): LocalDateTime {
    val total = date.toEpochDays() * 1440L + time.hour * 60 + time.minute + minutes
    val day = total.floorDiv(1440L)
    val minuteOfDay = total.mod(1440L).toInt()
    return LocalDateTime(LocalDate.fromEpochDays(day), LocalTime(minuteOfDay / 60, minuteOfDay % 60))
}

/** Wall-clock minutes from this value to [other]; used when an end has no source text. */
private fun LocalDateTime.wallMinutesUntil(other: LocalDateTime): Int =
    date.daysTo(other.date) * 1440 +
        (other.time.hour * 60 + other.time.minute) -
        (time.hour * 60 + time.minute)

/** RFC 5545 value of a parsed time; unknown zones resolve through the calendar-level definitions. */
internal fun IcsTime.toValue(): IcsTimeValue = when (this) {
    is IcsTime.AllDay -> IcsTimeValue.Date(date)
    is IcsTime.DateTime -> when (val zone = zone) {
        IcsZone.Floating -> IcsTimeValue.Floating(value)
        IcsZone.Utc -> IcsTimeValue.Utc(value.toInstant(TimeZone.UTC))
        is IcsZone.Platform -> IcsTimeValue.Zoned(value, zone.id)
        is IcsZone.Custom -> IcsTimeValue.Zoned(value, zone.zone.id)
    }
}

internal fun parseInstant(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun parseIcsInstant(value: String): String? {
    val line = ContentLine("VALUE", emptyMap(), value.trim(), value.trim())
    val time = parseTime(line, emptyMap(), mutableSetOf()) as? IcsTime.DateTime ?: return null
    return time.zone.toInstant(time.value).toString()
}
