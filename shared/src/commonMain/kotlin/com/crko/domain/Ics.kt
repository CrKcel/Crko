package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import com.crko.storage.AppAttachmentStore
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * RFC 5545 reader/writer for the parts of a calendar the app can represent. Unknown content lines
 * are kept on the model so that an import/export cycle does not silently drop properties.
 */

private const val LineBreak = "\r\n"
private const val FoldLimit = 75

private val weekdayCodes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

/**
 * Windows time zone names that Outlook and Exchange write instead of IANA ids. Zone rules are
 * shared by several cities, so each name points at the most common representative.
 */
private val windowsTimeZones = mapOf(
    "dateline standard time" to "Etc/GMT+12",
    "utc-11" to "Etc/GMT+11",
    "aleutian standard time" to "America/Adak",
    "hawaiian standard time" to "Pacific/Honolulu",
    "alaskan standard time" to "America/Anchorage",
    "pacific standard time" to "America/Los_Angeles",
    "pacific standard time (mexico)" to "America/Tijuana",
    "us mountain standard time" to "America/Phoenix",
    "mountain standard time" to "America/Denver",
    "central standard time" to "America/Chicago",
    "central america standard time" to "America/Guatemala",
    "canada central standard time" to "America/Regina",
    "central standard time (mexico)" to "America/Mexico_City",
    "eastern standard time" to "America/New_York",
    "us eastern standard time" to "America/Indiana/Indianapolis",
    "atlantic standard time" to "America/Halifax",
    "newfoundland standard time" to "America/St_Johns",
    "sa eastern standard time" to "America/Cayenne",
    "argentina standard time" to "America/Argentina/Buenos_Aires",
    "brasilia standard time" to "America/Sao_Paulo",
    "gmt standard time" to "Europe/London",
    "greenwich standard time" to "Atlantic/Reykjavik",
    "w. europe standard time" to "Europe/Berlin",
    "central europe standard time" to "Europe/Budapest",
    "central european standard time" to "Europe/Warsaw",
    "romance standard time" to "Europe/Paris",
    "e. europe standard time" to "Europe/Chisinau",
    "fle standard time" to "Europe/Kiev",
    "gtb standard time" to "Europe/Bucharest",
    "turkey standard time" to "Europe/Istanbul",
    "israel standard time" to "Asia/Jerusalem",
    "south africa standard time" to "Africa/Johannesburg",
    "russian standard time" to "Europe/Moscow",
    "arabian standard time" to "Asia/Dubai",
    "iran standard time" to "Asia/Tehran",
    "india standard time" to "Asia/Kolkata",
    "sri lanka standard time" to "Asia/Colombo",
    "nepal standard time" to "Asia/Kathmandu",
    "bangladesh standard time" to "Asia/Dhaka",
    "se asia standard time" to "Asia/Bangkok",
    "china standard time" to "Asia/Shanghai",
    "taipei standard time" to "Asia/Taipei",
    "singapore standard time" to "Asia/Singapore",
    "w. australia standard time" to "Australia/Perth",
    "tokyo standard time" to "Asia/Tokyo",
    "korea standard time" to "Asia/Seoul",
    "cen. australia standard time" to "Australia/Adelaide",
    "aus eastern standard time" to "Australia/Sydney",
    "e. australia standard time" to "Australia/Brisbane",
    "tasmania standard time" to "Australia/Hobart",
    "new zealand standard time" to "Pacific/Auckland",
    "fiji standard time" to "Pacific/Fiji",
)

enum class IcsWarning {
    UnresolvedTimeZone,
    InvalidValue,
    UnsupportedRecurrence,
    MultipleRrules,

    /** A VEVENT had no usable `DTSTART`, so nothing could be placed on the calendar. */
    MissingStart,

    /** Several exceptions shared one occurrence date, which the single-start model cannot tell apart. */
    AmbiguousRecurrenceId,

    /** An `EXRULE` was found; it is deprecated and its exclusions are not applied. */
    ExruleIgnored,

    /** A component carried child components other than `VALARM`, which are not supported. */
    UnknownSubcomponent,

    /** An `EXDATE` fell on an occurrence date but at a different time of day. */
    ExdateMismatch,
}

data class IcsImport(
    val events: List<Event> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val journals: List<Journal> = emptyList(),
    val warnings: List<IcsWarning> = emptyList(),
    /** The same warnings attached to the record (and content line) that caused them. */
    val warningDetails: List<IcsWarningDetail> = emptyList(),
    /** UIDs whose newest master copy is `STATUS:CANCELLED`; matching local records are removed. */
    val cancelledUids: Set<String> = emptySet(),
    /**
     * The cancelled series themselves, with title and line, so a per-record preview can offer the
     * removal as a choice instead of applying it behind the user's back.
     */
    val cancelled: List<CancelledIcsRecord> = emptyList(),
    /** iTIP `METHOD` of the calendar; null for a plain snapshot without scheduling semantics. */
    val method: ItipMethod? = null,
    /** `X-WR-CALNAME` of the source calendar, unescaped; null when the file does not name itself. */
    val calendarName: String? = null,
    /**
     * `REFRESH-INTERVAL` (or Google's `X-PUBLISHED-TTL`) in minutes; how often the publisher says a
     * subscribed copy should be refreshed.
     */
    val refreshMinutes: Int? = null,
    /** Raw `VTIMEZONE` definitions for `TZID` values the platform cannot resolve, deduplicated. */
    val timeZones: List<CalendarTimeZone> = emptyList(),
    /** Busy periods of the `VFREEBUSY` components the file carried. */
    val freeBusy: List<BusyInterval> = emptyList(),
    /**
     * Every UID the file still describes, kept rows or not. Reconciliation against this set must
     * not use the kept subset alone, or unchecking a preview row would turn "skip this update"
     * into "the source removed it" and delete the local record.
     */
    val describedUids: Set<String> = emptySet(),
)

/** A series whose newest master copy is `STATUS:CANCELLED`; importing it removes local records. */
data class CancelledIcsRecord(
    val uid: String,
    val summary: String,
    val line: Int? = null,
    /** `SEQUENCE` of the cancelled copy, so a stale cancellation cannot remove a newer event. */
    val sequence: Int = 0,
)

/** One warning occurrence, so the UI can say which record or line was affected. */
data class IcsWarningDetail(
    val warning: IcsWarning,
    val line: Int? = null,
    val record: String? = null,
)

/** Why a calendar file could not be imported; each case has its own user-facing message. */
enum class IcsImportFailure {
    /** The picked file is larger than the platform limit. */
    TooLarge,

    /** The bytes are not valid UTF-8. */
    InvalidEncoding,

    /** The file or its provider could not be read. */
    Unreadable,

    /** The text was read but is not a calendar the parser accepts. */
    InvalidFormat,
}

/** Serializes every event into one VCALENDAR with CRLF line endings. */
fun exportIcs(
    events: List<Event>,
    now: Instant = Clock.System.now(),
): String = exportCalendar(events = events, todos = emptyList(), now = now)

/**
 * Serializes events and todos into one VCALENDAR, emitting `VTIMEZONE` blocks for the unknown zones
 * the records reference. Definitions are deduplicated by `TZID`, and a known IANA zone is written
 * as a bare `TZID` reference because every client can resolve it itself.
 */
fun exportCalendar(
    events: List<Event>,
    todos: List<Todo> = emptyList(),
    now: Instant = Clock.System.now(),
    calendarName: String? = null,
    journals: List<Journal> = emptyList(),
    method: ItipMethod? = null,
    refreshMinutes: Int? = null,
    timeZones: List<CalendarTimeZone> = emptyList(),
): String = buildString {
    line("BEGIN:VCALENDAR")
    line("VERSION:2.0")
    line("PRODID:-//Crko//Crko Calendar//EN")
    line("CALSCALE:GREGORIAN")
    method?.let { line("METHOD:${it.wireName}") }
    if (!calendarName.isNullOrBlank()) line("X-WR-CALNAME:${escapeText(calendarName)}")
    refreshMinutes?.takeIf { it > 0 }?.let { minutes ->
        line("REFRESH-INTERVAL;VALUE=DURATION:${formatDuration(minutes)}")
    }
    // Only zones the calendar refers to are written, so a stored definition that no event uses does
    // not bloat the export. Known IANA zones need no block: a synthetic fixed-offset definition
    // would be wrong for series crossing a DST change.
    val stored = timeZones.associate { normalizeTimeZoneId(it.tzid) to it.definition }
    val zones = linkedMapOf<String, String>()
    events.forEach { event ->
        event.referencedTimeZoneIds().forEach { id ->
            if (id.equals("UTC", ignoreCase = true) || id in zones) return@forEach
            if (resolvePlatformZone(id) != null) return@forEach
            val definition = stored[id]?.takeIf { it.isNotBlank() }
                ?: icsTimeZoneDefinition(id)?.takeIf { it.isNotBlank() }
                ?: return@forEach
            // The block has to declare the very id the properties reference: import normalises away
            // a leading "/" (a Microsoft convention), so the verbatim text can differ from [id].
            zones[id] = rewriteDeclaredZoneId(normalizeComponent(definition), id)
        }
    }
    zones.forEach { (_, definition) ->
        definition.trimEnd().split(Regex("\\r?\\n")).forEach { line(it) }
    }
    events.forEachIndexed { index, event -> appendEvent(event, events, index, now) }
    todos.forEachIndexed { index, todo -> appendTodo(todo, index, now) }
    journals.forEachIndexed { index, journal -> appendJournal(journal, index, now) }
    line("END:VCALENDAR")
}

/** Every `TZID` a record names, including its recurrence set and exception slot. */
private fun Event.referencedTimeZoneIds(): Set<String> = buildSet {
    fun add(value: IcsTimeValue?) {
        (value as? IcsTimeValue.Zoned)?.let { add(it.tzid) }
    }
    add(start)
    add(end)
    add(recurrenceId)
    recurrence?.let { set ->
        add(set.rule?.until)
        set.rdates.forEach { add(it.value) }
        set.exdates.forEach(::add)
    }
}

/**
 * Parses every VEVENT and VTODO found in [text]. Recurrences stay a single series and bounded
 * series keep their COUNT/UNTIL instead of being expanded into thousands of records.
 */
fun importCalendar(
    text: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    untitledTitle: String,
): IcsImport = importCalendarLines(unfold(text).asSequence(), timeZone, untitledTitle)

/**
 * Same as [importCalendar] for lines that already arrived incrementally, so a file never has to be
 * in memory as one decoded text (see `CalendarTextReader`).
 */
