package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * RFC 5545 subset used by the app. Events are exported as floating local times, which matches how
 * they are stored (wall-clock date and minute of day), and imported from floating, UTC or TZID
 * timestamps.
 */

private const val LineBreak = "\r\n"
private const val FoldLimit = 75
private const val MaxExpandedOccurrences = 1000
private const val MaxAllDaySpanDays = 366

private val weekdayCodes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/** Serializes every event into one VCALENDAR with CRLF line endings. */
fun exportIcs(
    events: List<Event>,
    now: Instant = Clock.System.now(),
): String = buildString {
    line("BEGIN:VCALENDAR")
    line("VERSION:2.0")
    line("PRODID:-//Crko//Crko Calendar//EN")
    line("CALSCALE:GREGORIAN")
    events.forEachIndexed { index, event -> appendEvent(event, index, now) }
    line("END:VCALENDAR")
}

/**
 * Parses every VEVENT found in [text]. Recurrences the app can represent stay a single series;
 * series bounded by COUNT or UNTIL are expanded into individual events.
 */
fun importIcs(
    text: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    untitledTitle: String,
): List<Event> {
    val rawEvents = readEvents(text)
    if (rawEvents.isEmpty()) return emptyList()

    val baseEvents = mutableListOf<RawEvent>()
    val overrides = mutableMapOf<String, MutableList<RawEvent>>()
    rawEvents.forEach { raw ->
        val recurrenceId = raw.recurrenceId
        val uid = raw.uid
        if (recurrenceId != null && uid != null) {
            overrides.getOrPut(uid) { mutableListOf() } += raw
        } else {
            baseEvents += raw
        }
    }

    val imported = mutableListOf<Event>()
    val baseUids = mutableSetOf<String>()
    val cancelledUids = mutableSetOf<String>()
    baseEvents.forEach { raw ->
        val uid = raw.uid
        if (raw.isCancelled()) {
            uid?.let { cancelledUids += it }
            return@forEach
        }
        uid?.let { baseUids += it }
        val related = uid?.let { overrides[it] }.orEmpty()
        imported += mapBase(raw, related, timeZone, untitledTitle)
        related.forEach { override ->
            mapOverride(override, timeZone, untitledTitle)?.let { imported += it }
        }
    }
    // A file may contain only the modified occurrence of a series.
    overrides.forEach { (uid, list) ->
        if (uid !in baseUids && uid !in cancelledUids) {
            list.forEach { raw ->
                mapOverride(raw, timeZone, untitledTitle)?.let { imported += it }
            }
        }
    }
    return imported
}

private fun StringBuilder.appendEvent(event: Event, index: Int, now: Instant) {
    val uid = event.icsUid?.takeIf { it.isNotBlank() }
        ?: event.id?.let { "$it@crko" }
        ?: "crko-${index + 1}@local"
    line("BEGIN:VEVENT")
    line("UID:${escapeText(uid)}")
    line("DTSTAMP:${formatUtc(now)}")
    line("DTSTART:${formatDateTime(event.day, event.startMinutes)}")
    line("DTEND:${formatDateTime(event.day, event.endMinutes)}")
    line("SUMMARY:${escapeText(event.title)}")
    if (event.note.isNotBlank()) line("DESCRIPTION:${escapeText(event.note)}")
    event.repeat?.let { line("RRULE:${ruleValue(it, event.day)}") }
    if (event.repeat != null && event.excludedDates.isNotEmpty()) {
        val values = event.excludedDates
            .distinct()
            .sorted()
            .mapNotNull { parseDateText(it) }
            .joinToString(",") { formatDateTime(it, event.startMinutes) }
        if (values.isNotEmpty()) line("EXDATE:$values")
    }
    line("END:VEVENT")
}

private fun StringBuilder.line(value: String) {
    append(foldLine(value))
    append(LineBreak)
}

private fun ruleValue(rule: RepeatRule, anchor: LocalDate): String = when (rule.frequency) {
    RepeatFrequency.Daily -> "FREQ=DAILY"
    RepeatFrequency.Weekly -> "FREQ=WEEKLY;BYDAY=${weekdayCodes[anchor.mondayIndex]}"
    RepeatFrequency.Custom -> {
        val days = rule.weekdays.filter { it in 0..6 }.distinct().sorted()
        val byDay = if (days.isEmpty()) {
            weekdayCodes[anchor.mondayIndex]
        } else {
            days.joinToString(",") { weekdayCodes[it] }
        }
        "FREQ=WEEKLY;BYDAY=$byDay"
    }
}

