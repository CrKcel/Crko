package com.crko.state.sync

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarComponent
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.domain.recurrenceKeyText
import com.crko.domain.withAssignedUid
import com.crko.storage.OutboxBackend
import com.crko.storage.OutboxEntry
import com.crko.storage.OutboxStore

/**
 * Queues the local changes of sync-managed calendars while a user edit is persisted. It diffs the
 * previous state into the next one, assigns publish UIDs to records the server has never seen, and
 * upserts one pending change per record per backend — the unique outbox key collapses a burst of
 * edits of the same record into a single pending push. A writable CalDAV calendar drains into its
 * own server, a local calendar of an active cloud account drains into crko.cloud, and the keyed
 * queue happily holds both at once for the same record.
 */
internal class OutboxRecorder(
    private val outbox: OutboxStore,
    private val nowMillis: () -> Long,
    /** Whether a cloud account currently syncs; local calendars fan out into cloud rows when set. */
    private val cloudActive: () -> Boolean = { false },
) {
    /** Calendars whose pending rows changed; the caller triggers a prompt push for each. */
    val touchedCalendars: Set<String> get() = touched
    private val touched = mutableSetOf<String>()

    /**
     * Diffs [old] into [next] and returns the state to persist: [next] with any assigned publish
     * UIDs applied. Removing a writable CalDAV calendar only clears its queue — the server
     * collection itself is not touched. Removing a cloud-synced local calendar queues tombstones so
     * the deletion reaches the other devices.
     */
    fun record(old: AppState, next: AppState): AppState {
        touched.clear()
        val cloud = cloudActive()
        val nextIds = next.calendars.mapTo(mutableSetOf()) { it.id }
        old.calendars.filter { it.id !in nextIds }.forEach { gone ->
            when {
                gone.syncState?.writable == true -> outbox.deleteForCalendar(gone.id)
                cloud && gone.isLocal -> enqueueRemovedCalendar(gone)
            }
        }
        var calendars = next.calendars
        next.calendars.forEachIndexed { index, calendar ->
            val caldav = calendar.syncState?.writable == true
            val cloudScope = cloud && calendar.isLocal
            if (!caldav && !cloudScope) return@forEachIndexed
            val previous = old.calendars.firstOrNull { it.id == calendar.id }
            val events = diffEvents(calendar.id, previous?.events, calendar.events, caldav, cloudScope)
            val todos = diffTodos(calendar.id, previous?.todos, calendar.todos, caldav, cloudScope)
            val journals = diffJournals(calendar.id, previous?.journals, calendar.journals, caldav, cloudScope)
            if (cloudScope) diffCalendarConfig(previous, calendar)
            if (events !== calendar.events || todos !== calendar.todos || journals !== calendar.journals) {
                calendars = calendars.toMutableList().also {
                    it[index] = calendar.copy(events = events, todos = todos, journals = journals)
                }
            }
        }
        return next.copy(calendars = calendars)
    }

    private fun diffEvents(
        calendarId: String,
        old: List<Event>?,
        new: List<Event>,
        caldav: Boolean,
        cloud: Boolean,
    ): List<Event> {
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
                    fanOut(caldav, cloud) { enqueueRecord(it, calendarId, EntityEvent, withUid, create = true) }
                    withUid
                }
                !contentEquals(previous, record) -> {
                    fanOut(caldav, cloud) { enqueueRecord(it, calendarId, EntityEvent, record, create = false) }
                    record
                }
                else -> record
            }
        }
        old.orEmpty().forEach { record ->
            if (record.id !in newIds) {
                fanOut(caldav, cloud) { enqueueRecordDeletion(it, calendarId, EntityEvent, record) }
            }
        }
        return if (assignedUid) prepared else new
    }

    private fun diffTodos(
        calendarId: String,
        old: List<Todo>?,
        new: List<Todo>,
        caldav: Boolean,
        cloud: Boolean,
    ): List<Todo> {
        val oldById = old.orEmpty().associateBy { it.id }
        val newIds = new.mapNotNullTo(mutableSetOf()) { it.id }
        var assignedUid = false
        val prepared = new.map { record ->
            val previous = record.id?.let(oldById::get)
            when {
                previous == null -> {
                    val withUid = record.withAssignedUid(record.id) as Todo
                    assignedUid = assignedUid || withUid !== record
                    fanOut(caldav, cloud) { enqueueRecord(it, calendarId, EntityTodo, withUid, create = true) }
                    withUid
                }
                !contentEquals(previous, record) -> {
                    fanOut(caldav, cloud) { enqueueRecord(it, calendarId, EntityTodo, record, create = false) }
                    record
                }
                else -> record
            }
        }
        old.orEmpty().forEach { record ->
            if (record.id !in newIds) {
                fanOut(caldav, cloud) { enqueueRecordDeletion(it, calendarId, EntityTodo, record) }
            }
        }
        return if (assignedUid) prepared else new
    }

    private fun diffJournals(
        calendarId: String,
        old: List<Journal>?,
        new: List<Journal>,
        caldav: Boolean,
        cloud: Boolean,
    ): List<Journal> {
        val oldById = old.orEmpty().associateBy { it.id }
        val newIds = new.mapNotNullTo(mutableSetOf()) { it.id }
        var assignedUid = false
        val prepared = new.map { record ->
            val previous = record.id?.let(oldById::get)
            when {
                previous == null -> {
                    val withUid = record.withAssignedUid(record.id) as Journal
                    assignedUid = assignedUid || withUid !== record
                    fanOut(caldav, cloud) { enqueueRecord(it, calendarId, EntityJournal, withUid, create = true) }
                    withUid
                }
                !contentEquals(previous, record) -> {
                    fanOut(caldav, cloud) { enqueueRecord(it, calendarId, EntityJournal, record, create = false) }
                    record
                }
                else -> record
            }
        }
        old.orEmpty().forEach { record ->
            if (record.id !in newIds) {
                fanOut(caldav, cloud) { enqueueRecordDeletion(it, calendarId, EntityJournal, record) }
            }
        }
        return if (assignedUid) prepared else new
    }

    /**
     * Queues the calendar's container settings (name, color, visibility, zone definitions) whenever
     * they differ from the previous state. Only the cloud backend carries calendar rows — a CalDAV
     * calendar is owned by its server.
     */
    private fun diffCalendarConfig(previous: Calendar?, calendar: Calendar) {
        val changed = previous == null ||
            previous.name != calendar.name ||
            previous.colorArgb != calendar.colorArgb ||
            previous.visible != calendar.visible ||
            previous.timeZones != calendar.timeZones
        if (!changed) return
        upsertRow(
            existing = outbox.entry(OutboxBackend.Cloud, calendar.id, EntityCalendar, calendar.id, ""),
            backend = OutboxBackend.Cloud,
            calendarId = calendar.id,
            entity = EntityCalendar,
            uid = calendar.id,
            recurrenceKey = "",
            operation = if (previous == null) OutboxEntry.Operation.Create else OutboxEntry.Operation.Update,
            sequence = 0,
            baseEtag = null,
            href = null,
        )
    }

    /**
     * Queues the removal of a whole local calendar: one calendar tombstone plus one tombstone per
     * record, so every device drops the calendar instead of restoring it on the next pull.
     */
    private fun enqueueRemovedCalendar(calendar: Calendar) {
        upsertRow(
            existing = outbox.entry(OutboxBackend.Cloud, calendar.id, EntityCalendar, calendar.id, ""),
            backend = OutboxBackend.Cloud,
            calendarId = calendar.id,
            entity = EntityCalendar,
            uid = calendar.id,
            recurrenceKey = "",
            operation = OutboxEntry.Operation.Delete,
            sequence = 0,
            baseEtag = null,
            href = null,
        )
        calendar.events.forEach { enqueueRecordDeletion(OutboxBackend.Cloud, calendar.id, EntityEvent, it) }
        calendar.todos.forEach { enqueueRecordDeletion(OutboxBackend.Cloud, calendar.id, EntityTodo, it) }
        calendar.journals.forEach { enqueueRecordDeletion(OutboxBackend.Cloud, calendar.id, EntityJournal, it) }
    }

    private inline fun fanOut(caldav: Boolean, cloud: Boolean, enqueue: (String) -> Unit) {
        if (caldav) enqueue(OutboxBackend.CalDav)
        if (cloud) enqueue(OutboxBackend.Cloud)
    }

    /** Content equality that ignores sync bookkeeping: a fresh etag is not an edit. */
    private fun contentEquals(previous: Event, record: Event): Boolean =
        previous.copy(sync = record.sync) == record

    private fun contentEquals(previous: Todo, record: Todo): Boolean =
        previous.copy(sync = record.sync) == record

    private fun contentEquals(previous: Journal, record: Journal): Boolean =
        previous.copy(sync = record.sync) == record

    /**
     * Queues [record]'s current content for [backend]. A pending change of the same key is
     * replaced, so the last edit wins. The CalDAV operation depends on whether the server already
     * knows the resource; the cloud server upserts by tuple, so only "queued before or not" matters.
     */
    private fun enqueueRecord(backend: String, calendarId: String, entity: String, record: CalendarComponent, create: Boolean) {
        val uid = record.icsUid ?: return
        val recurrenceKey = recurrenceKeyText(record.recurrenceId)
        val existing = outbox.entry(backend, calendarId, entity, uid, recurrenceKey)
        val operation: String
        val baseEtag: String?
        val href: String?
        when (backend) {
            OutboxBackend.CalDav -> {
                operation = if (create && record.sync?.href == null) OutboxEntry.Operation.Create else OutboxEntry.Operation.Update
                baseEtag = if (operation == OutboxEntry.Operation.Create) null else record.sync?.etag ?: existing?.baseEtag
                href = record.sync?.href
            }
            else -> {
                operation = if (existing == null) OutboxEntry.Operation.Create else OutboxEntry.Operation.Update
                baseEtag = null
                href = null
            }
        }
        upsertRow(existing, backend, calendarId, entity, uid, recurrenceKey, operation, record.sequence.toLong(), baseEtag, href)
    }

    /**
     * Queues the removal of [record] on [backend]. A CalDAV record the server never saw simply
     * drops its pending change; the cloud server keeps opaque rows, so a deletion always answers
     * with a tombstone — except when the queued create never left the device and is cancelled.
     */
    private fun enqueueRecordDeletion(backend: String, calendarId: String, entity: String, record: CalendarComponent) {
        val uid = record.icsUid ?: return
        val recurrenceKey = recurrenceKeyText(record.recurrenceId)
        val existing = outbox.entry(backend, calendarId, entity, uid, recurrenceKey)
        when (backend) {
            OutboxBackend.CalDav -> if (record.sync?.href == null) {
                if (existing != null && !existing.isDelete) outbox.delete(existing.id)
                touched += calendarId
                return
            }
            else -> if (existing != null && existing.isCreate) {
                outbox.delete(existing.id)
                touched += calendarId
                return
            }
        }
        upsertRow(
            existing = existing,
            backend = backend,
            calendarId = calendarId,
            entity = entity,
            uid = uid,
            recurrenceKey = recurrenceKey,
            operation = OutboxEntry.Operation.Delete,
            sequence = record.sequence.toLong(),
            baseEtag = record.sync?.etag,
            href = record.sync?.href,
        )
    }

    /** Upserts one pending row, replacing any queued change of the same key with clean retries. */
    private fun upsertRow(
        existing: OutboxEntry?,
        backend: String,
        calendarId: String,
        entity: String,
        uid: String,
        recurrenceKey: String,
        operation: String,
        sequence: Long,
        baseEtag: String?,
        href: String?,
    ) {
        if (existing == null) {
            val now = nowMillis()
            outbox.upsert(
                OutboxEntry(
                    calendarId = calendarId,
                    entity = entity,
                    uid = uid,
                    recurrenceKey = recurrenceKey,
                    operation = operation,
                    sequence = sequence,
                    baseEtag = baseEtag,
                    href = href,
                    backend = backend,
                    createdMs = now,
                    updatedMs = now,
                ),
            )
        } else {
            outbox.upsert(
                existing.copy(
                    operation = operation,
                    sequence = sequence,
                    baseEtag = baseEtag,
                    href = href ?: existing.href,
                    updatedMs = nowMillis(),
                    attempts = 0,
                    nextAttemptMs = null,
                    lastError = null,
                ),
            )
        }
        touched += calendarId
    }

    private companion object {
        const val EntityEvent = "event"
        const val EntityTodo = "todo"
        const val EntityJournal = "journal"
        const val EntityCalendar = "calendar"
    }
}
