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
import kotlin.concurrent.atomics.AtomicReference
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

    /**
     * A `RECURRENCE-ID;RANGE=THISANDFUTURE` exception carried no recurrence of its own, so nothing
     * replaces the master's later instances and the exception covers its slot only.
     */
    UnappliedRecurrenceRange,
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
    // Any record kind can name a zone — leaving the todos and alarm triggers out exported a `TZID`
    // reference with no `VTIMEZONE` beside it, which no client could resolve.
    (events.map(Event::referencedTimeZoneIds) +
        todos.map(Todo::referencedTimeZoneIds) +
        journals.map(Journal::referencedTimeZoneIds)).forEach { ids ->
        ids.forEach { id ->
            if (id.equals("UTC", ignoreCase = true) || id in zones) return@forEach
            if (isPlatformTimeZone(id)) return@forEach
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

/** Every `TZID` a record names, including its recurrence set, exception slot and alarm triggers. */
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
    alarms.forEach { alarm -> (alarm.trigger as? AlarmTrigger.Absolute)?.let { add(it.value) } }
}

/** `TZID` values of a `VTODO`: its own times, the published recurrence dates and its alarms. */
private fun Todo.referencedTimeZoneIds(): Set<String> = buildSet {
    fun add(value: IcsTimeValue?) {
        (value as? IcsTimeValue.Zoned)?.let { add(it.tzid) }
    }
    add(start)
    add(due)
    add(repeat?.until)
    rDates.forEach { add(it.value) }
    alarms.forEach { alarm -> (alarm.trigger as? AlarmTrigger.Absolute)?.let { add(it.value) } }
}

/** `TZID` values of a `VJOURNAL`. */
private fun Journal.referencedTimeZoneIds(): Set<String> = buildSet {
    fun add(value: IcsTimeValue?) {
        (value as? IcsTimeValue.Zoned)?.let { add(it.tzid) }
    }
    add(start)
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
    timeZone: TimeZone,
    untitledTitle: String,
): IcsImport = importCalendarLines(unfold(text).asSequence(), timeZone, untitledTitle)

/**
 * Same as [importCalendar] for lines that already arrived incrementally, so a file never has to be
 * in memory as one decoded text (see `CalendarTextReader`).
 */
internal fun importCalendarLines(
    lines: Sequence<UnfoldedLine>,
    timeZone: TimeZone,
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
    timeZone: TimeZone,
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
    attendee: CalAddress,
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
    event.organizer?.takeIf { it.calAddress.isNotBlank() }?.let { line(it.toLine("ORGANIZER")) }
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
    appendComponentHeader(event, uid, now)
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

    appendComponentText(event)
    if (event.location.isNotBlank()) {
        line("LOCATION${event.textParameters.textPropertyParameters("LOCATION")}:${escapeText(event.location)}")
    }
    if (!event.geo.isNullOrBlank()) line("GEO:${sanitizeUriValue(event.geo)}")
    if (event.eventClass != null) line("CLASS:${event.eventClass.name.uppercase()}")
    if (event.status != EventStatus.Confirmed) line("STATUS:${event.status.name.uppercase()}")
    if (event.transparency == EventTransparency.Transparent) line("TRANSP:TRANSPARENT")
    event.priority?.let { if (it in 0..9) line("PRIORITY:$it") }
    event.organizer?.takeIf { it.calAddress.isNotBlank() }?.let { line(it.toLine("ORGANIZER")) }
    event.attendees.forEach { if (it.calAddress.isNotBlank()) line(it.toLine()) }

    event.recurrence?.let { appendRecurrence(it, event) }

    appendAlarms(event.alarms)
    // An unknown enum value kept in the extras is the only writer of its line unless the user
    // changed the enum; then the derived line replaces it instead of duplicating it.
    appendComponentExtras(
        event,
        buildSet {
            if (event.status != EventStatus.Confirmed) add("STATUS")
            if (event.transparency == EventTransparency.Transparent) add("TRANSP")
            if (event.eventClass != null) add("CLASS")
        },
    )
    line("END:VEVENT")
}

/** `UID`, timestamps, revision counter and the text properties every component carries. */
private fun StringBuilder.appendComponentHeader(
    component: CalendarComponent,
    uid: String,
    now: Instant,
    created: Instant? = null,
) {
    line("UID:${escapeText(uid)}")
    val stamp = parseInstant(component.dtStamp) ?: parseInstant(component.lastModified) ?: now
    line("DTSTAMP:${formatUtc(stamp)}")
    val createdAt = created ?: component.created?.let(::parseInstant)
    createdAt?.let { line("CREATED:${formatUtc(it)}") }
    component.lastModified?.let { line("LAST-MODIFIED:${formatUtc(parseInstant(it) ?: stamp)}") }
    if (component.sequence > 0 || component.lastModified != null) {
        line("SEQUENCE:${component.sequence.coerceAtLeast(0)}")
    }
}

/** `SUMMARY`, `DESCRIPTION`, `URL`, `CATEGORIES` and `ATTACH` of any component. */
private fun StringBuilder.appendComponentText(component: CalendarComponent) {
    line("SUMMARY${component.textParameters.textPropertyParameters("SUMMARY")}:${escapeText(component.title)}")
    if (component.note.isNotBlank()) {
        line("DESCRIPTION${component.textParameters.textPropertyParameters("DESCRIPTION")}:${escapeText(component.note)}")
    }
    if (component.categories.isNotEmpty()) {
        val categories = component.textParameters.textPropertyParameters("CATEGORIES")
        line("CATEGORIES$categories:${component.categories.joinToString(",") { escapeText(it) }}")
    }
    if (component.url.isNotBlank()) {
        line("URL${component.textParameters.textPropertyParameters("URL")}:${sanitizeUriValue(component.url)}")
    }
    appendAttachments(component.attachments)
}

/**
 * Unknown lines kept on every component, written last so they survive a round trip. [suppress]
 * names the properties the derived export also writes: an unknown `STATUS` lives in the extras and
 * must not appear a second time next to the line the enum path produced.
 */
private fun StringBuilder.appendComponentExtras(component: CalendarComponent, suppress: Set<String> = emptySet()) {
    component.extraProperties.forEach { extra ->
        if (extraPropertyName(extra) !in suppress) line(extra)
    }
}

/** Property name of a stored raw content line, for matching it against the derived lines. */
private fun extraPropertyName(raw: String): String =
    raw.substringBefore(':').substringBefore(';').trim().uppercase()

/** Writes `VALARM` subcomponents with their relative or absolute trigger. */
private fun StringBuilder.appendAlarms(alarms: List<Alarm>) {
    alarms.forEach { alarm ->
        line("BEGIN:VALARM")
        line("ACTION:${alarm.action.ifBlank { "DISPLAY" }}")
        when (val trigger = alarm.trigger) {
            is AlarmTrigger.Absolute -> appendAbsoluteTrigger(trigger.value)
            is AlarmTrigger.Relative -> {
                val related = if (trigger.related == AlarmRelated.End) ";RELATED=END" else ""
                line("TRIGGER$related:${formatSignedDuration(trigger.duration)}")
            }
        }
        alarm.repeat?.let { line("REPEAT:$it") }
        alarm.duration?.let { line("DURATION:${formatDurationSeconds(kotlin.math.abs(it.inWholeSeconds))}") }
        if (alarm.description.isNotBlank()) line("DESCRIPTION:${escapeText(alarm.description)}")
        alarm.extraProperties.forEach { line(it) }
        line("END:VALARM")
    }
}

/** Absolute `TRIGGER;VALUE=DATE-TIME` values keep their explicit value type on export. */
private fun StringBuilder.appendAbsoluteTrigger(value: IcsTimeValue) {
    when (value) {
        is IcsTimeValue.Date -> appendTimeLine("TRIGGER", value)
        is IcsTimeValue.Floating ->
            line("TRIGGER;VALUE=DATE-TIME:${formatIcsLocalDateTime(value.dateTime, utc = false)}")
        is IcsTimeValue.Utc -> line("TRIGGER;VALUE=DATE-TIME:${formatUtc(value.instant)}")
        is IcsTimeValue.Zoned -> {
            if (value.tzid.equals("UTC", ignoreCase = true)) {
                line("TRIGGER;VALUE=DATE-TIME:${formatUtc(value.dateTime.toInstant(TimeZone.UTC))}")
            } else {
                line(
                    "TRIGGER;VALUE=DATE-TIME;TZID=${icsParameterValue(value.tzid)}:" +
                        formatIcsLocalDateTime(value.dateTime, utc = false),
                )
            }
        }
    }
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
                line("$name;TZID=${icsParameterValue(value.tzid)}:${formatIcsLocalDateTime(value.dateTime, utc = false)}")
            }
        }
        is IcsTimeValue.Utc -> line("$name:${formatUtc(value.instant)}")
    }
}

