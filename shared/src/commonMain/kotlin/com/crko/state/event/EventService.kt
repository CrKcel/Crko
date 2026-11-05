package com.crko.state.event

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.boundedBefore
import com.crko.domain.daysTo
import com.crko.domain.movedByWallDays
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
 * injected permission callback, so the facade only delegates. Deletion lives in [EventDeletion]
 * because it also has to keep the undo snapshot.
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
                // The editor builds a fresh Event, so the CalDAV resource location of the stored
                // record has to be carried over: the next upload needs its etag for `If-Match`.
                val saved = validated.copy(
                    created = existing?.created ?: validated.created ?: now.toString(),
                    lastModified = now.toString(),
                    dtStamp = now.toString(),
                    sequence = (existing?.sequence ?: validated.sequence) + if (existing == null) 0 else 1,
                    sync = validated.sync ?: existing?.sync,
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
            sync = edited.sync ?: series.sync,
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
            // The exception shares the master's UID, which is what RFC 5545 requires — and with it
            // the CalDAV resource the whole group publishes under.
            icsUid = seriesUid,
            recurrenceId = slot,
            recurrenceRange = null,
            created = existingOverride?.created ?: now.toString(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = (existingOverride?.sequence ?: 0) + if (existingOverride == null) 0 else 1,
            sync = existingOverride?.sync ?: series.sync,
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
            // The new series has never been on the server: any location the editor carried
            // belonged to the old resource.
            sync = null,
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
                            // The new series publishes under a fresh UID and resource, so the
                            // re-parented exceptions must not keep the old location.
                            sync = null,
                        )
                    }
                else -> current
            }
        } + newSeries
        access.persist(state.replacingCalendar(calendarIndex) { it.copy(events = events) })
        return WriteResult.Success
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
