package com.crko.state.event

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.boundedBefore
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.recurrenceSlotOn
import com.crko.domain.targetCalendarIndex
import com.crko.domain.wallDate
import com.crko.domain.withExclusion
import com.crko.state.DeleteOutcome
import com.crko.state.DeleteUndo
import com.crko.state.DeleteUndoStore
import com.crko.state.EditScope
import com.crko.state.PlacedRecord
import com.crko.state.StateAccess
import kotlinx.datetime.LocalDate

/**
 * Event deletion, including the three scopes of a repeating series. Every delete first records what
 * it removes in the undo store, so the facade can restore the most recent one; persistence goes
 * through [StateAccess] like every other mutation.
 */
internal class EventDeletion(
    private val access: StateAccess,
    private val undo: DeleteUndoStore,
) {
    private val state: AppState get() = access.state

    /** Index of the calendar new local records go into; a payload always keeps at least one. */
    private val targetCalendarIndex: Int
        get() = state.targetCalendarIndex(access.defaultCalendarId)

    /** Index of the calendar that owns the record selected by [contains]. */
    private fun calendarIndexContaining(contains: (Calendar) -> Boolean): Int? =
        state.calendars.indexOfFirst(contains).takeIf { it >= 0 }

    /** Replaces one calendar, leaving every other calendar's records untouched. */
    private fun AppState.replacingCalendar(index: Int, transform: (Calendar) -> Calendar): AppState {
        if (index !in calendars.indices) return this
        val updated = calendars.toMutableList()
        updated[index] = transform(updated[index])
        return copy(calendars = updated)
    }

    /**
     * Keeps the records [after] no longer contains as the undo target. Returns whether anything was
     * removed: a scope that only skips an occurrence leaves every record in place.
     */
    private fun captureRemoved(before: List<Event>, after: List<Event>, calendarId: String): Boolean {
        val kept = after.mapNotNullTo(mutableSetOf()) { it.id }
        val removed = before.withIndex()
            .filter { (_, event) -> event.id == null || event.id !in kept }
            .map { PlacedRecord(it.value, it.index) }
        if (removed.isEmpty()) return false
        undo.record(DeleteUndo.Events(calendarId, removed))
        return true
    }

    /** Removes an event by id, without touching the rest of a series. */
    fun deleteEvent(id: String?): DeleteOutcome {
        if (id == null) return DeleteOutcome.Nothing
        val index = calendarIndexContaining { calendar -> calendar.events.any { it.id == id } }
            ?: return DeleteOutcome.Nothing
        val calendar = state.calendars[index]
        val removed = calendar.events.withIndex()
            .filter { (_, event) -> event.id == id }
            .map { PlacedRecord(it.value, it.index) }
        if (removed.isEmpty()) return DeleteOutcome.Nothing
        undo.record(DeleteUndo.Events(calendar.id, removed))
        access.persist(
            state.replacingCalendar(index) { current ->
                current.copy(events = current.events.filterNot { it.id == id })
            },
        )
        return DeleteOutcome.Removed
    }

    /**
     * Deletes [event] using [scope]. For a repeating series this can skip one occurrence, cut the
     * series at an occurrence, or remove the series together with all of its exceptions.
     */
    fun deleteEvent(
        event: Event,
        occurrenceDate: LocalDate? = null,
        scope: EditScope = EditScope.AllEvents,
    ): DeleteOutcome {
        val storedCalendarIndex = calendarIndexContaining { calendar ->
            calendar.events.any { it.id == event.id }
        } ?: targetCalendarIndex
        val storedEvents = state.calendars[storedCalendarIndex].events
        val stored = storedEvents.firstOrNull { it.id == event.id } ?: event
        // An exception's slot is its RECURRENCE-ID even when the record was moved to another day;
        // for a series the opened date has to resolve to the start of the occurrence covering it,
        // so deleting a multi-day occurrence from its second day still hits the right slot.
        val occurrence = stored.recurrenceId?.wallDate
            ?: occurrenceDate?.let { stored.occurrenceStartOn(it) ?: it }
            ?: stored.day
        val storedSlot = stored.recurrenceId
        val occurrenceSlot = storedSlot ?: stored.recurrenceSlotOn(occurrence)

        val updated = when {
            // An exception is removed on its own; the master only gains its slot as an exclusion.
            storedSlot != null -> {
                val master = storedEvents.firstOrNull {
                    it.id != stored.id && it.icsUid == stored.icsUid && it.recurrenceId == null
                }
                storedEvents.filterNot { it.id == stored.id }.map { current ->
                    if (master != null && current.id == master.id) {
                        current.copy(recurrence = current.recurrence.withExclusion(storedSlot))
                    } else {
                        current
                    }
                }
            }
            // A one-off event is removed as is.
            stored.recurrence == null -> storedEvents.filterNot { it.id == stored.id }
            else -> {
                val seriesUid = stored.uid
                val belongsToSeries: (Event) -> Boolean = { current ->
                    seriesUid != null && current.icsUid == seriesUid && current.recurrenceId != null
                }
                when (scope) {
                    EditScope.ThisEvent -> storedEvents
                        .filterNot { current ->
                            belongsToSeries(current) && current.recurrenceId == occurrenceSlot
                        }
                        .map { current ->
                            if (current.id == stored.id) {
                                current.copy(recurrence = current.recurrence.withExclusion(occurrenceSlot))
                            } else {
                                current
                            }
                        }
                    EditScope.ThisAndFuture -> {
                        if (occurrence <= stored.day) {
                            storedEvents.filterNot { it.id == stored.id || belongsToSeries(it) }
                        } else {
                            storedEvents.mapNotNull { current ->
                                when {
                                    current.id == stored.id -> current.copy(
                                        recurrence = current.recurrence?.boundedBefore(occurrence)?.first,
                                    )
                                    belongsToSeries(current) && current.recurrenceId!!.wallDate >= occurrence -> null
                                    else -> current
                                }
                            }
                        }
                    }
                    EditScope.AllEvents -> storedEvents.filterNot { it.id == stored.id || belongsToSeries(it) }
                }
            }
        }
        if (updated == storedEvents) return DeleteOutcome.Nothing
        val removed = captureRemoved(storedEvents, updated, state.calendars[storedCalendarIndex].id)
        access.persist(state.replacingCalendar(storedCalendarIndex) { it.copy(events = updated) })
        return if (removed) DeleteOutcome.Removed else DeleteOutcome.Changed
    }

    /**
     * Clears a single day: one-off events are removed while repeating events only skip that date,
     * so clearing today never wipes a whole series. Clearing many records at once keeps no undo
     * target; the snapshot only ever holds the records of one delete.
     */
    fun deleteEventsOn(date: LocalDate) {
        val calendars = state.calendars.map { calendar ->
            calendar.copy(
                events = calendar.events.mapNotNull { event ->
                    when {
                        !event.occursOn(date) -> event
                        event.recurrenceId != null -> null
                        event.recurrence == null -> null
                        else -> {
                            val occurrence = event.occurrenceStartOn(date) ?: date
                            event.copy(recurrence = event.recurrence.withExclusion(event.recurrenceSlotOn(occurrence)))
                        }
                    }
                },
            )
        }
        access.persist(state.copy(calendars = calendars))
    }
}
