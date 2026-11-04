package com.crko.domain

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

    val todos = alignTodoInstances(
        components
            .filter { it.name == "VTODO" }
            .mapNotNull { readTodo(it, timeZone, zones, floatingZone, details) },
        timeZone,
    )
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

internal data class RawEvent(
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

internal fun RawEvent.isCancelled(): Boolean = status.equals("CANCELLED", ignoreCase = true)

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
                if (status !in listOf("CONFIRMED", "TENTATIVE", "CANCELLED")) extra += line.raw
            }
            "TRANSP" -> {
                transparency = line.value.trim().uppercase()
                if (transparency !in listOf("OPAQUE", "TRANSPARENT")) extra += line.raw
            }
            "CLASS" -> {
                eventClass = line.value.trim().uppercase()
                if (rawClass(eventClass) == null && eventClass.isNotEmpty()) extra += line.raw
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
    var recurrenceIdValue: IcsTime? = null
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
            "RECURRENCE-ID" -> {
                recurrenceIdValue = parseTime(line, zones, warnings, floatingZone)
                if (recurrenceIdValue == null) warnings += IcsWarning.InvalidValue
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
                if (rawStatus !in listOf("NEEDS-ACTION", "IN-PROCESS", "COMPLETED", "CANCELLED")) {
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
        recurrenceId = recurrenceIdValue?.toValue(),
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

/**
 * Aligns the `RECURRENCE-ID` of an occurrence instance to its master's anchor value. RFC 5545 names
 * an instance in the master's own value type; feeds do mix types (a UTC slot inside a `TZID`
 * series), and an unaligned slot would not match the master's projection or a later export.
 */
private fun alignTodoInstances(todos: List<Todo>, deviceZone: TimeZone): List<Todo> {
    val masters = todos
        .filter { it.recurrenceId == null && it.icsUid != null }
        .associateBy { it.icsUid }
    return todos.map { todo ->
        val slot = todo.recurrenceId ?: return@map todo
        val anchor = todo.icsUid?.let { masters[it] }?.let { it.start ?: it.due } ?: return@map todo
        todo.copy(recurrenceId = alignExclusionValue(anchor, slot, deviceZone))
    }
}

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
                if (JournalStatus.parse(rawStatus) == null && rawStatus.isNotEmpty()) extra += line.raw
            }
            "CLASS" -> {
                journalClass = line.value.trim().uppercase()
                if (rawClass(journalClass) == null && journalClass.isNotEmpty()) extra += line.raw
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
