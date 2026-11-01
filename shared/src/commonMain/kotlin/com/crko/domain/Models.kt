package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RepeatFrequency { Daily, Weekly, Monthly, Yearly, Custom }

@Serializable
enum class EventStatus { Confirmed, Tentative, Cancelled }

@Serializable
enum class EventTransparency { Opaque, Transparent }

@Serializable
enum class EventClass { Public, Private, Confidential }

/** `BYDAY` value with an ordinal, e.g. `1MO` (first Monday) or `-1FR` (last Friday). */
@Serializable
data class OrdinalWeekday(val ordinal: Int, val weekday: Int)

@Serializable
data class Alarm(
    /** `DISPLAY`, `AUDIO` or `EMAIL`; kept as text so unknown actions survive a round trip. */
    val action: String = "DISPLAY",
    /**
     * Minutes relative to the start (or end when [relatedToEnd] is set): positive means before,
     * negative means after. Absolute triggers use [absoluteTrigger] instead.
     */
    val triggerMinutes: Int = 15,
    val relatedToEnd: Boolean = false,
    val repeat: Int? = null,
    val durationMinutes: Int? = null,
    val description: String = "",
    /** Absolute `TRIGGER;VALUE=DATE-TIME` value, kept verbatim. */
    val absoluteTrigger: String? = null,
    /** Unknown VALARM content lines kept verbatim. */
    val extraProperties: List<String> = emptyList(),
)

/** An `ATTACH` value. URIs can be opened from the editor; binary payloads are kept raw. */
@Serializable
data class Attachment(
    /** Original content line, including parameters, so exporting does not lose them. */
    val raw: String = "",
    val uri: String = "",
    /** `FILENAME` parameter or the last URI segment. */
    val name: String = "",
    /** `FMTTYPE` parameter. */
    val mimeType: String = "",
    /** Base64 payload for `ENCODING=BASE64` attachments. */
    val data: String = "",
    /** `ENCODING=BASE64` payload; it has no openable URI. */
    val binary: Boolean = false,
)

/** Characters a file name must never contain when it is handed to the platform file picker. */
private const val ReservedFileNameCharacters = "\\/:*?\"<>|"

/** Longest attachment name offered to the picker, so a pathological `FILENAME` stays usable. */
private const val MaxAttachmentNameChars = 80

/**
 * Safe destination name for [this] attachment. `FILENAME` and URI segments come straight out of an
 * imported file, so they have to lose path separators, parent references and control characters
 * before they may be joined to a directory the app does not control.
 */
fun safeAttachmentFileName(attachment: Attachment): String {
    val candidate = listOf(attachment.name, attachment.uri)
        .asSequence()
        .map { it.replace('\\', '/').substringAfterLast('/').substringBefore('?').substringBefore('#') }
        .map(::cleanFileNamePart)
        .firstOrNull { it.isNotEmpty() }
        ?: ""
    if (candidate.isEmpty()) return "attachment${attachmentExtension(attachment)}"
    return if ('.' in candidate) candidate else candidate + attachmentExtension(attachment)
}

/**
 * One path segment without separators, control characters or leading dots, so neither `../` nor an
 * absolute `FILENAME` can escape the folder the user picked.
 */
private fun cleanFileNamePart(value: String): String {
    val replaced = buildString(value.length) {
        value.forEach { ch ->
            val reserved = ch in ReservedFileNameCharacters || ch.code < 0x20 || ch.code == 0x7F
            append(if (reserved) ' ' else ch)
        }
    }
    return replaced
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .trimStart('.')
        .take(MaxAttachmentNameChars)
        .trimStart('.', ' ')
}

private fun attachmentExtension(attachment: Attachment): String = when (
    val type = attachment.mimeType.substringBefore(';').trim().lowercase()
) {
    "image/png" -> ".png"
    "image/jpeg", "image/jpg" -> ".jpg"
    "image/gif" -> ".gif"
    "image/webp" -> ".webp"
    "image/heic" -> ".heic"
    "application/pdf" -> ".pdf"
    "text/plain", "text/csv" -> ".txt"
    "application/json" -> ".json"
    "audio/mpeg" -> ".mp3"
    "video/mp4" -> ".mp4"
    else -> if (attachment.binary) ".bin" else ""
}

@Serializable
data class RepeatRule(
    val frequency: RepeatFrequency,
    /** Plain weekdays (0 = Monday) from `BYDAY`, used by weekly, daily and monthly rules. */
    val weekdays: List<Int> = emptyList(),
    val interval: Int = 1,
    /** Number of occurrences including the first one. */
    val count: Int? = null,
    /** Inclusive last occurrence: an ISO date, a floating date-time, or a UTC instant. */
    val until: String? = null,
    /** `BYDAY` values carrying an ordinal, for monthly and yearly rules. */
    val byDay: List<OrdinalWeekday> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val bySetPos: List<Int> = emptyList(),
    val byYearDay: List<Int> = emptyList(),
    val byWeekNo: List<Int> = emptyList(),
    /** Monday-first weekday index of `WKST`. */
    val weekStart: Int? = null,
    /** `BYHOUR`, in hours of the day. */
    val byHour: List<Int> = emptyList(),
    /** `BYMINUTE`, in minutes of the hour. */
    val byMinute: List<Int> = emptyList(),
    /** `BYSECOND`, in seconds of the minute. Kept for round trips; occurrences are day-granular. */
    val bySecond: List<Int> = emptyList(),
    /** `RDATE` dates, ISO-8601. */
    val rDates: List<String> = emptyList(),
)

