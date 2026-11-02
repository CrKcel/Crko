package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Persisted layouts before stage 2: device-local wall-clock fields for pre-stage-1 payloads, and a
 * `repeat` object with `weekdays`/`rDates`/string `until` for stage-1 ones. Both are only read.
 */

@Serializable
internal enum class LegacyRepeatFrequency { Daily, Weekly, Monthly, Yearly, Custom }

@Serializable
internal data class LegacyRepeatRule(
    val frequency: LegacyRepeatFrequency = LegacyRepeatFrequency.Daily,
    val weekdays: List<Int> = emptyList(),
    val interval: Int = 1,
    val count: Int? = null,
    /** ISO date, floating date-time or UTC instant, as the old model stored it. */
    val until: String? = null,
    val byDay: List<OrdinalWeekday> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val bySetPos: List<Int> = emptyList(),
    val byYearDay: List<Int> = emptyList(),
    val byWeekNo: List<Int> = emptyList(),
    val weekStart: Int? = null,
    val byHour: List<Int> = emptyList(),
    val byMinute: List<Int> = emptyList(),
    val bySecond: List<Int> = emptyList(),
    /** `RDATE` dates, ISO-8601. */
    val rDates: List<String> = emptyList(),
)

/** Old rule plus the date-only values that now belong to [Recurrence]. */
private data class LegacyRecurrenceSet(val rule: RepeatRule?, val rDates: List<String>)

private fun LegacyRepeatRule.toCurrent(): LegacyRecurrenceSet = LegacyRecurrenceSet(
    rule = RepeatRule(
        frequency = when (frequency) {
            LegacyRepeatFrequency.Daily -> RepeatFrequency.Daily
            LegacyRepeatFrequency.Weekly, LegacyRepeatFrequency.Custom -> RepeatFrequency.Weekly
            LegacyRepeatFrequency.Monthly -> RepeatFrequency.Monthly
            LegacyRepeatFrequency.Yearly -> RepeatFrequency.Yearly
        },
        interval = interval,
        count = count,
        until = parseLegacyUntil(until),
        // The old model kept plain weekdays separately; `BYDAY` now carries both shapes.
        byDay = (weekdays.map { OrdinalWeekday(0, it) } + byDay)
            .filter { it.weekday in 0..6 }
            .distinct(),
        byMonthDay = byMonthDay,
        byMonth = byMonth,
        bySetPos = bySetPos,
        byYearDay = byYearDay,
        byWeekNo = byWeekNo,
        weekStart = weekStart,
        byHour = byHour,
        byMinute = byMinute,
        bySecond = bySecond,
    ),
    rDates = rDates,
)

private fun parseLegacyUntil(text: String?): IcsTimeValue? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.endsWith("Z", ignoreCase = true)) {
        return runCatching { Instant.parse(value) }.getOrNull()?.let(IcsTimeValue::Utc)
    }
    if ('T' in value) {
        return runCatching { LocalDateTime.parse(value) }.getOrNull()?.let(IcsTimeValue::Floating)
    }
    return runCatching { LocalDate.parse(value) }.getOrNull()?.let(IcsTimeValue::Date)
}

private fun parseLegacyDate(text: String): IcsTimeValue? =
    runCatching { LocalDate.parse(text) }.getOrNull()?.let(IcsTimeValue::Date)

/**
 * Old versions encoded an exception's identity as `<seriesUID>#override-<date>`, and the series
 * side of a split as `#future-`/`#prior-`. The exception's `seriesUid` (or, failing that, the UID
 * before the marker) is the RFC identity, so the synthetic suffix must not survive migration.
 */
internal fun stripSyntheticSeriesUid(uid: String?): String? {
    val value = uid?.takeIf { it.isNotBlank() } ?: return null
    val marker = listOf("#override-", "#future-", "#prior-")
        .map { value.indexOf(it) }
        .filter { it >= 0 }
        .minOrNull() ?: return value
    return value.substring(0, marker).takeIf { it.isNotBlank() }
}

/** Builds the stage-2 recurrence set from the old rule, `RDATE` dates and excluded dates. */
private fun legacyRecurrence(
    repeat: LegacyRepeatRule?,
    excludedDates: List<String>,
): Recurrence? {
    val legacy = repeat?.toCurrent()
    val rdates = legacy?.rDates.orEmpty().mapNotNull { text ->
        parseLegacyDate(text)?.let(::RecurrenceDate)
    }
    val exdates = excludedDates.mapNotNull(::parseLegacyDate)
    if (legacy?.rule == null && rdates.isEmpty() && exdates.isEmpty()) return null
    return Recurrence(rule = legacy?.rule, rdates = rdates, exdates = exdates)
}