private fun formatDateTime(date: LocalDate, minutes: Int): String = buildString {
    append(date.year.toString().padStart(4, '0'))
    append(date.month.number.toString().padStart(2, '0'))
    append(date.day.toString().padStart(2, '0'))
    append('T')
    append((minutes / 60).toString().padStart(2, '0'))
    append((minutes % 60).toString().padStart(2, '0'))
    append("00")
}

private fun formatUtc(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.UTC)
    return buildString {
        append(local.date.year.toString().padStart(4, '0'))
        append(local.date.month.number.toString().padStart(2, '0'))
        append(local.date.day.toString().padStart(2, '0'))
        append('T')
        append(local.time.hour.toString().padStart(2, '0'))
        append(local.time.minute.toString().padStart(2, '0'))
        append(local.time.second.toString().padStart(2, '0'))
        append('Z')
    }
}

/** Folds at 75 octets without splitting UTF-8 sequences or surrogate pairs. */
private fun foldLine(line: String): String {
    if (line.encodeToByteArray().size <= FoldLimit) return line
    val result = StringBuilder(line.length + line.length / 60)
    var bytesOnLine = 0
    var firstLine = true
    var index = 0
    while (index < line.length) {
        val codePoints = if (line[index].isHighSurrogate() && index + 1 < line.length) 2 else 1
        val piece = line.substring(index, index + codePoints)
        val pieceBytes = piece.encodeToByteArray().size
        val limit = if (firstLine) FoldLimit else FoldLimit - 1
        if (bytesOnLine + pieceBytes > limit) {
            result.append(LineBreak).append(' ')
            bytesOnLine = 0
            firstLine = false
        }
        result.append(piece)
        bytesOnLine += pieceBytes
        index += codePoints
    }
    return result.toString()
}

private fun escapeText(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        when (val ch = value[index]) {
            '\\' -> append("\\\\")
            ';' -> append("\\;")
            ',' -> append("\\,")
            '\n' -> append("\\n")
            '\r' -> {
                // CRLF is one logical newline; the LF half is escaped on the next iteration.
                if (index + 1 >= value.length || value[index + 1] != '\n') append("\\n")
            }
            else -> append(ch)
        }
        index++
    }
}

private fun unescapeText(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '\\' && index + 1 < value.length) {
            when (val next = value[index + 1]) {
                'n', 'N' -> append('\n')
                '\\', ';', ',' -> append(next)
                else -> append(next)
            }
            index += 2
        } else {
            append(ch)
            index++
        }
    }
}

private fun parseDateText(text: String): LocalDate? =
    runCatching { LocalDate.parse(text) }.getOrNull()

private fun unfold(text: String): List<String> {
    val normalized = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val logical = mutableListOf<String>()
    normalized.split('\n').forEach { line ->
        if ((line.startsWith(' ') || line.startsWith('\t')) && logical.isNotEmpty()) {
            logical[logical.lastIndex] += line.substring(1)
        } else {
            logical += line
        }
    }
    return logical
}

private class ContentLine(val name: String, val params: Map<String, String>, val value: String)

private fun parseContentLine(line: String): ContentLine? {
    var colon = -1
    var quoted = false
    for (index in line.indices) {
        when (line[index]) {
            '"' -> quoted = !quoted
            ':' -> if (!quoted) {
                colon = index
                break
            }
        }
    }
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    val value = line.substring(colon + 1)
    val parts = splitParameters(head)
    if (parts.isEmpty()) return null
    val params = parts.drop(1).mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) {
            null
        } else {
            part.substring(0, equals).trim().uppercase() to part.substring(equals + 1).trim().removeSurrounding("\"")
        }
    }.toMap()
    return ContentLine(parts.first().trim().uppercase(), params, value)
}

