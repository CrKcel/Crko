package com.crko.state.event

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.boundedBefore
import com.crko.domain.daysTo
import com.crko.domain.movedByWallDays
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.domain.plusWallDays
import com.crko.domain.recurrenceSlotOn
import com.crko.domain.shiftedByWallDays
import com.crko.domain.targetCalendarIndex
import com.crko.domain.wallDate
import com.crko.domain.wallDateFor
import com.crko.domain.withExclusion
import com.crko.state.EditScope
import com.crko.state.StateAccess
import com.crko.state.WriteResult
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Event mutations, including the three edit scopes of a repeating series. Validation lives in
 * [EventNormalizer]; persistence, reminder scheduling and messages go through [StateAccess] and the
 * injected permission callback, so the facade only delegates.
 */
internal class EventService(
    private val access: StateAccess,
    private val clock: Clock,
    private val idProvider: () -> String,
    private val ensureReminderPermission: () -> Unit = {},
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

    /**
     * Saves [event]. For a repeating event, [scope] decides whether the change applies to this
     * occurrence, this and all later occurrences, or the whole series. [calendarId] picks the
     * container of a new event; an edit stays in the calendar that already owns the record.
     */
    fun saveEvent(
        event: Event,
        occurrenceDate: LocalDate? = null,
        scope: EditScope = EditScope.AllEvents,
        calendarId: String? = null,
    ): WriteResult {
        val normalized = EventNormalizer.normalize(event) ?: return EventNormalizer.validationError(event)
        // The editor leaves new records without an id; assigning it here keeps identifier creation
        // out of the UI, which no longer needs to know how a platform makes one.
        val identified = if (normalized.id == null) normalized.copy(id = idProvider()) else normalized
        val existing = state.events.firstOrNull { it.id == identified.id }
        val validated = identified
        val now = clock.now()
        if (validated.alarms.isNotEmpty()) ensureReminderPermission()
        // An edit belongs to the calendar that owns the stored record; a new record goes to the
        // calendar the editor chose, or to the default one.
        val calendarIndex = existing?.let { stored ->
            calendarIndexContaining { calendar -> calendar.events.any { it.id == stored.id } }
        } ?: requestedCalendarIndex(calendarId) ?: targetCalendarIndex
        val storedEvents = state.calendars[calendarIndex].events
        return when {
            scope == EditScope.ThisEvent && existing?.repeat != null -> {
                saveThisOccurrence(storedEvents, existing, validated, occurrenceDate, now, calendarIndex)
            }
            scope == EditScope.ThisAndFuture && existing?.repeat != null -> {
                saveThisAndFuture(storedEvents, existing, validated, occurrenceDate, now, calendarIndex)
            }
            existing?.repeat != null && occurrenceDate != null -> {
                saveWholeSeries(storedEvents, existing, validated, occurrenceDate, now, calendarIndex)
            }
            else -> {
                val saved = validated.copy(
                    created = existing?.created ?: validated.created ?: now.toString(),
                    lastModified = now.toString(),
                    dtStamp = now.toString(),
                    sequence = (existing?.sequence ?: validated.sequence) + if (existing == null) 0 else 1,
                )
                val updated = if (existing != null) {
                    storedEvents.map { if (it.id == saved.id) saved else it }
                } else {
                    storedEvents + saved
                }
                access.persist(state.replacingCalendar(calendarIndex) { it.copy(events = updated) })
                WriteResult.Success
            }
        }
    }

    private fun saveWholeSeries(
        storedEvents: List<Event>,
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate,
        now: Instant,
        calendarIndex: Int,
    ): WriteResult {
        // The editor shows the occurrence's date, so the delta is relative to that occurrence.
        val shift = occurrenceDate.daysTo(edited.day)
        val anchorWall = series.wallDay.plusDays(shift)
        val shiftToAnchor = edited.wallDay.daysTo(anchorWall)
        // If the repeat rule itself was not edited, its typed dates move with the series too. This
        // keeps `UNTIL`, `RDATE` and `EXDATE` aligned with the new anchor. The stored set is merged
        // in because an editor loaded before an exception was added still carries the old one.
        val savedRecurrence = edited.recurrence?.let { set ->
            val merged = if (EventNormalizer.sameRepeatRule(set.rule, series.repeat)) {
                set.copy(
                    rdates = (series.recurrence?.rdates.orEmpty() + set.rdates).distinct(),
                    exdates = (series.recurrence?.exdates.orEmpty() + set.exdates).distinct(),
                )
            } else {
                set
            }
            if (shift == 0) merged else merged.shiftedByWallDays(shift)
        }
        val saved = edited.movedByWallDays(shiftToAnchor).copy(
            recurrence = savedRecurrence,
            created = series.created ?: now.toString(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = series.sequence + 1,
        )
        // Exceptions belong to the same timeline through their shared UID. Moving the series anchor
        // shifts their dates and RECURRENCE-IDs with it, so they cannot collide with or become
        // detached from the moved series.
        val seriesUid = series.uid
        val updated = storedEvents.mapNotNull { current ->
            val isException = seriesUid != null && current.icsUid == seriesUid && current.recurrenceId != null
            when {
                current.id == series.id -> saved
                // Without a recurrence the series no longer owns its exceptions.
                edited.recurrence == null && isException -> null
                shift != 0 && isException -> current.shiftedBy(shift).copy(
                    lastModified = now.toString(),
                    sequence = current.sequence + 1,
                )
                else -> current
            }
        }
        access.persist(state.replacingCalendar(calendarIndex) { it.copy(events = updated) })
        return WriteResult.Success
    }

    private fun saveThisOccurrence(
        storedEvents: List<Event>,
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate?,
        now: Instant,
        calendarIndex: Int,
    ): WriteResult {
        val occurrence = occurrenceDate ?: edited.day
        val seriesUid = series.uid
        // The full slot, not just its date, so two exceptions on one day stay distinguishable.
        val slot = series.recurrenceSlotOn(occurrence)
        val existingOverride = storedEvents.firstOrNull {
            it.icsUid == seriesUid && it.recurrenceId == slot
        }
        // The editor starts on the occurrence date, so a different date is an intentional move.
        val moved = edited.day != occurrence
        val shiftToSlot = if (moved) 0 else edited.wallDay.daysTo(series.wallDateFor(occurrence))
        val placed = edited.movedByWallDays(shiftToSlot)
        val override = placed.copy(
            id = existingOverride?.id ?: idProvider(),
            // RECURRENCE-ID stays on the original slot; DTSTART may move to another date.
            recurrence = null,
            // The exception shares the master's UID, which is what RFC 5545 requires.
            icsUid = seriesUid,
            recurrenceId = slot,
            recurrenceRange = null,
            created = existingOverride?.created ?: now.toString(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = (existingOverride?.sequence ?: 0) + if (existingOverride == null) 0 else 1,
        )
        val updatedSeries = series.copy(
            recurrence = series.recurrence.withExclusion(slot),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = series.sequence + 1,
        )
        val events = storedEvents.map {
            when {
                it.id == series.id -> updatedSeries
                it.id == existingOverride?.id -> override
                else -> it
            }
        }.let { list ->
            if (existingOverride == null) list + override else list
        }
        access.persist(state.replacingCalendar(calendarIndex) { it.copy(events = events) })
        return WriteResult.Success
    }

    private fun saveThisAndFuture(
        storedEvents: List<Event>,
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate?,
        now: Instant,
        calendarIndex: Int,
    ): WriteResult {
        val occurrence = occurrenceDate ?: edited.day
        if (occurrence <= series.day) {
            return saveWholeSeries(storedEvents, series, edited, occurrence, now, calendarIndex)
        }
        // RDATEs and EXDATEs belong to whichever segment covers their slot. A value kept on the
        // wrong side either duplicates the other segment or becomes a phantom occurrence on export.
        val oldRecurrence = series.recurrence?.boundedBefore(occurrence)?.first
        val oldSeries = series.copy(
            recurrence = oldRecurrence,
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = series.sequence + 1,
        )
        val futureRecurrence = when {
            edited.recurrence == null -> null
            EventNormalizer.sameRepeatRule(edited.repeat, series.repeat) ->
                // The old series already consumed part of the rule; a new bound would be wrong.
                edited.recurrence.copy(
                    rule = edited.recurrence.rule?.copy(until = null, count = null, raw = null),
                    rdates = edited.recurrence.rdates.filter { it.value.wallDate >= occurrence },
                    exdates = edited.recurrence.exdates.filter { it.wallDate >= occurrence },
                )
            else -> edited.recurrence
        }
        val moved = edited.day != occurrence
        val slotWall = series.wallDateFor(occurrence)
        val shiftToSlot = if (moved) 0 else edited.wallDay.daysTo(slotWall)
        val newId = idProvider()
        val newSeries = edited.movedByWallDays(shiftToSlot).copy(
            id = newId,
            recurrence = futureRecurrence,
            // A genuine new UID: this is a separate series rather than an exception, never a
            // synthetic id glued onto the old one.
            icsUid = null,
            recurrenceId = null,
            recurrenceRange = null,
            created = now.toString(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = 0,
        )
        val newUid = newSeries.uid
        val events = storedEvents.mapNotNull { current ->
            val isException = current.icsUid == series.uid && current.recurrenceId != null
            when {
                current.id == series.id -> oldSeries
                isException && current.recurrenceId.wallDate >= occurrence ->
                    if (futureRecurrence == null) {
                        null
                    } else {
                        current.copy(
                            icsUid = newUid,
                            lastModified = now.toString(),
                        )
                    }
                else -> current
            }
        } + newSeries
        access.persist(state.replacingCalendar(calendarIndex) { it.copy(events = events) })
        return WriteResult.Success
    }

    /** Removes an event by id, without touching the rest of a series. */
    fun deleteEvent(id: String?) {
        if (id == null) return
        val index = calendarIndexContaining { calendar -> calendar.events.any { it.id == id } } ?: return
        access.persist(state.replacingCalendar(index) { calendar ->
            calendar.copy(events = calendar.events.filterNot { it.id == id })
        })
    }

    /**
     * Deletes [event] using [scope]. For a repeating series this can skip one occurrence, cut the
     * series at an occurrence, or remove the series together with all of its exceptions.
     */
    fun deleteEvent(
        event: Event,
        occurrenceDate: LocalDate? = null,
        scope: EditScope = EditScope.AllEvents,
    ) {
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

        // An exception is removed on its own; the master only gains its slot as an exclusion.
        if (storedSlot != null) {
            val master = storedEvents.firstOrNull {
                it.id != stored.id && it.icsUid == stored.icsUid && it.recurrenceId == null
            }
            val updated = storedEvents.filterNot { it.id == stored.id }.map { current ->
                if (master != null && current.id == master.id) {
                    current.copy(recurrence = current.recurrence.withExclusion(storedSlot))
                } else {
                    current
                }
            }
            access.persist(state.replacingCalendar(storedCalendarIndex) { it.copy(events = updated) })
            return
        }

        // A one-off event is removed as is.
        if (stored.recurrence == null) {
            deleteEvent(stored.id)
            return
        }

        val seriesUid = stored.uid
        val belongsToSeries: (Event) -> Boolean = { current ->
            seriesUid != null && current.icsUid == seriesUid && current.recurrenceId != null
        }
        val updated = when (scope) {
            EditScope.ThisEvent -> storedEvents
                .filterNot { current -> belongsToSeries(current) && current.recurrenceId == occurrenceSlot }
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
        access.persist(state.replacingCalendar(storedCalendarIndex) { it.copy(events = updated) })
    }

    /**
     * Clears a single day: one-off events are removed while repeating events only skip that date,
     * so clearing today never wipes a whole series.
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

/** Moves an event (and its associated recurrence values) by whole calendar days. */
private fun Event.shiftedBy(days: Int): Event {
    if (days == 0) return this
    return movedByWallDays(days).copy(
        recurrence = recurrence?.shiftedByWallDays(days),
        recurrenceId = recurrenceId?.plusWallDays(days),
    )
}