private fun StringBuilder.appendTodo(todo: Todo, index: Int, now: Instant) {
    line("BEGIN:VTODO")
    val createdFallback = todo.createdAt.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
    appendComponentHeader(todo, todo.icsUid ?: "${todo.id}@crko", now, createdFallback)
    todo.start?.let { appendTimeLine("DTSTART", it) }
    todo.due?.let { appendTimeLine("DUE", it) }
    appendComponentText(todo)
    if (todo.location.isNotBlank()) {
        line("LOCATION${todo.textParameters.textPropertyParameters("LOCATION")}:${escapeText(todo.location)}")
    }
    todo.relatedTo.forEach { related ->
        val reltype = related.reltype.uppercase()
        val parameter = if (reltype == "PARENT" || reltype.isBlank()) "" else ";RELTYPE=$reltype"
        line("RELATED-TO$parameter:${escapeText(related.uid)}")
    }
    todo.repeat?.let { rule -> line("RRULE:${rule.raw ?: ruleValue(rule, todo.repeatStartValue())}") }
    // An added or excluded instance belongs to the recurrence set, which a `VTODO` may carry without
    // a rule at all; writing them only next to an `RRULE` dropped them from such a todo.
    appendTodoRDate(todo)
    appendTodoExDate(todo)
    val statusText = when (todo.status) {
        TodoStatus.NeedsAction -> "NEEDS-ACTION"
        TodoStatus.InProcess -> "IN-PROCESS"
        TodoStatus.Completed -> "COMPLETED"
        TodoStatus.Cancelled -> "CANCELLED"
    }
    val percent = when {
        todo.percentComplete in 1..100 -> todo.percentComplete
        todo.status == TodoStatus.Completed -> 100
        else -> 0
    }
    // An unknown STATUS kept in the extras stands in for the derived line while the todo carries no
    // other status; once the user picks a real one, the derived line replaces it instead of
    // appearing next to it (RFC 5545 allows STATUS only once).
    val extraCarriesStatus = todo.extraProperties.any { extraPropertyName(it) == "STATUS" }
    if (todo.status != TodoStatus.NeedsAction || !extraCarriesStatus) line("STATUS:$statusText")
    line("PERCENT-COMPLETE:$percent")
    if (todo.status == TodoStatus.Completed) {
        todo.completed?.let { line("COMPLETED:${formatUtc(it)}") }
    }
    todo.priority?.let { if (it in 0..9) line("PRIORITY:$it") }
    appendAlarms(todo.alarms)
    appendComponentExtras(
        todo,
        if (extraCarriesStatus && todo.status == TodoStatus.NeedsAction) emptySet() else setOf("STATUS"),
    )
    line("END:VTODO")
}

/** Start value a todo series' `UNTIL` is rendered against; a date-only todo keeps the DATE form. */
private fun Todo.repeatStartValue(): IcsTimeValue? = start ?: due

/** `RDATE` lines of a todo series, keeping each value's own time and length. */
private fun StringBuilder.appendTodoRDate(todo: Todo) {
    appendRecurrenceDates(todo.rDates)
}