private fun splitParameters(head: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    head.forEach { ch ->
        when {
            ch == '"' -> {
                quoted = !quoted
                current.append(ch)
            }
            ch == ';' && !quoted -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
    }
    parts += current.toString()
    return parts
}

private sealed interface IcsTime {
    data class AllDay(val date: LocalDate) : IcsTime
    data class DateTime(val value: LocalDateTime, val zone: TimeZone?) : IcsTime
}

private data class RawRule(
    val frequency: String,
    val interval: Int,
    val byDay: List<Int>,
    val until: IcsTime?,
    val count: Int?,
)

private data class RawEvent(
    val uid: String? = null,
    val summary: String = "",
    val description: String = "",
    val start: IcsTime? = null,
    val end: IcsTime? = null,
    val durationMinutes: Int? = null,
    val recurrenceId: IcsTime? = null,
    val status: String? = null,
    val rule: RawRule? = null,
    val exdates: List<IcsTime> = emptyList(),
)

private fun RawEvent.isCancelled(): Boolean = status.equals("CANCELLED", ignoreCase = true)

private class RawEventBuilder {
    var uid: String? = null
    var summary = ""
    var description = ""
    var start: IcsTime? = null
    var end: IcsTime? = null
    var durationMinutes: Int? = null
    var recurrenceId: IcsTime? = null
    var status: String? = null
    var rule: RawRule? = null
    val exdates = mutableListOf<IcsTime>()

    fun build() = RawEvent(
        uid = uid,
        summary = summary,
        description = description,
        start = start,
        end = end,
        durationMinutes = durationMinutes,
        recurrenceId = recurrenceId,
        status = status,
        rule = rule,
        exdates = exdates.toList(),
    )
}

private fun readEvents(text: String): List<RawEvent> {
    val events = mutableListOf<RawEvent>()
    var builder: RawEventBuilder? = null
    var nestedDepth = 0
    unfold(text).forEach { line ->
        val parsed = parseContentLine(line) ?: return@forEach
        when (parsed.name) {
            "BEGIN" -> {
                val component = parsed.value.trim()
                if (builder == null && component.equals("VEVENT", ignoreCase = true)) {
                    builder = RawEventBuilder()
                    nestedDepth = 0
                } else if (builder != null) {
                    nestedDepth++
                }
            }
            "END" -> {
                val component = parsed.value.trim()
                when {
                    builder == null -> Unit
                    component.equals("VEVENT", ignoreCase = true) && nestedDepth == 0 -> {
                        events += builder.build()
                        builder = null
                    }
                    nestedDepth > 0 -> nestedDepth--
                }
            }
            else -> if (builder != null && nestedDepth == 0) applyProperty(builder, parsed)
        }
    }
    return events
}

private fun applyProperty(target: RawEventBuilder, line: ContentLine) {
    when (line.name) {
        "UID" -> target.uid = unescapeText(line.value).trim().takeIf { it.isNotEmpty() }
        "SUMMARY" -> target.summary = unescapeText(line.value).trim()
        "DESCRIPTION" -> target.description = unescapeText(line.value).trim()
        "DTSTART" -> target.start = parseIcsTime(line.value, line.params)
        "DTEND" -> target.end = parseIcsTime(line.value, line.params)
        "DURATION" -> target.durationMinutes = parseDurationMinutes(line.value)
        "RECURRENCE-ID" -> target.recurrenceId = parseIcsTime(line.value, line.params)
        "STATUS" -> target.status = line.value.trim().uppercase()
        "RRULE" -> target.rule = parseRule(line.value)
        "EXDATE" -> line.value.split(',').forEach { value ->
            parseIcsTime(value, line.params)?.let { target.exdates += it }
        }
    }
}

private fun parseRule(value: String): RawRule? {
    val parts = value.split(';').mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) null else part.substring(0, equals).trim().uppercase() to part.substring(equals + 1).trim()
    }.toMap()
    val frequency = parts["FREQ"]?.uppercase() ?: return null
    val interval = parts["INTERVAL"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    val byDay = parts["BYDAY"].orEmpty().split(',').mapNotNull { day ->
        val code = day.trim().uppercase().takeLast(2)
        weekdayCodes.indexOf(code).takeIf { it >= 0 }
    }.distinct().sorted()
    val until = parts["UNTIL"]?.let { parseIcsTime(it, emptyMap()) }
    val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
    return RawRule(frequency, interval, byDay, until, count)
}

private fun parseDurationMinutes(value: String): Int? {
    val text = value.trim().uppercase()
    if (!text.startsWith("P")) return null
    var total = 0
    var number = 0
    var inTime = false
    var sawDigit = false
    text.drop(1).forEach { ch ->
        when {
            ch == 'T' -> inTime = true
            ch.isDigit() -> {
                number = number * 10 + (ch - '0')
                sawDigit = true
            }
            else -> {
                total += when (ch) {
                    'W' -> number * 7 * 24 * 60
                    'D' -> number * 24 * 60
                    'H' -> if (inTime) number * 60 else 0
                    'M' -> if (inTime) number else 0
                    else -> 0
                }
                number = 0
            }
        }
    }
    return if (sawDigit) total else null
}

