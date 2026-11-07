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

/**
 * Whether the todo carries a recurrence set (a rule, added dates or exclusions) and projects
 * occurrences instead of living on its stored date alone. This is the gate that matches
 * [occursOn]; testing `repeat` alone misses an RDATE-only series, which the importer produces
 * whenever a file adds dates without a rule.
 */
val Todo.isSeries: Boolean
    get() = repeat != null || rDates.isNotEmpty() || excludedDates.isNotEmpty()

/** Time of day the series' anchor (`DTSTART`, falling back to `DUE`) names; null for date-only. */
private fun Todo.seriesStartTime(): OccurrenceTime? {
    val value = start ?: due ?: return null
    val time = value.wallTime ?: return null
    return OccurrenceTime(time.hour * 60 + time.minute, time.second)
}

/**
 * Display dates of [instances] that belong to this series. An instance shares its master's `UID`
 * and names its slot through `RECURRENCE-ID`, so a completed occurrence is skipped by the master's
 * projection without the master itself having to change.
 */
fun Todo.instanceDates(instances: List<Todo>): Set<LocalDate> {
    val uid = effectiveUid
    return instances.mapNotNullTo(mutableSetOf()) { instance ->
        val slot = instance.recurrenceId ?: return@mapNotNullTo null
        if (instance.effectiveUid != uid) return@mapNotNullTo null
        occurrenceDate(slot.wallDate)
    }
}

/**
 * Whether [todo] is shown on [date]: the stored date for a one-off todo, and the display date of
 * an occurrence for a series. [instances] are the stored occurrences of the same series; their
 * slots count as taken, so completing one moves the projection on instead of repeating it.
 */
fun Todo.occursOn(date: LocalDate, instances: List<Todo> = emptyList()): Boolean {
    val anchor = seriesAnchor ?: return false
    if (date in instanceDates(instances)) return false
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
    val recurrence = recurrenceSet() ?: return emptyList()
    val rule = repeat
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
                recurrence,
                candidate,
                startTime = seriesStartTime(),
                count = rule?.count,
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
 * when the series has already ended or the search window ran out. Slots covered by [instances]
 * are skipped, so a completed occurrence never comes back as the next one.
 */
fun Todo.nextOccurrenceDate(today: LocalDate, instances: List<Todo> = emptyList()): LocalDate? {
    val anchor = seriesAnchor ?: return null
    val recurrence = recurrenceSet() ?: return occurrenceDate(anchor)
    val rule = repeat
    val offset = displayOffset(anchor)
    val covered = instanceDates(instances)
    // An occurrence is shown under its display date, so the first candidate starts that many days
    // before today.
    var candidate = maxOf(anchor, today.plusDays(-offset))
    var steps = 0
    while (steps <= NextOccurrenceSearchDays) {
        if (isRuleOccurrence(
                anchor,
                rule,
                recurrence,
                candidate,
                startTime = seriesStartTime(),
                count = rule?.count,
                anchorValue = start ?: due,
            )
        ) {
            val shown = candidate.plusDays(offset)
            if (shown !in covered) return shown
        }
        candidate = candidate.plusDays(1)
        steps++
    }
    // The series is over, or its next occurrence lies beyond the search window: the stored due
    // stays what the todo is shown and sorted under, like the one-off branch above returns.
    return occurrenceDate(anchor)
}

/**
 * `RECURRENCE-ID` of the occurrence shown on [date]: the series anchor in the record's own value
 * type, moved to the occurrence start. Used for the completion instance so its slot aligns with the
 * master's `DUE` / `DTSTART`.
 */
fun Todo.recurrenceSlotOn(date: LocalDate): IcsTimeValue? {
    val anchor = seriesAnchor ?: return null
    val value = start ?: due ?: return null
    return value.withWallDate(date.plusDays(-displayOffset(anchor)))
}

/** Days the next-occurrence search walks before giving up and falling back to the stored due. */
private const val NextOccurrenceSearchDays = 366 * 5