internal fun importCalendarLines(
    lines: Sequence<UnfoldedLine>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    untitledTitle: String,
): IcsImport {
    val warnings = linkedSetOf<IcsWarning>()
    val details = mutableListOf<IcsWarningDetail>()
    val roots = parseComponents(lines)
    val calendars = roots.filter { it.name == "VCALENDAR" }
    val components = if (calendars.isEmpty()) roots else calendars.flatMap { it.children }
    val calendarMeta = readCalendarMeta(calendars)
    val zones = mutableMapOf<String, CustomZone>()
    // Only unknown zones need their definition kept; an IANA id is enough for every platform.
    val timeZones = linkedMapOf<String, CalendarTimeZone>()
    components.filter { it.name == "VTIMEZONE" }.forEach { component ->
        val zoneId = component.first("TZID")?.value?.trim()?.removePrefix("/")
        val zone = parseCustomZone(component)
        if (zone == null) {
            warnings.add(IcsWarning.UnresolvedTimeZone)
        } else {
            zones[zone.id] = zone
            val id = zoneId ?: zone.id
            if (!isPlatformTimeZone(id)) timeZones[id] = CalendarTimeZone(id, component.toText())
        }
    }
    // Register before mapping values: an unknown zone's absolute instant is needed to align ends,
    // expand occurrences and compare times, and the event itself only keeps the TZID.
    registerIcsTimeZones(timeZones.values)
    // Floating times are device-local by default, but a calendar may declare the zone they belong
    // to. Resolving it here keeps DTSTART/UNTIL in the same zone instead of silently shifting.
    val floatingZone = calendarFloatingZone(calendars, zones, warnings)

    val rawEvents = components
        .filter { it.name == "VEVENT" }
        .map { readEvent(it, zones, floatingZone, details) }
    val groups = linkedMapOf<String, MutableList<RawEvent>>()
    val orphanEvents = mutableListOf<RawEvent>()
    rawEvents.forEach { raw ->
        val uid = raw.uid
        if (uid == null) orphanEvents += raw else groups.getOrPut(uid) { mutableListOf() } += raw
    }

    val events = mutableListOf<Event>()
    orphanEvents.forEach { raw ->
        if (!raw.isCancelled()) {
            val local = linkedSetOf<IcsWarning>()
            mapSingle(raw, timeZone, untitledTitle, zones, local)?.let(events::add)
            mergeWarnings(warnings, details, local, raw.lineNumber, raw.summary.ifBlank { raw.uid })
        }
    }
    groups.forEach { (uid, group) ->
        val local = linkedSetOf<IcsWarning>()
        events += mapSeries(uid, group, timeZone, untitledTitle, zones, local)
        mergeWarnings(warnings, details, local, group.firstOrNull()?.lineNumber, uid)
    }
    // Only the newest master copy of a UID can cancel the series; a stale CANCELLED copy next to a
    // live one must not remove the user's event.
    val cancelled = groups.mapNotNull { (uid, group) ->
        val newest = group.filter { it.recurrenceId == null }.maxWithOrNull(masterCopyComparator)
            ?: return@mapNotNull null
        if (!newest.isCancelled()) return@mapNotNull null
        CancelledIcsRecord(
            uid = uid,
            summary = newest.summary,
            line = newest.lineNumber.takeIf { it > 0 },
            sequence = newest.sequence,
        )
    }
    val cancelledUids = cancelled.mapTo(mutableSetOf()) { it.uid }

    val todos = components
        .filter { it.name == "VTODO" }
        .mapNotNull { readTodo(it, timeZone, zones, floatingZone, details) }
    val journals = components
        .filter { it.name == "VJOURNAL" }
        .mapNotNull { readJournal(it, timeZone, zones, floatingZone, details) }
    val freeBusy = components.filter { it.name == "VFREEBUSY" }.flatMap { component ->
        readFreeBusy(component, zones, floatingZone, details)
    }
    val allWarnings = linkedSetOf<IcsWarning>().apply {
        addAll(warnings)
        details.forEach { add(it.warning) }
    }
    return IcsImport(
        events = events,
        todos = todos,
        journals = journals,
        warnings = allWarnings.toList(),
        warningDetails = details.asWarningDetails(),
        cancelledUids = cancelledUids,
        cancelled = cancelled,
        method = calendarMeta.method,
        calendarName = calendarMeta.name,
        refreshMinutes = calendarMeta.refreshMinutes,
        timeZones = timeZones.values.toList(),
        freeBusy = freeBusy,
        describedUids = buildSet {
            events.mapNotNullTo(this) { it.icsUid }
            todos.mapNotNullTo(this) { it.icsUid }
            journals.mapNotNullTo(this) { it.icsUid }
            // A cancelled series is still described by the file: declining its removal in the
            // preview must not let the vanished-record pass delete it instead.
            cancelledUids.mapTo(this) { it }
        },
    )
}

/** Calendar-level properties of a `VCALENDAR` that are not tied to one record. */
private data class CalendarMeta(
    val method: ItipMethod? = null,
    val name: String? = null,
    val refreshMinutes: Int? = null,
)

/**
 * Reads `METHOD`, `X-WR-CALNAME` and the published refresh interval from the calendar components.
 * The first calendar that carries a value wins, so a bundle of calendars still parses sensibly.
 */
private fun readCalendarMeta(calendars: List<IcsComponent>): CalendarMeta {
    var method: ItipMethod? = null
    var name: String? = null
    var refreshMinutes: Int? = null
    calendars.forEach { calendar ->
        calendar.properties.forEach { line ->
            when (line.name) {
                "METHOD" -> method = ItipMethod.parse(line.value) ?: method
                "X-WR-CALNAME" -> name = unescapeText(line.value).trim().takeIf { it.isNotEmpty() } ?: name
                "REFRESH-INTERVAL", "X-PUBLISHED-TTL" -> {
                    val minutes = parseDurationMinutes(line.value)?.takeIf { it > 0 }
                    if (minutes != null) refreshMinutes = minutes
                }
            }
        }
    }
    return CalendarMeta(method, name, refreshMinutes)
}

/** Total number of records a message may point at; beyond that the summary stays honest anyway. */
private const val MaxWarningDetails = 20

/** At most this many characters of a record name are quoted back to the user. */
private const val MaxWarningRecordChars = 60

/** Drops duplicates and keeps the list small enough to render inside one message. */
private fun List<IcsWarningDetail>.asWarningDetails(): List<IcsWarningDetail> =
    distinct().take(MaxWarningDetails)

/** Trims the record name so one long `SUMMARY` cannot bloat the whole warning message. */
private fun warningDetail(warning: IcsWarning, line: Int?, record: String?): IcsWarningDetail =
    IcsWarningDetail(
        warning = warning,
        line = line?.takeIf { it > 0 },
        record = record?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                if (it.length <= MaxWarningRecordChars) it else it.take(MaxWarningRecordChars - 1) + "…"
            },
    )

private fun mergeWarnings(
    warnings: MutableSet<IcsWarning>,
    details: MutableList<IcsWarningDetail>,
    local: Set<IcsWarning>,
    line: Int?,
    record: String?,
) {
    local.forEach { warning ->
        warnings += warning
        details += warningDetail(warning, line, record)
    }
}

fun importIcs(
    text: String,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    untitledTitle: String,
): List<Event> = importCalendar(text, timeZone, untitledTitle).events

/**
 * iTIP message that proposes or updates [events] (`METHOD:REQUEST`). Receivers decide by `UID` and
 * `SEQUENCE` whether the proposal replaces their copy, so every record keeps its full content.
 */
fun exportItipRequest(
    events: List<Event>,
    now: Instant = Clock.System.now(),
    calendarName: String? = null,
): String = exportCalendar(events = events, now = now, calendarName = calendarName, method = ItipMethod.Request)

/**
 * iTIP message that withdraws [events] (`METHOD:CANCEL`): every copy is written with
 * `STATUS:CANCELLED` and a bumped `SEQUENCE`, so receivers drop or hide their local records.
 */
fun exportItipCancel(
    events: List<Event>,
    now: Instant = Clock.System.now(),
): String = exportCalendar(
    events = events.map { it.copy(status = EventStatus.Cancelled) },
    now = now,
    method = ItipMethod.Cancel,
)

/**
 * iTIP `REPLY` for [attendee]'s answer to [event] (RFC 5546 §3.2.3). The message carries the bare
 * scheduling facts — `UID`, `DTSTAMP`, `SEQUENCE`, `ORGANIZER`, the replying `ATTENDEE` with its
 * new `PARTSTAT`, and `RECURRENCE-ID` for an instance — so the organizer can merge it even when the
 * reply arrives without the full event body.
 */
fun exportItipReply(
    event: Event,
    attendee: IcsAttendee,
    now: Instant = Clock.System.now(),
): String = buildString {
    line("BEGIN:VCALENDAR")
    line("VERSION:2.0")
    line("PRODID:-//Crko//Crko Calendar//EN")
    line("CALSCALE:GREGORIAN")
    line("METHOD:REPLY")
    line("BEGIN:VEVENT")
    val uid = event.uid ?: "crko-reply@local"
    line("UID:${escapeText(uid)}")
    line("DTSTAMP:${formatUtc(parseInstant(event.dtStamp) ?: now)}")
    if (event.sequence > 0) line("SEQUENCE:${event.sequence}")
    val organizerRaw = event.organizerRaw
    if (!organizerRaw.isNullOrBlank()) {
        line(organizerRaw)
    } else {
        event.organizer?.let { if (it.isNotBlank()) line("ORGANIZER:${sanitizeUriValue(it)}") }
    }
    line(attendee.toLine())
    event.recurrenceId?.let { appendTimeLine("RECURRENCE-ID", it) }
    appendTimeLine("DTSTART", event.start)
    line("END:VEVENT")
    line("END:VCALENDAR")
}

private fun StringBuilder.line(value: String) {
    append(foldLine(value))
    append(LineBreak)
}

private fun StringBuilder.appendEvent(event: Event, allEvents: List<Event>, index: Int, now: Instant) {
    val uid = event.uid ?: "crko-${index + 1}@local"
    line("BEGIN:VEVENT")
    line("UID:${escapeText(uid)}")
    line("DTSTAMP:${formatUtc(parseInstant(event.dtStamp) ?: parseInstant(event.lastModified) ?: now)}")
    event.created?.let { if (it.isNotBlank()) line("CREATED:${formatUtc(parseInstant(it) ?: now)}") }
    event.lastModified?.let { if (it.isNotBlank()) line("LAST-MODIFIED:${formatUtc(parseInstant(it) ?: now)}") }
    if (event.sequence > 0 || event.lastModified != null) line("SEQUENCE:${event.sequence.coerceAtLeast(0)}")
    // A RECURRENCE-ID is only meaningful inside the file that carries its master series, so an
    // exception whose master is absent is exported as a plain event instead of a dangling one.
    val masterPresent = event.recurrenceId != null && allEvents.any { candidate ->
        candidate !== event && candidate.uid == event.uid && candidate.recurrenceId == null
    }
    if (masterPresent) {
        val range = event.recurrenceRange?.let { ";RANGE=${it.wireName}" }.orEmpty()
        event.recurrenceId.let { appendTimeLine("RECURRENCE-ID$range", it) }
    }

    appendTimeLine("DTSTART", event.start)
    // RFC 5545 represents a zero-length timed event by leaving out DTEND/DURATION.
    event.end?.let { appendTimeLine("DTEND", it) }

    line("SUMMARY:${escapeText(event.title)}")
    if (event.note.isNotBlank()) line("DESCRIPTION:${escapeText(event.note)}")
    if (event.location.isNotBlank()) line("LOCATION:${escapeText(event.location)}")
    if (event.categories.isNotEmpty()) {
        line("CATEGORIES:${event.categories.joinToString(",") { escapeText(it) }}")
    }
    if (event.url.isNotBlank()) line("URL:${sanitizeUriValue(event.url)}")
    if (!event.geo.isNullOrBlank()) line("GEO:${sanitizeUriValue(event.geo)}")
    appendAttachments(event.attachments)
    if (event.eventClass != null) line("CLASS:${event.eventClass.name.uppercase()}")
    if (event.status != EventStatus.Confirmed) line("STATUS:${event.status.name.uppercase()}")
    if (event.transparency == EventTransparency.Transparent) line("TRANSP:TRANSPARENT")
    event.priority?.let { if (it in 0..9) line("PRIORITY:$it") }
    val organizerRaw = event.organizerRaw
    if (!organizerRaw.isNullOrBlank()) {
        line(organizerRaw)
    } else {
        event.organizer?.let { if (it.isNotBlank()) line("ORGANIZER:${sanitizeUriValue(it)}") }
    }
    if (event.attendeeRaw.isNotEmpty()) {
        event.attendeeRaw.forEach { if (it.isNotBlank()) line(it) }
    } else {
        event.attendees.forEach { if (it.isNotBlank()) line("ATTENDEE:${sanitizeUriValue(it)}") }
    }

    event.recurrence?.let { appendRecurrence(it, event) }

    event.alarms.forEach { alarm ->
        line("BEGIN:VALARM")
        line("ACTION:${alarm.action.ifBlank { "DISPLAY" }}")
        val absolute = alarm.absoluteTrigger
        if (absolute != null) {
            line("TRIGGER;VALUE=DATE-TIME:$absolute")
        } else {
            val related = if (alarm.relatedToEnd) ";RELATED=END" else ""
            line("TRIGGER$related:${formatTriggerDuration(alarm.triggerMinutes)}")
        }
        alarm.repeat?.let { line("REPEAT:$it") }
        alarm.durationMinutes?.let { line("DURATION:${formatDuration(it)}") }
        if (alarm.description.isNotBlank()) line("DESCRIPTION:${escapeText(alarm.description)}")
        alarm.extraProperties.forEach { line(it) }
        line("END:VALARM")
    }

    event.extraProperties.forEach { line(it) }
    line("END:VEVENT")
}

/** Writes [value] as the property [name], in the value type its RFC time value declares. */
internal fun StringBuilder.appendTimeLine(name: String, value: IcsTimeValue) {
    when (value) {
        is IcsTimeValue.Date -> line("$name;VALUE=DATE:${formatDate(value.date)}")
        is IcsTimeValue.Floating -> line("$name:${formatIcsLocalDateTime(value.dateTime, utc = false)}")
        is IcsTimeValue.Zoned -> {
            if (value.tzid.equals("UTC", ignoreCase = true)) {
                line("$name:${formatUtc(value.dateTime.toInstant(TimeZone.UTC))}")
            } else {
                line("$name;TZID=${value.tzid}:${formatIcsLocalDateTime(value.dateTime, utc = false)}")
            }
        }
        is IcsTimeValue.Utc -> line("$name:${formatUtc(value.instant)}")
    }
}

