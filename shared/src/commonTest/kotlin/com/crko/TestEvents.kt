package com.crko

import com.crko.domain.Alarm
import com.crko.domain.Attachment
import com.crko.domain.CalAddress
import com.crko.domain.CalendarTimeZone
import com.crko.domain.Event
import com.crko.domain.EventClass
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalRecordMeta
import com.crko.domain.MaxEndMinutes
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceRange
import com.crko.domain.RepeatRule
import com.crko.domain.parseStoredDate
import com.crko.domain.plusDays
import com.crko.domain.wallDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/*
 * Fixture builders shared by the calendar tests. They construct an [Event] from the compact
 * day-and-minute shape the tests describe; `LegacyEventTestSupport` projects an event back onto the
 * same shape for assertions.
 */

/** Compact `YYYYMMDDTHHMMSS` ICS text (`Z` suffix for UTC) of a time value. */
internal fun icsSourceText(value: IcsTimeValue?): String? = when (value) {
    is IcsTimeValue.Zoned -> compactIcsLocal(value.dateTime)
    is IcsTimeValue.Utc -> compactIcsLocal(value.instant.toLocalDateTime(TimeZone.UTC)) + "Z"
    else -> null
}

private fun compactIcsLocal(value: LocalDateTime): String = buildString {
    append(value.year.toString().padStart(4, '0'))
    append(value.month.number.toString().padStart(2, '0'))
    append(value.day.toString().padStart(2, '0'))
    append('T')
    append(value.hour.toString().padStart(2, '0'))
    append(value.minute.toString().padStart(2, '0'))
    append(value.second.toString().padStart(2, '0'))
}

/** Builds a recurrence set from the fixture rule and date-only exclusions. */
private fun fixtureRecurrence(rule: RepeatRule?, excludedDates: List<String>): Recurrence? {
    val exdates = excludedDates.mapNotNull { text -> parseStoredDate(text)?.let(IcsTimeValue::Date) }
    if (rule == null && exdates.isEmpty()) return null
    return Recurrence(rule = rule, exdates = exdates)
}

/** Converts the fixture's day-and-minute start fields into an RFC time value. */
private fun fixtureTimeValue(
    date: String,
    minutes: Int,
    seconds: Int,
    allDay: Boolean,
    source: String?,
    tzid: String?,
    definition: String?,
    definitions: MutableMap<String, CalendarTimeZone>? = null,
): IcsTimeValue {
    val storedDate = parseStoredDate(date) ?: LocalDate(1970, 1, 1)
    if (allDay) return IcsTimeValue.Date(storedDate)
    val time = fixtureTime(minutes, seconds)
    val sourceLocal = source?.let(::parseFixtureSource)
    val wall = sourceLocal ?: LocalDateTime(storedDate, time)
    // Without original source text the zone cannot be reconstructed; the plain day-and-minute
    // fields describe the device wall clock, which is exactly what a floating value preserves.
    if (source == null) return IcsTimeValue.Floating(LocalDateTime(storedDate, time))
    return when {
        source.endsWith("Z", ignoreCase = true) == true -> IcsTimeValue.Utc(wall.toInstant(TimeZone.UTC))
        tzid.equals("UTC", ignoreCase = true) -> IcsTimeValue.Utc(wall.toInstant(TimeZone.UTC))
        !tzid.isNullOrBlank() -> {
            definition?.takeIf { it.isNotBlank() }?.let { text ->
                definitions?.put(tzid, CalendarTimeZone(tzid, text))
            }
            IcsTimeValue.Zoned(wall, tzid)
        }
        else -> IcsTimeValue.Floating(wall)
    }
}

/** Converts the fixture's inclusive end fields into an exclusive RFC end (null for zero length). */
private fun fixtureEndValue(
    date: String,
    endDate: String?,
    startMinutes: Int,
    endMinutes: Int,
    startSeconds: Int,
    endSeconds: Int,
    allDay: Boolean,
    startSource: String?,
    endSource: String?,
    tzid: String?,
    definition: String?,
    definitions: MutableMap<String, CalendarTimeZone>? = null,
): IcsTimeValue? {
    val startDate = parseStoredDate(date) ?: LocalDate(1970, 1, 1)
    if (allDay) {
        val last = parseStoredDate(endDate ?: date) ?: startDate
        val exclusive = last.plusDays(1)
        return IcsTimeValue.Date(if (exclusive > startDate) exclusive else startDate.plusDays(1))
    }
    val start = fixtureTimeValue(
        date = date,
        minutes = startMinutes,
        seconds = startSeconds,
        allDay = false,
        source = startSource,
        tzid = tzid,
        definition = definition,
        definitions = definitions,
    )
    val wall = endSource?.let(::parseFixtureSource)
        ?: LocalDateTime(parseStoredDate(endDate ?: date) ?: startDate, fixtureTime(endMinutes, endSeconds))
    if (wall <= start.wallDateTime) return null
    return when (start) {
        is IcsTimeValue.Zoned -> IcsTimeValue.Zoned(wall, start.tzid)
        is IcsTimeValue.Utc -> IcsTimeValue.Utc(wall.toInstant(TimeZone.UTC))
        is IcsTimeValue.Floating -> IcsTimeValue.Floating(wall)
        is IcsTimeValue.Date -> IcsTimeValue.Date(wall.date)
    }
}

