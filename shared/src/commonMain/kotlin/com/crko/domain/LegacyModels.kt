package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.minutes
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

/** Old `DTSTART`/`DUE`/`COMPLETED` text: ISO instant, floating date-time, or date. */
internal fun parseLegacyTimeValue(text: String?): IcsTimeValue? {
    val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.endsWith("Z", ignoreCase = true)) {
        return runCatching { Instant.parse(value) }.getOrNull()?.let(IcsTimeValue::Utc)
    }
    if ('T' in value) {
        return runCatching { LocalDateTime.parse(value) }.getOrNull()?.let(IcsTimeValue::Floating)
            ?: parseCompactDateTime(value)?.let(IcsTimeValue::Floating)
    }
    return runCatching { LocalDate.parse(value) }.getOrNull()?.let(IcsTimeValue::Date)
}

private fun parseCompactDateTime(value: String): LocalDateTime? {
    val digits = value.filter { it.isDigit() }
    if (digits.length < 13 || digits[8] != 'T') return null
    return runCatching {
        LocalDateTime(
            LocalDate(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt()),
            kotlinx.datetime.LocalTime(
                digits.substring(9, 11).toInt(),
                digits.substring(11, 13).toInt(),
                if (digits.length >= 15) digits.substring(13, 15).toInt() else 0,
            ),
        )
    }.getOrNull()
}

/** A pre-stage-4 `ATTACH`: value, decoded parameters and the original line side by side. */
@Serializable
internal data class LegacyAttachment(
    val raw: String = "",
    val uri: String = "",
    val name: String = "",
    val mimeType: String = "",
    val data: String = "",
    val binary: Boolean = false,
    val storedFile: String? = null,
) {
    fun toCurrent(): Attachment {
        val params = LinkedHashMap<String, String>()
        parseContentLine(UnfoldedLine(raw, 0))?.params?.forEach { (name, value) -> params[name] = value }
        if (mimeType.isNotBlank() && "FMTTYPE" !in params) params["FMTTYPE"] = mimeType
        if (name.isNotBlank() && "FILENAME" !in params) params["FILENAME"] = name
        if (binary && "ENCODING" !in params) params["ENCODING"] = "BASE64"
        return Attachment(
            value = if (binary) data else uri,
            parameters = params,
            binary = binary,
            storedFile = storedFile,
        )
    }
}

/** A pre-stage-4 `VALARM` with minutes and a raw absolute trigger string. */
@Serializable
internal data class LegacyAlarm(
    val action: String = "DISPLAY",
    val triggerMinutes: Int = 15,
    val relatedToEnd: Boolean = false,
    val repeat: Int? = null,
    val durationMinutes: Int? = null,
    val description: String = "",
    val absoluteTrigger: String? = null,
    val extraProperties: List<String> = emptyList(),
) {
    fun toCurrent(): Alarm {
        val absolute = parseLegacyTimeValue(absoluteTrigger)
        val trigger = if (absolute != null) {
            AlarmTrigger.Absolute(absolute)
        } else {
            // Old `triggerMinutes` counted positive minutes before the related time.
            AlarmTrigger.Relative(
                duration = (-triggerMinutes).minutes,
                related = if (relatedToEnd) AlarmRelated.End else AlarmRelated.Start,
            )
        }
        return Alarm(
            action = action,
            trigger = trigger,
            repeat = repeat,
            duration = durationMinutes?.minutes,
            description = description,
            extraProperties = extraProperties,
        )
    }
}

