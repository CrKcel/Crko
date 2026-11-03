package com.crko.migration

import com.crko.domain.Alarm
import com.crko.domain.Attachment
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
import com.crko.domain.legacyCalAddress
import com.crko.domain.legacyCalAddresses
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
 * Migration helpers for the pre-migration event fields. Production code uses the RFC time values
 * and the display projections in TimeValues.kt; these convert legacy fixtures and stored payloads.
 */

/** Original ICS local text (compact form, `Z` for UTC) of a time value. */
internal fun legacySourceText(value: IcsTimeValue?): String? = when (value) {
    is IcsTimeValue.Zoned -> compactIcsLocal(value.dateTime)
    is IcsTimeValue.Utc -> compactIcsLocal(value.instant.toLocalDateTime(TimeZone.UTC)) + "Z"
    else -> null
}

internal fun compactIcsLocal(value: LocalDateTime): String = buildString {
    append(value.year.toString().padStart(4, '0'))
    append(value.month.number.toString().padStart(2, '0'))
    append(value.day.toString().padStart(2, '0'))
    append('T')
    append(value.hour.toString().padStart(2, '0'))
    append(value.minute.toString().padStart(2, '0'))
    append(value.second.toString().padStart(2, '0'))
}

/** Builds a stage-2 recurrence set from the pre-stage-2 rule and date-only exclusions. */
internal fun legacyRecurrence(rule: RepeatRule?, excludedDates: List<String>): Recurrence? {
    val exdates = excludedDates.mapNotNull { text -> parseStoredDate(text)?.let(IcsTimeValue::Date) }
    if (rule == null && exdates.isEmpty()) return null
    return Recurrence(rule = rule, exdates = exdates)
}

/** Converts the old device-local start fields into an RFC time value. */
internal fun legacyIcsTimeValue(
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
    val time = legacyTime(minutes, seconds)
    val sourceLocal = source?.let(::parseLegacySource)
    val wall = sourceLocal ?: LocalDateTime(storedDate, time)
    // Without the original source text the zone cannot be reconstructed; the old value was the
    // device wall clock, which is exactly what a floating value preserves.
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

/** Converts the old inclusive end fields into an exclusive RFC end (null for zero length). */
internal fun legacyEndValue(
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
    val start = legacyIcsTimeValue(
        date = date,
        minutes = startMinutes,
        seconds = startSeconds,
        allDay = false,
        source = startSource,
        tzid = tzid,
        definition = definition,
        definitions = definitions,
    )
    val wall = endSource?.let(::parseLegacySource)
        ?: LocalDateTime(parseStoredDate(endDate ?: date) ?: startDate, legacyTime(endMinutes, endSeconds))
    if (wall <= start.wallDateTime) return null
    return when (start) {
        is IcsTimeValue.Zoned -> IcsTimeValue.Zoned(wall, start.tzid)
        is IcsTimeValue.Utc -> IcsTimeValue.Utc(wall.toInstant(TimeZone.UTC))
        is IcsTimeValue.Floating -> IcsTimeValue.Floating(wall)
        is IcsTimeValue.Date -> IcsTimeValue.Date(wall.date)
    }
}

private fun legacyTime(minutes: Int, seconds: Int): LocalTime {
    // The old model clamped an end late at night onto 23:59; keep that ceiling.
    val safeMinutes = minutes.coerceIn(0, MaxEndMinutes)
    return LocalTime(safeMinutes / 60, safeMinutes % 60, seconds.coerceIn(0, 59))
}

/**
 * Builds an event from the pre-migration device-local fields. Fixtures and migration tests use it;
 * production code constructs [Event] from RFC time values.
 */
internal fun legacyEvent(
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
    start = legacyIcsTimeValue(
        date = date,
        minutes = startMinutes,
        seconds = startSeconds,
        allDay = allDay,
        source = sourceStart,
        tzid = timeZoneId,
        definition = null,
    ),
    end = legacyEndValue(
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
    organizer = legacyCalAddress(organizerRaw, organizer),
    attendees = legacyCalAddresses(attendeeRaw, attendees),
    alarms = alarms,
    recurrence = legacyRecurrence(repeat, excludedDates),
    // An old exception stored its series UID separately; the RFC identity is the shared UID.
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

/** Compact `YYYYMMDDTHHMMSS` value from the old `sourceStart`/`sourceEnd` text. */
internal fun parseLegacySource(text: String): LocalDateTime? {
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
