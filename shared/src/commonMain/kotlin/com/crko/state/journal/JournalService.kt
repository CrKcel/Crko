package com.crko.state.journal

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.IcsTimeValue
import com.crko.domain.Journal
import com.crko.domain.targetCalendarIndex
import com.crko.state.DeleteOutcome
import com.crko.state.DeleteUndo
import com.crko.state.DeleteUndoStore
import com.crko.state.PlacedRecord
import com.crko.state.StateAccess
import com.crko.state.WriteResult
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/** Journal ordering and mutations; keeps the stored UUID/dtStamp bookkeeping in one place. */
internal class JournalService(
    private val access: StateAccess,
    private val clock: Clock,
    private val idProvider: () -> String,
    private val undo: DeleteUndoStore,
) {
    private val state: AppState get() = access.state

    /** Index of the calendar new local records go into; a payload always keeps at least one. */
    private val targetCalendarIndex: Int
        get() = state.targetCalendarIndex(access.defaultCalendarId)

    /** Index of the calendar that owns the record selected by [contains]. */
    private fun calendarIndexContaining(contains: (Calendar) -> Boolean): Int? =
        state.calendars.indexOfFirst(contains).takeIf { it >= 0 }

    /** A calendar explicitly chosen in the editor; null when it no longer exists. */
    private fun requestedCalendarIndex(id: String?): Int? =
        id?.let { calendarId -> state.calendars.indexOfFirst { it.id == calendarId }.takeIf { it >= 0 } }

    /** Replaces one calendar, leaving every other calendar's records untouched. */
    private fun AppState.replacingCalendar(index: Int, transform: (Calendar) -> Calendar): AppState {
        if (index !in calendars.indices) return this
        val updated = calendars.toMutableList()
        updated[index] = transform(updated[index])
        return copy(calendars = updated)
    }

    /** Journals sorted newest first; undated ones keep their import order at the end. */
    fun journalsSorted(): List<Journal> = state.visibleJournals
        .sortedWith(
            compareByDescending<Journal> { it.date != null }
                .thenByDescending { it.date ?: "" },
        )

    fun addJournal(title: String, note: String, date: LocalDate?, calendarId: String? = null): WriteResult {
        if (title.isBlank() && note.isBlank()) return WriteResult.EmptyTitle
        val now = clock.now()
        val journal = Journal(
            id = idProvider(),
            title = title.trim(),
            note = note,
            start = date?.let(IcsTimeValue::Date),
            lastModified = now.toString(),
            dtStamp = now.toString(),
        )
        access.persist(
            state.replacingCalendar(requestedCalendarIndex(calendarId) ?: targetCalendarIndex) {
                it.copy(journals = it.journals + journal)
            },
        )
        return WriteResult.Success
    }

    fun updateJournal(journal: Journal): WriteResult {
        val index = calendarIndexContaining { calendar -> calendar.journals.any { it.id == journal.id } }
            ?: return WriteResult.Success
        val now = clock.now()
        access.persist(
            state.replacingCalendar(index) { calendar ->
                calendar.copy(
                    journals = calendar.journals.map {
                        if (it.id == journal.id) {
                            journal.copy(
                                lastModified = now.toString(),
                                dtStamp = now.toString(),
                                sequence = journal.sequence + 1,
                            )
                        } else {
                            it
                        }
                    },
                )
            },
        )
        return WriteResult.Success
    }

    /**
     * Removes [id] and keeps it as the undo target, so a mis-tapped delete can be restored in its
     * calendar at the position it had.
     */
    fun deleteJournal(id: String): DeleteOutcome {
        val index = calendarIndexContaining { calendar -> calendar.journals.any { it.id == id } }
            ?: return DeleteOutcome.Nothing
        val calendar = state.calendars[index]
        val removed = calendar.journals.withIndex()
            .filter { (_, journal) -> journal.id == id }
            .map { PlacedRecord(it.value, it.index) }
        if (removed.isEmpty()) return DeleteOutcome.Nothing
        undo.record(DeleteUndo.Journals(calendar.id, removed))
        access.persist(
            state.replacingCalendar(index) { current ->
                current.copy(journals = current.journals.filterNot { it.id == id })
            },
        )
        return DeleteOutcome.Removed
    }
}