@Serializable
internal data class LegacyEvent(
    val id: String? = null,
    val title: String = "",
    /** Pre-stage-1 fields. */
    val date: String = "",
    val startMinutes: Int = 0,
    val endMinutes: Int = 0,
    val startSeconds: Int = 0,
    val endSeconds: Int = 0,
    val note: String = "",
    val endDate: String? = null,
    val allDay: Boolean = false,
    /** Stage-1 fields; when present they are already RFC values. */
    val start: IcsTimeValue? = null,
    val end: IcsTimeValue? = null,
    val location: String = "",
    val categories: List<String> = emptyList(),
    val url: String = "",
    val geo: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val status: EventStatus = EventStatus.Confirmed,
    val transparency: EventTransparency = EventTransparency.Opaque,
    val eventClass: EventClass? = null,
    val priority: Int? = null,
    val organizer: String? = null,
    val attendees: List<String> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
    val repeat: LegacyRepeatRule? = null,
    val excludedDates: List<String> = emptyList(),
    val icsUid: String? = null,
    val seriesUid: String? = null,
    val recurrenceId: String? = null,
    val thisAndFuture: Boolean = false,
    val sourceStart: String? = null,
    val sourceEnd: String? = null,
    val timeZoneId: String? = null,
    val timeZoneDefinition: String? = null,
    val created: String? = null,
    val lastModified: String? = null,
    val dtStamp: String? = null,
    val sequence: Int = 0,
    val extraProperties: List<String> = emptyList(),
    val organizerRaw: String? = null,
    val attendeeRaw: List<String> = emptyList(),
    val importSource: String? = null,
    val sourceLine: Int = 0,
)

/** Converts a stored legacy record, or null when its anchor date is unusable. */
internal fun LegacyEvent.toCurrentEvent(): Event? {
    val currentStart = start ?: run {
        // A damaged payload can carry a date that is present but unparseable; dropping the record
        // is better than putting it on 1970-01-01 and crashing every later calendar query.
        if (parseStoredDate(date) == null) return null
        legacyIcsTimeValue(
            date = date,
            minutes = startMinutes,
            seconds = startSeconds,
            allDay = allDay,
            source = sourceStart,
            tzid = timeZoneId,
            definition = timeZoneDefinition,
        )
    }
    val currentEnd = if (start != null) {
        end
    } else {
        legacyEndValue(
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
            definition = timeZoneDefinition,
        )
    }
    return Event(
        id = id,
        title = title,
        start = currentStart,
        end = currentEnd,
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
        organizer = organizer,
        attendees = attendees,
        alarms = alarms,
        recurrence = legacyRecurrence(repeat, excludedDates),
        // Old exceptions stored the series UID separately; RFC identity is the shared UID.
        icsUid = seriesUid ?: stripSyntheticSeriesUid(icsUid),
        recurrenceId = recurrenceId?.let(::parseLegacyDate),
        recurrenceRange = if (thisAndFuture) RecurrenceRange.ThisAndFuture else null,
        timeZoneDefinition = timeZoneDefinition,
        created = created,
        lastModified = lastModified,
        dtStamp = dtStamp,
        sequence = sequence,
        extraProperties = extraProperties,
        organizerRaw = organizerRaw,
        attendeeRaw = attendeeRaw,
        importSource = importSource,
        sourceLine = sourceLine,
    )
}

/** Stage-1 `Todo`, whose `repeat` still carried `weekdays`/`rDates` and a string `until`. */
@Serializable
internal data class LegacyTodo(
    val id: String = "",
    val title: String = "",
    val done: Boolean = false,
    val priority: TodoPriority = TodoPriority.Normal,
    val createdAt: Long = 0L,
    val start: String? = null,
    val startTime: String? = null,
    val due: String? = null,
    val dueTime: String? = null,
    val note: String = "",
    val location: String = "",
    val categories: List<String> = emptyList(),
    val url: String = "",
    val attachments: List<Attachment> = emptyList(),
    val relatedTo: List<String> = emptyList(),
    val icsUid: String? = null,
    val completedAt: String? = null,
    val sequence: Int = 0,
    val lastModified: String? = null,
    val dtStamp: String? = null,
    val extraProperties: List<String> = emptyList(),
    val status: TodoStatus = TodoStatus.NeedsAction,
    val percentComplete: Int = 0,
    val completedTime: String? = null,
    val alarms: List<Alarm> = emptyList(),
    val repeat: LegacyRepeatRule? = null,
    val excludedDates: List<String> = emptyList(),
    val importSource: String? = null,
    val sourceLine: Int = 0,
)

internal fun LegacyTodo.toCurrentTodo(): Todo {
    val legacy = repeat?.toCurrent()
    return Todo(
        id = id,
        title = title,
        done = done,
        priority = priority,
        createdAt = createdAt,
        start = start,
        startTime = startTime,
        due = due,
        dueTime = dueTime,
        note = note,
        location = location,
        categories = categories,
        url = url,
        attachments = attachments,
        relatedTo = relatedTo,
        icsUid = icsUid,
        completedAt = completedAt,
        sequence = sequence,
        lastModified = lastModified,
        dtStamp = dtStamp,
        extraProperties = extraProperties,
        status = status,
        percentComplete = percentComplete,
        completedTime = completedTime,
        alarms = alarms,
        repeat = legacy?.rule,
        rDates = legacy?.rDates.orEmpty().mapNotNull { text ->
            parseLegacyDate(text)?.let(::RecurrenceDate)
        },
        excludedDates = excludedDates,
        importSource = importSource,
        sourceLine = sourceLine,
    )
}

/** The whole pre-stage-2 payload. */
@Serializable
internal data class LegacyAppState(
    val events: List<LegacyEvent> = emptyList(),
    val todos: List<LegacyTodo> = emptyList(),
    val journals: List<Journal> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val busyPeriods: List<BusyInterval> = emptyList(),
)
