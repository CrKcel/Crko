package com.crko.storage

/** Which sync backend drains an [OutboxEntry]. */
object OutboxBackend {
    /** The calendar's own CalDAV server; drained by [com.crko.state.sync.CalDavPushService]. */
    const val CalDav = "caldav"

    /** The crko.cloud relay; drained by [com.crko.state.sync.CloudPushService]. */
    const val Cloud = "cloud"
}

/**
 * One pending local change queued for a sync backend. Rows are keyed by
 * `(backend, calendarId, entity, uid, recurrenceKey)`: a second change to the same record replaces
 * the pending one, so a burst of edits drains as at most one push per record per backend. A record
 * may queue for both backends at once — a CalDAV calendar that also syncs to the cloud.
 */
data class OutboxEntry(
    /** 0 for an entry the caller has not persisted yet. */
    val id: Long = 0,
    val calendarId: String,
    /** Record kind: `event`, `todo`, `journal`, or `calendar` for a calendar config change. */
    val entity: String,
    /** RFC UID the resource is published under; for a calendar row, the calendar id. */
    val uid: String,
    /** Stable text encoding of `RECURRENCE-ID`; empty on a master or standalone record. */
    val recurrenceKey: String = "",
    /** `create`, `update` or `delete`. */
    val operation: String,
    /** RFC `SEQUENCE` of the record at enqueue time, for diagnostics only. */
    val sequence: Long = 0,
    /** ETag the record last carried from the server; null for a record never pushed. */
    val baseEtag: String? = null,
    /** Server href of the record's resource; needed by a queued deletion after the record is gone. */
    val href: String? = null,
    /** Which backend drains this row; one of [OutboxBackend]. */
    val backend: String = OutboxBackend.CalDav,
    val createdMs: Long,
    val updatedMs: Long,
    val attempts: Long = 0,
    val nextAttemptMs: Long? = null,
    val lastError: String? = null,
) {
    val isCreate: Boolean get() = operation == Operation.Create
    val isUpdate: Boolean get() = operation == Operation.Update
    val isDelete: Boolean get() = operation == Operation.Delete

    /** Operations an [OutboxEntry] can carry. */
    object Operation {
        const val Create = "create"
        const val Update = "update"
        const val Delete = "delete"
    }
}

/**
 * The pending-change queue the sync engines drain. A storage-layer port like [StateStorage]: the
 * push engines live in the state layer, the rows live next to the rest of the SQLite schema.
 */
interface OutboxStore {
    /** Every pending entry, oldest first. */
    fun entries(): List<OutboxEntry>

    /** Entries of one calendar, oldest first. */
    fun entriesFor(calendarId: String): List<OutboxEntry>

    /** Entries drained by one backend, oldest first. */
    fun entriesForBackend(backend: String): List<OutboxEntry>

    /** The pending entry for [backend], [calendarId], [entity], [uid] and [recurrenceKey], if any. */
    fun entry(backend: String, calendarId: String, entity: String, uid: String, recurrenceKey: String): OutboxEntry?

    /** Inserts [entry] (id 0) or replaces the stored row with the same id. */
    fun upsert(entry: OutboxEntry)

    /** Persists retry bookkeeping after a failed drain. */
    fun updateAttempt(id: Long, attempts: Long, nextAttemptMs: Long?, lastError: String?)

    /** Removes the entry: a drained push, or a change a later one coalesced away. */
    fun delete(id: Long)

    /** Removes every entry of a calendar, e.g. when the calendar itself is deleted. */
    fun deleteForCalendar(calendarId: String)

    /** Removes every entry drained by [backend], e.g. when a cloud account signs out. */
    fun deleteForBackend(backend: String)
}

/** In-memory [OutboxStore]; the fallback storage and the tests run on this. */
class MemoryOutboxStore : OutboxStore {
    private val rows = mutableListOf<OutboxEntry>()
    private var nextId = 1L

    override fun entries(): List<OutboxEntry> = rows.sortedWith(compareBy({ it.createdMs }, { it.id }))

    override fun entriesFor(calendarId: String): List<OutboxEntry> =
        entries().filter { it.calendarId == calendarId }

    override fun entriesForBackend(backend: String): List<OutboxEntry> =
        entries().filter { it.backend == backend }

    override fun entry(backend: String, calendarId: String, entity: String, uid: String, recurrenceKey: String): OutboxEntry? =
        rows.firstOrNull {
            it.backend == backend && it.calendarId == calendarId && it.entity == entity &&
                it.uid == uid && it.recurrenceKey == recurrenceKey
        }

    override fun upsert(entry: OutboxEntry) {
        if (entry.id == 0L) {
            rows += entry.copy(id = nextId++)
        } else {
            rows.removeAll { it.id == entry.id }
            rows += entry
        }
    }

    override fun updateAttempt(id: Long, attempts: Long, nextAttemptMs: Long?, lastError: String?) {
        val index = rows.indexOfFirst { it.id == id }
        if (index >= 0) rows[index] = rows[index].copy(attempts = attempts, nextAttemptMs = nextAttemptMs, lastError = lastError)
    }

    override fun delete(id: Long) {
        rows.removeAll { it.id == id }
    }

    override fun deleteForCalendar(calendarId: String) {
        rows.removeAll { it.calendarId == calendarId }
    }

    override fun deleteForBackend(backend: String) {
        rows.removeAll { it.backend == backend }
    }
}
