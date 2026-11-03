package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Whether this event happens on [date] in [zone]; repeating events cover every matching date. */
fun Event.occursOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    // A cancelled exception is stored so its SEQUENCE survives, but it occupies no time.
    if (status == EventStatus.Cancelled) return false
    val recurrence = recurrence
    if (recurrence == null || (recurrence.rule == null && recurrence.rdates.isEmpty())) {
        return date >= day && date <= lastOccupiedDay
    }
    return occurrenceStartOn(date, zone) != null
}

/** Display date of the occurrence whose span covers [date], if any. */
fun Event.occurrenceStartOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): LocalDate? {
    if (status == EventStatus.Cancelled) return null
    val anchor = day
    val recurrence = recurrence
    if (recurrence == null || (recurrence.rule == null && recurrence.rdates.isEmpty())) {
        return if (date >= anchor && date <= lastOccupiedDay) anchor else null
    }
    val span = anchor.daysTo(lastOccupiedDay).coerceAtLeast(0)
    // A zone projection can move an occurrence by one day, so the window has one extra day of
    // slack; an RDATE may also name an instance before the anchor.
    for (offset in 0..span + 1) {
        val candidate = date.plusDays(-offset)
        val wall = wallDateFor(candidate, zone)
        if (!isOccurrenceStart(wall, recurrence.rule)) continue
        if (date <= displayLastOccupiedDayOf(candidate, wall, zone)) return candidate
    }
    return null
}

/** Display date on which the occurrence starting on [wallStart] stops occupying time. */
internal fun Event.displayLastOccupiedDayOf(
    displayStart: LocalDate,
    wallStart: LocalDate,
    zone: TimeZone,
): LocalDate {
    if (start is IcsTimeValue.Date) return displayStart.plusDays(wallDay.daysTo(wallLastOccupiedDay()))
    val startInstant = occurrenceStartInstant(wallStart, wallStartTime(), zone)
    val endInstant = startInstant + occurrenceDurationOn(wallStart)
    val startLocal = startInstant.toLocalDateTime(zone)
    val endLocal = endInstant.toLocalDateTime(zone)
    val last = if (endLocal.date > startLocal.date && endLocal.time == LocalTime(0, 0)) {
        endLocal.date.plusDays(-1)
    } else {
        endLocal.date
    }
    return if (last > displayStart) last else displayStart
}

/** Events happening on [date], all-day ones first and then ordered by start time. */
fun List<Event>.occurringOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<Event> = filter { it.occursOn(date, zone) }
    .sortedWith(
        compareBy<Event> { it.start !is IcsTimeValue.Date }
            .thenBy { it.localStart(zone).time.let { time -> time.hour * 60 + time.minute } },
    )

/** The slice of [event] that falls on [date] in [zone], clipped to the day. */
fun Event.timeRangeOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Int, Int> {
    if (start is IcsTimeValue.Date) return 0 to EndOfDayMinutes
    val rawStart = localStart(zone).time.let { it.hour * 60 + it.minute }
    val rawEnd = localEnd(zone)?.time?.let { it.hour * 60 + it.minute } ?: rawStart
    // Slicing past the occurrence (or an uncovered date) has no meaningful clip, so the raw range is
    // returned for callers that bypass occursOn.
    val displayStart = occurrenceStartOn(date, zone) ?: return rawStart to rawEnd
    val wallStart = wallDateFor(displayStart, zone)
    val startInstant = occurrenceStartInstant(wallStart, wallStartTime(), zone)
    val endInstant = startInstant + occurrenceDurationOn(wallStart)
    val startLocal = startInstant.toLocalDateTime(zone)
    val endLocal = endInstant.toLocalDateTime(zone)
    // An end at exactly midnight occupies the previous day through 24:00.
    val lastDisplay = if (endLocal.date > startLocal.date && endLocal.time == LocalTime(0, 0)) {
        endLocal.date.plusDays(-1)
    } else {
        endLocal.date
    }
    if (date > lastDisplay) return rawStart to rawEnd
    val start = if (date == startLocal.date) startLocal.hour * 60 + startLocal.minute else 0
    val end = when {
        date < lastDisplay -> EndOfDayMinutes
        endLocal.time == LocalTime(0, 0) && lastDisplay != endLocal.date -> EndOfDayMinutes
        else -> endLocal.hour * 60 + endLocal.minute
    }
    return start to end
}

/** Whether the event spans more than one display day (a midnight end counts as spanning). */
fun Event.isMultiDay(): Boolean = endDay > day
