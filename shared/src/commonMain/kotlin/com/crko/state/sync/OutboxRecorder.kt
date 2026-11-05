package com.crko.state.sync

import com.crko.domain.AppState
import com.crko.domain.CalendarComponent
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.domain.recurrenceKeyText
import com.crko.domain.withAssignedUid
import com.crko.storage.OutboxEntry
import com.crko.storage.OutboxStore

/**
 * Queues the local changes of writable CalDAV calendars while a user edit is persisted. It diffs
 * the previous state into the next one, assigns publish UIDs to records the server has never seen,
 * and upserts one pending change per record — the unique outbox key collapses a burst of edits of
 * the same record into a single pending push.
 */
internal class OutboxRecorder(
    private val outbox: OutboxStore,
    private val nowMillis: () -> Long,
) {
    /** Calendars whose pending rows changed; the caller triggers a prompt push for each. */
    val touchedCalendars: Set<String> get() = touched
    private val touched = mutableSetOf<String>()

    /**
     * Diffs [old] into [next] and returns the state to persist: [next] with any assigned publish
     * UIDs applied. Calendars without write access pass through untouched, and removing a writable
     * calendar only clears its queue — the server collection itself is not touched.
     */
    fun record(old: AppState, next: AppState): AppState {
        touched.clear()
        val nextIds = next.calendars.mapTo(mutableSetOf()) { it.id }
        old.calendars.filter { it.syncState?.writable == true && it.id !in nextIds }.forEach { gone ->
            outbox.deleteForCalendar(gone.id)
        }
        var calendars = next.calendars
        next.calendars.forEachIndexed { index, calendar ->
            if (calendar.syncState?.writable != true) return@forEachIndexed
            val previous = old.calendars.firstOrNull { it.id == calendar.id }
            val events = diffEvents(calendar.id, previous?.events, calendar.events)
            val todos = diffTodos(calendar.id, previous?.todos, calendar.todos)
            val journals = diffJournals(calendar.id, previous?.journals, calendar.journals)
            if (events !== calendar.events || todos !== calendar.todos || journals !== calendar.journals) {
                calendars = calendars.toMutableList().also {
                    it[index] = calendar.copy(events = events, todos = todos, journals = journals)
                }
            }
        }
        return next.copy(calendars = calendars)
    }

    private fun diffEvents(calendarId: String, old: List<Event>?, new: List<Event>): List<Event> {
        val oldById = old.orEmpty().associateBy { it.id }
        val newIds = new.mapNotNullTo(mutableSetOf()) { it.id }
        var assignedUid = false
        val prepared = new.map { record ->
            val previous = record.id?.let(oldById::get)
            when {
                previous == null -> {
                    // The publish UID is assigned at queue time, so the pending change keeps a
                    // stable key and the first push uploads an already-identified record.
                    val withUid = record.withAssignedUid(record.id) as Event
                    assignedUid = assignedUid || withUid !== record
                    enqueue(calendarId, EntityEvent, withUid, create = true)
                    withUid
                }
                !contentEquals(previous, record) -> {
                    enqueue(calendarId, EntityEvent, record, create = false)
                    record
                }
                else -> record
            }
        }
        old.orEmpty().forEach { record ->
            if (record.id !in newIds) enqueueDeletion(calendarId, EntityEvent, record)
        }
        return if (assignedUid) prepared else new
    }

    private fun diffTodos(calendarId: String, old: List<Todo>?, new: List<Todo>): List<Todo> {
        val oldById = old.orEmpty().associateBy { it.id }
        val newIds = new.mapNotNullTo(mutableSetOf()) { it.id }
        var assignedUid = false
        val prepared = new.map { record ->
            val previous = record.id?.let(oldById::get)
            when {
                previous == null -> {
                    val withUid = record.withAssignedUid(record.id) as Todo
                    assignedUid = assignedUid || withUid !== record
                    enqueue(calendarId, EntityTodo, withUid, create = true)
                    withUid
                }
                !contentEquals(previous, record) -> {
                    enqueue(calendarId, EntityTodo, record, create = false)
                    record
                }
                else -> record
            }
        }
        old.orEmpty().forEach { record ->
            if (record.id !in newIds) enqueueDeletion(calendarId, EntityTodo, record)
        }
        return if (assignedUid) prepared else new
    }

    private fun diffJournals(calendarId: String, old: List<Journal>?, new: List<Journal>): List<Journal> {
        val oldById = old.orEmpty().associateBy { it.id }
        val newIds = new.mapNotNullTo(mutableSetOf()) { it.id }
        var assignedUid = false
        val prepared = new.map { record ->
            val previous = record.id?.let(oldById::get)
            when {
                previous == null -> {
                    val withUid = record.withAssignedUid(record.id) as Journal
                    assignedUid = assignedUid || withUid !== record
                    enqueue(calendarId, EntityJournal, withUid, create = true)
                    withUid
                }
                !contentEquals(previous, record) -> {
                    enqueue(calendarId, EntityJournal, record, create = false)
                    record
                }
                else -> record
            }
        }
        old.orEmpty().forEach { record ->
            if (record.id !in newIds) enqueueDeletion(calendarId, EntityJournal, record)
        }
        return if (assignedUid) prepared else new
    }

    /** Content equality that ignores sync bookkeeping: a fresh etag is not an edit. */
    private fun contentEquals(previous: Event, record: Event): Boolean =
        previous.copy(sync = record.sync) == record

    private fun contentEquals(previous: Todo, record: Todo): Boolean =
        previous.copy(sync = record.sync) == record

    private fun contentEquals(previous: Journal, record: Journal): Boolean =
        previous.copy(sync = record.sync) == record

    /**
     * Queues [record]'s current content. A pending change of the same key is replaced, so the last
     * edit wins; a delete after a pending create cancels the row, because nothing was ever sent.
     */
    private fun enqueue(calendarId: String, entity: String, record: CalendarComponent, create: Boolean) {
        val uid = record.icsUid ?: return
        val recurrenceKey = recurrenceKeyText(record.recurrenceId)
        val existing = outbox.entry(calendarId, entity, uid, recurrenceKey)
        val operation = if (create && record.sync?.href == null) {
            OutboxEntry.Operation.Create
        } else {
            OutboxEntry.Operation.Update
        }
        val baseEtag = if (operation == OutboxEntry.Operation.Create) {
            null
        } else {
            record.sync?.etag ?: existing?.baseEtag
        }
        if (existing == null) {
            val now = nowMillis()
            outbox.upsert(
                OutboxEntry(
                    calendarId = calendarId,
                    entity = entity,
                    uid = uid,
                    recurrenceKey = recurrenceKey,
                    operation = operation,
                    sequence = record.sequence.toLong(),
                    baseEtag = baseEtag,
                    href = record.sync?.href,
                    createdMs = now,
                    updatedMs = now,
                ),
            )
        } else {
            outbox.upsert(
                existing.copy(
                    operation = operation,
                    sequence = record.sequence.toLong(),
                    baseEtag = baseEtag,
                    href = record.sync?.href ?: existing.href,
                    updatedMs = nowMillis(),
                ),
            )
        }
        touched += calendarId
    }

    /**
     * Queues the removal of [record]. A record the server never saw simply drops its pending
     * change; a pushed record becomes a delete addressed by its stored href.
     */
    private fun enqueueDeletion(calendarId: String, entity: String, record: CalendarComponent) {
        val uid = record.icsUid ?: return
        val recurrenceKey = recurrenceKeyText(record.recurrenceId)
        val existing = outbox.entry(calendarId, entity, uid, recurrenceKey)
        if (record.sync?.href == null) {
            if (existing != null && !existing.isDelete) outbox.delete(existing.id)
            return
        }
        if (existing == null) {
            val now = nowMillis()
            outbox.upsert(
                OutboxEntry(
                    calendarId = calendarId,
                    entity = entity,
                    uid = uid,
                    recurrenceKey = recurrenceKey,
                    operation = OutboxEntry.Operation.Delete,
                    sequence = record.sequence.toLong(),
                    baseEtag = record.sync?.etag,
                    href = record.sync?.href,
                    createdMs = now,
                    updatedMs = now,
                ),
            )
        } else {
            outbox.upsert(
                existing.copy(
                    operation = OutboxEntry.Operation.Delete,
                    sequence = record.sequence.toLong(),
                    baseEtag = record.sync?.etag ?: existing.baseEtag,
                    href = record.sync?.href ?: existing.href,
                    updatedMs = nowMillis(),
                ),
            )
        }
        touched += calendarId
    }

    private companion object {
        const val EntityEvent = "event"
        const val EntityTodo = "todo"
        const val EntityJournal = "journal"
    }
}
