package com.crko.storage

import com.crko.storage.db.CrkoQueries

/** One device-local support row; the payload is decoded by the state layer. */
data class SupportRecord(
    val recordId: String,
    val kind: String,
    val day: String?,
    val payload: String,
    val updatedMs: Long,
)

/**
 * Storage of the small, device-local rows behind the today plan and the focus session. They never
 * enter [com.crko.domain.AppState], the outbox or the cloud payload.
 */
interface SupportStore {
    fun records(): List<SupportRecord>

    fun recordsOfKind(kind: String): List<SupportRecord>

    fun upsert(record: SupportRecord)

    fun delete(recordId: String)

    /** Drops rows of [kind] whose day is before [day] (exclusive); rows without a day stay. */
    fun deleteBefore(kind: String, day: String)
}

/** Storage that keeps nothing; hosts and tests without support storage fall back to it. */
object EmptySupportStore : SupportStore {
    override fun records(): List<SupportRecord> = emptyList()

    override fun recordsOfKind(kind: String): List<SupportRecord> = emptyList()

    override fun upsert(record: SupportRecord) = Unit

    override fun delete(recordId: String) = Unit

    override fun deleteBefore(kind: String, day: String) = Unit
}

/** In-memory store for unit tests that need support data without a database. */
class MemorySupportStore : SupportStore {
    private val rows = mutableMapOf<String, SupportRecord>()

    override fun records(): List<SupportRecord> = rows.values.sortedBy { it.updatedMs }

    override fun recordsOfKind(kind: String): List<SupportRecord> =
        rows.values.filter { it.kind == kind }.sortedBy { it.updatedMs }

    override fun upsert(record: SupportRecord) {
        rows[record.recordId] = record
    }

    override fun delete(recordId: String) {
        rows.remove(recordId)
    }

    override fun deleteBefore(kind: String, day: String) {
        rows.values
            .filter { it.kind == kind && it.day != null && it.day < day }
            .map { it.recordId }
            .forEach { rows.remove(it) }
    }
}

/** [SupportStore] over the `support_record` table created by `3.sqm`. */
internal class SqlSupportStore(
    private val queries: CrkoQueries,
    private val nowMillis: () -> Long,
) : SupportStore {
    override fun records(): List<SupportRecord> =
        queries.selectSupportRecords().executeAsList().map { it.toSupportRecord() }

    override fun recordsOfKind(kind: String): List<SupportRecord> =
        queries.selectSupportRecordsByKind(kind).executeAsList().map { it.toSupportRecord() }

    override fun upsert(record: SupportRecord) {
        queries.upsertSupportRecord(
            record_id = record.recordId,
            kind = record.kind,
            day = record.day,
            payload = record.payload,
            updated_ms = record.updatedMs.takeIf { it > 0 } ?: nowMillis(),
        )
    }

    override fun delete(recordId: String) {
        queries.deleteSupportRecord(recordId)
    }

    override fun deleteBefore(kind: String, day: String) {
        queries.deleteSupportRecordsByKindBeforeDay(kind, day)
    }
}

private fun com.crko.storage.db.Support_record.toSupportRecord(): SupportRecord = SupportRecord(
    recordId = record_id,
    kind = kind,
    day = day,
    payload = payload,
    updatedMs = updated_ms,
)