@Serializable
data class Event(
    /** Null for events that have not been saved yet. */
    val id: String?,
    val title: String,
    /** The date of the first occurrence, also the anchor for a repeating event. */
    val date: String,
    /** Minutes after midnight. */
    val startMinutes: Int,
    /** Minutes after midnight. For an event ending at midnight this is 0 and [endDate] is the next day. */
    val endMinutes: Int,
    /** Seconds of the start minute, preserved from ICS; the editor still picks whole minutes. */
    val startSeconds: Int = 0,
    /** Seconds of the end minute. */
    val endSeconds: Int = 0,
    val note: String = "",
    /** Last day the event touches, inclusive. Null means [date]. */
    val endDate: String? = null,
    /** Dates from `VALUE=DATE`; times are ignored. */
    val allDay: Boolean = false,
    val location: String = "",
    val categories: List<String> = emptyList(),
    val url: String = "",
    /** Raw `GEO` value, e.g. `37.386013;-122.082932`. */
    val geo: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val status: EventStatus = EventStatus.Confirmed,
    val transparency: EventTransparency = EventTransparency.Opaque,
    val eventClass: EventClass? = null,
    /** `PRIORITY`, 0 (undefined) through 9 (lowest). */
    val priority: Int? = null,
    val organizer: String? = null,
    val attendees: List<String> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
    /** Null for a one-off event. */
    val repeat: RepeatRule? = null,
    /** Dates on which a repeating event is skipped, e.g. a single day cleared from the agenda. */
    val excludedDates: List<String> = emptyList(),
    /** UID from an imported ICS file; keeps re-importing the same file from duplicating events. */
    val icsUid: String? = null,
    /** UID of the series this event overrides, for a `RECURRENCE-ID` exception. */
    val seriesUid: String? = null,
    /** Original occurrence date of an exception, ISO-8601. */
    val recurrenceId: String? = null,
    /** The exception was imported from `RECURRENCE-ID;RANGE=THISANDFUTURE`. */
    val thisAndFuture: Boolean = false,
    /** Original ICS local start value, including `Z` for UTC, used to export `TZID` events losslessly. */
    val sourceStart: String? = null,
    /** Original ICS local end value. */
    val sourceEnd: String? = null,
    val timeZoneId: String? = null,
    /** Original raw `VTIMEZONE` block when the zone is not known to the platform. */
    val timeZoneDefinition: String? = null,
    /** ISO-8601 instant. */
    val created: String? = null,
    /** ISO-8601 instant. */
    val lastModified: String? = null,
    /** Original `DTSTAMP`; picks the newest copy when the same UID is imported again. */
    val dtStamp: String? = null,
    val sequence: Int = 0,
    /** Unknown content lines kept verbatim so a round trip does not drop them. */
    val extraProperties: List<String> = emptyList(),
    /** Original ORGANIZER line, including CN/ROLE/... parameters. */
    val organizerRaw: String? = null,
    /** Original ATTENDEE lines, including CN/ROLE/PARTSTAT/... parameters. */
    val attendeeRaw: List<String> = emptyList(),
) {
    /** Parsed [date], cached because [occursOn] runs for every visible calendar cell. */
    val day: LocalDate by lazy(LazyThreadSafetyMode.NONE) { LocalDate.parse(date) }

    /** Last date the event touches; clamped so a stale end date can never precede the start. */
    val endDay: LocalDate by lazy(LazyThreadSafetyMode.NONE) {
        val parsed = endDate?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        if (parsed != null && parsed > day) parsed else day
    }

    /** Last date on which the event actually occupies time, treating `00:00` as exclusive. */
    val lastOccupiedDay: LocalDate
        get() = if (!allDay && endMinutes == 0 && endDay > day) endDay.plusDays(-1) else endDay

    /** Stable UID used for series/exception relationships and exports. */
    val uid: String? get() = icsUid?.takeIf { it.isNotBlank() } ?: id?.let { "$it@crko" }
}

/** RFC 5545: a timed event without `DTEND`/`DURATION` has zero length rather than one hour. */
val Event.isZeroDuration: Boolean
    get() = !allDay &&
        endDay == day &&
        endMinutes == startMinutes &&
        endSeconds == startSeconds

/**
 * Latest minute of day an event may end at (23:59). Midnight endings are represented by the next
 * day at 00:00 so the editor's LocalTime picker never sees an hour of 24.
 */