private fun StringBuilder.appendTodo(todo: Todo, index: Int, now: Instant) {
    line("BEGIN:VTODO")
    line("UID:${escapeText(todo.icsUid ?: "${todo.id}@crko")}")
    val stamp = parseInstant(todo.dtStamp) ?: parseInstant(todo.lastModified) ?: now
    line("DTSTAMP:${formatUtc(stamp)}")
    if (todo.createdAt > 0) {
        line("CREATED:${formatUtc(Instant.fromEpochMilliseconds(todo.createdAt))}")
    }
    line("SUMMARY:${escapeText(todo.title)}")
    if (todo.note.isNotBlank()) line("DESCRIPTION:${escapeText(todo.note)}")
    if (todo.location.isNotBlank()) line("LOCATION:${escapeText(todo.location)}")
    if (todo.categories.isNotEmpty()) {
        line("CATEGORIES:${todo.categories.joinToString(",") { escapeText(it) }}")
    }
    if (todo.url.isNotBlank()) line("URL:${sanitizeUriValue(todo.url)}")
    appendAttachments(todo.attachments)
    todo.relatedTo.forEach { if (it.isNotBlank()) line("RELATED-TO:${escapeText(it)}") }
    val startInstant = todo.startTime?.let(::parseInstant)
    if (startInstant != null) {
        line("DTSTART:${formatUtc(startInstant)}")
    } else {
        todo.start?.let { parseDateText(it)?.let { date -> line("DTSTART;VALUE=DATE:${formatDate(date)}") } }
    }
    val dueInstant = todo.dueTime?.let(::parseInstant)
    if (dueInstant != null) {
        line("DUE:${formatUtc(dueInstant)}")
    } else {
        todo.due?.let { parseDateText(it)?.let { date -> line("DUE;VALUE=DATE:${formatDate(date)}") } }
    }
    todo.repeat?.let { rule ->
        line("RRULE:${rule.raw ?: ruleValue(rule, todo.repeatStartValue())}")
        appendTodoRDate(todo)
        appendTodoExDate(todo, rule)
    }
    val exportStatus = when {
        todo.status != TodoStatus.NeedsAction -> todo.status
        todo.done -> TodoStatus.Completed
        else -> TodoStatus.NeedsAction
    }
    val percent = when {
        todo.percentComplete in 1..100 -> todo.percentComplete
        exportStatus == TodoStatus.Completed -> 100
        else -> 0
    }
    val statusText = when (exportStatus) {
        TodoStatus.NeedsAction -> "NEEDS-ACTION"
        TodoStatus.InProcess -> "IN-PROCESS"
        TodoStatus.Completed -> "COMPLETED"
        TodoStatus.Cancelled -> "CANCELLED"
    }
    line("STATUS:$statusText")
    line("PERCENT-COMPLETE:$percent")
    if (exportStatus == TodoStatus.Completed) {
        // COMPLETED is a UTC DATE-TIME in RFC 5545; never invent one from DUE.
        val completed = todo.completedTime?.let(::parseInstant)?.let(::formatUtc)
            ?: todo.completedAt?.let(::parseDateText)?.let { "${formatDate(it)}T000000Z" }
        if (completed != null) line("COMPLETED:$completed")
    }
    line("PRIORITY:${todoPriorityToIcs(todo.priority)}")
    todo.lastModified?.let { line("LAST-MODIFIED:${formatUtc(parseInstant(it) ?: now)}") }
    if (todo.sequence > 0) line("SEQUENCE:${todo.sequence}")
    todo.alarms.forEach { alarm ->
        line("BEGIN:VALARM")
        line("ACTION:${alarm.action.ifBlank { "DISPLAY" }}")
        val absolute = alarm.absoluteTrigger
        if (absolute != null) {
            line("TRIGGER;VALUE=DATE-TIME:$absolute")
        } else {
            val related = if (alarm.relatedToEnd) ";RELATED=END" else ""
            line("TRIGGER$related:${formatTriggerDuration(alarm.triggerMinutes)}")
        }
        alarm.repeat?.let { line("REPEAT:$it") }
        alarm.durationMinutes?.let { line("DURATION:${formatDuration(it)}") }
        if (alarm.description.isNotBlank()) line("DESCRIPTION:${escapeText(alarm.description)}")
        alarm.extraProperties.forEach { line(it) }
        line("END:VALARM")
    }
    todo.extraProperties.forEach { line(it) }
    line("END:VTODO")
}

/** Start value a todo series' `UNTIL` is rendered against; a date-only todo keeps the DATE form. */
private fun Todo.repeatStartValue(): IcsTimeValue? {
    startTime?.let(::parseInstant)?.let { return IcsTimeValue.Utc(it) }
    val date = (start ?: due)?.let(::parseDateText)
    return date?.let(IcsTimeValue::Date)
}

/** `RDATE` lines of a todo series, keeping each value's own time and length. */
private fun StringBuilder.appendTodoRDate(todo: Todo) {
    appendRecurrenceDates(todo.rDates)
}

private fun StringBuilder.appendTodoExDate(todo: Todo, rule: RepeatRule) {
    if (todo.excludedDates.isEmpty()) return
    val anchor = todo.seriesAnchor ?: return
    val exdates = todo.excludedDates.mapNotNull { parseStoredDate(it)?.let(IcsTimeValue::Date) }
    // The exclusions are matched against the untouched set: an EXDATE must not hide itself.
    val recurrence = Recurrence(rule = rule, rdates = todo.rDates)
    // Only dates that really are occurrences of the rule belong in EXDATE.
    val values = exdates
        .filter { date -> isRuleOccurrence(anchor, rule, recurrence, date.date) }
        .map { formatDate(it.date) }
    if (values.isNotEmpty()) line("EXDATE;VALUE=DATE:${values.joinToString(",")}")
}

/** Writes the `RRULE`, `RDATE` and `EXDATE` of [recurrence]. */
private fun StringBuilder.appendRecurrence(recurrence: Recurrence, event: Event) {
    val rule = recurrence.rule
    if (rule != null) {
        line("RRULE:${rule.raw ?: ruleValue(rule, event.start)}")
    } else {
        recurrence.rawRule?.let { line("RRULE:$it") }
    }
    appendRecurrenceDates(recurrence.rdates)
    appendExDate(recurrence, event)
}

private fun StringBuilder.appendRecurrenceDates(rdates: List<RecurrenceDate>) {
    if (rdates.isEmpty()) return
    rdates.groupBy { occurrenceParameter(it.value, period = it.duration != null) }
        .forEach { (params, group) ->
            val values = group.map { rdate ->
                val value = formatTimeValue(rdate.value)
                rdate.duration?.let { "$value/${formatDurationSeconds(it.inWholeSeconds)}" } ?: value
            }
            line("RDATE$params:${values.joinToString(",")}")
        }
}

private fun StringBuilder.appendExDate(recurrence: Recurrence, event: Event) {
    if (recurrence.exdates.isEmpty()) return
    val values = recurrence.exdates.flatMap { it.exclusionFor(event, recurrence) }
    values.groupBy { occurrenceParameter(it) }.forEach { (params, group) ->
        line("EXDATE$params:${group.distinct().sortedBy { it.wallDateTime }.joinToString(",") { formatTimeValue(it) }}")
    }
}

/**
 * An `EXDATE` must use DTSTART's value type. A date-only exclusion on a timed series therefore
 * names every start of that day, and a timed exclusion on an all-day series becomes its date.
 */
private fun IcsTimeValue.exclusionFor(event: Event, recurrence: Recurrence): List<IcsTimeValue> {
    val start = event.start
    if (start is IcsTimeValue.Date) return listOf(IcsTimeValue.Date(wallDate))
    if (this !is IcsTimeValue.Date) return listOf(this)
    val times = event.copy(recurrence = recurrence.copy(exdates = emptyList())).occurrenceTimesOn(wallDate)
    if (times.isEmpty()) return listOf(start.withWallDate(wallDate))
    return times.map { time ->
        start.withWallDateTime(
            LocalDateTime(wallDate, LocalTime(time.minutes / 60, time.minutes % 60, time.seconds)),
        )
    }
}

private fun occurrenceParameter(value: IcsTimeValue, period: Boolean = false): String = when {
    period -> ";VALUE=PERIOD"
    value is IcsTimeValue.Date -> ";VALUE=DATE"
    value is IcsTimeValue.Zoned -> if (value.tzid.equals("UTC", ignoreCase = true)) "" else ";TZID=${value.tzid}"
    else -> ""
}

private fun formatTimeValue(value: IcsTimeValue): String = when (value) {
    is IcsTimeValue.Date -> formatDate(value.date)
    is IcsTimeValue.Floating -> formatIcsLocalDateTime(value.dateTime, utc = false)
    is IcsTimeValue.Zoned -> if (value.tzid.equals("UTC", ignoreCase = true)) {
        formatUtc(value.dateTime.toInstant(TimeZone.UTC))
    } else {
        formatIcsLocalDateTime(value.dateTime, utc = false)
    }
    is IcsTimeValue.Utc -> formatUtc(value.instant)
}

/**
 * `RRULE` value of [rule]; [start] is the component's `DTSTART`, which decides the `UNTIL` value
 * type (a DATE series always writes a DATE).
 */
private fun ruleValue(rule: RepeatRule, start: IcsTimeValue? = null): String = buildString {
    append(
        when (rule.frequency) {
            RepeatFrequency.Daily -> "FREQ=DAILY"
            RepeatFrequency.Weekly -> "FREQ=WEEKLY"
            RepeatFrequency.Monthly -> "FREQ=MONTHLY"
            RepeatFrequency.Yearly -> "FREQ=YEARLY"
        },
    )
    if (rule.interval > 1) append(";INTERVAL=${rule.interval}")
    if (rule.count != null && rule.count > 0) append(";COUNT=${rule.count}")
    else rule.until?.let { until -> append(";UNTIL=${formatUntil(until, start)}") }
    // RFC 5545 only gives an ordinal in BYDAY a meaning for MONTHLY and YEARLY rules.
    val ordinalsAllowed = rule.frequency == RepeatFrequency.Monthly || rule.frequency == RepeatFrequency.Yearly
    val days = rule.byDay.map { entry ->
        val code = weekdayCodes[entry.weekday.coerceIn(0, 6)]
        if (ordinalsAllowed && entry.ordinal != 0) "${entry.ordinal}$code" else code
    }.distinct()
    if (days.isNotEmpty()) append(";BYDAY=${days.joinToString(",")}")
    if (rule.byMonthDay.isNotEmpty()) append(";BYMONTHDAY=${rule.byMonthDay.joinToString(",")}")
    if (rule.byMonth.isNotEmpty()) append(";BYMONTH=${rule.byMonth.joinToString(",")}")
    if (rule.bySetPos.isNotEmpty()) append(";BYSETPOS=${rule.bySetPos.joinToString(",")}")
    if (rule.byYearDay.isNotEmpty()) append(";BYYEARDAY=${rule.byYearDay.joinToString(",")}")
    if (rule.byWeekNo.isNotEmpty()) append(";BYWEEKNO=${rule.byWeekNo.joinToString(",")}")
    rule.weekStart?.let { append(";WKST=${weekdayCodes[it.coerceIn(0, 6)]}") }
    if (rule.byHour.isNotEmpty()) append(";BYHOUR=${rule.byHour.joinToString(",")}")
    if (rule.byMinute.isNotEmpty()) append(";BYMINUTE=${rule.byMinute.joinToString(",")}")
    if (rule.bySecond.isNotEmpty()) append(";BYSECOND=${rule.bySecond.joinToString(",")}")
}

