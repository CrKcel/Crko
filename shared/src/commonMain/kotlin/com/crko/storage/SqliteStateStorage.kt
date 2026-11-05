package com.crko.storage

import app.cash.sqldelight.db.SqlDriver
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.PersistedSettings
import com.crko.storage.db.CrkoDatabase
import kotlin.time.Clock

/**
 * SQLite-backed state facade. It keeps the SQLDelight driver, the previous batch (the diff baseline)
 * and the load damage; the repository only sees the unchanged [StateStorage] contract.
 */
class SqliteStateStorage(
    private val database: CrkoDatabase,
    private val settingsStore: () -> KeyValueStore,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : StateStorage {
    private var previous: AppState? = null
    private var loadedState: AppState? = null
    private var lastDamage: LoadDamage = LoadDamage.None

    override val outbox: OutboxStore = SqlOutboxStore(database.crkoQueries, nowMillis)

    override fun loadState(): AppState {
        // The composition root probes once to decide whether SQLite is usable; the repository then
        // asks again for the first frame. Cache the decoded state so that probe does not consume
        // `droppedRecords` or decode the same payloads twice.
        loadedState?.let { return it }
        val result = runCatching { readSqliteState(database, nowMillis) }.getOrElse {
            // An unreadable database must not look like an empty calendar; the factory sees the
            // flag and falls back to the still-preserved `state.v1`.
            lastDamage = LoadDamage(unreadable = true)
            return AppState()
        }
        lastDamage = LoadDamage(droppedRecords = result.droppedRecords)
        previous = result.state
        loadedState = result.state
        return result.state
    }

    override fun loadDamage(): LoadDamage = lastDamage

    override fun saveState(state: AppState) {
        saveSqliteState(database, state, previous, nowMillis())
        previous = state
        loadedState = state
    }

    override fun loadSettings(): PersistedSettings {
        val raw = settingsStore().getString(SETTINGS_KEY) ?: return PersistedSettings()
        return decodeSettings(raw)
    }

    override fun saveSettings(settings: PersistedSettings) {
        settingsStore().putString(
            SETTINGS_KEY,
            AppJson.encodeToString(PersistedSettings.serializer(), settings),
        )
    }
}

/** WAL lets background reads see the last complete state while the single writer is busy. */
internal fun SqlDriver.applyPersistencePragmas() {
    listOf(
        "PRAGMA journal_mode=WAL",
        "PRAGMA foreign_keys=ON",
        "PRAGMA busy_timeout=3000",
    ).forEach { sql ->
        runCatching { execute(null, sql, 0) }
    }
}

/** [OutboxStore] over the `outbox` table of the shared database. */
private class SqlOutboxStore(
    private val queries: com.crko.storage.db.CrkoQueries,
    private val nowMillis: () -> Long,
) : OutboxStore {
    override fun entries(): List<OutboxEntry> =
        queries.selectOutboxEntries().executeAsList().map { it.toEntry() }

    override fun entriesFor(calendarId: String): List<OutboxEntry> =
        queries.selectOutboxEntriesForCalendar(calendarId).executeAsList().map { it.toEntry() }

    override fun entry(calendarId: String, entity: String, uid: String, recurrenceKey: String): OutboxEntry? =
        queries.selectOutboxEntry(calendarId, entity, uid, recurrenceKey).executeAsOneOrNull()?.toEntry()

    override fun upsert(entry: OutboxEntry) {
        if (entry.id == 0L) {
            queries.insertOutboxEntry(
                calendar_id = entry.calendarId,
                entity = entry.entity,
                uid = entry.uid,
                recurrence_key = entry.recurrenceKey,
                operation = entry.operation,
                sequence = entry.sequence,
                base_etag = entry.baseEtag,
                href = entry.href,
                created_ms = entry.createdMs,
                updated_ms = entry.updatedMs,
            )
        } else {
            queries.updateOutboxEntry(
                operation = entry.operation,
                sequence = entry.sequence,
                base_etag = entry.baseEtag,
                href = entry.href,
                updated_ms = entry.updatedMs.takeIf { it > 0 } ?: nowMillis(),
                outbox_id = entry.id,
            )
        }
    }

    override fun updateAttempt(id: Long, attempts: Long, nextAttemptMs: Long?, lastError: String?) {
        queries.updateOutboxAttempt(
            attempts = attempts,
            next_attempt_ms = nextAttemptMs,
            last_error = lastError,
            updated_ms = nowMillis(),
            outbox_id = id,
        )
    }

    override fun delete(id: Long) {
        queries.deleteOutboxEntry(id)
    }

    override fun deleteForCalendar(calendarId: String) {
        queries.deleteOutboxEntriesForCalendar(calendarId)
    }
}

private fun com.crko.storage.db.Outbox.toEntry(): OutboxEntry = OutboxEntry(
    id = outbox_id,
    calendarId = calendar_id,
    entity = entity,
    uid = uid,
    recurrenceKey = recurrence_key,
    operation = operation,
    sequence = sequence,
    baseEtag = base_etag,
    href = href,
    createdMs = created_ms,
    updatedMs = updated_ms,
    attempts = attempts,
    nextAttemptMs = next_attempt_ms,
    lastError = last_error,
)