private fun StringBuilder.appendTodoExDate(todo: Todo) {
    if (todo.excludedDates.isEmpty()) return
    val anchor = todo.seriesAnchor ?: return
    val rule = todo.repeat
    // The exclusions are matched against the untouched set: an EXDATE must not hide itself.
    val recurrence = Recurrence(rule = rule, rdates = todo.rDates)
    // Only values that really are instances of the set belong in EXDATE; without a rule the added
    // dates and the anchor are the only ones a date-only exclusion can remove. The todo's own value
    // type decides where a UTC `UNTIL` bounds the series, exactly as in the expansion queries: left
    // out, the export folded the candidate day through the device zone and wrote (or dropped) the
    // last day's `EXDATE` differently on every machine.
    val values = todo.excludedDates
        .filter { isRuleOccurrence(anchor, rule, recurrence, it.wallDate, anchorValue = todo.start ?: todo.due) }
    values.groupBy { recurrenceValueParameters(it) }.forEach { (params, group) ->
        val written = group.distinct().sortedBy { it.wallDateTime }
        line("EXDATE$params:${written.joinToString(",") { formatTimeValue(it) }}")
    }
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
    rdates.groupBy { recurrenceValueParameters(it.value, period = it.duration != null) }
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
    values.groupBy { recurrenceValueParameters(it) }.forEach { (params, group) ->
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

private fun recurrenceValueParameters(value: IcsTimeValue, period: Boolean = false): String = when {
    period -> ";VALUE=PERIOD"
    value is IcsTimeValue.Date -> ";VALUE=DATE"
    value is IcsTimeValue.Zoned ->
        if (value.tzid.equals("UTC", ignoreCase = true)) "" else ";TZID=${icsParameterValue(value.tzid)}"
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
    // RFC 5545 wants the UTC form whenever `DTSTART` carries a `TZID`, and a zone only a
    // calendar-level `VTIMEZONE` describes is resolvable here too: a bare local time would be read
    // as floating by the next import and shift the last instance by the zone offset.
    until is IcsTimeValue.Zoned -> {
        val instant = zonedInstantOrNull(until.dateTime, until.tzid)
        if (instant != null) formatUtc(instant) else formatIcsLocalDateTime(until.dateTime, utc = false)
    }
    // Date-only UNTIL: the last occurrence on that date at the component's own wall-clock time.
    until is IcsTimeValue.Date -> {
        val time = start?.wallDateTime?.time ?: LocalTime(0, 0)
        val last = LocalDateTime(until.date, time)
        when (start) {
            is IcsTimeValue.Zoned -> {
                val instant = zonedInstantOrNull(last, start.tzid)
                if (instant != null) formatUtc(instant) else formatIcsLocalDateTime(last, utc = false)
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
    val colon = valueSeparatorIndex(line)
    if (colon <= 0) return null
    val head = line.substring(0, colon)
    var value = line.substring(colon + 1)
    val parts = splitParameters(head)
    if (parts.isEmpty()) return null
    val params = parts.drop(1).mapNotNull { part ->
        val equals = part.indexOf('=')
        if (equals <= 0) null
        else part.substring(0, equals).trim().uppercase() to unquoteParameterValue(part.substring(equals + 1))
    }.toMap()
    if (params["ENCODING"].equals("QUOTED-PRINTABLE", ignoreCase = true)) {
        value = decodeQuotedPrintable(value, params["CHARSET"])
    }
    return ContentLine(parts.first().trim().uppercase(), params, value, line, unfolded.lineNumber)
}

/**
 * Index of the `:` that separates the parameters from the value. RFC 5545 escapes a double quote
 * inside a quoted parameter value as `\"`; without honouring the escape, `CN=Bob "B" Smith` and
 * every other stray quote kept the scan looking for a separator past the real one and dropped the
 * whole content line.
 */
private fun valueSeparatorIndex(line: String): Int {
    var quoted = false
    var index = 0
    while (index < line.length) {
        val ch = line[index]
        if (quoted && ch == '\\' && index + 1 < line.length) {
            index += 2
            continue
        }
        when {
            ch == '"' -> quoted = !quoted
            ch == ':' && !quoted -> return index
        }
        index++
    }
    return -1
}

/**
 * A quoted parameter value loses its surrounding quotes and, inside them, the backslash that
 * protects a double quote. Unquoted values are returned as they are.
 */
private fun unquoteParameterValue(text: String): String {
    val trimmed = text.trim()
    if (!isSingleQuotedValue(trimmed)) return trimmed
    val body = trimmed.substring(1, trimmed.length - 1)
    if (!body.contains('\\')) return body
    return buildString(body.length) {
        var index = 0
        while (index < body.length) {
            val ch = body[index]
            if (ch == '\\' && index + 1 < body.length && body[index + 1] == '"') {
                append('"')
                index += 2
            } else {
                append(ch)
                index++
            }
        }
    }
}

/**
 * Whether the value is one quoted string: it opens with a `"` and the quote that closes it is its
 * last character. A comma-separated list of quoted values (`DELEGATED-TO="mailto:a","mailto:b"`) is
 * not one, and keeping its quotes is what lets the list be split on the commas that sit outside them
 * instead of inside a quoted address.
 */
private fun isSingleQuotedValue(text: String): Boolean {
    if (text.length < 2 || text.first() != '"' || text.last() != '"') return false
    var index = 1
    while (index < text.length - 1) {
        when (text[index]) {
            '"' -> return false
            '\\' -> index++
        }
        index++
    }
    return true
}

private fun splitParameters(head: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < head.length) {
        val ch = head[index]
        when {
            // The escaped quote is part of the value, not the end of the quoted section.
            quoted && ch == '\\' && index + 1 < head.length -> {
                current.append(ch).append(head[index + 1])
                index += 2
                continue
            }
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
        index++
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
        val periodSeconds: Long? = null,
    ) : IcsTime
}

/**
 * Length carried by an `RDATE;VALUE=PERIOD` value, if any. RFC 5545 durations are second-precise, so
 * the length is carried in seconds rather than rounded to whole minutes on the way in.
 */
private fun IcsTime.periodDuration(): Duration? =
    (this as? IcsTime.DateTime)?.periodSeconds?.takeIf { it > 0L }?.seconds

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

/** Wall clock of [instant] in the zone [tzid] names, calendar-level `VTIMEZONE` included. */
internal fun zoneDateTimeOrNull(instant: Instant, tzid: String): LocalDateTime? = when (val zone = resolveZone(tzid)) {
    is IcsZone.Platform -> instant.toLocalDateTime(zone.zone)
    is IcsZone.Custom -> zone.zone.toLocalDateTime(instant)
    IcsZone.Utc -> instant.toLocalDateTime(TimeZone.UTC)
    IcsZone.Floating, null -> null
}

/** Whether the platform resolves [tzid] on its own, so no `VTIMEZONE` block needs to be stored. */
private fun isPlatformTimeZone(tzid: String): Boolean =
    tzid.equals("UTC", ignoreCase = true) || tzid.equals("GMT", ignoreCase = true) ||
        resolvePlatformZone(tzid) != null

internal fun normalizeTimeZoneId(tzid: String): String = tzid.trim().removePrefix("/")

/**
 * Scope of definitions that name no stored calendar: a file being parsed, an import preview. A record
 * only resolves through them while no calendar declares the same `TZID`.
 */
internal const val UnscopedTimeZoneScope = "\u0000unscoped"

/**
 * Adds the calendar-level definitions of unknown zones to the runtime lookup cache. [scope] names the
 * calendar (or preview) the definitions were declared by, so a second calendar that declares the same
 * `TZID` differently does not overwrite the first one's interpretation.
 */
internal fun registerIcsTimeZones(timeZones: Iterable<CalendarTimeZone>, scope: String = UnscopedTimeZoneScope) {
    timeZones.forEach { IcsTimeZoneCache.register(scope, it.tzid, it.definition) }
}

/**
 * Rebuilds the calendar scopes of the lookup cache from the stored state. This is what a deleted
 * subscription or a discarded import preview needs: definitions only ever leave the cache through this
 * call, so a scope no longer in [calendars] stops resolving.
 */
internal fun replaceIcsTimeZones(calendars: List<Calendar>) {
    IcsTimeZoneCache.replaceCalendarScopes(calendars)
}

/** Stored `VTIMEZONE` text of an unknown zone, for an export that only carries [Event] values. */
internal fun icsTimeZoneDefinition(tzid: String, calendarId: String? = null): String? =
    IcsTimeZoneCache.definitionOf(tzid, calendarId)

/**
 * Every `TZID` more than one calendar declares with a different definition. RFC 5545 scopes a `TZID`
 * to its own `VCALENDAR`, so such a name is ambiguous for the record-level projections and only the
 * per-calendar lookup via [icsTimeZoneDefinition] is exact.
 */
internal fun icsTimeZoneConflicts(): Set<String> = IcsTimeZoneCache.conflicts()

/**
 * Process-wide lookup of the calendar-level definitions. [Calendar.timeZones] remains the persisted
 * source of truth; this cache only lets the `Event` display projections resolve unknown zones
 * without threading the whole state through every recurrence query. Parsed definitions are kept so
 * hundreds of values from one file share a single interpretation of their `VTIMEZONE`.
 *
 * Entries live per declaring scope: two calendars naming the same `TZID` keep both definitions, and a
 * record whose calendar is not known resolves through the first scope declaring it — the state order,
 * so the local calendar's copy wins and a later import cannot silently move events that are already
 * stored.
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private object IcsTimeZoneCache {
    /**
     * scope id -> (`TZID` -> raw `VTIMEZONE` text), in registration order. The map itself is never
     * mutated: writers build a replacement snapshot and swap it through an atomic reference, so a
     * lookup running on another thread (a background import, export or reminder pass) iterates a
     * stable map instead of racing a rebuild.
     */
    private val scopes = AtomicReference<Map<String, Map<String, String>>>(emptyMap())

    /** Parsed zones keyed by the exact text they were read from, so two definitions never share a parse. */
    private val parsed = AtomicReference<Map<Pair<String, String>, CustomZone?>>(emptyMap())

    fun register(scope: String, tzid: String, definition: String) {
        val id = normalizeTimeZoneId(tzid)
        if (id.isEmpty() || definition.isBlank() || isPlatformTimeZone(id)) return
        while (true) {
            val current = scopes.load()
            val entries = current[scope]
            if (entries?.get(id) == definition) return
            val updated = current + (scope to (entries ?: emptyMap()) + (id to definition))
            if (scopes.compareAndSet(current, updated)) return
        }
    }

    fun replaceCalendarScopes(calendars: List<Calendar>) {
        val rebuilt = LinkedHashMap<String, Map<String, String>>()
        calendars.forEach { calendar ->
            val entries = LinkedHashMap<String, String>()
            calendar.timeZones.forEach { zone ->
                val id = normalizeTimeZoneId(zone.tzid)
                if (id.isNotEmpty() && zone.definition.isNotBlank() && !isPlatformTimeZone(id)) {
                    entries[id] = zone.definition
                }
            }
            rebuilt[calendar.id] = entries
        }
        // Registrations that name no calendar (a file being parsed, a preview not applied yet) live only
        // until the next write: the stored calendars are the source of truth, so a definition nobody
        // declares any more — a deleted subscription, a discarded preview — stops resolving here.
        scopes.store(rebuilt)
        val alive = rebuilt.values.flatMapTo(mutableSetOf()) { entries -> entries.map { (id, text) -> id to text } }
        while (true) {
            val current = parsed.load()
            if (current.keys == alive) return
            val kept = current.filterKeys { it in alive }.toMap()
            if (parsed.compareAndSet(current, kept)) return
        }
    }

    fun definitionOf(tzid: String, scope: String?): String? {
        val id = normalizeTimeZoneId(tzid)
        val snapshot = scopes.load()
        scope?.let { named -> snapshot[named]?.get(id) }?.let { return it }
        snapshot.values.forEach { entries -> entries[id]?.let { return it } }
        return null
    }

    fun conflicts(): Set<String> {
        val first = mutableMapOf<String, String>()
        val out = linkedSetOf<String>()
        scopes.load().values.forEach { entries ->
            entries.forEach { (id, definition) ->
                val known = first[id]
                when {
                    known == null -> first[id] = definition
                    known != definition -> out += id
                }
            }
        }
        return out
    }

    fun customZoneOrNull(tzid: String): CustomZone? {
        val definition = definitionOf(tzid, null) ?: return null
        val key = normalizeTimeZoneId(tzid) to definition
        parsed.load().let { if (it.containsKey(key)) return it[key] }
        val zone = customZoneFromText(definition)
        while (true) {
            val current = parsed.load()
            if (current.containsKey(key)) return current[key]
            if (parsed.compareAndSet(current, current + (key to zone))) return zone
        }
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
 *
 * A prefix is only stripped from an id with more than two path segments: a real IANA id is
 * `Region/City`, so a third segment belongs to the producer. `Custom/UTC` has two and is an unknown
 * zone that merely *ends* in a real name — reading it as the platform's UTC would drop the offsets
 * its own `VTIMEZONE` declares.
 *
 * The same reason keeps the stripped suffix to at least one more segment: after removing a producer's
 * prefix the remainder still has to read as `Region/City`, otherwise
 * `example.org/20050126_1/Custom/UTC` loses everything but its last token and is resolved as the
 * platform's `UTC` — which is the very defect above, only one segment deeper.
 */
private fun resolvePlatformZone(tzid: String): IcsZone.Platform? {
    val normalized = tzid.trim().removePrefix("/")
    if (normalized.isEmpty()) return null
    val candidates = buildList {
        add(normalized)
        windowsTimeZones[normalized.lowercase()]?.let { add(it) }
        if (normalized.count { it == '/' } >= 2) {
            var slash = normalized.indexOf('/')
            while (slash >= 0 && slash + 1 < normalized.length) {
                val suffix = normalized.substring(slash + 1)
                if (suffix.contains('/')) add(suffix)
                slash = normalized.indexOf('/', slash + 1)
            }
        }
    }
    candidates.forEach { id ->
        runCatching { TimeZone.of(id) }.getOrNull()?.let { return IcsZone.Platform(id, it) }
    }
    return null
}

/**
 * Content lines every component shares, read once by `VEVENT`, `VTODO` and `VJOURNAL` parsers.
 * `handle` returns false for a line the component has to interpret itself.
 */
private class CommonFields {
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
private fun Map<String, String>.textPropertyParameters(name: String): String =
    this[name]?.let { ";$it" }.orEmpty()

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
    val organizer: CalAddress? = null,
    val attendees: List<CalAddress> = emptyList(),
    val start: IcsTime? = null,
    val end: IcsTime? = null,
    /** `DURATION` of the record, in the second precision RFC 5545 writes it with. */
    val duration: Duration? = null,
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
    val alarms: List<Alarm> = emptyList(),
    val extraProperties: List<String> = emptyList(),
    /** Parameters of the TEXT properties, carried to [Event.textParameters] on the way out. */
    val textParameters: Map<String, String> = emptyMap(),
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
    val common = CommonFields()
    var location = ""
    var geo: String? = null
    var status: String? = null
    var transparency: String? = null
    var eventClass: String? = null
    var priority: Int? = null
    var organizer: CalAddress? = null
    val attendees = mutableListOf<CalAddress>()
    var start: IcsTime? = null
    var end: IcsTime? = null
    var duration: Duration? = null
    var recurrenceId: IcsTime? = null
    var recurrenceRange: String? = null
    var rule: RepeatRule? = null
    var rawRule: String? = null
    val exdates = mutableListOf<IcsTime>()
    val rdates = mutableListOf<IcsTime>()
    val extra = mutableListOf<String>()

    component.properties.forEach { line ->
        when (line.name) {
            "LOCATION" -> {
                location = unescapeText(line.value).trim()
                common.captureTextParameter(line)
            }
            "GEO" -> geo = line.value.trim().takeIf { it.isNotEmpty() }
            "STATUS" -> {
                status = line.value.trim().uppercase()
                // RFC 5545 allows X- extension values; the model has no slot for them, so the raw
                // line is kept for the export instead of silently normalizing what the file said.
                if (status != null && status !in listOf("CONFIRMED", "TENTATIVE", "CANCELLED")) extra += line.raw
            }
            "TRANSP" -> {
                transparency = line.value.trim().uppercase()
                if (transparency != null && transparency !in listOf("OPAQUE", "TRANSPARENT")) extra += line.raw
            }
            "CLASS" -> {
                eventClass = line.value.trim().uppercase()
                if (rawClass(eventClass) == null && eventClass?.isNotEmpty() == true) extra += line.raw
            }
            "PRIORITY" -> priority = line.value.trim().toIntOrNull()?.coerceIn(0, 9)
            "ORGANIZER" -> organizer = CalAddress.fromContentLine(line)
            "ATTENDEE" -> CalAddress.fromContentLine(line)
                .takeIf { it.calAddress.isNotBlank() }
                ?.let { attendees += it }
            "DTSTART" -> start = parseTime(line, zones, warnings, floatingZone)
            "DTEND" -> end = parseTime(line, zones, warnings, floatingZone)
            "DURATION" -> duration = parseDurationSeconds(line.value)?.seconds
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
            "PRODID", "VERSION", "CALSCALE", "METHOD", "X-WR-CALNAME" -> Unit
            else -> if (!common.handle(line)) extra += line.raw
        }
    }

    val alarms = component.children
        .filter { it.name == "VALARM" }
        .mapNotNull { readAlarm(it, zones, floatingZone, warnings) }
    if (component.children.any { it.name != "VALARM" }) {
        warnings += IcsWarning.UnknownSubcomponent
    }
    val record = common.summary.ifBlank { common.uid }
    warnings.forEach { details += warningDetail(it, component.lineNumber, record) }
    return RawEvent(
        uid = common.uid,
        summary = common.summary,
        description = common.description,
        location = location,
        categories = common.categories.distinct(),
        url = common.url,
        geo = geo,
        attachments = common.attachments,
        status = status,
        transparency = transparency,
        eventClass = eventClass,
        priority = priority,
        organizer = organizer,
        attendees = attendees,
        start = start,
        end = end,
        duration = duration,
        recurrenceId = recurrenceId,
        recurrenceRange = recurrenceRange,
        rule = rule,
        rawRule = rawRule,
        exdates = exdates,
        rDates = rdates,
        created = common.created,
        lastModified = common.lastModified,
        sequence = common.sequence,
        dtStamp = common.dtStamp,
        alarms = alarms,
        extraProperties = extra,
        textParameters = common.textParameters,
        lineNumber = component.lineNumber,
    )
}

private fun readAttachment(line: ContentLine): Attachment {
    val binary = line.params["ENCODING"].equals("BASE64", ignoreCase = true)
    return Attachment(
        value = line.value.trim(),
        parameters = line.params,
        binary = binary,
    )
}

private fun readAlarm(
    component: IcsComponent,
    zones: Map<String, CustomZone>,
    floatingZone: IcsZone?,
    warnings: MutableSet<IcsWarning>,
): Alarm? {
    val action = component.first("ACTION")?.value?.trim()?.uppercase() ?: "DISPLAY"
    val trigger = component.first("TRIGGER") ?: return null
    val triggerValue = trigger.value.trim()
    val relative = triggerValue.startsWith("P") || triggerValue.startsWith("-P") || triggerValue.startsWith("+P")
    val triggerValueParsed = if (relative) {
        // The RFC sign is kept as it is written: negative fires before the related time.
        val signed = parseSignedDurationSeconds(triggerValue) ?: return null
        AlarmTrigger.Relative(signed.seconds, AlarmRelated.parse(trigger.params["RELATED"]))
    } else {
        if (triggerValue.isEmpty()) return null
        val absolute = parseTime(trigger, zones, warnings, floatingZone)?.toValue() ?: return null
        AlarmTrigger.Absolute(absolute)
    }
    val repeat = component.first("REPEAT")?.value?.trim()?.toIntOrNull()
    // `DURATION` is the spacing between repeated firings; a whole-minute reading turned `PT30S` into
    // no spacing at all, which silently dropped every `REPEAT` continuation.
    val duration = component.first("DURATION")?.value?.trim()?.let(::parseDurationSeconds)?.seconds
    val description = component.first("DESCRIPTION")?.value?.let(::unescapeText).orEmpty()
    val known = setOf("ACTION", "TRIGGER", "REPEAT", "DURATION", "DESCRIPTION")
    return Alarm(
        action = action,
        trigger = triggerValueParsed,
        repeat = repeat,
        duration = duration,
        description = description,
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

/**
 * `DURATION` value in seconds. RFC 5545 durations are second-precise (`PT30S`, `-PT1H30M30S`), so
 * this is the granularity every caller that keeps a [Duration] needs; [parseDurationMinutes] rounds
 * it for the few whole-minute values the model stores.
 */
private fun parseDurationSeconds(value: String): Long? {
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

private fun parseSignedDurationSeconds(value: String): Long? {
    val trimmed = value.trim()
    val sign = if (trimmed.startsWith('-')) -1L else 1L
    val body = trimmed.removePrefix("-").removePrefix("+")
    val seconds = parseDurationSeconds(body) ?: return null
    return sign * seconds
}

/** Whole minutes of a duration, for the calendar-level values that are stored in minutes. */
private fun parseDurationMinutes(value: String): Int? = parseDurationSeconds(value)?.let { (it / 60).toInt() }

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

/** Date the calendar file itself names, independent of the device zone. */
private fun IcsTime.localWallDate(): LocalDate = when (this) {
    is IcsTime.AllDay -> date
    is IcsTime.DateTime -> value.date
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
    val slots = group.filter { it.recurrenceId != null }.mapNotNull { raw ->
        raw.recurrenceId?.toValue()?.let { raw to alignExclusionValue(series.start, it, deviceZone) }
    }
    // An imported `RANGE=THISANDFUTURE` exception that carries a recurrence of its own takes over
    // everything from its slot on: the master is truncated before the slot and the exception
    // becomes the segment generating the later instances. Without a recurrence of its own there is
    // no pattern to continue with, so the file's own shape — an exception replacing its slot only —
    // is kept and reported instead.
    val split = slots.firstOrNull { (raw, slot) ->
        RecurrenceRange.parse(raw.recurrenceRange) == RecurrenceRange.ThisAndFuture &&
            slot.wallDate > series.wallDay &&
            mapRecurrence(raw.rule, raw.rawRule, raw.rDates, raw.exdates) != null
    }?.first
    var masterRecurrence = series.recurrence
    var segment: Event? = null
    var segmentSlot: IcsTimeValue? = null
    val segmentExclusions = mutableListOf<IcsTimeValue>()
    slots.forEach { (override, slot) ->
        if (!seenSlots.add(slot)) ambiguous = true
        val range = RecurrenceRange.parse(override.recurrenceRange)
        if (range == RecurrenceRange.ThisAndPrior) {
            // THISANDPRIOR is not RFC 5545; keep it so an export writes the range back, but say so.
            warnings += IcsWarning.UnsupportedRecurrence
        }
        if (override === split) {
            val own = mapRecurrence(override.rule, override.rawRule, override.rDates, override.exdates)
            if (own != null) {
                // The master keeps only what came before the slot; its own later RDATEs and EXDATEs
                // describe old-pattern instances the segment never generates, so they go with the
                // truncation — the same partition a "this and future" edit performs in the app.
                masterRecurrence = (masterRecurrence ?: Recurrence()).boundedBefore(slot.wallDate).first
                val mapped = mapSingle(override, deviceZone, untitledTitle, zones, warnings, recurrence = own)
                    ?: return@forEach
                segment = mapped.copy(icsUid = uid, recurrenceId = slot, recurrenceRange = range)
                segmentSlot = slot
                return@forEach
            }
        }
        if (range == RecurrenceRange.ThisAndFuture) {
            warnings += IcsWarning.UnappliedRecurrenceRange
        }
        val mapped = mapSingle(override, deviceZone, untitledTitle, zones, warnings, recurrence = null)
            ?: return@forEach
        exceptions += mapped.copy(icsUid = uid, recurrenceId = slot, recurrenceRange = range)
        // Exceptions from the slot on replace instances the segment generates, not the master's:
        // it was truncated, and their slots would come back as mismatched exclusions on it.
        if (segmentSlot != null && slot.wallDate >= segmentSlot!!.wallDate) {
            segmentExclusions += alignExclusionValue(segmentSlot!!, slot, deviceZone)
        } else {
            exclusions += slot
        }
    }
    if (ambiguous) warnings += IcsWarning.AmbiguousRecurrenceId
    // The master skips every slot an exception describes; the exception itself carries the change,
    // including a `STATUS:CANCELLED` copy that must keep its SEQUENCE for later imports.
    val boundedMaster = when {
        exclusions.isEmpty() -> masterRecurrence
        else -> {
            val current = masterRecurrence ?: Recurrence()
            current.copy(exdates = (current.exdates + exclusions).distinct())
        }
    }
    val results = mutableListOf(series.copy(recurrence = boundedMaster))
    segment?.let { seg ->
        val updated = when {
            segmentExclusions.isEmpty() -> seg
            else -> seg.copy(
                recurrence = seg.recurrence?.copy(exdates = (seg.recurrence.exdates + segmentExclusions).distinct()),
            )
        }
        results += updated
    }
    return results + exceptions
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
    val aligned = recurrence?.takeIf { it.exdates.isNotEmpty() }?.let { set ->
        set.copy(exdates = set.exdates.map { alignExclusionValue(start, it, deviceZone) })
    } ?: recurrence
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
        alarms = raw.alarms,
        recurrence = aligned,
        icsUid = raw.uid,
        recurrenceId = raw.recurrenceId?.toValue(),
        recurrenceRange = RecurrenceRange.parse(raw.recurrenceRange),
        created = raw.created,
        lastModified = raw.lastModified,
        dtStamp = raw.dtStamp,
        sequence = raw.sequence,
        extraProperties = raw.extraProperties,
        textParameters = raw.textParameters,
        local = LocalRecordMeta(sourceLine = raw.lineNumber),
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
            // A date-time `DTEND` on a DATE `DTSTART` names its day in the file's own clock; reading
            // it through the device zone would drop a day for every user west of that zone.
            is IcsTime.DateTime -> end.localWallDate().takeIf { it > start.date } ?: start.date.plusDays(1)
            null -> raw.duration
                ?.let { length -> start.date.plusDays(length.inWholeDays.toInt().coerceAtLeast(1)) }
                ?: start.date.plusDays(1)
        }
        return IcsTimeValue.Date(exclusive)
    }
    val parsedEnd = (raw.end as? IcsTime.DateTime)?.toValue()
    val resolved = when {
        parsedEnd != null -> alignEndTo(start, parsedEnd, deviceZone)
        raw.duration != null && raw.duration > Duration.ZERO ->
            start.plusDuration(raw.duration, deviceZone)
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

/**
 * RFC 5545 gives the values of one series a single identity: `EXDATE` and `RECURRENCE-ID` must name
 * the instance in `DTSTART`'s own value type, and exclusion matching compares wall clocks in the
 * series' zone. Real feeds do mix types — a UTC `EXDATE:…T140000Z` inside a `TZID` series is the
 * common shape — so the value is re-expressed in the master's type here, keeping its instant. Left
 * unaligned, the exclusion names no instance: the master keeps showing the deleted occurrence next
 * to its exception (a doubled occurrence), or `withValidExdates` drops the exclusion as mismatched.
 * A master whose zone only a calendar-level `VTIMEZONE` describes is read through that definition
 * too, so its exclusions align like any other `TZID` series.
 */
private fun alignExclusionValue(start: IcsTimeValue, value: IcsTimeValue, deviceZone: TimeZone): IcsTimeValue {
    // A date-only value excludes the whole day of any series.
    if (value is IcsTimeValue.Date) return value
    // A timed value on an all-day series names that day.
    if (start is IcsTimeValue.Date) return IcsTimeValue.Date(value.wallDate)
    return when (start) {
        is IcsTimeValue.Floating -> when (value) {
            is IcsTimeValue.Floating -> value
            is IcsTimeValue.Utc -> IcsTimeValue.Floating(value.instant.toLocalDateTime(deviceZone))
            is IcsTimeValue.Zoned -> value.instantOrNull(deviceZone)
                ?.let { IcsTimeValue.Floating(it.toLocalDateTime(deviceZone)) }
                ?: value
        }
        is IcsTimeValue.Utc -> when (value) {
            is IcsTimeValue.Utc -> value
            // A floating value on a UTC series is read on the series' own wall clock.
            is IcsTimeValue.Floating -> IcsTimeValue.Utc(value.dateTime.toInstant(TimeZone.UTC))
            is IcsTimeValue.Zoned -> value.instantOrNull(deviceZone)?.let(IcsTimeValue::Utc) ?: value
        }
        is IcsTimeValue.Zoned -> {
            if (value is IcsTimeValue.Zoned && value.tzid == start.tzid) return value
            val instant = when (value) {
                // A floating exclusion belongs to the series' own wall clock.
                is IcsTimeValue.Floating -> zonedInstantOrNull(value.dateTime, start.tzid)
                else -> value.instantOrNull(deviceZone)
            } ?: return value
            val local = zoneDateTimeOrNull(instant, start.tzid) ?: return value
            IcsTimeValue.Zoned(local, start.tzid)
        }
        is IcsTimeValue.Date -> value
    }
}

/** Keeps an end in the same value type as the start; RFC 5545 forbids mixing them. */
private fun alignEndTo(start: IcsTimeValue, end: IcsTimeValue, deviceZone: TimeZone): IcsTimeValue {
    if (end is IcsTimeValue.Date) return end
    return when (start) {
        is IcsTimeValue.Date -> end
        is IcsTimeValue.Floating -> IcsTimeValue.Floating(end.wallDateTime)
        is IcsTimeValue.Zoned -> {
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
            // The instant is written back as the wall clock of the series' own zone, which may be one
            // only a calendar-level `VTIMEZONE` describes; through the device zone the end would land
            // on the far side of the offset difference (or before the start, losing the length).
            val local = zoneDateTimeOrNull(instant, start.tzid) ?: instant.toLocalDateTime(zone)
            IcsTimeValue.Zoned(local, start.tzid)
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

private fun readTodo(
    component: IcsComponent,
    deviceZone: TimeZone,
    zones: Map<String, CustomZone>,
    floatingZone: IcsZone?,
    details: MutableList<IcsWarningDetail>,
): Todo? {
    val warnings = linkedSetOf<IcsWarning>()
    val common = CommonFields()
    var startValue: IcsTime? = null
    var dueValue: IcsTime? = null
    var completedValue: IcsTime? = null
    var rule: RepeatRule? = null
    var rawRule: String? = null
    val exdates = mutableListOf<IcsTime>()
    val rdates = mutableListOf<IcsTime>()
    var location = ""
    val relatedTo = mutableListOf<RelatedTo>()
    var rawStatus: String? = null
    var percent = 0
    var priority: Int? = null
    val extra = mutableListOf<String>()
    component.properties.forEach { line ->
        when (line.name) {
            "DTSTART" -> {
                startValue = parseTime(line, zones, warnings, floatingZone)
                if (startValue == null) warnings += IcsWarning.InvalidValue
            }
            "DUE" -> {
                dueValue = parseTime(line, zones, warnings, floatingZone)
                if (dueValue == null) warnings += IcsWarning.InvalidValue
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
            "LOCATION" -> {
                location = unescapeText(line.value).trim()
                common.captureTextParameter(line)
            }
            "RELATED-TO" -> unescapeText(line.value).trim().takeIf { it.isNotEmpty() }?.let { target ->
                val reltype = line.params["RELTYPE"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: "PARENT"
                relatedTo += RelatedTo(target, reltype)
            }
            "STATUS" -> {
                rawStatus = line.value.trim().uppercase()
                // An X- extension status has no enum slot; keep the raw line so the export does not
                // quietly replace it with NEEDS-ACTION.
                if (rawStatus != null && rawStatus !in listOf("NEEDS-ACTION", "IN-PROCESS", "COMPLETED", "CANCELLED")) {
                    extra += line.raw
                }
            }
            "PERCENT-COMPLETE" -> percent = line.value.trim().toIntOrNull()?.coerceIn(0, 100) ?: 0
            "PRIORITY" -> priority = line.value.trim().toIntOrNull()?.coerceIn(0, 9)
            "COMPLETED" -> completedValue = parseTime(line, zones, warnings, floatingZone)
            "EXRULE" -> {
                warnings += IcsWarning.ExruleIgnored
                extra += line.raw
            }
            else -> if (!common.handle(line)) extra += line.raw
        }
    }
    val alarms = component.children
        .filter { it.name == "VALARM" }
        .mapNotNull { readAlarm(it, zones, floatingZone, warnings) }
    if (component.children.any { it.name != "VALARM" }) {
        warnings += IcsWarning.UnknownSubcomponent
    }
    // A rule this model cannot expand is kept verbatim so a later export does not drop it.
    rawRule?.let { extra += "RRULE:$it" }
    if (common.summary.isBlank() && common.uid == null) {
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
    val completed = completedValue?.let { time ->
        val value = time.toValue()
        value.instantOrNull(deviceZone) ?: (value as? IcsTimeValue.Date)?.date
            ?.let { LocalDateTime(it, LocalTime(0, 0)).toInstant(deviceZone) }
    }
    val record = common.summary.ifBlank { common.uid }
    warnings.forEach { details += warningDetail(it, component.lineNumber, record) }
    // The series is anchored on DTSTART, falling back to DUE the way RFC 5545 anchors a VTODO.
    return Todo(
        id = "",
        title = common.summary.ifBlank { common.uid ?: "" },
        start = startValue?.toValue(),
        due = dueValue?.toValue(),
        completed = completed,
        priority = priority,
        note = common.description,
        location = location,
        categories = common.categories.distinct(),
        url = common.url,
        attachments = common.attachments,
        relatedTo = relatedTo.distinct(),
        icsUid = common.uid,
        created = common.created,
        createdAt = parseInstant(common.created)?.toEpochMilliseconds() ?: 0L,
        sequence = common.sequence,
        lastModified = common.lastModified,
        dtStamp = common.dtStamp,
        extraProperties = extra,
        textParameters = common.textParameters,
        status = status,
        percentComplete = percent,
        alarms = alarms,
        repeat = rule,
        rDates = rdates.map { RecurrenceDate(it.toValue(), it.periodDuration()) },
        // Aligned to the series' own value type, like an event's exclusions: a UTC `EXDATE` inside a
        // TZID todo series still names its instance, and a date-only one means the day the file
        // itself names — projecting it through the device zone could move it to the day before or
        // after.
        excludedDates = alignedTodoExdates(
            exdates,
            startValue?.toValue() ?: dueValue?.toValue(),
            deviceZone,
        ),
        local = LocalRecordMeta(sourceLine = component.lineNumber),
    )
}

/** Typed exclusions of a todo series, aligned to the anchor's value type like an event's are. */
private fun alignedTodoExdates(
    exdates: List<IcsTime>,
    anchor: IcsTimeValue?,
    deviceZone: TimeZone,
): List<IcsTimeValue> = exdates
    .map { it.toValue() }
    .mapNotNull { value -> anchor?.let { alignExclusionValue(it, value, deviceZone) } ?: value }
    .distinct()

private fun readJournal(
    component: IcsComponent,
    deviceZone: TimeZone,
    zones: Map<String, CustomZone>,
    floatingZone: IcsZone?,
    details: MutableList<IcsWarningDetail>,
): Journal? {
    val warnings = linkedSetOf<IcsWarning>()
    val common = CommonFields()
    var startValue: IcsTime? = null
    var rule: RepeatRule? = null
    var rawRule: String? = null
    val exdates = mutableListOf<IcsTime>()
    val rdates = mutableListOf<IcsTime>()
    var rawStatus: String? = null
    var journalClass: String? = null
    val extra = mutableListOf<String>()
    component.properties.forEach { line ->
        when (line.name) {
            "DTSTART" -> {
                startValue = parseTime(line, zones, warnings, floatingZone)
                if (startValue == null) warnings += IcsWarning.InvalidValue
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
            "STATUS" -> {
                rawStatus = line.value.trim().uppercase()
                // `STATUS` accepts an extension value; with no enum entry for it the raw line is kept
                // so an export does silently drop what the file declared.
                if (JournalStatus.parse(rawStatus) == null && rawStatus?.isNotEmpty() == true) extra += line.raw
            }
            "CLASS" -> {
                journalClass = line.value.trim().uppercase()
                if (rawClass(journalClass) == null && journalClass?.isNotEmpty() == true) extra += line.raw
            }
            "EXRULE" -> {
                warnings += IcsWarning.ExruleIgnored
                extra += line.raw
            }
            else -> if (!common.handle(line)) extra += line.raw
        }
    }
    rawRule?.let { extra += "RRULE:$it" }
    if (common.uid == null && common.summary.isBlank() && common.description.isBlank()) {
        warnings.forEach { details += warningDetail(it, component.lineNumber, null) }
        return null
    }
    val record = common.summary.ifBlank { common.uid }
    warnings.forEach { details += warningDetail(it, component.lineNumber, record) }
    val recurrence = if (rule == null && rawRule == null && rdates.isEmpty() && exdates.isEmpty()) {
        null
    } else {
        Recurrence(
            rule = rule,
            rdates = rdates.map { RecurrenceDate(it.toValue(), it.periodDuration()) },
            exdates = exdates.map { it.toValue() },
            rawRule = rawRule,
        )
    }
    return Journal(
        id = "",
        title = common.summary,
        note = common.description,
        start = startValue?.toValue(),
        url = common.url,
        categories = common.categories.distinct(),
        attachments = common.attachments,
        created = common.created,
        lastModified = common.lastModified,
        dtStamp = common.dtStamp,
        sequence = common.sequence,
        icsUid = common.uid,
        extraProperties = extra,
        textParameters = common.textParameters,
        recurrence = recurrence,
        status = JournalStatus.parse(rawStatus),
        eventClass = rawClass(journalClass),
        local = LocalRecordMeta(sourceLine = component.lineNumber),
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
            val seconds = period.periodSeconds ?: return@forEach
            val startInstant = period.zone.toInstant(period.value)
            intervals += BusyInterval.of(type, startInstant, startInstant + seconds.seconds)
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

private fun StringBuilder.appendJournal(journal: Journal, index: Int, now: Instant) {
    line("BEGIN:VJOURNAL")
    appendComponentHeader(journal, journal.icsUid ?: "${journal.id}@crko", now)
    journal.start?.let { appendTimeLine("DTSTART", it) }
    appendComponentText(journal)
    journal.status?.let { line("STATUS:${it.wireName}") }
    journal.eventClass?.let { line("CLASS:${it.name.uppercase()}") }
    journal.recurrence?.let { appendJournalRecurrence(it) }
    appendComponentExtras(
        journal,
        buildSet {
            if (journal.status != null) add("STATUS")
            if (journal.eventClass != null) add("CLASS")
        },
    )
    line("END:VJOURNAL")
}

/** Writes the `RRULE`, `RDATE` and `EXDATE` of a journal, without an event's occurrence handling. */
private fun StringBuilder.appendJournalRecurrence(recurrence: Recurrence) {
    val rule = recurrence.rule
    if (rule != null) {
        line("RRULE:${rule.raw ?: ruleValue(rule, null)}")
    } else {
        recurrence.rawRule?.let { line("RRULE:$it") }
    }
    appendRecurrenceDates(recurrence.rdates)
    if (recurrence.exdates.isEmpty()) return
    recurrence.exdates.groupBy { recurrenceValueParameters(it) }.forEach { (params, group) ->
        line("EXDATE$params:${group.distinct().sortedBy { it.wallDateTime }.joinToString(",") { formatTimeValue(it) }}")
    }
}

// ----- custom VTIMEZONE ----------------------------------------------------

/** Generation horizon for open-ended observance rules; no calendar the app schedules reaches it. */
private const val MaxTransitionYear = 2200

private class CustomZone(val id: String, val observances: List<Observance>) {
    fun toInstant(local: LocalDateTime): Instant = local.toInstant(TimeZone.UTC).minusSeconds(offsetSecondsAt(local))

    /**
     * Wall clock of [instant] in this zone — the reverse of [toInstant]. A local value is the instant
     * plus the offset in effect *at that local value*, so the offset has to be found by refining the
     * guess: one step lands on the wrong side of a transition, the next confirms the one before it.
     * Without this, an instant could only be read back in the device zone, which moved every custom
     * zone's ends, edited times and exclusions by the difference between the two offsets.
     */
    fun toLocalDateTime(instant: Instant): LocalDateTime {
        var local = instant.toLocalDateTime(TimeZone.UTC)
        repeat(3) {
            val next = (instant + offsetSecondsAt(local).seconds).toLocalDateTime(TimeZone.UTC)
            if (next == local) return local
            local = next
        }
        return local
    }

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

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
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
     * Transitions generated so far, with the year their generation covers. One imported file
     * resolves hundreds of values against the same zone, and an observance anchored in 1970 would
     * otherwise rebuild every year of its rule for every single value. The list is immutable and
     * published by reference swap, so lookups running on another thread never iterate a list that
     * a concurrent query is still growing.
     */
    private val transitions = AtomicReference(start.year - 1 to (listOf(start) + rDates))

    /** Every transition of this observance up to and including the year [year] + 1. */
    fun transitionsThrough(year: Int): List<LocalDateTime> {
        val repeat = rule ?: return transitions.load().second
        // Rules without UNTIL or COUNT are open ended, so generation stops at a horizon far beyond
        // any calendar the app schedules; later values keep the offset of the last known transition.
        val targetYear = (year + 1).coerceAtMost(MaxTransitionYear)
        while (true) {
            val snapshot = transitions.load()
            if (snapshot.first >= targetYear) return snapshot.second
            val regenerated = generateTransitions(repeat, targetYear)
            if (transitions.compareAndSet(snapshot, targetYear to regenerated)) return regenerated
        }
    }

    /** Rebuilds every transition from the anchor up to [targetYear]; races just redo this work. */
    private fun generateTransitions(repeat: RepeatRule, targetYear: Int): List<LocalDateTime> {
        val anchor = start.date
        val out = ArrayList(listOf(start) + rDates)
        var candidateYear = anchor.year
        while (candidateYear <= targetYear) {
            val candidates = when (repeat.frequency) {
                RepeatFrequency.Yearly -> yearCandidates(repeat, anchor, candidateYear, withSetPos = true)
                RepeatFrequency.Monthly -> (1..12).flatMap { month ->
                    monthCandidates(repeat, anchor, candidateYear, month)
                }
                else -> emptyList()
            }
            out += candidates.filter { date ->
                date >= anchor && withinUntil(date) && withinCount(repeat, anchor, date)
            }.map { LocalDateTime(it, start.time) }
            candidateYear++
        }
        return out
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

/** A relative trigger keeps the RFC sign: negative fires before, positive after the related time. */
private fun formatSignedDuration(duration: Duration): String {
    if (duration == Duration.ZERO) return "PT0M"
    val sign = if (duration.isNegative()) "-" else "+"
    return sign + formatDurationSeconds(kotlin.math.abs(duration.inWholeSeconds))
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
        // A stored payload is re-encoded at export time; the parameters stay with the record so
        // FMTTYPE and any unknown parameter survive without the base64 blob living in the state.
        val stored = attachment.storedFile?.takeIf { it.isNotBlank() }
        val value = if (attachment.binary) {
            if (attachment.value.isNotBlank()) {
                attachment.value.filterNot { it.isWhitespace() }
            } else {
                val bytes = stored?.let(AppAttachmentStore::read) ?: return@forEach
                Base64.encode(bytes)
            }
        } else {
            sanitizeUriValue(attachment.value)
        }
        if (value.isBlank()) return@forEach
        val parameters = LinkedHashMap(attachment.parameters)
        if (attachment.binary && "ENCODING" !in parameters) parameters["ENCODING"] = "BASE64"
        line(
            buildString {
                append("ATTACH")
                parameters.forEach { (name, parameter) ->
                    append(';').append(name).append('=').append(icsParameterValue(parameter))
                }
                append(':').append(value)
            },
        )
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
