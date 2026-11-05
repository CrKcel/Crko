package com.crko.domain

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * One CalDAV resource: every record of a calendar sharing a UID — the master plus each exception —
 * grouped for one `PUT`. Servers store a whole UID as a single collection member, so the two-way
 * sync always moves a group, never a lone record.
 */
data class CalDavResource(
    val uid: String,
    val events: List<Event> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val journals: List<Journal> = emptyList(),
) {
    val isEmpty: Boolean get() = events.isEmpty() && todos.isEmpty() && journals.isEmpty()

    /** Every record of the group, whatever its kind. */
    val components: List<CalendarComponent> get() = events + todos + journals

    /** Kind label for the outbox; a UID group never mixes kinds. */
    val entity: String
        get() = when {
            events.isNotEmpty() -> "event"
            todos.isNotEmpty() -> "todo"
            else -> "journal"
        }
}

/**
 * Collects the records of [calendar] that publish under [uid]: records whose `icsUid` matches, plus
 * unsynced records whose storage id is the own-export form of the UID (`<id>@crko`). Null when the
 * calendar holds no such record.
 */
fun Calendar.uidGroup(uid: String): CalDavResource? {
    val resource = CalDavResource(
        uid = uid,
        events = events.filter { matchesUid(it, uid) },
        todos = todos.filter { matchesUidRecord(it, uid) },
        journals = journals.filter { matchesUidRecord(it, uid) },
    )
    return resource.takeUnless { it.isEmpty }
}

private fun matchesUidRecord(todo: Todo, uid: String): Boolean =
    todo.icsUid == uid || matchesOwnExport(todo.id, uid)

private fun matchesUidRecord(journal: Journal, uid: String): Boolean =
    journal.icsUid == uid || matchesOwnExport(journal.id, uid)

/**
 * Serializes the group into one VCALENDAR for upload, carrying the calendar's VTIMEZONEs so the
 * server copy is readable by clients that only know the referenced TZIDs.
 */
fun CalDavResource.toIcsText(
    calendar: Calendar,
    now: Instant = Clock.System.now(),
): String = exportCalendar(
    events = events,
    todos = todos,
    now = now,
    journals = journals,
    timeZones = calendar.timeZones,
)

/**
 * Assigns the UID the record publishes under: the own-export form `<recordId>@crko`, so a later
 * server answer maps back onto this exact record via the merge rules. Records already carrying a
 * UID (downloaded, or uploaded once) keep it.
 */
fun CalendarComponent.withAssignedUid(recordId: String?): CalendarComponent = when (this) {
    is Event -> if (icsUid != null || recordId == null) this else copy(icsUid = "$recordId@crko")
    is Todo -> if (icsUid != null || recordId == null) this else copy(icsUid = "$recordId@crko")
    is Journal -> if (icsUid != null || recordId == null) this else copy(icsUid = "$recordId@crko")
    else -> this
}

/**
 * Stable text encoding of `RECURRENCE-ID`, shared by the SQLite index columns and the outbox keys,
 * so both spell a series exception the same way. Empty string for a master or standalone record.
 */
fun recurrenceKeyText(value: IcsTimeValue?): String = when (value) {
    null -> ""
    is IcsTimeValue.Date -> "date:${value.date}"
    is IcsTimeValue.Floating -> "floating:${value.dateTime}"
    is IcsTimeValue.Zoned -> "zoned:${value.tzid}:${value.dateTime}"
    is IcsTimeValue.Utc -> "utc:${value.instant}"
}