private fun formatUntil(until: IcsTimeValue, start: IcsTimeValue?): String = when {
    start is IcsTimeValue.Date -> formatDate(until.wallDate)
    until is IcsTimeValue.Utc -> formatUtc(until.instant)
    until is IcsTimeValue.Floating -> formatIcsLocalDateTime(until.dateTime, utc = false)
    until is IcsTimeValue.Zoned -> {
        val zone = resolveIcsZoneOrNull(until.tzid)
        if (zone != null) formatUtc(until.dateTime.toInstant(zone)) else formatIcsLocalDateTime(until.dateTime, utc = false)
    }
    // Date-only UNTIL: the last occurrence on that date at the component's own wall-clock time.
    until is IcsTimeValue.Date -> {
        val time = start?.wallDateTime?.time ?: LocalTime(0, 0)
        val last = LocalDateTime(until.date, time)
        when (start) {
            is IcsTimeValue.Zoned -> {
                val zone = resolveIcsZoneOrNull(start.tzid)
                if (zone != null) formatUtc(last.toInstant(zone)) else formatIcsLocalDateTime(last, utc = false)
            }
            is IcsTimeValue.Utc -> formatUtc(last.toInstant(TimeZone.UTC))
            else -> formatIcsLocalDateTime(last, utc = false)
        }
    }
    else -> formatDate(until.wallDate)
}

internal class ContentLine(
    val name: String,
    val params: Map<String, String>,
    val value: String,
    val raw: String,
    val lineNumber: Int = 0,
)

internal class IcsComponent(
    val name: String,
    val lineNumber: Int = 0,
    val properties: MutableList<ContentLine> = mutableListOf(),
    val children: MutableList<IcsComponent> = mutableListOf(),
) {
    fun first(name: String): ContentLine? = properties.firstOrNull { it.name == name }
    fun all(name: String): List<ContentLine> = properties.filter { it.name == name }

    fun toText(): String = buildString {
        append("BEGIN:${name}\r\n")
        properties.forEach { append(it.raw).append("\r\n") }
        children.forEach { append(it.toText()) }
        append("END:${name}\r\n")
    }
}

private fun normalizeComponent(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').trimEnd().split('\n').joinToString("\n")

/**
 * Rewrites the `TZID` a `VTIMEZONE` block declares to [id], so a `TZID=<id>` property reference in
 * the same exported file always resolves. Import strips a leading "/" from both sides, which would
 * otherwise leave the reference dangling for zones named like `/Europe/Amsterdam`.
 */
private fun rewriteDeclaredZoneId(text: String, id: String): String {
    val lines = text.split('\n')
    val index = lines.indexOfFirst { it.startsWith("TZID", ignoreCase = true) }
    if (index < 0) return text
    val colon = lines[index].indexOf(':')
    if (colon < 0) return text
    val rebuilt = lines.toMutableList()
    rebuilt[index] = lines[index].substring(0, colon) + ":" + id
    return rebuilt.joinToString("\n")
}

internal data class UnfoldedLine(val text: String, val lineNumber: Int)

private fun parseComponents(text: String): List<IcsComponent> = parseComponents(unfold(text).asSequence())

private fun parseComponents(lines: Sequence<UnfoldedLine>): List<IcsComponent> {
    val roots = mutableListOf<IcsComponent>()
    val stack = ArrayDeque<IcsComponent>()
    lines.forEach { unfolded ->
        val parsed = parseContentLine(unfolded) ?: return@forEach
        when (parsed.name) {
            "BEGIN" -> {
                val component = IcsComponent(parsed.value.trim().uppercase(), unfolded.lineNumber)
                if (stack.isEmpty()) roots += component else stack.last().children += component
                stack.addLast(component)
            }
            "END" -> {
                // Close only a component whose name matches: a stray or mismatched END must not pop
                // the enclosing VCALENDAR, which would drop every event that follows it.
                val closing = parsed.value.trim().uppercase()
                if (stack.lastOrNull()?.name == closing) stack.removeLast()
            }
            else -> stack.lastOrNull()?.properties?.add(parsed)
        }
    }
    return roots
}

/**
 * Turns a whole calendar text into unfolded logical lines. For text that arrives in pieces, use
 * [IcsUnfolder], which produces the very same lines without the whole text in memory.
 */
internal fun unfold(text: String): List<UnfoldedLine> {
    val unfolder = IcsUnfolder()
    val lines = unfolder.feed(text)
    return lines + unfolder.finish()
}

/**
 * Assembles physical lines into folded ICS logical lines, one piece at a time. Line numbers follow
 * the physical lines of the original file, so warnings can point at them. Feeding the same text as
 * one piece produces the lines of the previous whole-text [unfold], including its edge cases: a
 * byte order mark is dropped, `\r\n` and lone `\r` both end a line, and a trailing newline counts
 * as one final (empty) physical line.
 */
internal class IcsUnfolder {
    private var logical: String? = null
    private var logicalNumber = 0
    private var lineNumber = 0
    private val pending = StringBuilder()
    private var pendingCarriageReturn = false
    private var started = false
    private var endedWithNewline = false

    /** Feeds one decoded piece of the file; returns the logical lines completed by it. */
    fun feed(piece: String): List<UnfoldedLine> {
        val lines = mutableListOf<UnfoldedLine>()
        var text = piece
        if (!started) {
            started = true
            // A byte order mark is produced by real exporters but is not part of the first line.
            if (text.startsWith('\uFEFF')) text = text.substring(1)
        }
        if (pendingCarriageReturn) {
            pendingCarriageReturn = false
            // `\r\n` split across pieces is one line break, not two.
            if (text.startsWith('\n')) text = text.substring(1)
        }
        var index = 0
        while (index < text.length) {
            when (val ch = text[index]) {
                '\r' -> {
                    flush(pending.toString(), lines)
                    pending.clear()
                    endedWithNewline = true
                    if (index + 1 < text.length) {
                        if (text[index + 1] == '\n') index++
                    } else {
                        pendingCarriageReturn = true
                    }
                }
                '\n' -> {
                    flush(pending.toString(), lines)
                    pending.clear()
                    endedWithNewline = true
                }
                else -> {
                    pending.append(ch)
                    endedWithNewline = false
                }
            }
            index++
        }
        return lines
    }

    /** Ends the stream; call after the last [feed]. */
    fun finish(): List<UnfoldedLine> {
        val lines = mutableListOf<UnfoldedLine>()
        // A file ending in a line break has one final empty physical line, which still counts.
        if (pending.isNotEmpty() || endedWithNewline) {
            flush(pending.toString(), lines)
            pending.clear()
        }
        logical?.let { lines += UnfoldedLine(it, logicalNumber) }
        logical = null
        return lines
    }

    private fun flush(physical: String, lines: MutableList<UnfoldedLine>) {
        lineNumber++
        val current = logical
        when {
            current != null && (physical.startsWith(' ') || physical.startsWith('\t')) ->
                logical = current + physical.substring(1)
            current != null && current.endsWith("=") &&
                current.contains("ENCODING=QUOTED-PRINTABLE", ignoreCase = true) ->
                // vCalendar 1.0 folds a quoted-printable value with a trailing "=" instead of a space.
                logical = current.dropLast(1) + physical
            else -> {
                if (current != null) lines += UnfoldedLine(current, logicalNumber)
                logical = physical
                logicalNumber = lineNumber
            }
        }
    }
}

internal fun parseContentLine(unfolded: UnfoldedLine): ContentLine? {
    val line = unfolded.text
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
    var value = line.substring(colon + 1)
    val parts = splitParameters(head)
    if (parts.isEmpty()) return null
    val params = parts.drop(1).mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) null
        else part.substring(0, equals).trim().uppercase() to part.substring(equals + 1).trim().removeSurrounding("\"")
    }.toMap()
    if (params["ENCODING"].equals("QUOTED-PRINTABLE", ignoreCase = true)) {
        value = decodeQuotedPrintable(value, params["CHARSET"])
    }
    return ContentLine(parts.first().trim().uppercase(), params, value, line, unfolded.lineNumber)
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
    data class DateTime(
        val value: LocalDateTime,
        val zone: IcsZone,
        val source: String,
        /** Length of an `RDATE;VALUE=PERIOD` occurrence; null for a plain DATE-TIME. */
        val periodMinutes: Int? = null,
    ) : IcsTime
}

/** Length carried by an `RDATE;VALUE=PERIOD` value, if any. */
private fun IcsTime.periodDuration(): Duration? =
    (this as? IcsTime.DateTime)?.periodMinutes?.takeIf { it > 0 }?.minutes

private sealed interface IcsZone {
    data object Floating : IcsZone
    data object Utc : IcsZone
    data class Platform(override val id: String, val zone: TimeZone) : IcsZone
    data class Custom(val zone: CustomZone) : IcsZone

    val id: String?
        get() = when (this) {
            Floating, Utc -> null
            is Platform -> id
            is Custom -> zone.id
        }
}

private fun resolveZone(tzid: String?): IcsZone? = when {
    tzid == null -> null
    tzid.equals("UTC", ignoreCase = true) || tzid.equals("GMT", ignoreCase = true) -> IcsZone.Utc
    else -> resolvePlatformZone(tzid)
        ?: IcsTimeZoneCache.customZoneOrNull(tzid)?.let { IcsZone.Custom(it) }
}

/** Platform zone a `TZID` resolves to, or null when only a stored calendar definition knows it. */
internal fun resolveIcsZoneOrNull(tzid: String): TimeZone? =
    when (val zone = resolveZone(tzid)) {
        is IcsZone.Platform -> zone.zone
        IcsZone.Utc -> TimeZone.UTC
        else -> null
    }

/** Absolute instant of a value whose zone may only exist in a calendar-level `VTIMEZONE`. */
internal fun customZoneInstantOrNull(dateTime: LocalDateTime, tzid: String): Instant? =
    resolveZone(tzid)?.toInstant(dateTime)

/** Whether the platform resolves [tzid] on its own, so no `VTIMEZONE` block needs to be stored. */
private fun isPlatformTimeZone(tzid: String): Boolean =
    tzid.equals("UTC", ignoreCase = true) || tzid.equals("GMT", ignoreCase = true) ||
        resolvePlatformZone(tzid) != null

internal fun normalizeTimeZoneId(tzid: String): String = tzid.trim().removePrefix("/")

/** Adds the calendar-level definitions of unknown zones to the runtime lookup cache. */
internal fun registerIcsTimeZones(timeZones: Iterable<CalendarTimeZone>) {
    timeZones.forEach { IcsTimeZoneCache.register(it.tzid, it.definition) }
}

/** Stored `VTIMEZONE` text of an unknown zone, for an export that only carries [Event] values. */
internal fun icsTimeZoneDefinition(tzid: String): String? = IcsTimeZoneCache.definitionOf(tzid)

/**
 * Process-wide lookup of the calendar-level definitions. [AppState.calendar] remains the persisted
 * source of truth; this cache only lets the `Event` display projections resolve unknown zones
 * without threading the whole state through every recurrence query. Parsed definitions are kept so
 * hundreds of values from one file share a single interpretation of their `VTIMEZONE`.
 */
private object IcsTimeZoneCache {
    private val definitions = mutableMapOf<String, String>()
    private val parsed = mutableMapOf<String, CustomZone?>()

    fun register(tzid: String, definition: String) {
        val id = normalizeTimeZoneId(tzid)
        if (id.isEmpty() || definition.isBlank() || isPlatformTimeZone(id)) return
        if (definitions[id] == definition) return
        definitions[id] = definition
        parsed.remove(id)
    }

    fun definitionOf(tzid: String): String? = definitions[normalizeTimeZoneId(tzid)]

    fun customZoneOrNull(tzid: String): CustomZone? {
        val id = normalizeTimeZoneId(tzid)
        val definition = definitions[id] ?: return null
        return parsed.getOrPut(id) { customZoneFromText(definition) }
    }

    private fun customZoneFromText(text: String): CustomZone? =
        parseComponents(text).firstOrNull { it.name == "VTIMEZONE" }?.let(::parseCustomZone)
}

/** The zone a calendar declares for values without a `TZID`, if any. */
private fun calendarFloatingZone(
    calendars: List<IcsComponent>,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
): IcsZone? {
    val raw = calendars
        .firstNotNullOfOrNull { it.first("X-WR-TIMEZONE")?.value }
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removePrefix("/")
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    zones[raw]?.let { return IcsZone.Custom(it) }
    if (raw.equals("UTC", ignoreCase = true) || raw.equals("GMT", ignoreCase = true)) return IcsZone.Utc
    return resolvePlatformZone(raw) ?: run {
        warnings += IcsWarning.UnresolvedTimeZone
        null
    }
}

/**
 * Resolves a platform zone for [tzid]. Exchange writes Windows names ("China Standard Time") and
 * some clients prefix the real id with their own domain, e.g.
 * `/mozilla.org/20050126_1/America/New_York`; both have to become a real [TimeZone] or the event
 * would silently fall back to floating time.
 */
