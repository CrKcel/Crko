package com.crko.state

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo

/** Kind of record a delete removed; part of the snapshot so the caller knows what it holds. */
internal enum class DeleteKind { Event, Todo, Journal }

/** One deleted record and the index it occupied in its calendar's list. */
internal data class PlacedRecord<T>(val record: T, val index: Int)

/**
 * What one delete removed: the records themselves, the calendar that owned them, the record kind and
 * their original positions, so an undo can put every record back where it was. A series delete keeps
 * the master together with all of its exceptions/instances in one snapshot.
 *
 * Only the most recent delete is kept, and the snapshot lives in memory only: after a process
 * restart there is nothing to undo. That is deliberate — an undo target from a previous session
 * could resurrect records the user has since deleted for good.
 */
internal sealed interface DeleteUndo {
    val kind: DeleteKind

    /** Id of the calendar that owned the removed records. */
    val calendarId: String

    data class Events(
        override val calendarId: String,
        val entries: List<PlacedRecord<Event>>,
    ) : DeleteUndo {
        override val kind: DeleteKind get() = DeleteKind.Event
    }

    data class Todos(
        override val calendarId: String,
        val entries: List<PlacedRecord<Todo>>,
    ) : DeleteUndo {
        override val kind: DeleteKind get() = DeleteKind.Todo
    }

    data class Journals(
        override val calendarId: String,
        val entries: List<PlacedRecord<Journal>>,
    ) : DeleteUndo {
        override val kind: DeleteKind get() = DeleteKind.Journal
    }
}

/** Outcome of putting a snapshot back; the [state] is only present when the restore succeeded. */
internal sealed interface DeleteRestore {
    data class Restored(val state: AppState) : DeleteRestore

    /** A record with one of the deleted ids exists again; overwriting it would lose live data. */
    data object Conflict : DeleteRestore

    /** The calendar that owned the records no longer exists. */
    data object CalendarMissing : DeleteRestore
}

/** Result of [AppStateHolder.undoLastDelete], so the UI can word the feedback. */
enum class UndoResult { Success, NothingToUndo, Unavailable }

/** What one delete did, so the facade can announce it and decide whether undo is offered. */
internal enum class DeleteOutcome {
    /** No stored record matched the request; nothing changed. */
    Nothing,

    /** The store changed but no record was removed, e.g. an occurrence that was only skipped. */
    Changed,

    /** Records were removed and kept as the undo target. */
    Removed,
}

/** Keeps the snapshot of the most recent delete; memory only, never persisted. */
internal class DeleteUndoStore {
    var last: DeleteUndo? = null
        private set

    /** Replaces the previous snapshot: only one delete can be undone at a time. */
    fun record(snapshot: DeleteUndo) {
        last = snapshot
    }

    fun clear() {
        last = null
    }
}

/**
 * The state with the snapshot's records back in their original calendar and positions, or why that
 * is impossible. Records are inserted in their recorded order, so a restored series keeps the order
 * it had before it was deleted.
 */
internal fun DeleteUndo.restore(state: AppState): DeleteRestore {
    val index = state.calendars.indexOfFirst { it.id == calendarId }
    if (index < 0) return DeleteRestore.CalendarMissing
    val calendar = state.calendars[index]
    val restored: Calendar = when (this) {
        is DeleteUndo.Events -> calendar.copy(
            events = calendar.events.reinserting(entries) { it.id } ?: return DeleteRestore.Conflict,
        )
        is DeleteUndo.Todos -> calendar.copy(
            todos = calendar.todos.reinserting(entries) { it.id } ?: return DeleteRestore.Conflict,
        )
        is DeleteUndo.Journals -> calendar.copy(
            journals = calendar.journals.reinserting(entries) { it.id } ?: return DeleteRestore.Conflict,
        )
    }
    val calendars = state.calendars.toMutableList()
    calendars[index] = restored
    return DeleteRestore.Restored(state.copy(calendars = calendars))
}

/**
 * Puts [entries] back at their recorded indices; null when one of their ids is already in use.
 * Indices are clamped, so a list that changed in the meantime still receives every record.
 */
private fun <T> List<T>.reinserting(entries: List<PlacedRecord<T>>, id: (T) -> String?): List<T>? {
    val incoming = entries.mapNotNullTo(mutableSetOf()) { id(it.record) }
    if (any { id(it) in incoming }) return null
    val result = toMutableList()
    entries.sortedBy { it.index }.forEach { placed ->
        result.add(placed.index.coerceIn(0, result.size), placed.record)
    }
    return result
}