const val MaxEndMinutes = 23 * 60 + 59

/** End of the day, used when clipping multi-day records to one day. */
const val EndOfDayMinutes = 24 * 60

enum class TodoPriority { High, Normal, Low }

@Serializable
enum class TodoStatus { NeedsAction, InProcess, Completed, Cancelled }

@Serializable
data class Todo(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val priority: TodoPriority = TodoPriority.Normal,
    /** Creation time in epoch milliseconds; used only for ordering. */
    val createdAt: Long = 0L,
    /** `DTSTART` date. */
    val start: String? = null,
    /** ISO-8601 instant of `DTSTART` when it carried a time. */
    val startTime: String? = null,
    val due: String? = null,
    /** ISO-8601 instant of `DUE` when it carried a time; [due] stays the date used for ordering. */
    val dueTime: String? = null,
    val note: String = "",
    val location: String = "",
    val categories: List<String> = emptyList(),
    val url: String = "",
    val attachments: List<Attachment> = emptyList(),
    /** Raw `RELATED-TO` values; the hierarchy is not shown in the UI yet. */
    val relatedTo: List<String> = emptyList(),
    /** UID from an imported ICS file. */
    val icsUid: String? = null,
    /** ISO-8601 date the item was completed; used by the statistics screen. */
    val completedAt: String? = null,
    val sequence: Int = 0,
    val lastModified: String? = null,
    val dtStamp: String? = null,
    val extraProperties: List<String> = emptyList(),
    /** `STATUS`; older saved data defaults to [TodoStatus.NeedsAction]. */
    val status: TodoStatus = TodoStatus.NeedsAction,
    /** `PERCENT-COMPLETE`, 0..100. */
    val percentComplete: Int = 0,
    /** Exact `COMPLETED` instant from ICS when available. */
    val completedTime: String? = null,
    val alarms: List<Alarm> = emptyList(),
)

/** A `VJOURNAL` entry. Parsed and exported so a calendar round trip does not drop journals. */
@Serializable
data class Journal(
    val id: String,
    val title: String = "",
    val note: String = "",
    /** `DTSTART` date. */
    val date: String? = null,
    /** ISO-8601 instant of `DTSTART` when it carried a time. */
    val startTime: String? = null,
    val url: String = "",
    val categories: List<String> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val created: String? = null,
    val lastModified: String? = null,
    val dtStamp: String? = null,
    val sequence: Int = 0,
    val icsUid: String? = null,
    val extraProperties: List<String> = emptyList(),
)

@Serializable
data class AppState(
    val events: List<Event> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val journals: List<Journal> = emptyList(),
)

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class PersistedSettings(
    val language: String = "",
    val darkMode: String = "",
    val palette: String = "",
    val keyColor: Long = 0xFF3B6CF5,
    val agendaView: String = "",
    val agendaScrollHour: Int = -1,
)

/** Whether this event happens on [date]; repeating events also cover every later matching date. */
fun Event.occursOn(date: LocalDate): Boolean {
    if (repeat == null) return date >= day && date <= lastOccupiedDay
    return isCoveredByOccurrence(date)
}

/** The occurrence start whose span covers [date], if any. */
fun Event.occurrenceStartOn(date: LocalDate): LocalDate? {
    val rule = repeat ?: return if (occursOn(date)) day else null
    if (date < day) return null
    val span = day.daysTo(lastOccupiedDay).coerceAtLeast(0)
    for (offset in 0..span) {
        val candidate = date.plusDays(-offset)
        if (candidate < day) break
        if (isOccurrenceStart(candidate, rule)) return candidate
    }
    return null
}

private fun Event.isCoveredByOccurrence(date: LocalDate): Boolean {
    val rule = repeat ?: return false
    val span = day.daysTo(lastOccupiedDay).coerceAtLeast(0)
    for (offset in 0..span) {
        val candidate = date.plusDays(-offset)
        if (candidate < day) break
        if (isOccurrenceStart(candidate, rule)) return true
    }
    return false
}

/** Events happening on [date], all-day ones first and then ordered by start time. */
fun List<Event>.occurringOn(date: LocalDate): List<Event> =
    filter { it.occursOn(date) }.sortedWith(compareBy<Event> { !it.allDay }.thenBy { it.startMinutes })

/** The slice of [event] that falls on [date], clipped to the day. */
fun Event.timeRangeOn(date: LocalDate): Pair<Int, Int> {
    if (allDay) return 0 to EndOfDayMinutes
    if (date < day || date > lastOccupiedDay) return startMinutes to endMinutes
    val start = if (date == day) startMinutes else 0
    val end = when {
        endMinutes == 0 && endDay > day && date >= day -> EndOfDayMinutes
        date == lastOccupiedDay -> endMinutes
        date > day -> EndOfDayMinutes
        else -> endMinutes
    }
    return start to end
}

/** Whether the event spans more than one calendar day. */
fun Event.isMultiDay(): Boolean = endDay > day || (!allDay && endMinutes == 0 && endDay > day)
