package com.crko.state.todo

import com.crko.domain.IcsTimeValue
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.effectiveUid
import com.crko.domain.occurrenceDate
import com.crko.domain.wallDate
import com.crko.domain.withWallDate
import kotlin.time.Instant

/**
 * Stored record of one completed occurrence: a `VTODO` sharing the master's `UID` and naming its
 * slot through `RECURRENCE-ID`. [slot] is the occurrence start the master's rule generates, in the
 * master's own value type, so `DTSTART` / `DUE` of the instance align with the master's fields.
 * The master itself stays untouched; it keeps projecting the next occurrence.
 */
internal fun Todo.completedOccurrence(slot: IcsTimeValue, id: String, now: Instant): Todo {
    val shown = occurrenceDate(slot.wallDate)
    return Todo(
        id = id,
        title = title,
        start = start?.withWallDate(slot.wallDate),
        due = due?.withWallDate(shown),
        completed = now,
        priority = priority,
        createdAt = now.toEpochMilliseconds(),
        note = note,
        location = location,
        url = url,
        categories = categories,
        relatedTo = relatedTo,
        icsUid = effectiveUid,
        created = now.toString(),
        lastModified = now.toString(),
        dtStamp = now.toString(),
        sequence = sequence + 1,
        status = TodoStatus.Completed,
        percentComplete = 100,
        recurrenceId = slot,
    )
}

/** The same instance completed again: a reopened occurrence keeps one record, not two. */
internal fun Todo.occurrenceCompleted(now: Instant): Todo = copy(
    completed = now,
    status = TodoStatus.Completed,
    percentComplete = 100,
    lastModified = now.toString(),
    dtStamp = now.toString(),
    sequence = sequence + 1,
)
