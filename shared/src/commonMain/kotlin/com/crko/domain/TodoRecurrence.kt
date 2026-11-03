package com.crko.domain

import kotlinx.datetime.LocalDate

// ----- recurring todos (VTODO) -----

/** Anchor of a todo series: `DTSTART`, falling back to `DUE`; null when the todo has no dates. */
val Todo.seriesAnchor: LocalDate?
    get() = (start ?: due)?.wallDate

/** Whole days from the series anchor to the date an occurrence is shown under. */
private fun Todo.displayOffset(anchor: LocalDate): Int =
    due?.wallDate?.let { anchor.daysTo(it) } ?: 0

/** Date an occurrence starting on [occStart] is shown under: its due, or the start without a due. */
fun Todo.occurrenceDate(occStart: LocalDate): LocalDate {
    val anchor = seriesAnchor ?: return occStart
    return occStart.plusDays(displayOffset(anchor))
}

/** Recurrence set a todo series expands, including the typed exclusions the model stores. */
private fun Todo.recurrenceSet(): Recurrence? {
    if (repeat == null && rDates.isEmpty() && excludedDates.isEmpty()) return null
    return Recurrence(
        rule = repeat,
        rdates = rDates,
        exdates = excludedDates,
    )
}

/** Time of day the series' anchor (`DTSTART`, falling back to `DUE`) names; null for date-only. */
private fun Todo.seriesStartTime(): OccurrenceTime? {
    val value = start ?: due ?: return null
    val time = value.wallTime ?: return null
    return OccurrenceTime(time.hour * 60 + time.minute, time.second)
}

/**
 * Whether [todo] is shown on [date]: the stored date for a one-off todo, and the display date of
 * an occurrence for a series.
 */
fun Todo.occursOn(date: LocalDate): Boolean {
    val anchor = seriesAnchor ?: return false
    val rule = repeat
    val recurrence = recurrenceSet()
    if (rule == null && recurrence == null) return date == occurrenceDate(anchor)
    val occStart = date.plusDays(-displayOffset(anchor))
    return isRuleOccurrence(
        anchor,
        rule,
        recurrence,
        occStart,
        startTime = seriesStartTime(),
        count = rule?.count,
        anchorValue = start ?: due,
    )
}

/**
 * Occurrence start dates whose display date falls inside [from]..[to] (inclusive), ordered. Empty
 * for one-off todos, which are anchored on their stored date alone.
 */
fun Todo.occurrenceStartsBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
    val rule = repeat ?: return emptyList()
    val anchor = seriesAnchor ?: return emptyList()
    val offset = displayOffset(anchor)
    // Occurrences are shown under their display date, so the window sits offset days earlier here.
    val dates = mutableListOf<LocalDate>()
    var candidate = maxOf(anchor, from.plusDays(-offset))
    val last = to.plusDays(-offset)
    var steps = 0
    while (candidate <= last && steps <= NextOccurrenceSearchDays) {
        if (isRuleOccurrence(
                anchor,
                rule,
                recurrenceSet(),
                candidate,
                startTime = seriesStartTime(),
                count = rule.count,
                anchorValue = start ?: due,
            )
        ) {
            dates += candidate
        }
        candidate = candidate.plusDays(1)
        steps++
    }
    return dates
}

/**
 * Display date of the next occurrence on or after [today]; the stored due date is the fallback
 * when the series has already ended or the search window ran out.
 */
fun Todo.nextOccurrenceDate(today: LocalDate): LocalDate? {
    val anchor = seriesAnchor ?: return null
    val rule = repeat ?: return occurrenceDate(anchor)
    val offset = displayOffset(anchor)
    // An occurrence is shown under its display date, so the first candidate starts that many days
    // before today.
    var candidate = maxOf(anchor, today.plusDays(-offset))
    var steps = 0
    while (steps <= NextOccurrenceSearchDays) {
        if (isRuleOccurrence(
                anchor,
                rule,
                recurrenceSet(),
                candidate,
                startTime = seriesStartTime(),
                count = rule.count,
                anchorValue = start ?: due,
            )
        ) {
            return candidate.plusDays(offset)
        }
        candidate = candidate.plusDays(1)
        steps++
    }
    // The series is over, or its next occurrence lies beyond the search window: the stored due
    // stays what the todo is shown and sorted under, like the one-off branch above returns.
    return occurrenceDate(anchor)
}

/** Days the next-occurrence search walks before giving up and falling back to the stored due. */
private const val NextOccurrenceSearchDays = 366 * 5