private fun parseIcsTime(value: String, params: Map<String, String>): IcsTime? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val dateOnly = params["VALUE"]?.equals("DATE", ignoreCase = true) == true ||
        (text.length == 8 && text.all { it.isDigit() })
    if (dateOnly) {
        val date = parseDate(text.take(8)) ?: return null
        return IcsTime.AllDay(date)
    }
    val utc = text.endsWith("Z", ignoreCase = true)
    val body = if (utc) text.dropLast(1) else text
    if (body.length < 15 || body[8] != 'T') return null
    val date = parseDate(body.substring(0, 8)) ?: return null
    val hour = body.substring(9, 11).toIntOrNull() ?: return null
    val minute = body.substring(11, 13).toIntOrNull() ?: return null
    val second = body.substring(13, 15).toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
    val time = LocalTime(hour, minute, second)
    val zone = when {
        utc -> TimeZone.UTC
        params["TZID"] != null -> runCatching { TimeZone.of(params.getValue("TZID")) }.getOrNull()
        else -> null
    }
    return IcsTime.DateTime(LocalDateTime(date, time), zone)
}

private fun parseDate(text: String): LocalDate? {
    if (text.length < 8) return null
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(4, 6).toIntOrNull() ?: return null
    val day = text.substring(6, 8).toIntOrNull() ?: return null
    return runCatching { LocalDate(year, month, day) }.getOrNull()
}

private fun IcsTime.localDateIn(deviceZone: TimeZone): LocalDate = when (this) {
    is IcsTime.AllDay -> date
    is IcsTime.DateTime -> zone?.let { sourceZone ->
        runCatching { value.toInstant(sourceZone).toLocalDateTime(deviceZone).date }.getOrNull()
    } ?: value.date
}

private fun IcsTime.localMinutesIn(deviceZone: TimeZone): Pair<LocalDate, Int?> = when (this) {
    is IcsTime.AllDay -> date to null
    is IcsTime.DateTime -> {
        val resolved = zone?.let { sourceZone ->
            runCatching { value.toInstant(sourceZone).toLocalDateTime(deviceZone) }.getOrNull()
        }
        if (resolved == null) {
            value.date to value.time.minutesOfDay()
        } else {
            resolved.date to resolved.time.minutesOfDay()
        }
    }
}

private fun mapBase(
    raw: RawEvent,
    overrides: List<RawEvent>,
    deviceZone: TimeZone,
    untitledTitle: String,
): List<Event> {
    val start = raw.start ?: return emptyList()
    val title = raw.summary.ifBlank { untitledTitle }
    val uid = raw.uid
    val anchor = start.localDateIn(deviceZone)
    val exclusions = (raw.exdates.map { it.localDateIn(deviceZone) } +
        overrides.mapNotNull { it.recurrenceId?.localDateIn(deviceZone) })
        .distinct()
        .sorted()
    val rule = raw.rule
    val repeatsForever = rule != null && rule.count == null && rule.until == null
    val series = if (repeatsForever) mapInfiniteRule(rule, anchor) else null
    val expanded = if (series == null && rule != null && (rule.count != null || rule.until != null)) {
        expandDates(anchor, rule, deviceZone)
    } else {
        null
    }

    if (start is IcsTime.AllDay) {
        val endExclusive = when (val end = raw.end) {
            is IcsTime.AllDay -> if (end.date > start.date) end.date else start.date.plusDays(1)
            else -> start.date.plusDays(1)
        }
        if (series != null) {
            return listOf(
                Event(
                    id = null,
                    title = title,
                    date = start.date.toString(),
                    startMinutes = 0,
                    endMinutes = MaxEndMinutes,
                    note = raw.description,
                    repeat = series,
                    excludedDates = exclusions.map { it.toString() },
                    icsUid = uid,
                ),
            )
        }
        if (expanded != null) {
            return expanded.filterNot { it in exclusions }.map { date ->
                Event(
                    id = null,
                    title = title,
                    date = date.toString(),
                    startMinutes = 0,
                    endMinutes = MaxEndMinutes,
                    note = raw.description,
                    icsUid = occurrenceUid(uid, date),
                )
            }
        }
        val days = generateSequence(start.date) { it.plusDays(1) }
            .takeWhile { it < endExclusive }
            .take(MaxAllDaySpanDays)
            .toList()
            .ifEmpty { listOf(start.date) }
        return days.map { date ->
            Event(
                id = null,
                title = title,
                date = date.toString(),
                startMinutes = 0,
                endMinutes = MaxEndMinutes,
                note = raw.description,
                icsUid = if (days.size == 1) uid else uid?.let { "$it#part-$date" },
            )
        }
    }

    val (startDate, startValue) = start.localMinutesIn(deviceZone)
    val endLocal = raw.end?.localMinutesIn(deviceZone)
    val rawStart = startValue ?: 0
    val endMinutes = when {
        endLocal == null -> (rawStart + (raw.durationMinutes ?: 60)).coerceAtMost(MaxEndMinutes)
        endLocal.first > startDate -> MaxEndMinutes
        endLocal.first < startDate -> (rawStart + (raw.durationMinutes ?: 60)).coerceAtMost(MaxEndMinutes)
        else -> endLocal.second ?: rawStart
    }
    val safeStart = rawStart.coerceIn(0, MaxEndMinutes - 1)
    val safeEnd = if (endMinutes > safeStart) {
        endMinutes.coerceAtMost(MaxEndMinutes)
    } else {
        (safeStart + 60).coerceAtMost(MaxEndMinutes)
    }
    val base = Event(
        id = null,
        title = title,
        date = startDate.toString(),
        startMinutes = safeStart,
        endMinutes = safeEnd,
        note = raw.description,
        repeat = series,
        excludedDates = if (series != null) exclusions.map { it.toString() } else emptyList(),
        icsUid = uid,
    )
    if (series != null) return listOf(base)
    if (expanded != null) {
        return expanded.filterNot { it in exclusions }.map { date ->
            base.copy(date = date.toString(), icsUid = occurrenceUid(uid, date))
        }
    }
    return listOf(base.copy(excludedDates = emptyList()))
}

