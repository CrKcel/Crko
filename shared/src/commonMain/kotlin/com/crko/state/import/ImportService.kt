package com.crko.state.`import`

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.IcsPartStat
import com.crko.domain.IcsImport
import com.crko.domain.ItipDecision
import com.crko.domain.ItipMethod
import com.crko.domain.Todo
import com.crko.domain.applyingReply
import com.crko.domain.gatedCancellations
import com.crko.domain.itipDecision
import com.crko.domain.itipReply
import com.crko.domain.matchesReplyTarget
import com.crko.domain.mergeBusyPeriods
import com.crko.domain.mergeCalendarTimeZones
import com.crko.domain.mergeEvents
import com.crko.domain.mergeJournals
import com.crko.domain.mergeTodos
import com.crko.domain.registerIcsTimeZones
import com.crko.state.AppMessage
import com.crko.state.ImportOutcome
import com.crko.state.StateAccess
import kotlin.time.Clock

/**
 * Calendar import pipeline. Parsing stays in the caller (or the subscription service); this service
 * merges parsed records into the stored calendar, materializes attachments and applies iTIP replies.
 * Persistence and messages go through [StateAccess], so the facade only delegates.
 */
internal class ImportService(
    private val access: StateAccess,
    private val attachments: AttachmentMaterializer,
    private val clock: Clock,
    private val idProvider: () -> String,
) {
    private val state: AppState get() = access.state

    /** Index of the calendar new local records go into; a payload always keeps at least one. */
    private val localCalendarIndex: Int
        get() = state.calendars.indexOfFirst { it.isLocal }.takeIf { it >= 0 } ?: 0

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
     * Merges events parsed from an ICS file. A known UID is replaced when the file carries a newer
     * `SEQUENCE`/`DTSTAMP`, so re-importing an updated file does not keep stale copies.
     */
    fun importEvents(events: List<Event>): Int {
        val index = localCalendarIndex
        val merged = mergeEvents(
            base = state.calendars[index].events,
            incoming = events.map(attachments::materialize),
            cancelledUids = emptySet(),
            source = null,
            removeVanished = false,
            idProvider = idProvider,
        )
        if (merged.changed > 0 || merged.items != state.calendars[index].events) {
            access.persist(state.replacingCalendar(index) { it.copy(events = merged.items) })
        }
        return merged.changed
    }

    /** Merges todos from an ICS file, updating known UIDs newer than the stored copy. */
    fun importTodos(todos: List<Todo>): Int {
        val index = localCalendarIndex
        val merged = mergeTodos(
            base = state.calendars[index].todos,
            incoming = todos.map(attachments::materialize),
            source = null,
            removeVanished = false,
            idProvider = idProvider,
        )
        if (merged.changed > 0 || merged.items != state.calendars[index].todos) {
            access.persist(state.replacingCalendar(index) { it.copy(todos = merged.items) })
        }
        return merged.changed
    }

    /**
     * Merges everything one calendar message contained in a single write: two separate writes could
     * leave a stored calendar with the events of a half-finished import, and serialised the whole
     * state twice. [source] is the picked file's name; records keep it, so a later import of the
     * same source can tell "removed upstream" apart from a partial export.
     *
     * A calendar that carries an iTIP `METHOD` is not a plain snapshot: a `REPLY` only updates the
     * answering attendee's status and a `CANCEL` must carry a `SEQUENCE` at least as new as the
     * stored copy before it may remove anything.
     */
    fun importCalendar(
        import: IcsImport,
        source: String? = null,
        calendarId: String? = null,
    ): ImportOutcome {
        if (import.method == ItipMethod.Reply) return applyItipReply(import)
        val hasContent = import.events.isNotEmpty() || import.todos.isNotEmpty() ||
            import.journals.isNotEmpty() || import.cancelledUids.isNotEmpty() || import.freeBusy.isNotEmpty()
        val hasCalendarMeta = import.calendarName != null || import.refreshMinutes != null ||
            import.timeZones.isNotEmpty()
        if (!hasContent && !hasCalendarMeta && source.isNullOrBlank()) return ImportOutcome()
        val calendarIndex = calendarId
            ?.let { id -> state.calendars.indexOfFirst { it.id == id }.takeIf { it >= 0 } }
            ?: localCalendarIndex
        val baseCalendar = state.calendars[calendarIndex]
        // Registered under the calendar the records land in, so a file naming a TZID its own way
        // cannot redefine a same-named zone of another calendar.
        registerIcsTimeZones(import.timeZones, baseCalendar.id)
        val sourceName = source?.takeIf { it.isNotBlank() }
        // The vanished-record pass compares against every UID the file describes, not against the
        // kept rows alone: unchecking a preview row skips that one update without deleting the
        // local record the file still lists. A directly built import derives the set from its rows.
        val described = import.describedUids.ifEmpty {
            buildSet {
                import.events.mapNotNullTo(this) { it.icsUid }
                import.todos.mapNotNullTo(this) { it.icsUid }
                import.journals.mapNotNullTo(this) { it.icsUid }
                import.cancelledUids.mapTo(this) { it }
            }
        }
        // Removal of records that no longer appear is scoped per kind: a file that carries no todo
        // at all (or none but cancellations) says nothing about the user's todos.
        val events = mergeEvents(
            base = baseCalendar.events,
            incoming = import.events.map(attachments::materialize),
            cancelledUids = gatedCancellations(import, baseCalendar.events),
            source = sourceName,
            removeVanished = sourceName != null &&
                (import.events.isNotEmpty() || import.cancelledUids.isNotEmpty()),
            describedUids = described,
            idProvider = idProvider,
        )
        val todos = mergeTodos(
            base = baseCalendar.todos,
            incoming = import.todos.map(attachments::materialize),
            source = sourceName,
            removeVanished = sourceName != null && import.todos.isNotEmpty(),
            describedUids = described,
            idProvider = idProvider,
        )
        val journals = mergeJournals(
            base = baseCalendar.journals,
            incoming = import.journals.map(attachments::materialize),
            source = sourceName,
            removeVanished = sourceName != null && import.journals.isNotEmpty(),
            describedUids = described,
            idProvider = idProvider,
        )
        val outcome = ImportOutcome(
            events = events.changed,
            todos = todos.changed,
            journals = journals.changed,
            removed = events.removed + todos.removed + journals.removed,
        )
        val updatedCalendar = baseCalendar.copy(
            name = import.calendarName ?: baseCalendar.name,
            refreshMinutes = import.refreshMinutes ?: baseCalendar.refreshMinutes,
            method = import.method?.wireName ?: baseCalendar.method,
            timeZones = mergeCalendarTimeZones(baseCalendar.timeZones, import.timeZones),
            events = events.items,
            todos = todos.items,
            journals = journals.items,
        )
        val changed = updatedCalendar != baseCalendar || import.freeBusy.isNotEmpty()
        // An all-zero outcome is only "nothing happened" when the calendar did not move either;
        // provenance may have followed the file even though no record was added or replaced.
        if (outcome.isEmpty && !changed) return outcome
        if (changed) {
            access.persist(
                state.replacingCalendar(calendarIndex) { updatedCalendar }.copy(
                    busyPeriods = mergeBusyPeriods(state.busyPeriods, import.freeBusy, clock.now()),
                ),
            )
        }
        return outcome
    }

    /**
     * Applies an imported `METHOD:REPLY`: the replying attendee's `PARTSTAT` is written onto the
     * stored record the reply refers to. The whole outcome is one attendee status, so there is no
     * record preview for this method.
     */
    private fun applyItipReply(import: IcsImport): ImportOutcome {
        val reply = import.itipReply() ?: return ImportOutcome()
        val calendarIndex = calendarIndexContaining { calendar ->
            calendar.events.any { event -> event.matchesReplyTarget(reply) }
        } ?: return ImportOutcome().also { access.notify(AppMessage.ItipReplyUnknown(reply.attendee.displayName)) }
        val storedEvents = state.calendars[calendarIndex].events
        val target = storedEvents.firstOrNull { event -> event.matchesReplyTarget(reply) }
        if (target == null) {
            access.notify(AppMessage.ItipReplyUnknown(reply.attendee.displayName))
            return ImportOutcome()
        }
        // A reply that answers an older revision than the stored copy must not overwrite a newer
        // state; messages without a sequence are always allowed (mainstream senders omit it).
        if (itipDecision(reply.sequence, target.sequence) == ItipDecision.Stale) {
            access.notify(AppMessage.ItipReplyUnknown(reply.attendee.displayName))
            return ImportOutcome()
        }
        val result = storedEvents.applyingReply(reply, clock.now())
        if (!result.applied) return ImportOutcome()
        access.persist(state.replacingCalendar(calendarIndex) { it.copy(events = result.events) })
        access.notify(AppMessage.ItipReplyProcessed(reply.attendee.displayName, reply.attendee.partStat ?: IcsPartStat.NeedsAction))
        return ImportOutcome(events = 1)
    }
}
