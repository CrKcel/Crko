package com.crko.domain

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration

/**
 * Copies of one UID ranked from stale to newest: `SEQUENCE` is the authoritative counter for a UID,
 * then `DTSTAMP`, then `LAST-MODIFIED`. Ties keep the earlier copy, so a file without any of them
 * still imports deterministically.
 */
internal val masterCopyComparator = compareBy<RawEvent>(
    { it.sequence },
    { parseInstant(it.dtStamp)?.epochSeconds ?: Long.MIN_VALUE },
    { parseInstant(it.lastModified)?.epochSeconds ?: Long.MIN_VALUE },
)

internal fun mapSeries(
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
        if (segmentSlot != null && slot.wallDate >= segmentSlot.wallDate) {
            segmentExclusions += alignExclusionValue(segmentSlot, slot, deviceZone)
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

internal fun mapSingle(
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
internal fun alignExclusionValue(start: IcsTimeValue, value: IcsTimeValue, deviceZone: TimeZone): IcsTimeValue {
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

internal fun rawStatus(value: String?): EventStatus = when (value?.uppercase()) {
    "TENTATIVE" -> EventStatus.Tentative
    "CANCELLED" -> EventStatus.Cancelled
    else -> EventStatus.Confirmed
}

internal fun rawTransparency(value: String?): EventTransparency =
    if (value.equals("TRANSPARENT", ignoreCase = true)) EventTransparency.Transparent else EventTransparency.Opaque

internal fun rawClass(value: String?): EventClass? = when (value?.uppercase()) {
    "PUBLIC" -> EventClass.Public
    "PRIVATE" -> EventClass.Private
    "CONFIDENTIAL" -> EventClass.Confidential
    else -> null
}