private fun occurrenceUid(uid: String?, date: LocalDate): String? = uid?.let { "$it#$date" }

private fun mapOverride(raw: RawEvent, deviceZone: TimeZone, untitledTitle: String): Event? {
    if (raw.isCancelled()) return null
    val normalized = raw.copy(
        rule = null,
        exdates = emptyList(),
        recurrenceId = null,
        start = raw.start ?: raw.recurrenceId,
    )
    val mapped = mapBase(normalized, emptyList(), deviceZone, untitledTitle).firstOrNull() ?: return null
    val suffix = raw.recurrenceId?.localDateIn(deviceZone)?.toString() ?: mapped.date
    return mapped.copy(icsUid = raw.uid?.let { "$it#override-$suffix" })
}

private fun mapInfiniteRule(rule: RawRule, anchor: LocalDate): RepeatRule? {
    if (rule.interval != 1) return null
    return when (rule.frequency) {
        "DAILY" -> if (rule.byDay.isEmpty()) {
            RepeatRule(RepeatFrequency.Daily)
        } else {
            weekdaysRule(rule.byDay, anchor)
        }
        "WEEKLY" -> if (rule.byDay.isEmpty()) {
            RepeatRule(RepeatFrequency.Weekly)
        } else {
            weekdaysRule(rule.byDay, anchor)
        }
        else -> null
    }
}

private fun weekdaysRule(days: List<Int>, anchor: LocalDate): RepeatRule {
    val normalized = days.filter { it in 0..6 }.distinct().sorted()
    return if (normalized.isEmpty() || normalized == listOf(anchor.mondayIndex)) {
        RepeatRule(RepeatFrequency.Weekly)
    } else {
        RepeatRule(RepeatFrequency.Custom, normalized)
    }
}

private fun expandDates(anchor: LocalDate, rule: RawRule, deviceZone: TimeZone): List<LocalDate>? {
    val interval = rule.interval.coerceAtLeast(1)
    val until = rule.until?.localDateIn(deviceZone)
    val limit = minOf(rule.count ?: MaxExpandedOccurrences, MaxExpandedOccurrences)
    if (limit <= 0) return emptyList()
    val dates = mutableListOf<LocalDate>()
    val guardLimit = MaxExpandedOccurrences * 8
    when (rule.frequency) {
        "DAILY" -> {
            if (rule.byDay.isNotEmpty() && interval != 1) return null
            var cursor = anchor
            var guard = 0
            while (dates.size < limit && guard <= guardLimit) {
                if (until != null && cursor > until) break
                if (rule.byDay.isEmpty() || cursor.mondayIndex in rule.byDay) dates += cursor
                cursor = cursor.plusDays(interval)
                guard++
            }
        }
        "WEEKLY" -> {
            val weekdays = rule.byDay.ifEmpty { listOf(anchor.mondayIndex) }.sorted()
            var weekStart = anchor.startOfWeek()
            var guard = 0
            while (dates.size < limit && guard <= guardLimit) {
                for (weekday in weekdays) {
                    if (dates.size >= limit) break
                    val date = weekStart.plusDays(weekday)
                    if (date < anchor) continue
                    if (until != null && date > until) return dates
                    dates += date
                }
                weekStart = weekStart.plusDays(7 * interval)
                guard++
            }
        }
        else -> return null
    }
    return dates
}
