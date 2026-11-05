package com.crko.storage

/**
 * One pending local change of a writable CalDAV calendar. Rows are keyed by
 * `(calendarId, entity, uid, recurrenceKey)`: a second change to the same record replaces the
 * pending one, so a burst of edits drains as at most one push per record.
 */
data class OutboxEntry(
    /** 0 for an entry the caller has not persisted yet. */
    val id: Long = 0,
    val calendarId: String,
    /** Record kind: `event`, `todo` or `journal`. */
    val entity: String,
    /** RFC UID the resource is published under; empty while the record has none yet. */
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
 * The pending-change queue a two-way CalDAV sync drains. A storage-layer port like [StateStorage]:
 * the push engine lives in the state layer, the rows live next to the rest of the SQLite schema.
 */
interface OutboxStore {
    /** Every pending entry, oldest first. */
    fun entries(): List<OutboxEntry>

    /** Entries of one calendar, oldest first. */
    fun entriesFor(calendarId: String): List<OutboxEntry>

    /** The pending entry for [calendarId], [entity], [uid] and [recurrenceKey], if any. */
    fun entry(calendarId: String, entity: String, uid: String, recurrenceKey: String): OutboxEntry?

    /** Inserts [entry] (id 0) or replaces the stored row with the same id. */
    fun upsert(entry: OutboxEntry)

    /** Persists retry bookkeeping after a failed drain. */
    fun updateAttempt(id: Long, attempts: Long, nextAttemptMs: Long?, lastError: String?)

    /** Removes the entry: a drained push, or a change a later one coalesced away. */
    fun delete(id: Long)

    /** Removes every entry of a calendar, e.g. when the calendar itself is deleted. */
    fun deleteForCalendar(calendarId: String)
}

/** In-memory [OutboxStore]; the fallback storage and the tests run on this. */
class MemoryOutboxStore : OutboxStore {
    private val rows = mutableListOf<OutboxEntry>()
    private var nextId = 1L

    override fun entries(): List<OutboxEntry> = rows.sortedWith(compareBy({ it.createdMs }, { it.id }))

    override fun entriesFor(calendarId: String): List<OutboxEntry> =
        entries().filter { it.calendarId == calendarId }

    override fun entry(calendarId: String, entity: String, uid: String, recurrenceKey: String): OutboxEntry? =
        rows.firstOrNull {
            it.calendarId == calendarId && it.entity == entity &&
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
}