private fun resolvePlatformZone(tzid: String): IcsZone.Platform? {
    val normalized = tzid.trim().removePrefix("/")
    if (normalized.isEmpty()) return null
    val candidates = buildList {
        add(normalized)
        windowsTimeZones[normalized.lowercase()]?.let { add(it) }
        var slash = normalized.indexOf('/')
        while (slash >= 0 && slash + 1 < normalized.length) {
            add(normalized.substring(slash + 1))
            slash = normalized.indexOf('/', slash + 1)
        }
    }
    candidates.forEach { id ->
        runCatching { TimeZone.of(id) }.getOrNull()?.let { return IcsZone.Platform(id, it) }
    }
    return null
}

private data class RawAlarm(
    val action: String,
    val triggerMinutes: Int,
    val relatedToEnd: Boolean,
    val repeat: Int?,
    val durationMinutes: Int?,
    val description: String,
    val absoluteTrigger: String? = null,
    val extraProperties: List<String> = emptyList(),
)

private data class RawEvent(
    val uid: String? = null,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val categories: List<String> = emptyList(),
    val url: String = "",
    val geo: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val status: String? = null,
    val transparency: String? = null,
    val eventClass: String? = null,
    val priority: Int? = null,
    val organizer: String? = null,
    val attendees: List<String> = emptyList(),
    val organizerRaw: String? = null,
    val attendeeRaw: List<String> = emptyList(),
    val start: IcsTime? = null,
    val end: IcsTime? = null,
    val durationMinutes: Int? = null,
    val recurrenceId: IcsTime? = null,
    val recurrenceRange: String? = null,
    val rule: RepeatRule? = null,
    /** Original `RRULE` text when even its `FREQ` could not be represented. */
    val rawRule: String? = null,
    val exdates: List<IcsTime> = emptyList(),
    val rDates: List<IcsTime> = emptyList(),
    val created: String? = null,
    val lastModified: String? = null,
    val sequence: Int = 0,
    /** `DTSTAMP` of the copy, used to tell the newest copy of a UID apart from its stale ones. */
    val dtStamp: String? = null,
    val alarms: List<RawAlarm> = emptyList(),
    val extraProperties: List<String> = emptyList(),
    /** Line of `BEGIN:VEVENT`, used to point a warning at the record. */
    val lineNumber: Int = 0,
)

private fun RawEvent.isCancelled(): Boolean = status.equals("CANCELLED", ignoreCase = true)

private fun readEvent(
    component: IcsComponent,
    zones: Map<String, CustomZone>,
    floatingZone: IcsZone?,
    details: MutableList<IcsWarningDetail>,
): RawEvent {
    val warnings = linkedSetOf<IcsWarning>()
    var uid: String? = null
    var summary = ""
    var description = ""
    var location = ""
    val categories = mutableListOf<String>()
    val attachments = mutableListOf<Attachment>()
    var url = ""
    var geo: String? = null
    var status: String? = null
    var transparency: String? = null
    var eventClass: String? = null
    var priority: Int? = null
    var organizer: String? = null
    val attendees = mutableListOf<String>()
    var organizerRaw: String? = null
    val attendeeRaw = mutableListOf<String>()
    var start: IcsTime? = null
    var end: IcsTime? = null
    var durationMinutes: Int? = null
    var recurrenceId: IcsTime? = null
    var recurrenceRange: String? = null
    var rule: RepeatRule? = null
    var rawRule: String? = null
    val exdates = mutableListOf<IcsTime>()
    val rdates = mutableListOf<IcsTime>()
    var created: String? = null
    var lastModified: String? = null
    var sequence = 0
    var dtStamp: String? = null
    val extra = mutableListOf<String>()

    component.properties.forEach { line ->
        when (line.name) {
            "UID" -> uid = unescapeText(line.value).trim().takeIf { it.isNotEmpty() }
            "SUMMARY" -> summary = unescapeText(line.value).trim()
            "DESCRIPTION" -> description = unescapeText(line.value).trim()
            "LOCATION" -> location = unescapeText(line.value).trim()
            "CATEGORIES" -> categories += splitList(line.value).map(::unescapeText).filter { it.isNotBlank() }
            "URL" -> url = unescapeText(line.value).trim()
            "GEO" -> geo = line.value.trim().takeIf { it.isNotEmpty() }
            "ATTACH" -> attachments += readAttachment(line)
            "STATUS" -> status = line.value.trim().uppercase()
            "TRANSP" -> transparency = line.value.trim().uppercase()
            "CLASS" -> eventClass = line.value.trim().uppercase()
            "PRIORITY" -> priority = line.value.trim().toIntOrNull()?.coerceIn(0, 9)
            "ORGANIZER" -> {
                organizerRaw = line.raw
                organizer = unescapeText(line.value).trim().takeIf { it.isNotEmpty() }
            }
            "ATTENDEE" -> {
                attendeeRaw += line.raw
                unescapeText(line.value).trim().takeIf { it.isNotEmpty() }?.let { attendees += it }
            }
            "DTSTART" -> start = parseTime(line, zones, warnings, floatingZone)
            "DTEND" -> end = parseTime(line, zones, warnings, floatingZone)
            "DURATION" -> durationMinutes = parseDurationMinutes(line.value)
            "RECURRENCE-ID" -> {
                recurrenceId = parseTime(line, zones, warnings, floatingZone)
                recurrenceRange = line.params["RANGE"]?.uppercase()
            }
            "RRULE" -> {
                val parsed = parseRule(line.value, zones, warnings, floatingZone, start is IcsTime.AllDay)
                if (rule == null && rawRule == null) {
                    rule = parsed
                    // An unrepresentable FREQ keeps the property text so an export does not lose it.
                    rawRule = line.value.trim().takeIf { parsed == null }
                } else {
                    warnings += IcsWarning.MultipleRrules
                }
            }
            "EXRULE" -> {
                warnings += IcsWarning.ExruleIgnored
                extra += line.raw
            }
            "EXDATE" -> splitList(line.value).forEach { value ->
                parseTime(
                    ContentLine("EXDATE", line.params, value, line.raw),
                    zones,
                    warnings,
                    floatingZone,
                )?.let { exdates += it }
            }
            "RDATE" -> splitList(line.value).forEach { value ->
                parseTime(
                    ContentLine("RDATE", line.params, value, line.raw),
                    zones,
                    warnings,
                    floatingZone,
                )?.let { rdates += it }
            }
            "CREATED" -> created = parseIcsInstant(line.value) ?: line.value.trim()
            "LAST-MODIFIED" -> lastModified = parseIcsInstant(line.value) ?: line.value.trim()
            "SEQUENCE" -> sequence = line.value.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            "DTSTAMP" -> dtStamp = parseIcsInstant(line.value) ?: line.value.trim()
            "PRODID", "VERSION", "CALSCALE", "METHOD", "X-WR-CALNAME" -> Unit
            else -> extra += line.raw
        }
    }

    val alarms = component.children.filter { it.name == "VALARM" }.mapNotNull(::readAlarm)
    if (component.children.any { it.name != "VALARM" }) {
        warnings += IcsWarning.UnknownSubcomponent
    }
    val record = summary.ifBlank { uid }
    warnings.forEach { details += warningDetail(it, component.lineNumber, record) }
    return RawEvent(
        uid = uid,
        summary = summary,
        description = description,
        location = location,
        categories = categories.distinct(),
        url = url,
        geo = geo,
        attachments = attachments,
        status = status,
        transparency = transparency,
        eventClass = eventClass,
        priority = priority,
        organizer = organizer,
        attendees = attendees,
        organizerRaw = organizerRaw,
        attendeeRaw = attendeeRaw,
        start = start,
        end = end,
        durationMinutes = durationMinutes,
        recurrenceId = recurrenceId,
        recurrenceRange = recurrenceRange,
        rule = rule,
        rawRule = rawRule,
        exdates = exdates,
        rDates = rdates,
        created = created,
        lastModified = lastModified,
        sequence = sequence,
        dtStamp = dtStamp,
        alarms = alarms,
        extraProperties = extra,
        lineNumber = component.lineNumber,
    )
}

private fun readAttachment(line: ContentLine): Attachment {
    val binary = line.params["ENCODING"].equals("BASE64", ignoreCase = true)
    val uri = if (binary) "" else line.value.trim()
    val name = line.params["FILENAME"]?.trim()?.removeSurrounding("\"")
        ?.takeIf { it.isNotEmpty() }
        ?: uri.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
        ?: ""
    val mimeType = line.params["FMTTYPE"]?.trim()?.removeSurrounding("\"") ?: ""
    return Attachment(
        raw = line.raw,
        uri = uri,
        name = name,
        mimeType = mimeType,
        data = if (binary) line.value.trim() else "",
        binary = binary,
    )
}

private fun readAlarm(component: IcsComponent): RawAlarm? {
    val action = component.first("ACTION")?.value?.trim()?.uppercase() ?: "DISPLAY"
    val trigger = component.first("TRIGGER") ?: return null
    val triggerValue = trigger.value.trim()
    val relative = triggerValue.startsWith("P") || triggerValue.startsWith("-P") || triggerValue.startsWith("+P")
    val signed = if (relative) parseSignedDurationMinutes(triggerValue) else null
    if (relative && signed == null) return null
    if (!relative && triggerValue.isEmpty()) return null
    val repeat = component.first("REPEAT")?.value?.trim()?.toIntOrNull()
    val duration = component.first("DURATION")?.value?.trim()?.let(::parseDurationMinutes)
    val description = component.first("DESCRIPTION")?.value?.let(::unescapeText).orEmpty()
    val known = setOf("ACTION", "TRIGGER", "REPEAT", "DURATION", "DESCRIPTION")
    return RawAlarm(
        action = action,
        triggerMinutes = -(signed ?: 0),
        relatedToEnd = trigger.params["RELATED"]?.equals("END", ignoreCase = true) == true,
        repeat = repeat,
        durationMinutes = duration,
        description = description,
        absoluteTrigger = triggerValue.takeIf { !relative },
        extraProperties = component.properties.filterNot { it.name in known }.map { it.raw },
    )
}

private fun parseTime(
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
    if (minute !in 0..59 || second !in 0..59 || hour !in 0..24) {
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
    val local = LocalDateTime(date, LocalTime(hour, minute, second))
    val zone = resolveLineZone(utc, line.params["TZID"], zones, warnings, defaultZone)
    return IcsTime.DateTime(local, zone, text)
}

/**
 * Parses `start/end` and `start/duration` periods. The occurrence keeps its start; the length is
 * carried so an RDATE instance can end at its own time instead of inheriting the series duration.
 */
private fun parsePeriod(
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
        parseSignedDurationMinutes(endText)
    } else {
        val end = parseTime(
            ContentLine(line.name, line.params - "VALUE", endText, line.raw),
            zones,
            warnings,
            defaultZone,
        ) as? IcsTime.DateTime ?: return null
        val seconds = end.zone.toInstant(end.value).epochSeconds - start.zone.toInstant(start.value).epochSeconds
        (seconds / 60).toInt()
    }
    return IcsTime.DateTime(
        value = start.value,
        zone = start.zone,
        source = startText,
        periodMinutes = duration?.takeIf { it > 0 } ?: run {
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

private fun parseRule(
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
                    'S' -> if (inTime) number / 60 else 0
                    else -> 0
                }
                number = 0
            }
        }
    }
    return if (sawDigit) total else null
}

private fun parseSignedDurationMinutes(value: String): Int? {
    val trimmed = value.trim()
    val sign = if (trimmed.startsWith('-')) -1 else 1
    val body = trimmed.removePrefix("-").removePrefix("+")
    val minutes = parseDurationMinutes(body) ?: return null
    return sign * minutes
}

private fun parseDate(text: String): LocalDate? {
    if (text.length < 8) return null
    val year = text.substring(0, 4).toIntOrNull() ?: return null
    val month = text.substring(4, 6).toIntOrNull() ?: return null
    val day = text.substring(6, 8).toIntOrNull() ?: return null
    return runCatching { LocalDate(year, month, day) }.getOrNull()
}