/** Local metadata of a pre-stage-4 record. */
private fun legacyLocal(importSource: String?, sourceLine: Int): LocalRecordMeta =
    LocalRecordMeta(importSource, sourceLine)

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
    val attachments: List<LegacyAttachment> = emptyList(),
    val status: EventStatus = EventStatus.Confirmed,
    val transparency: EventTransparency = EventTransparency.Opaque,
    val eventClass: EventClass? = null,
    val priority: Int? = null,
    val organizer: String? = null,
    val attendees: List<String> = emptyList(),
    val alarms: List<LegacyAlarm> = emptyList(),
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
internal fun LegacyEvent.toCurrentEvent(
    definitions: MutableMap<String, CalendarTimeZone>? = null,
): Event? {
    // Stage-1 payloads already carried typed start/end values, so the definition is collected
    // whether or not the legacy wall-clock fields were used.
    if (!timeZoneId.isNullOrBlank()) {
        timeZoneDefinition?.takeIf { it.isNotBlank() }?.let { text ->
            definitions?.put(timeZoneId, CalendarTimeZone(timeZoneId, text))
        }
    }
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
            definitions = definitions,
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
            definitions = definitions,
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
        attachments = attachments.map { it.toCurrent() },
        status = status,
        transparency = transparency,
        eventClass = eventClass,
        priority = priority,
        organizer = legacyCalAddress(organizerRaw, organizer),
        attendees = legacyCalAddresses(attendeeRaw, attendees),
        alarms = alarms.map { it.toCurrent() },
        recurrence = legacyRecurrence(repeat, excludedDates),
        // Old exceptions stored the series UID separately; RFC identity is the shared UID.
        icsUid = seriesUid ?: stripSyntheticSeriesUid(icsUid),
        recurrenceId = recurrenceId?.let(::parseLegacyDate),
        recurrenceRange = if (thisAndFuture) RecurrenceRange.ThisAndFuture else null,
        created = created,
        lastModified = lastModified,
        dtStamp = dtStamp,
        sequence = sequence,
        extraProperties = extraProperties,
        local = legacyLocal(importSource, sourceLine),
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
    val attachments: List<LegacyAttachment> = emptyList(),
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
    val alarms: List<LegacyAlarm> = emptyList(),
    val repeat: LegacyRepeatRule? = null,
    /** Stage-2 payloads stored typed `RDATE` values next to the rule. */
    val rDates: List<RecurrenceDate> = emptyList(),
    val excludedDates: List<String> = emptyList(),
    val importSource: String? = null,
    val sourceLine: Int = 0,
)

internal fun LegacyTodo.toCurrentTodo(): Todo {
    val legacy = repeat?.toCurrent()
    return buildTodo(
        id = id,
        title = title,
        done = done,
        priority = priority.toIcsPriority(),
        createdAt = createdAt,
        start = parseLegacyTimeValue(startTime) ?: start?.let(::parseLegacyDate),
        due = parseLegacyTimeValue(dueTime) ?: due?.let(::parseLegacyDate),
        completedAt = completedAt,
        completedTime = completedTime,
        note = note,
        location = location,
        categories = categories,
        url = url,
        attachments = attachments,
        relatedTo = relatedTo,
        icsUid = icsUid,
        sequence = sequence,
        lastModified = lastModified,
        dtStamp = dtStamp,
        extraProperties = extraProperties,
        status = status,
        percentComplete = percentComplete,
        alarms = alarms,
        repeat = legacy?.rule,
        rDates = if (rDates.isNotEmpty()) {
            rDates
        } else {
            legacy?.rDates.orEmpty().mapNotNull { text -> parseLegacyDate(text)?.let(::RecurrenceDate) }
        },
        excludedDates = excludedDates,
        importSource = importSource,
        sourceLine = sourceLine,
    )
}

/** Pre-stage-4 `VJOURNAL`, whose only time value was a date plus an optional instant. */
@Serializable
internal data class LegacyJournal(
    val id: String = "",
    val title: String = "",
    val note: String = "",
    val date: String? = null,
    val startTime: String? = null,
    val url: String = "",
    val categories: List<String> = emptyList(),
    val attachments: List<LegacyAttachment> = emptyList(),
    val created: String? = null,
    val lastModified: String? = null,
    val dtStamp: String? = null,
    val sequence: Int = 0,
    val icsUid: String? = null,
    val extraProperties: List<String> = emptyList(),
    val importSource: String? = null,
    val sourceLine: Int = 0,
) {
    fun toCurrent(): Journal = Journal(
        id = id,
        title = title,
        note = note,
        start = parseLegacyTimeValue(startTime) ?: date?.let(::parseLegacyDate),
        url = url,
        categories = categories,
        attachments = attachments.map { it.toCurrent() },
        created = created,
        lastModified = lastModified,
        dtStamp = dtStamp,
        sequence = sequence,
        icsUid = icsUid,
        extraProperties = extraProperties,
        local = legacyLocal(importSource, sourceLine),
    )
}

