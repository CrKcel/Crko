package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Pure matching rules behind the global search: a query is a case-insensitive substring that may
 * hit any text field a record carries, and a date window asks whether the record has an occurrence
 * inside it. Nothing here reads storage, state or UI, so the matching can be unit-tested on its own.
 */

/** Days a date-window check may walk through a repeating record before giving up. */
internal const val MaxSearchRangeWalkDays = 366 * 10

/** Trimmed query, or null when there is nothing to search for. */
fun searchQueryText(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

/** Case-insensitive substring test that also accepts a missing field. */
private fun String?.containsQuery(query: String): Boolean =
    this != null && contains(query, ignoreCase = true)

private fun Iterable<String>.anyContainsQuery(query: String): Boolean =
    any { it.contains(query, ignoreCase = true) }

/** Whether any of [addresses] names [query] in its address or display name. */
private fun List<CalAddress>.anyMatchesQuery(query: String): Boolean =
    any { address -> address.calAddress.containsQuery(query) || address.cn.containsQuery(query) }

/** Title, notes, place, URL, categories and `UID` of a plain component. */
private fun CalendarComponent.commonFieldsMatch(query: String): Boolean =
    title.containsQuery(query) ||
        note.containsQuery(query) ||
        url.containsQuery(query) ||
        icsUid.containsQuery(query) ||
        categories.anyContainsQuery(query)

/**
 * Whether [event] carries [query] in any searchable field: title, notes, place, URL, categories,
 * organizer, attendees or `UID`. A blank query matches nothing.
 */
fun Event.matchesSearch(query: String?): Boolean {
    val needle = searchQueryText(query) ?: return false
    if (commonFieldsMatch(needle)) return true
    if (id.containsQuery(needle) || location.containsQuery(needle)) return true
    return (attendees + listOfNotNull(organizer)).anyMatchesQuery(needle)
}

/** Whether [todo] carries [query] in title, notes, place, URL, categories, sub-task links or `UID`. */
fun Todo.matchesSearch(query: String?): Boolean {
    val needle = searchQueryText(query) ?: return false
    if (commonFieldsMatch(needle)) return true
    if (id.containsQuery(needle) || location.containsQuery(needle)) return true
    return relatedTo.any { it.uid.containsQuery(needle) }
}

/** Whether [journal] carries [query] in title, notes, URL, categories or `UID`. */
fun Journal.matchesSearch(query: String?): Boolean {
    val needle = searchQueryText(query) ?: return false
    return commonFieldsMatch(needle) || id.containsQuery(needle)
}

/**
 * Category filter: an empty selection keeps every record, otherwise one of the record's categories
 * has to name one of the selected ones (case-insensitively).
 */
fun CalendarComponent.matchesSearchCategories(selected: Set<String>): Boolean {
    if (selected.isEmpty()) return true
    return categories.any { category -> selected.any { it.equals(category, ignoreCase = true) } }
}

/** Whether the record belongs to a repeating series, as master or as one of its instances. */
val Event.partOfSeries: Boolean
    get() = repeat != null || recurrence?.rdates?.isNotEmpty() == true || recurrenceId != null

/** Whether the todo is a series master or a stored occurrence instance. */
val Todo.partOfSeries: Boolean
    get() = repeat != null || isOccurrence

/** Whether the journal carries a recurrence set. */
val Journal.partOfSeries: Boolean
    get() = recurrence?.rule != null || recurrence?.rdates?.isNotEmpty() == true

/**
 * Whether [event] has an occurrence inside [from]..[to]; either end may be null for "no bound".
 * A repeating series is matched through [Event.occursOn] on the days of the window instead of being
 * expanded into instances, so an endless rule costs at most the window, not the series. One-off
 * records compare their occupied span against the window directly.
 */
fun Event.occursWithin(
    from: LocalDate?,
    to: LocalDate?,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    if (from == null && to == null) return true
    if (status == EventStatus.Cancelled) return false
    val recurrence = recurrence
    if (recurrence == null || (recurrence.rule == null && recurrence.rdates.isEmpty())) {
        return (from == null || lastOccupiedDay >= from) && (to == null || day <= to)
    }
    // An added instance may sit before the anchor, so `from` alone cannot decide the answer.
    val rdateHit = recurrence.rdates.any { rdate ->
        val date = rdate.value.displayDate(zone)
        (from == null || date >= from) && (to == null || date <= to)
    }
    if (rdateHit) return true
    val windowStart = from ?: day
    val windowEnd = to ?: windowStart.plusDays(MaxSearchRangeWalkDays)
    val ruleEnd = recurrence.rule?.until?.wallDate
    val last = if (ruleEnd != null && ruleEnd < windowEnd) ruleEnd else windowEnd
    if (windowStart > last) return false
    var date = windowStart
    var steps = 0
    while (date <= last && steps <= MaxSearchRangeWalkDays) {
        if (occursOn(date, zone)) return true
        date = date.plusDays(1)
        steps++
    }
    return false
}

/**
 * Whether [todo] is shown on a day of [from]..[to] — its own date, or any occurrence of its series.
 * Either bound may be null.
 */
fun Todo.occursWithin(from: LocalDate?, to: LocalDate?): Boolean {
    if (from == null && to == null) return true
    val anchor = seriesAnchor ?: return false
    val inWindow = { date: LocalDate -> (from == null || date >= from) && (to == null || date <= to) }
    if (inWindow(occurrenceDate(anchor))) return true
    if (repeat == null) return false
    val first = from ?: anchor
    val last = to ?: first.plusDays(MaxSearchRangeWalkDays)
    if (first > last) return false
    // The projection returns occurrence starts; the window is expressed in display dates.
    return occurrenceStartsBetween(first, last).any { inWindow(occurrenceDate(it)) }
}

/** Whether the journal's date falls inside [from]..[to]; an undated journal never matches a window. */
fun Journal.occursWithin(
    from: LocalDate?,
    to: LocalDate?,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    if (from == null && to == null) return true
    val date = start?.displayDate(zone) ?: return false
    return (from == null || date >= from) && (to == null || date <= to)
}