private fun parseDateText(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()

private fun splitList(value: String): List<String> {
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

private fun IcsTime.localDate(deviceZone: TimeZone): LocalDate = when (this) {
    is IcsTime.AllDay -> date
    is IcsTime.DateTime -> localDateTime(deviceZone).date
}

/** Date the calendar file itself names, independent of the device zone. */
private fun IcsTime.localWallDate(): LocalDate = when (this) {
    is IcsTime.AllDay -> date
    is IcsTime.DateTime -> value.date
}

private fun IcsTime.localMinutes(deviceZone: TimeZone): Int? = when (this) {
    is IcsTime.AllDay -> null
    is IcsTime.DateTime -> localDateTime(deviceZone).time.let { it.hour * 60 + it.minute }
}

/**
 * Instant a todo or journal stores for [this] value. A floating time belongs to the device, not to
 * UTC: stamping it as UTC would move the recorded date every time the file is read back in a zone
 * west of Greenwich. Date-only values have no instant at all.
 */
private fun IcsTime.instantForDevice(deviceZone: TimeZone): String? = when (this) {
    is IcsTime.AllDay -> null
    is IcsTime.DateTime -> when (zone) {
        IcsZone.Floating -> value.toInstant(deviceZone)
        else -> zone.toInstant(value)
    }.toString()
}

private fun IcsZone.toInstant(local: LocalDateTime): Instant = when (this) {
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

/**
 * Copies of one UID ranked from stale to newest: `SEQUENCE` is the authoritative counter for a UID,
 * then `DTSTAMP`, then `LAST-MODIFIED`. Ties keep the earlier copy, so a file without any of them
 * still imports deterministically.
 */
private val masterCopyComparator = compareBy<RawEvent>(
    { it.sequence },
    { parseInstant(it.dtStamp)?.epochSeconds ?: Long.MIN_VALUE },
    { parseInstant(it.lastModified)?.epochSeconds ?: Long.MIN_VALUE },
)

private fun mapSeries(
    uid: String,
    group: List<RawEvent>,
    deviceZone: TimeZone,
    untitledTitle: String,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
): List<Event> {
    // Real feeds do carry several master copies for one UID (an updated or cancelled copy next to
    // the live one). Letting the newest copy govern, instead of the first line in the file, is what
    // decides whether the user's event survives the import.
    val base = group.filter { it.recurrenceId == null }.maxWithOrNull(masterCopyComparator)
    if (base == null) {
        // An exception without its master has no series to attach to. Keep the record (and its
        // RECURRENCE-ID, which a bare iTIP REPLY still needs) but the exporter omits the id when
        // the master is not in the same file, so no dangling reference leaves the app.
        return group.mapNotNull { override ->
            mapSingle(override, deviceZone, untitledTitle, zones, warnings, recurrence = null)?.let { event ->
                event.copy(icsUid = uid, recurrenceRange = RecurrenceRange.parse(override.recurrenceRange))
            }
        }.filterNot { it.status == EventStatus.Cancelled }
    }
    if (base.isCancelled()) return emptyList()

    val series = mapSingle(base, deviceZone, untitledTitle, zones, warnings) ?: return emptyList()
    val exceptions = mutableListOf<Event>()
    val exclusions = mutableListOf<IcsTimeValue>()
    val seenSlots = mutableSetOf<IcsTimeValue>()
    var ambiguous = false
    group.filter { it.recurrenceId != null }.forEach { override ->
        val slot = override.recurrenceId?.toValue() ?: return@forEach
        // Two exceptions naming the very same instant are indistinguishable; two on one day but at
        // different times are fine, which is exactly what the typed slot makes possible.
        if (!seenSlots.add(slot)) ambiguous = true
        exclusions += slot
        val range = RecurrenceRange.parse(override.recurrenceRange)
        if (range == RecurrenceRange.ThisAndPrior) {
            // THISANDPRIOR is not RFC 5545; keep it so an export writes the range back, but say so.
            warnings += IcsWarning.UnsupportedRecurrence
        }
        val mapped = mapSingle(
            override,
            deviceZone,
            untitledTitle,
            zones,
            warnings,
            recurrence = null,
        ) ?: return@forEach
        exceptions += mapped.copy(
            icsUid = uid,
            recurrenceId = slot,
            recurrenceRange = range,
        )
    }
    if (ambiguous) warnings += IcsWarning.AmbiguousRecurrenceId
    if (exclusions.isEmpty()) return listOf(series) + exceptions
    // The master skips every slot an exception describes; the exception itself carries the change,
    // including a `STATUS:CANCELLED` copy that must keep its SEQUENCE for later imports.
    val recurrence = series.recurrence?.copy(
        exdates = (series.recurrence.exdates + exclusions).distinct(),
    ) ?: Recurrence(exdates = exclusions.distinct())
    return listOf(series.copy(recurrence = recurrence)) + exceptions
}

private fun mapSingle(
    raw: RawEvent,
    deviceZone: TimeZone,
    untitledTitle: String,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
    recurrence: Recurrence? = mapRecurrence(raw.rule, raw.rawRule, raw.rDates, raw.exdates),
): Event? {
    val parsedStart = raw.start ?: raw.recurrenceId
    if (parsedStart == null) {
        // Saying only "invalid value" left the user with no way to find the record that vanished.
        warnings += IcsWarning.MissingStart
        return null
    }
    val title = raw.summary.ifBlank { untitledTitle }
    val start = parsedStart.toValue()
    val end = mapEnd(start, raw, deviceZone)
    return Event(
        id = null,
        title = title,
        start = start,
        end = end,
        note = raw.description,
        location = raw.location,
        categories = raw.categories,
        url = raw.url,
        geo = raw.geo,
        attachments = raw.attachments,
        status = rawStatus(raw.status),
        transparency = rawTransparency(raw.transparency),
        eventClass = rawClass(raw.eventClass),
        priority = raw.priority,
        organizer = raw.organizer,
        attendees = raw.attendees,
        organizerRaw = raw.organizerRaw,
        attendeeRaw = raw.attendeeRaw,
        alarms = raw.alarms.map { it.toAlarm() },
        recurrence = recurrence,
        icsUid = raw.uid,
        recurrenceId = raw.recurrenceId?.toValue(),
        recurrenceRange = RecurrenceRange.parse(raw.recurrenceRange),
        created = raw.created,
        lastModified = raw.lastModified,
        dtStamp = raw.dtStamp,
        sequence = raw.sequence,
        extraProperties = raw.extraProperties,
        sourceLine = raw.lineNumber,
    ).withValidExdates(warnings)
}

/**
 * Drops `EXDATE` values that name no start of the series. An exclusion at another time of day can
 * never match a typed occurrence, so storing it would only hide the problem; the importer reports
 * it instead. Rules kept as raw text cannot be expanded, so their exclusions are left alone.
 */
private fun Event.withValidExdates(warnings: MutableSet<IcsWarning>): Event {
    val set = recurrence ?: return this
    if (set.exdates.isEmpty() || set.rawRule != null) return this
    val unexcluded = copy(recurrence = set.copy(exdates = emptyList()))
    val kept = set.exdates.filter { exdate ->
        val day = exdate.wallDate
        if (exdate is IcsTimeValue.Date) {
            unexcluded.occursOn(day)
        } else {
            val time = exdate.wallTime
            time != null && unexcluded.occurrenceTimesOn(day).any {
                it.minutes == time.hour * 60 + time.minute && it.seconds == time.second
            }
        }
    }
    if (kept.size == set.exdates.size) return this
    warnings += IcsWarning.ExdateMismatch
    return copy(recurrence = set.copy(exdates = kept))
}

/** Typed recurrence set of a component; null when it carries neither rule nor added/excluded values. */
private fun mapRecurrence(
    rule: RepeatRule?,
    rawRule: String?,
    rDates: List<IcsTime>,
    exdates: List<IcsTime>,
): Recurrence? {
    val rdates = rDates.map { RecurrenceDate(it.toValue(), it.periodDuration()) }
    val excluded = exdates.map { it.toValue() }
    if (rule == null && rawRule == null && rdates.isEmpty() && excluded.isEmpty()) return null
    return Recurrence(rule = rule, rdates = rdates, exdates = excluded, rawRule = rawRule)
}

/** RFC 5545 value of a parsed time; unknown zones resolve through the calendar-level definitions. */
private fun IcsTime.toValue(): IcsTimeValue = when (this) {
    is IcsTime.AllDay -> IcsTimeValue.Date(date)
    is IcsTime.DateTime -> when (val zone = zone) {
        IcsZone.Floating -> IcsTimeValue.Floating(value)
        IcsZone.Utc -> IcsTimeValue.Utc(value.toInstant(TimeZone.UTC))
        is IcsZone.Platform -> IcsTimeValue.Zoned(value, zone.id)
        is IcsZone.Custom -> IcsTimeValue.Zoned(value, zone.zone.id)
    }
}

/**
 * End of a record: `DTEND`, `DURATION`, or null for a zero-length timed event. Date-only records
 * always get an exclusive one-day end so the all-day branch never has to special-case it.
 */
private fun mapEnd(start: IcsTimeValue, raw: RawEvent, deviceZone: TimeZone): IcsTimeValue? {
    if (start is IcsTimeValue.Date) {
        val exclusive = when (val end = raw.end) {
            is IcsTime.AllDay -> if (end.date > start.date) end.date else start.date.plusDays(1)
            is IcsTime.DateTime -> end.localDate(deviceZone).takeIf { it > start.date } ?: start.date.plusDays(1)
            null -> raw.durationMinutes
                ?.let { minutes -> start.date.plusDays((minutes / (24 * 60)).coerceAtLeast(1)) }
                ?: start.date.plusDays(1)
        }
        return IcsTimeValue.Date(exclusive)
    }
    val parsedEnd = (raw.end as? IcsTime.DateTime)?.toValue()
    val resolved = when {
        parsedEnd != null -> alignEndTo(start, parsedEnd, deviceZone)
        raw.durationMinutes != null && raw.durationMinutes > 0 ->
            start.plusDuration(raw.durationMinutes.minutes, deviceZone)
        // RFC 5545: no DTEND and no DURATION means the event has zero length.
        else -> null
    } ?: return null
    val startInstant = start.instantOrNull(deviceZone)
    val endInstant = resolved.instantOrNull(deviceZone)
    val positive = if (startInstant != null && endInstant != null) {
        endInstant > startInstant
    } else {
        resolved.wallDateTime > start.wallDateTime
    }
    return resolved.takeIf { positive }
}

/** Keeps an end in the same value type as the start; RFC 5545 forbids mixing them. */
private fun alignEndTo(start: IcsTimeValue, end: IcsTimeValue, deviceZone: TimeZone): IcsTimeValue {
    if (end is IcsTimeValue.Date) return end
    return when (start) {
        is IcsTimeValue.Date -> end
        is IcsTimeValue.Floating -> IcsTimeValue.Floating(end.wallDateTime)
        is IcsTimeValue.Zoned -> {
            // Same TZID on both values needs no conversion; this also keeps a custom zone, whose
            // wall clock cannot be recovered from an instant without its rules.
            if (end is IcsTimeValue.Zoned && end.tzid == start.tzid) {
                return IcsTimeValue.Zoned(end.dateTime, start.tzid)
            }
            val zone = icsZoneOrNull(start.tzid) ?: deviceZone
            val instant = when (end) {
                is IcsTimeValue.Utc -> end.instant
                is IcsTimeValue.Zoned -> end.instantOrNull(deviceZone) ?: end.dateTime.toInstant(zone)
                is IcsTimeValue.Floating -> end.dateTime.toInstant(zone)
                is IcsTimeValue.Date -> return end
            }
            IcsTimeValue.Zoned(instant.toLocalDateTime(zone), start.tzid)
        }
        is IcsTimeValue.Utc -> {
            val instant = when (end) {
                is IcsTimeValue.Utc -> end.instant
                is IcsTimeValue.Zoned -> end.instantOrNull(deviceZone) ?: end.dateTime.toInstant(TimeZone.UTC)
                is IcsTimeValue.Floating -> end.dateTime.toInstant(TimeZone.UTC)
                is IcsTimeValue.Date -> return end
            }
            IcsTimeValue.Utc(instant)
        }
    }
}

private fun rawStatus(value: String?): EventStatus = when (value?.uppercase()) {
    "TENTATIVE" -> EventStatus.Tentative
    "CANCELLED" -> EventStatus.Cancelled
    else -> EventStatus.Confirmed
}

private fun rawTransparency(value: String?): EventTransparency =
    if (value.equals("TRANSPARENT", ignoreCase = true)) EventTransparency.Transparent else EventTransparency.Opaque

private fun rawClass(value: String?): EventClass? = when (value?.uppercase()) {
    "PUBLIC" -> EventClass.Public
    "PRIVATE" -> EventClass.Private
    "CONFIDENTIAL" -> EventClass.Confidential
    else -> null
}

private fun RawAlarm.toAlarm(): Alarm = Alarm(
    action = action,
    triggerMinutes = triggerMinutes,
    relatedToEnd = relatedToEnd,
    repeat = repeat,
    durationMinutes = durationMinutes,
    description = description,
    absoluteTrigger = absoluteTrigger,
    extraProperties = extraProperties,
)

private fun readTodo(
    component: IcsComponent,
    deviceZone: TimeZone,
    zones: Map<String, CustomZone>,
    floatingZone: IcsZone?,
    details: MutableList<IcsWarningDetail>,
): Todo? {
    val warnings = linkedSetOf<IcsWarning>()
    var uid: String? = null
    var title = ""
    var note = ""
    var start: String? = null
    var startTime: String? = null
    var startValue: IcsTime? = null
    var due: String? = null
    var dueTime: String? = null
    var dueValue: IcsTime? = null
    var rule: RepeatRule? = null
    var rawRule: String? = null
    val exdates = mutableListOf<IcsTime>()
    val rdates = mutableListOf<IcsTime>()
    var location = ""
    val categories = mutableListOf<String>()
    val attachments = mutableListOf<Attachment>()
    var url = ""
    val relatedTo = mutableListOf<String>()
    var rawStatus: String? = null
    var percent = 0
    var priority = 0
    var completed: String? = null
    var completedTime: String? = null
    var created: String? = null
    var lastModified: String? = null
    var dtStamp: String? = null
    var sequence = 0
    val extra = mutableListOf<String>()
    component.properties.forEach { line ->
        when (line.name) {
            "UID" -> uid = unescapeText(line.value).trim().takeIf { it.isNotEmpty() }
            "SUMMARY" -> title = unescapeText(line.value).trim()
            "DESCRIPTION" -> note = unescapeText(line.value).trim()
            "DTSTART" -> {
                val time = parseTime(line, zones, warnings, floatingZone)
                startValue = time
                start = when (time) {
                    is IcsTime.AllDay -> time.date.toString()
                    is IcsTime.DateTime -> time.localDate(deviceZone).toString()
                    null -> null
                }
                startTime = time?.instantForDevice(deviceZone)
                if (time == null) warnings += IcsWarning.InvalidValue
            }
            "DUE" -> {
                val time = parseTime(line, zones, warnings, floatingZone)
                dueValue = time
                due = when (time) {
                    is IcsTime.AllDay -> time.date.toString()
                    is IcsTime.DateTime -> time.localDate(deviceZone).toString()
                    null -> null
                }
                dueTime = time?.instantForDevice(deviceZone)
                if (time == null) warnings += IcsWarning.InvalidValue
            }
            "RRULE" -> {
                val parsed = parseRule(
                    line.value,
                    zones,
                    warnings,
                    floatingZone,
                    startIsDate = startValue is IcsTime.AllDay,
                )
                if (rule == null && rawRule == null) {
                    rule = parsed
                    rawRule = line.value.trim().takeIf { parsed == null }
                } else {
                    warnings += IcsWarning.MultipleRrules
                }
            }
            "EXDATE" -> splitList(line.value).forEach { value ->
                parseTime(
                    ContentLine("EXDATE", line.params, value, line.raw),
                    zones,
                    warnings,
                    floatingZone,
                )?.let { exdates += it }
            }
            "RDATE" -> splitList(line.value).forEach { value ->
                parseTime(
                    ContentLine("RDATE", line.params, value, line.raw),
                    zones,
                    warnings,
                    floatingZone,
                )?.let { rdates += it }
            }
            "LOCATION" -> location = unescapeText(line.value).trim()
            "CATEGORIES" -> categories += splitList(line.value).map(::unescapeText).filter { it.isNotBlank() }
            "URL" -> url = unescapeText(line.value).trim()
            "ATTACH" -> attachments += readAttachment(line)
            "RELATED-TO" -> when (line.params["RELTYPE"]?.trim()?.uppercase()) {
                // Absent RELTYPE is the default PARENT the hierarchy is built on.
                null, "", "PARENT" -> unescapeText(line.value).trim().takeIf { it.isNotEmpty() }?.let { relatedTo += it }
                // CHILD and SIBLING name the same link in the other direction; keeping the raw line
                // preserves the parameter for the next export instead of inverting the hierarchy.
                else -> extra += line.raw
            }
            "STATUS" -> rawStatus = line.value.trim().uppercase()
            "PERCENT-COMPLETE" -> percent = line.value.trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
            "PRIORITY" -> priority = line.value.trim().toIntOrNull()?.coerceIn(0, 9) ?: 0
            "COMPLETED" -> {
                val time = parseTime(line, zones, warnings, floatingZone)
                completed = time?.localDate(deviceZone)?.toString()
                completedTime = time?.instantForDevice(deviceZone)
            }
            "CREATED" -> created = parseIcsInstant(line.value) ?: line.value.trim()
            "LAST-MODIFIED" -> lastModified = parseIcsInstant(line.value) ?: line.value.trim()
            "SEQUENCE" -> sequence = line.value.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            "DTSTAMP" -> dtStamp = parseIcsInstant(line.value) ?: line.value.trim()
            "EXRULE" -> {
                warnings += IcsWarning.ExruleIgnored
                extra += line.raw
            }
            else -> extra += line.raw
        }
    }
    val alarms = component.children.filter { it.name == "VALARM" }.mapNotNull(::readAlarm)
    if (component.children.any { it.name != "VALARM" }) {
        warnings += IcsWarning.UnknownSubcomponent
    }
    // A rule this model cannot expand is kept verbatim so a later export does not drop it.
    rawRule?.let { extra += "RRULE:$it" }
    if (title.isBlank() && uid == null) {
        // Nothing identifies this record, but the user still learns that a part of the file was
        // dropped instead of wondering where a todo went.
        warnings.forEach { details += warningDetail(it, component.lineNumber, null) }
        return null
    }
    val status = when (rawStatus) {
        "COMPLETED" -> TodoStatus.Completed
        "IN-PROCESS" -> TodoStatus.InProcess
        "CANCELLED" -> TodoStatus.Cancelled
        "NEEDS-ACTION" -> TodoStatus.NeedsAction
        else -> when {
            percent >= 100 -> TodoStatus.Completed
            percent > 0 -> TodoStatus.InProcess
            else -> TodoStatus.NeedsAction
        }
    }
    val done = status == TodoStatus.Completed ||
        status == TodoStatus.Cancelled ||
        percent >= 100 ||
        completed != null
    val record = title.ifBlank { uid }
    warnings.forEach { details += warningDetail(it, component.lineNumber, record) }
    // The series is anchored on DTSTART, falling back to DUE the way RFC 5545 anchors a VTODO.
    val repeat = rule
    return Todo(
        id = "",
        title = title.ifBlank { uid ?: "" },
        done = done,
        priority = when {
            priority in 1..4 -> TodoPriority.High
            priority in 5..7 -> TodoPriority.Normal
            priority in 8..9 -> TodoPriority.Low
            else -> TodoPriority.Normal
        },
        start = start,
        startTime = startTime,
        due = due,
        dueTime = dueTime,
        note = note,
        location = location,
        categories = categories.distinct(),
        url = url,
        attachments = attachments,
        relatedTo = relatedTo.distinct(),
        icsUid = uid,
        createdAt = parseInstant(created)?.toEpochMilliseconds() ?: 0L,
        completedAt = completed,
        sequence = sequence,
        lastModified = lastModified,
        dtStamp = dtStamp,
        extraProperties = extra,
        status = status,
        percentComplete = percent,
        completedTime = completedTime,
        alarms = alarms.map { it.toAlarm() },
        repeat = repeat,
        rDates = rdates.map { RecurrenceDate(it.toValue(), it.periodDuration()) },
        excludedDates = exdates.map { it.localDate(deviceZone).toString() }.distinct(),
        sourceLine = component.lineNumber,
    )
}

private fun todoPriorityToIcs(priority: TodoPriority): Int = when (priority) {
    TodoPriority.High -> 1
    TodoPriority.Normal -> 5
    TodoPriority.Low -> 9
}

private fun readJournal(
    component: IcsComponent,
    deviceZone: TimeZone,
    zones: Map<String, CustomZone>,
    floatingZone: IcsZone?,
    details: MutableList<IcsWarningDetail>,
): Journal? {
    val warnings = linkedSetOf<IcsWarning>()
    var uid: String? = null
    var title = ""
    var note = ""
    var date: String? = null
    var startTime: String? = null
    var url = ""
    val categories = mutableListOf<String>()
    val attachments = mutableListOf<Attachment>()
    var created: String? = null
    var lastModified: String? = null
    var dtStamp: String? = null
    var sequence = 0
    val extra = mutableListOf<String>()
    component.properties.forEach { line ->
        when (line.name) {
            "UID" -> uid = unescapeText(line.value).trim().takeIf { it.isNotEmpty() }
            "SUMMARY" -> title = unescapeText(line.value).trim()
            "DESCRIPTION" -> note = unescapeText(line.value).trim()
            "DTSTART" -> {
                val time = parseTime(line, zones, warnings, floatingZone)
                date = when (time) {
                    is IcsTime.AllDay -> time.date.toString()
                    is IcsTime.DateTime -> time.localDate(deviceZone).toString()
                    null -> null
                }
                startTime = time?.instantForDevice(deviceZone)
                if (time == null) warnings += IcsWarning.InvalidValue
            }
            "URL" -> url = unescapeText(line.value).trim()
            "ATTACH" -> attachments += readAttachment(line)
            "CATEGORIES" -> categories += splitList(line.value).map(::unescapeText).filter { it.isNotBlank() }
            "CREATED" -> created = parseIcsInstant(line.value) ?: line.value.trim()
            "LAST-MODIFIED" -> lastModified = parseIcsInstant(line.value) ?: line.value.trim()
            "SEQUENCE" -> sequence = line.value.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            "DTSTAMP" -> dtStamp = parseIcsInstant(line.value) ?: line.value.trim()
            else -> extra += line.raw
        }
    }
    if (uid == null && title.isBlank() && note.isBlank()) {
        warnings.forEach { details += warningDetail(it, component.lineNumber, null) }
        return null
    }
    val record = title.ifBlank { uid }
    warnings.forEach { details += warningDetail(it, component.lineNumber, record) }
    return Journal(
        id = "",
        title = title,
        note = note,
        date = date,
        startTime = startTime,
        url = url,
        categories = categories.distinct(),
        attachments = attachments,
        created = created,
        lastModified = lastModified,
        dtStamp = dtStamp,
        sequence = sequence,
        icsUid = uid,
        extraProperties = extra,
        sourceLine = component.lineNumber,
    )
}

/**
 * Reads the busy periods of one `VFREEBUSY` component. Only `FREEBUSY` lines matter for a
 * free/busy query; the component's `UID`/`DTSTAMP`/`URL` metadata has no meaning once the periods
 * are in the app's hands.
 */
private fun readFreeBusy(
    component: IcsComponent,
    zones: Map<String, CustomZone>,
    floatingZone: IcsZone?,
    details: MutableList<IcsWarningDetail>,
): List<BusyInterval> {
    val warnings = linkedSetOf<IcsWarning>()
    val intervals = mutableListOf<BusyInterval>()
    component.properties.forEach { line ->
        if (line.name != "FREEBUSY") return@forEach
        val type = busyTypeOf(line.params["FBTYPE"])
        splitList(line.value).forEach { value ->
            val period = parsePeriod(
                ContentLine(line.name, line.params, value, line.raw),
                value,
                zones,
                warnings,
                floatingZone,
            ) as? IcsTime.DateTime ?: return@forEach
            val minutes = period.periodMinutes ?: return@forEach
            val startInstant = period.zone.toInstant(period.value)
            intervals += BusyInterval.of(type, startInstant, startInstant + (minutes * 60).seconds)
        }
    }
    warnings.forEach { details += warningDetail(it, component.lineNumber, null) }
    return intervals
}

/** `FBTYPE` of a `FREEBUSY` property; anything unknown reads as plain busy time. */
private fun busyTypeOf(value: String?): BusyType = when (value?.trim()?.uppercase()) {
    "FREE" -> BusyType.Free
    "BUSY-UNAVAILABLE" -> BusyType.BusyUnavailable
    "BUSY-TENTATIVE" -> BusyType.Tentative
    else -> BusyType.Busy
}

private fun StringBuilder.appendJournal(journal: Journal, index: Int, now: Instant) {    line("BEGIN:VJOURNAL")
    line("UID:${escapeText(journal.icsUid ?: "${journal.id}@crko")}")
    val stamp = parseInstant(journal.dtStamp) ?: parseInstant(journal.lastModified) ?: now
    line("DTSTAMP:${formatUtc(stamp)}")
    journal.created?.let { if (it.isNotBlank()) line("CREATED:${formatUtc(parseInstant(it) ?: stamp)}") }
    journal.lastModified?.let { if (it.isNotBlank()) line("LAST-MODIFIED:${formatUtc(parseInstant(it) ?: stamp)}") }
    if (journal.sequence > 0 || journal.lastModified != null) line("SEQUENCE:${journal.sequence.coerceAtLeast(0)}")
    journal.date?.let { dateText ->
        val parsed = parseDateText(dateText)
        if (parsed != null) {
            val start = journal.startTime?.let(::parseInstant)
            if (start != null) {
                line("DTSTART:${formatUtc(start)}")
            } else {
                line("DTSTART;VALUE=DATE:${formatDate(parsed)}")
            }
        }
    }
    if (journal.title.isNotBlank()) line("SUMMARY:${escapeText(journal.title)}")
    if (journal.note.isNotBlank()) line("DESCRIPTION:${escapeText(journal.note)}")
    if (journal.url.isNotBlank()) line("URL:${sanitizeUriValue(journal.url)}")
    appendAttachments(journal.attachments)
    if (journal.categories.isNotEmpty()) {
        line("CATEGORIES:${journal.categories.joinToString(",") { escapeText(it) }}")
    }
    journal.extraProperties.forEach { line(it) }
    line("END:VJOURNAL")
}

// ----- custom VTIMEZONE ----------------------------------------------------

/** Generation horizon for open-ended observance rules; no calendar the app schedules reaches it. */
private const val MaxTransitionYear = 2200

private class CustomZone(val id: String, val observances: List<Observance>) {
    fun toInstant(local: LocalDateTime): Instant = local.toInstant(TimeZone.UTC).minusSeconds(offsetSecondsAt(local))

    /**
     * Offset in effect at [local]. The latest transition not after it wins; when the value predates
     * every known transition the earliest one is used, which is what a zone with rules that only
     * start later implies.
     */
    fun offsetSecondsAt(local: LocalDateTime): Int {
        var latestTime: LocalDateTime? = null
        var latestOffset = 0
        var earliestTime: LocalDateTime? = null
        var earliestOffset = 0
        observances.forEach { observance ->
            observance.transitionsThrough(local.year).forEach { transition ->
                val latest = latestTime
                if (transition <= local && (latest == null || latest < transition)) {
                    latestTime = transition
                    latestOffset = observance.offsetToSeconds
                }
                val earliest = earliestTime
                if (earliest == null || earliest > transition) {
                    earliestTime = transition
                    earliestOffset = observance.offsetToSeconds
                }
            }
        }
        return if (latestTime != null) latestOffset else earliestOffset
    }
}

private class Observance(
    val start: LocalDateTime,
    /** Offset before the transition, in seconds. */
    val offsetFromSeconds: Int,
    /** Offset after the transition, in seconds. */
    val offsetToSeconds: Int,
    val rule: RepeatRule?,
    /** Explicit `RDATE` transitions. */
    val rDates: List<LocalDateTime> = emptyList(),
) {
    /**
     * Transitions generated so far. One imported file resolves hundreds of values against the same
     * zone, and an observance anchored in 1970 would otherwise rebuild every year of its rule for
     * every single value.
     */
    private val transitions = mutableListOf(start).apply { addAll(rDates) }

    // One year before the anchor, so the first query also generates the anchor year's own transitions.
    private var generatedThroughYear = start.year - 1

    /** Every transition of this observance up to and including the year [year] + 1. */
    fun transitionsThrough(year: Int): List<LocalDateTime> {
        val repeat = rule
        // Rules without UNTIL or COUNT are open ended, so generation stops at a horizon far beyond
        // any calendar the app schedules; later values keep the offset of the last known transition.
        val targetYear = (year + 1).coerceAtMost(MaxTransitionYear)
        if (repeat != null && targetYear > generatedThroughYear) {
            val anchor = start.date
            var candidateYear = maxOf(generatedThroughYear + 1, anchor.year)
            while (candidateYear <= targetYear) {
                val candidates = when (repeat.frequency) {
                    RepeatFrequency.Yearly -> yearCandidates(repeat, anchor, candidateYear, withSetPos = true)
                    RepeatFrequency.Monthly -> (1..12).flatMap { month ->
                        monthCandidates(repeat, anchor, candidateYear, month)
                    }
                    else -> emptyList()
                }
                transitions += candidates.filter { date ->
                    date >= anchor && withinUntil(date) && withinCount(repeat, anchor, date)
                }.map { LocalDateTime(it, start.time) }
                candidateYear++
            }
            generatedThroughYear = targetYear
        }
        return transitions
    }

    private fun withinUntil(date: LocalDate): Boolean {
        val until = rule?.until ?: return true
        val local = LocalDateTime(date, start.time)
        return when (until) {
            is IcsTimeValue.Date -> date <= until.date
            is IcsTimeValue.Floating -> local <= until.dateTime
            is IcsTimeValue.Zoned -> local <= until.dateTime
            is IcsTimeValue.Utc ->
                local.toInstant(TimeZone.UTC).minusSeconds(offsetFromSeconds) <= until.instant
        }
    }

    private fun withinCount(repeat: RepeatRule, anchor: LocalDate, date: LocalDate): Boolean {
        val count = repeat.count ?: return true
        val ordinal = occurrenceOrdinal(repeat, anchor, date, count) ?: return false
        return ordinal <= count
    }
}

private fun parseCustomZone(component: IcsComponent): CustomZone? {
    val id = component.first("TZID")?.value?.trim()?.removePrefix("/") ?: return null
    val observances = component.children.mapNotNull { child ->
        if (child.name != "STANDARD" && child.name != "DAYLIGHT") return@mapNotNull null
        val startLine = child.first("DTSTART") ?: return@mapNotNull null
        val start = parseTime(startLine, emptyMap(), mutableSetOf()) as? IcsTime.DateTime ?: return@mapNotNull null
        val from = child.first("TZOFFSETFROM")?.value?.let(::parseUtcOffset) ?: 0
        val to = child.first("TZOFFSETTO")?.value?.let(::parseUtcOffset) ?: from
        val rule = child.first("RRULE")?.value?.let { parseRule(it, emptyMap(), mutableSetOf()) }
        val rDates = child.all("RDATE").flatMap { line ->
            splitList(line.value).mapNotNull { value ->
                parseTime(
                    ContentLine("RDATE", line.params, value, line.raw),
                    emptyMap(),
                    mutableSetOf(),
                ) as? IcsTime.DateTime
            }
        }.map { it.value }
        Observance(start.value, from, to, rule, rDates)
    }
    if (observances.isEmpty()) return null
    return CustomZone(id, observances)
}

/** `TZOFFSETFROM`/`TZOFFSETTO` in seconds; RFC 5545 also allows an optional seconds part. */
private fun parseUtcOffset(value: String): Int? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val sign = if (text.startsWith('-')) -1 else 1
    val digits = text.removePrefix("+").removePrefix("-").filter { it.isDigit() }
    if (digits.length < 4) return null
    val hours = digits.substring(0, 2).toIntOrNull() ?: return null
    val minutes = digits.substring(2, 4).toIntOrNull() ?: return null
    val seconds = if (digits.length >= 6) digits.substring(4, 6).toIntOrNull() ?: 0 else 0
    if (minutes !in 0..59 || seconds !in 0..59) return null
    return sign * (hours * 3600 + minutes * 60 + seconds)
}

private fun formatDate(date: LocalDate): String = buildString {
    append(date.year.toString().padStart(4, '0'))
    append(date.month.number.toString().padStart(2, '0'))
    append(date.day.toString().padStart(2, '0'))
}

private fun formatDateTime(date: LocalDate, minutes: Int, seconds: Int = 0): String {
    val resolved = if (minutes >= 24 * 60) date.plusDays(1) to (minutes - 24 * 60) else date to minutes
    return "${formatDate(resolved.first)}T${formatTime(resolved.second, seconds)}"
}

private fun formatTime(minutes: Int, seconds: Int = 0): String =
    "${(minutes / 60).toString().padStart(2, '0')}${(minutes % 60).toString().padStart(2, '0')}${seconds.coerceIn(0, 59).toString().padStart(2, '0')}"

internal fun formatUtc(instant: Instant): String {
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

private fun parseInstant(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun parseIcsInstant(value: String): String? {
    val line = ContentLine("VALUE", emptyMap(), value.trim(), value.trim())
    val time = parseTime(line, emptyMap(), mutableSetOf()) as? IcsTime.DateTime ?: return null
    return time.zone.toInstant(time.value).toString()
}

/** Trigger duration with the RFC sign: positive minutes mean before the start. */
private fun formatTriggerDuration(minutes: Int): String = when {
    minutes > 0 -> "-${formatDuration(minutes)}"
    minutes < 0 -> "+${formatDuration(-minutes)}"
    else -> "PT0M"
}

private fun formatDuration(minutes: Int): String {
    if (minutes == 0) return "PT0M"
    val sign = if (minutes < 0) "-" else ""
    var remaining = kotlin.math.abs(minutes)
    val days = remaining / (24 * 60)
    remaining %= 24 * 60
    val hours = remaining / 60
    remaining %= 60
    return buildString {
        append(sign).append('P')
        if (days > 0) append(days).append('D')
        if (hours > 0 || remaining > 0 || days == 0) {
            append('T')
            if (hours > 0) append(hours).append('H')
            if (remaining > 0 || (hours == 0 && days == 0)) append(remaining).append('M')
        }
    }
}

/** ISO-8601 duration with second precision, the form an `RDATE;VALUE=PERIOD` length is written in. */
private fun formatDurationSeconds(totalSeconds: Long): String {
    if (totalSeconds == 0L) return "PT0S"
    val sign = if (totalSeconds < 0) "-" else ""
    var remaining = kotlin.math.abs(totalSeconds)
    val days = remaining / 86_400
    remaining %= 86_400
    val hours = remaining / 3_600
    remaining %= 3_600
    val minutes = remaining / 60
    val seconds = remaining % 60
    return buildString {
        append(sign).append('P')
        if (days > 0) append(days).append('D')
        if (hours > 0 || minutes > 0 || seconds > 0 || days == 0L) {
            append('T')
            if (hours > 0) append(hours).append('H')
            if (minutes > 0) append(minutes).append('M')
            if (seconds > 0 || (hours == 0L && minutes == 0L)) append(seconds).append('S')
        }
    }
}

private fun formatIcsLocalDateTime(value: LocalDateTime, utc: Boolean): String = buildString {
    append(formatDate(value.date))
    append('T')
    append(value.time.hour.toString().padStart(2, '0'))
    append(value.time.minute.toString().padStart(2, '0'))
    append(value.time.second.toString().padStart(2, '0'))
    if (utc) append('Z')
}

private fun Instant.minusSeconds(seconds: Int): Instant = this - seconds.seconds

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

@OptIn(ExperimentalEncodingApi::class)
private fun StringBuilder.appendAttachments(attachments: List<Attachment>) {
    attachments.forEach { attachment ->
        // A stored payload is re-encoded at export time. The record keeps the parameter head of the
        // original line, so FMTTYPE and any other parameters survive the round trip without the
        // base64 blob ever living inside the serialized state.
        val stored = attachment.storedFile?.takeIf { it.isNotBlank() }
        if (attachment.binary && attachment.data.isBlank() && stored != null) {
            val bytes = stored.let(AppAttachmentStore::read)
            if (bytes != null) {
                val head = attachment.raw.ifBlank {
                    buildString {
                        append("ATTACH;ENCODING=BASE64")
                        if (attachment.mimeType.isNotBlank()) append(";FMTTYPE=${attachment.mimeType}")
                        if (attachment.name.isNotBlank()) append(";FILENAME=${escapeText(attachment.name)}")
                    }
                }
                line("$head:${Base64.encode(bytes)}")
            }
            // A payload whose file is gone cannot be invented; the attachment is left out instead
            // of exporting an empty value every client would reject.
            return@forEach
        }
        val raw = attachment.raw
        line(raw.ifBlank { "ATTACH:${sanitizeUriValue(attachment.uri)}" })
    }
}

/**
 * `url`, `caladdress` and `geo` are not TEXT values, so `,`, `;` and `\` have to stay literal;
 * escaping them breaks links in every client that reads the file. Only control characters are
 * removed, because those would inject additional content lines into the exported calendar.
 */
private fun sanitizeUriValue(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        if (ch.code >= 0x20 && ch.code != 0x7F) append(ch)
    }
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