/** Stage-3/4 `VEVENT`, already carrying typed start/end/recurrence values. */
@Serializable
internal data class StageFourEvent(
    val id: String? = null,
    val title: String = "",
    val start: IcsTimeValue? = null,
    val end: IcsTimeValue? = null,
    val note: String = "",
    val location: String = "",
    val categories: List<String> = emptyList(),
    val url: String = "",
    val geo: String? = null,
    val attachments: List<LegacyAttachment> = emptyList(),
    val status: EventStatus = EventStatus.Confirmed,
    val transparency: EventTransparency = EventTransparency.Opaque,
    val eventClass: EventClass? = null,
    val priority: Int? = null,
    val organizer: String? = null,
    val attendees: List<String> = emptyList(),
    val alarms: List<LegacyAlarm> = emptyList(),
    val recurrence: Recurrence? = null,
    val icsUid: String? = null,
    val recurrenceId: IcsTimeValue? = null,
    val recurrenceRange: RecurrenceRange? = null,
    val created: String? = null,
    val lastModified: String? = null,
    val dtStamp: String? = null,
    val sequence: Int = 0,
    val extraProperties: List<String> = emptyList(),
    val organizerRaw: String? = null,
    val attendeeRaw: List<String> = emptyList(),
    val importSource: String? = null,
    val sourceLine: Int = 0,
    /** Stage-3 payloads kept the zone text on the event. */
    val timeZoneDefinition: String? = null,
) {
    fun toCurrent(definitions: MutableMap<String, CalendarTimeZone>? = null): Event? {
        val start = start ?: return null
        val zoneId = (start as? IcsTimeValue.Zoned)?.tzid
        if (zoneId != null) {
            timeZoneDefinition?.takeIf { it.isNotBlank() }?.let { text ->
                definitions?.put(zoneId, CalendarTimeZone(zoneId, text))
            }
        }
        return Event(
            id = id,
            title = title,
            start = start,
            end = end,
            note = note,
            location = location,
            categories = categories,
            url = url,
            geo = geo,
            attachments = attachments.map { it.toCurrent() },
            status = status,
            transparency = transparency,
            eventClass = eventClass,
            priority = priority,
            organizer = legacyCalAddress(organizerRaw, organizer),
            attendees = legacyCalAddresses(attendeeRaw, attendees),
            alarms = alarms.map { it.toCurrent() },
            recurrence = recurrence,
            icsUid = icsUid,
            recurrenceId = recurrenceId,
            recurrenceRange = recurrenceRange,
            created = created,
            lastModified = lastModified,
            dtStamp = dtStamp,
            sequence = sequence,
            extraProperties = extraProperties,
            local = legacyLocal(importSource, sourceLine),
        )
    }
}

/** Stage-4 `VTODO`, before its times, completion and priority were typed. */
@Serializable
internal data class StageFourTodo(
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
    val attachments: List<LegacyAttachment> = emptyList(),
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
    val alarms: List<LegacyAlarm> = emptyList(),
    val repeat: RepeatRule? = null,
    val rDates: List<RecurrenceDate> = emptyList(),
    val excludedDates: List<String> = emptyList(),
    val importSource: String? = null,
    val sourceLine: Int = 0,
) {
    fun toCurrent(): Todo = buildTodo(
        id = id,
        title = title,
        done = done,
        priority = priority.toIcsPriority(),
        createdAt = createdAt,
        start = parseLegacyTimeValue(startTime) ?: start?.let(::parseLegacyDate),
        due = parseLegacyTimeValue(dueTime) ?: due?.let(::parseLegacyDate),
        completedAt = completedAt,
        completedTime = completedTime,
        note = note,
        location = location,
        categories = categories,
        url = url,
        attachments = attachments,
        relatedTo = relatedTo,
        icsUid = icsUid,
        sequence = sequence,
        lastModified = lastModified,
        dtStamp = dtStamp,
        extraProperties = extraProperties,
        status = status,
        percentComplete = percentComplete,
        alarms = alarms,
        repeat = repeat,
        rDates = rDates,
        excludedDates = excludedDates,
        importSource = importSource,
        sourceLine = sourceLine,
    )
}

