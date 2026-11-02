package com.crko.domain

import kotlinx.serialization.Serializable

/**
 * The persisted `Event` layout before stage 1: device-local wall-clock fields plus the original ICS
 * source text. Only used to read payloads written by older versions.
 */
@Serializable
internal data class LegacyEvent(
    val id: String? = null,
    val title: String = "",
    val date: String = "",
    val startMinutes: Int = 0,
    val endMinutes: Int = 0,
    val startSeconds: Int = 0,
    val endSeconds: Int = 0,
    val note: String = "",
    val endDate: String? = null,
    val allDay: Boolean = false,
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
    val repeat: RepeatRule? = null,
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

/** The whole pre-stage-1 payload; every other record kind already serializes in its final shape. */
@Serializable
internal data class LegacyAppState(
    val events: List<LegacyEvent> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val journals: List<Journal> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val busyPeriods: List<BusyInterval> = emptyList(),
)

/** Converts a stored legacy record, or null when its anchor date is unusable. */
internal fun LegacyEvent.toCurrentEvent(): Event? {
    // A damaged payload can carry a date that is present but unparseable; dropping the record is
    // better than putting it on 1970-01-01 and crashing every later calendar query.
    if (parseStoredDate(date) == null) return null
    val start = legacyIcsTimeValue(
        date = date,
        minutes = startMinutes,
        seconds = startSeconds,
        allDay = allDay,
        source = sourceStart,
        tzid = timeZoneId,
        definition = timeZoneDefinition,
    )
    val end = legacyEndValue(
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
        attachments = attachments,
        status = status,
        transparency = transparency,
        eventClass = eventClass,
        priority = priority,
        organizer = organizer,
        attendees = attendees,
        alarms = alarms,
        repeat = repeat,
        excludedDates = excludedDates,
        icsUid = icsUid,
        seriesUid = seriesUid,
        recurrenceId = recurrenceId,
        thisAndFuture = thisAndFuture,
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
