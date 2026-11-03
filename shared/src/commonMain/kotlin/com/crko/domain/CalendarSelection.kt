package com.crko.domain

/** Number of records a calendar owns, shown by the delete confirmation. */
val Calendar.recordCount: Int
    get() = events.size + todos.size + journals.size

/**
 * Calendar new local records go into: the chosen default, otherwise the first local calendar, and
 * only when a payload has no local calendar at all the first calendar of any kind.
 */
fun AppState.targetCalendar(defaultCalendarId: String?): Calendar? {
    defaultCalendarId?.let { id -> calendars.firstOrNull { it.id == id } }?.let { return it }
    return calendars.firstOrNull { it.isLocal } ?: calendars.firstOrNull()
}

/** Index of [targetCalendar]; a payload always keeps at least one calendar. */
fun AppState.targetCalendarIndex(defaultCalendarId: String?): Int {
    val target = targetCalendar(defaultCalendarId) ?: return 0
    return calendars.indexOf(target)
}
