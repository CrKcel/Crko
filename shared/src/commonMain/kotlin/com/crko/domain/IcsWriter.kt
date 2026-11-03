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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** RFC 5545 line break and fold budget shared by every writer. */
private const val LineBreak = "\r\n"
private const val FoldLimit = 75

fun exportIcs(
    events: List<Event>,
    now: Instant = Clock.System.now(),
    storedAttachment: (String) -> ByteArray? = { null },
): String = exportCalendar(
    events = events,
    todos = emptyList(),
    now = now,
    storedAttachment = storedAttachment,
)

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
    /**
     * Reads the payload of a materialized attachment by its stored file name. Domain code has no
     * file-system access, so the caller supplies this port; the default writes no stored payloads,
     * keeping every existing read/write test call unchanged.
     */
    storedAttachment: (String) -> ByteArray? = { null },
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
    events.forEachIndexed { index, event -> appendEvent(event, events, index, now, storedAttachment) }
    todos.forEachIndexed { index, todo -> appendTodo(todo, index, now, storedAttachment) }
    journals.forEachIndexed { index, journal -> appendJournal(journal, index, now, storedAttachment) }
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
 * iTIP message that proposes or updates [events] (`METHOD:REQUEST`). Receivers decide by `UID` and
 * `SEQUENCE` whether the proposal replaces their copy, so every record keeps its full content.
 */
fun exportItipRequest(
    events: List<Event>,
    now: Instant = Clock.System.now(),
    calendarName: String? = null,
    storedAttachment: (String) -> ByteArray? = { null },
): String = exportCalendar(
    events = events,
    now = now,
    calendarName = calendarName,
    method = ItipMethod.Request,
    storedAttachment = storedAttachment,
)

/**
 * iTIP message that withdraws [events] (`METHOD:CANCEL`): every copy is written with
 * `STATUS:CANCELLED` and a bumped `SEQUENCE`, so receivers drop or hide their local records.
 */
fun exportItipCancel(
    events: List<Event>,
    now: Instant = Clock.System.now(),
    storedAttachment: (String) -> ByteArray? = { null },
): String = exportCalendar(
    events = events.map { it.copy(status = EventStatus.Cancelled) },
    now = now,
    method = ItipMethod.Cancel,
    storedAttachment = storedAttachment,
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

internal fun StringBuilder.line(value: String) {
    append(foldLine(value))
    append(LineBreak)
}

private fun StringBuilder.appendEvent(
    event: Event,
    allEvents: List<Event>,
    index: Int,
    now: Instant,
    storedAttachment: (String) -> ByteArray?,
) {
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

    appendComponentText(event, storedAttachment)
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
private fun StringBuilder.appendComponentText(
    component: CalendarComponent,
    storedAttachment: (String) -> ByteArray?,
) {
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
    appendAttachments(component.attachments, storedAttachment)
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

private fun StringBuilder.appendTodo(
    todo: Todo,
    index: Int,
    now: Instant,
    storedAttachment: (String) -> ByteArray?,
) {
    line("BEGIN:VTODO")
    val createdFallback = todo.createdAt.takeIf { it > 0 }?.let { Instant.fromEpochMilliseconds(it) }
    appendComponentHeader(todo, todo.icsUid ?: "${todo.id}@crko", now, createdFallback)
    todo.start?.let { appendTimeLine("DTSTART", it) }
    todo.due?.let { appendTimeLine("DUE", it) }
    appendComponentText(todo, storedAttachment)
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

private fun StringBuilder.appendJournal(
    journal: Journal,
    index: Int,
    now: Instant,
    storedAttachment: (String) -> ByteArray?,
) {
    line("BEGIN:VJOURNAL")
    appendComponentHeader(journal, journal.icsUid ?: "${journal.id}@crko", now)
    journal.start?.let { appendTimeLine("DTSTART", it) }
    appendComponentText(journal, storedAttachment)
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

internal fun Instant.minusSeconds(seconds: Int): Instant = this - seconds.seconds

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

/**
 * `url`, `caladdress` and `geo` are not TEXT values, so `,`, `;` and `\` have to stay literal;
 * escaping them breaks links in every client that reads the file. Only control characters are
 * removed, because those would inject additional content lines into the exported calendar.
 */
internal fun sanitizeUriValue(value: String): String = buildString(value.length) {
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

internal fun unescapeText(value: String): String = buildString(value.length) {
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
