package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// ----- time-of-day expansion (`BYHOUR` / `BYMINUTE` / `BYSECOND`) -----

/** One start time of an occurrence: minutes and seconds after midnight. */
data class OccurrenceTime(val minutes: Int, val seconds: Int)

/**
 * Start times [rule] puts on a rule-generated date: `BYHOUR` / `BYMINUTE` / `BYSECOND` multiply
 * the single start into several (RFC 5545 expands the time of day), and parts the rule omits keep
 * the anchor's own time. [startTime] is that anchor time.
 */
internal fun expandedStartTimes(rule: RepeatRule, startTime: OccurrenceTime): List<OccurrenceTime> {
    if (rule.byHour.isEmpty() && rule.byMinute.isEmpty() && rule.bySecond.isEmpty()) {
        return listOf(startTime)
    }
    val hours = rule.byHour.ifEmpty { listOf(startTime.minutes / 60) }
    val minutes = rule.byMinute.ifEmpty { listOf(startTime.minutes % 60) }
    val seconds = rule.bySecond.ifEmpty { listOf(startTime.seconds) }
    return hours.flatMap { hour ->
        minutes.flatMap { minute ->
            seconds.map { second -> OccurrenceTime(hour * 60 + minute, second) }
        }
    }.distinct().sortedBy { it.minutes * 60 + it.seconds }
}

/**
 * Start times of the occurrence starting on [date], where [date] is a wall-clock date in the
 * event's own zone. One per expanded time of day when the rule carries `BYHOUR` / `BYMINUTE` /
 * `BYSECOND`, the event's own start for a `DTSTART` that misses the `BY*` filters, and the `RDATE`
 * value's own time for an added instance. Timed `EXDATE` values remove only their exact start.
 */
fun Event.occurrenceTimesOn(date: LocalDate): List<OccurrenceTime> {
    val recurrence = recurrence
    val rule = recurrence?.rule
    if (recurrence == null) {
        val covered = date >= wallDay && date <= wallLastOccupiedDay()
        if (!covered) return emptyList()
        return if (start is IcsTimeValue.Date) listOf(OccurrenceTime(0, 0)) else listOf(wallStartTime())
    }
    if (start is IcsTimeValue.Date) {
        return if (isOccurrenceStart(date, rule)) listOf(OccurrenceTime(0, 0)) else emptyList()
    }
    if (!isOccurrenceStart(date, rule)) return emptyList()
    val anchorTime = wallStartTime()
    val times = mutableListOf<OccurrenceTime>()
    if (date == wallDay) times += anchorTime
    if (rule != null && matchesRule(rule, wallDay, date)) times += expandedStartTimes(rule, anchorTime)
    recurrence.rdates.filter { it.value.wallDate == date }.forEach { rdate ->
        rdate.value.occurrenceTime(anchorTime)?.let { times += it }
    }
    return times
        .filterNot { isExcluded(recurrence.exdates, date, it) }
        .distinct()
        .sortedBy { it.minutes * 60 + it.seconds }
}

/** Length of the occurrence starting on wall date [wallDate]; an `RDATE` period keeps its own. */
fun Event.occurrenceDurationOn(wallDate: LocalDate): Duration {
    val own = recurrence?.rdates?.firstOrNull { it.value.wallDate == wallDate }?.duration
    return own ?: occurrenceDurationSeconds.seconds
}

/** Wall-clock length of the anchor occurrence, in seconds, derived from its start and end. */
val Event.occurrenceDurationSeconds: Long
    get() {
        if (start is IcsTimeValue.Date) {
            val exclusive = end?.wallDate ?: wallDay.plusDays(1)
            return (exclusive.toEpochDays() - wallDay.toEpochDays()).coerceAtLeast(1) * 86_400L
        }
        val endValue = end ?: return 0
        return start.durationUntil(endValue, TimeZone.currentSystemDefault()).inWholeSeconds
    }