private fun buildTodo(
    id: String,
    title: String,
    done: Boolean,
    priority: Int,
    createdAt: Long,
    start: IcsTimeValue?,
    due: IcsTimeValue?,
    completedAt: String?,
    completedTime: String?,
    note: String,
    location: String,
    categories: List<String>,
    url: String,
    attachments: List<LegacyAttachment>,
    relatedTo: List<String>,
    icsUid: String?,
    sequence: Int,
    lastModified: String?,
    dtStamp: String?,
    extraProperties: List<String>,
    status: TodoStatus,
    percentComplete: Int,
    alarms: List<LegacyAlarm>,
    repeat: RepeatRule?,
    rDates: List<RecurrenceDate>,
    excludedDates: List<String>,
    importSource: String?,
    sourceLine: Int,
): Todo {
    val completed = completedTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
        ?: completedAt?.let(::parseStoredDate)?.atStartOfDayIn(TimeZone.UTC)
    val resolvedStatus = if (done && status == TodoStatus.NeedsAction) TodoStatus.Completed else status
    return Todo(
        id = id,
        title = title,
        start = start,
        due = due,
        completed = completed,
        priority = priority,
        createdAt = createdAt,
        note = note,
        location = location,
        categories = categories,
        url = url,
        attachments = attachments.map { it.toCurrent() },
        relatedTo = relatedTo.map { RelatedTo.parent(it) },
        icsUid = icsUid,
        lastModified = lastModified,
        dtStamp = dtStamp,
        sequence = sequence,
        extraProperties = extraProperties,
        status = resolvedStatus,
        percentComplete = percentComplete,
        alarms = alarms.map { it.toCurrent() },
        repeat = repeat,
        rDates = rDates,
        excludedDates = excludedDates,
        local = legacyLocal(importSource, sourceLine),
    )
}

/** The whole stage-3/4 payload. */
@Serializable
internal data class StageFourAppState(
    val events: List<StageFourEvent> = emptyList(),
    val todos: List<StageFourTodo> = emptyList(),
    val journals: List<LegacyJournal> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val busyPeriods: List<BusyInterval> = emptyList(),
    val calendar: CalendarMetadata = CalendarMetadata(),
) {
    fun toCurrent(definitions: MutableMap<String, CalendarTimeZone>? = null): AppState {
        val events = events.mapNotNull { it.toCurrent(definitions) }
        val zones = mergeZoneDefinitions(calendar.timeZones, definitions.orEmpty().values)
        return AppState(
            events = events,
            todos = todos.map { it.toCurrent() },
            journals = journals.map { it.toCurrent() },
            subscriptions = subscriptions,
            busyPeriods = busyPeriods,
            calendar = calendar.copy(timeZones = zones),
            schemaVersion = CurrentSchemaVersion,
        )
    }
}

private fun mergeZoneDefinitions(
    existing: List<CalendarTimeZone>,
    extra: Collection<CalendarTimeZone>,
): List<CalendarTimeZone> {
    val merged = LinkedHashMap<String, CalendarTimeZone>()
    (existing + extra).forEach { zone ->
        if (zone.tzid.isNotBlank() && zone.definition.isNotBlank()) merged[zone.tzid] = zone
    }
    return merged.values.toList()
}

/** The whole pre-stage-2 payload. */
@Serializable
internal data class LegacyAppState(
    val events: List<LegacyEvent> = emptyList(),
    val todos: List<LegacyTodo> = emptyList(),
    val journals: List<LegacyJournal> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val busyPeriods: List<BusyInterval> = emptyList(),
)