private fun fixtureTime(minutes: Int, seconds: Int): LocalTime {
    // A late-night end stays clamped to 23:59; midnight is written as the next day at 00:00.
    val safeMinutes = minutes.coerceIn(0, MaxEndMinutes)
    return LocalTime(safeMinutes / 60, safeMinutes % 60, seconds.coerceIn(0, 59))
}

/** Compact `YYYYMMDDTHHMMSS` value from the fixture's `sourceStart`/`sourceEnd` text. */
private fun parseFixtureSource(text: String): LocalDateTime? {
    val body = text.trim().removeSuffix("Z").removeSuffix("z")
    if (body.length < 15 || body[8] != 'T') return null
    val date = runCatching {
        LocalDate(body.substring(0, 4).toInt(), body.substring(4, 6).toInt(), body.substring(6, 8).toInt())
    }.getOrNull() ?: return null
    return runCatching {
        LocalDateTime(
            date,
            LocalTime(body.substring(9, 11).toInt(), body.substring(11, 13).toInt(), body.substring(13, 15).toInt()),
        )
    }.getOrNull()
}

/** Fixture participants stored as bare values and, separately, their original lines. */
private fun fixtureCalAddress(raw: String?, value: String?): CalAddress? =
    raw?.takeIf { it.isNotBlank() }?.let { CalAddress.parse(it, "ORGANIZER") }
        ?: value?.takeIf { it.isNotBlank() }?.let(::CalAddress)

private fun fixtureCalAddresses(raw: List<String>, values: List<String>): List<CalAddress> =
    raw.mapNotNull { CalAddress.parse(it, "ATTENDEE") }.ifEmpty {
        values.filter { it.isNotBlank() }.map(::CalAddress)
    }

private fun stripSyntheticSeriesUid(uid: String?): String? {
    val value = uid?.takeIf { it.isNotBlank() } ?: return null
    val marker = listOf("#override-", "#future-", "#prior-")
        .map { value.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: return value
    return value.substring(0, marker).takeIf { it.isNotBlank() }
}

/** Builds an [Event] from the fixture fields; production code constructs events from RFC values. */
internal fun testEvent(
    id: String?,
    title: String,
    date: String,
    startMinutes: Int,
    endMinutes: Int,
    startSeconds: Int = 0,
    endSeconds: Int = 0,
    note: String = "",
    endDate: String? = null,
    allDay: Boolean = false,
    location: String = "",
    categories: List<String> = emptyList(),
    url: String = "",
    geo: String? = null,
    attachments: List<Attachment> = emptyList(),
    status: EventStatus = EventStatus.Confirmed,
    transparency: EventTransparency = EventTransparency.Opaque,
    eventClass: EventClass? = null,
    priority: Int? = null,
    organizer: String? = null,
    attendees: List<String> = emptyList(),
    alarms: List<Alarm> = emptyList(),
    repeat: RepeatRule? = null,
    excludedDates: List<String> = emptyList(),
    icsUid: String? = null,
    seriesUid: String? = null,
    recurrenceId: String? = null,
    thisAndFuture: Boolean = false,
    sourceStart: String? = null,
    sourceEnd: String? = null,
    timeZoneId: String? = null,
    created: String? = null,
    lastModified: String? = null,
    dtStamp: String? = null,
    sequence: Int = 0,
    extraProperties: List<String> = emptyList(),
    organizerRaw: String? = null,
    attendeeRaw: List<String> = emptyList(),
    importSource: String? = null,
    sourceLine: Int = 0,
): Event = Event(
    id = id,
    title = title,
    start = fixtureTimeValue(
        date = date,
        minutes = startMinutes,
        seconds = startSeconds,
        allDay = allDay,
        source = sourceStart,
        tzid = timeZoneId,
        definition = null,
    ),
    end = fixtureEndValue(
        date = date,
        endDate = endDate,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        startSeconds = startSeconds,
        endSeconds = endSeconds,
        allDay = allDay,
        startSource = sourceStart,
        endSource = sourceEnd,
        tzid = timeZoneId,
        definition = null,
    ),
    note = note,
    location = location,
    categories = categories,
    url = url,
    geo = geo,
    attachments = attachments,
    status = status,
    transparency = transparency,
    eventClass = eventClass,
    priority = priority,
    organizer = fixtureCalAddress(organizerRaw, organizer),
    attendees = fixtureCalAddresses(attendeeRaw, attendees),
    alarms = alarms,
    recurrence = fixtureRecurrence(repeat, excludedDates),
    // A fixture exception carries its series UID separately; the RFC identity is the shared UID.
    icsUid = seriesUid ?: stripSyntheticSeriesUid(icsUid),
    recurrenceId = recurrenceId?.let { text ->
        parseStoredDate(text)?.let(IcsTimeValue::Date)
    },
    recurrenceRange = if (thisAndFuture) RecurrenceRange.ThisAndFuture else null,
    created = created,
    lastModified = lastModified,
    dtStamp = dtStamp,
    sequence = sequence,
    extraProperties = extraProperties,
    local = LocalRecordMeta(importSource, sourceLine),
)