/** Instant at which the occurrence starting on wall date [date] at [time] begins. */
fun Event.occurrenceStartInstant(
    date: LocalDate,
    time: OccurrenceTime,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Instant {
    val dateTime = LocalDateTime(date, LocalTime(time.minutes / 60, time.minutes % 60, time.seconds))
    val value = start.withWallDateTime(dateTime)
    return value.instantOrNull(zone) ?: dateTime.toInstant(zone)
}

/** Instant at which the occurrence starting on [date] at [time] ends. */
fun Event.occurrenceEndInstant(
    date: LocalDate,
    time: OccurrenceTime,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Instant = occurrenceStartInstant(date, time, zone) + occurrenceDurationOn(date)

/**
 * Slices of [event] on [date] in [zone]: one range per occurrence instance covering the day,
 * clipped to it. Rules without `BYHOUR` / `BYMINUTE` / `BYSECOND`, recurrences without `RDATE` —
 * and all-day events — keep the single slice [timeRangeOn] computes.
 */
fun Event.occurrenceRangesOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<Pair<Int, Int>> {
    val recurrence = recurrence ?: return listOf(timeRangeOn(date, zone))
    val rule = recurrence.rule
    val expands = rule?.let { it.byHour.isNotEmpty() || it.byMinute.isNotEmpty() || it.bySecond.isNotEmpty() }
        ?: false
    if (start is IcsTimeValue.Date || (!expands && recurrence.rdates.isEmpty())) {
        return listOf(timeRangeOn(date, zone))
    }
    val anchor = day
    val span = anchor.daysTo(lastOccupiedDay).coerceAtLeast(0)
    // An instance occupies as many days as the anchor, so the covering instance may start up to a
    // whole span earlier. A rule never starts before the anchor, but an RDATE may.
    val firstOffset = minOf(span, anchor.daysTo(date).coerceAtLeast(0)).coerceAtLeast(0)
    val ranges = mutableListOf<Pair<Int, Int>>()
    for (offset in firstOffset downTo 0) {
        val displayStart = date.plusDays(-offset)
        val wallStart = wallDateFor(displayStart, zone)
        if (!isOccurrenceStart(wallStart, rule)) continue
        for (time in occurrenceTimesOn(wallStart)) {
            occurrenceRangeOn(date, wallStart, time, zone)?.let { ranges += it }
        }
    }
    // Not an occurrence day, or a slice the day cannot hold: keep the raw range for callers that
    // bypass occursOn.
    return ranges.ifEmpty { listOf(timeRangeOn(date, zone)) }
}

/** The slice of the instance starting on [wallStart] at [time] that falls on [date], or null. */
private fun Event.occurrenceRangeOn(
    date: LocalDate,
    wallStart: LocalDate,
    time: OccurrenceTime,
    zone: TimeZone,
): Pair<Int, Int>? {
    val startInstant = occurrenceStartInstant(wallStart, time, zone)
    val endInstant = occurrenceEndInstant(wallStart, time, zone)
    val startLocal = startInstant.toLocalDateTime(zone)
    val endLocal = endInstant.toLocalDateTime(zone)
    val lastDay = if (endLocal.date > startLocal.date && endLocal.time == LocalTime(0, 0)) {
        endLocal.date.plusDays(-1)
    } else {
        endLocal.date
    }
    if (date < startLocal.date || date > lastDay) return null
    val start = if (date == startLocal.date) startLocal.hour * 60 + startLocal.minute else 0
    val endMinutes = when {
        date < lastDay -> EndOfDayMinutes
        endLocal.time == LocalTime(0, 0) && lastDay != endLocal.date -> EndOfDayMinutes
        else -> endLocal.hour * 60 + endLocal.minute
    }
    return start to endMinutes
}

/** Adds [slot] to the excluded set, creating the recurrence set when the series has none yet. */
fun Recurrence?.withExclusion(slot: IcsTimeValue): Recurrence =
    (this ?: Recurrence()).let { it.copy(exdates = (it.exdates + slot).distinct()) }

/**
 * Truncates the set at [date]: the rule ends the day before (COUNT and its own text no longer
 * describe the shorter series), and every added or excluded instance from [date] on belongs to the
 * part after the split, returned separately. An already earlier `UNTIL` is kept, so truncating a
 * series that was truncated before is a no-op. This is what "this and future" edits do to the old
 * series, both in the app and when an imported `RANGE=THISANDFUTURE` exception carries its own
 * recurrence.
 */
fun Recurrence.boundedBefore(date: LocalDate): Pair<Recurrence, Recurrence> {
    val previous = date.plusDays(-1)
    val rule = rule
    val bounded = if (rule == null) {
        null
    } else {
        val until = rule.until
        val earlier = when (until) {
            is IcsTimeValue.Date -> if (until.date < previous) until else IcsTimeValue.Date(previous)
            null -> IcsTimeValue.Date(previous)
            else -> if (until.wallDate < previous) until else IcsTimeValue.Date(previous)
        }
        rule.copy(until = earlier, count = null, raw = null)
    }
    val head = copy(
        rule = bounded,
        rdates = rdates.filter { it.value.wallDate < date },
        exdates = exdates.filter { it.wallDate < date },
    )
    val tail = copy(
        rule = null,
        rdates = rdates.filter { it.value.wallDate >= date },
        exdates = exdates.filter { it.wallDate >= date },
    )
    return head to tail
}

/** Moves every typed value of the recurrence set by whole wall days, keeping its value type. */
fun Recurrence.shiftedByWallDays(days: Int): Recurrence {
    if (days == 0) return this
    return copy(
        // A shifted rule no longer matches its original text.
        rule = rule?.copy(until = rule.until?.plusWallDays(days), raw = null),
        rdates = rdates.map { it.copy(value = it.value.plusWallDays(days)) },
        exdates = exdates.map { it.plusWallDays(days) },
    )
}

/**
 * `RECURRENCE-ID` an exception for the occurrence shown on [displayDate] carries: the same slot the
 * expansion engine generates for that occurrence, in the series' own value type.
 */
fun Event.recurrenceSlotOn(
    displayDate: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): IcsTimeValue = start.withWallDate(wallDateFor(displayDate, zone))

/** Wall date of a typed `RECURRENCE-ID`, i.e. the display slot the exception replaces. */
fun IcsTimeValue.recurrenceSlotDate(): LocalDate = wallDate
