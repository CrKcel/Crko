package com.crko.storage

import com.crko.domain.AppState
import com.crko.domain.withLocalCalendar
import com.crko.storage.db.CrkoDatabase
import kotlin.time.Clock

/**
 * One-shot `state.v1` → SQLite migration. The JSON payload is only read: it is backed up first, the
 * database is written in one transaction, and the database is read back and compared before the
 * engine flag and `db.v1.ready` marker are allowed to switch over. Any failure leaves the original
 * payload and the JSON engine in charge, so the next launch can retry.
 */
internal class JsonToSqliteMigrator(
    private val database: CrkoDatabase,
    private val stateStore: KeyValueStore,
    private val backup: PersistenceBackup,
    private val seedProvider: (() -> AppState?)?,
    private val idProvider: () -> String,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /** True when this launch may use SQLite. A false result means JSON must stay the source. */
    fun migrateIfNeeded(): Boolean {
        if (stateStore.getString(DB_READY_KEY) != null) return true
        val raw = stateStore.getString(STATE_KEY)
        return if (raw == null) {
            migrateFirstLaunch()
        } else {
            migratePayload(raw)
        }
    }

    private fun migrateFirstLaunch(): Boolean {
        val seed = seedProvider?.invoke() ?: AppState()
        val normalized = seed.ensureRecordIds(idProvider).withLocalCalendar()
        replaceSqliteState(database, normalized, nowMillis())
        return markReady(normalized, source = if (seedProvider == null) "new-install" else "seed")
    }

    private fun migratePayload(raw: String): Boolean {
        // Keep the exact legacy text before decoding anything, and never overwrite `state.v1`.
        runCatching { backup.writePreSqlite(raw) }.getOrElse { return false }
        val decoded = decodeStateWithReport(raw)
        if (decoded.damage.unreadable) return false
        val normalized = decoded.state.ensureRecordIds(idProvider).withLocalCalendar()
        replaceSqliteState(database, normalized, nowMillis())
        val expected = normalized.ensureRecordIds(idProvider)
        val readBack = readSqliteState(database, nowMillis)
        if (!semanticEquals(expected, readBack.state)) return false
        return markReady(expected, source = "state.v1")
    }

    private fun markReady(state: AppState, source: String): Boolean = runCatching {
        val recordCount = state.calendars.sumOf { it.events.size + it.todos.size + it.journals.size }
        val migratedAt = nowMillis()
        database.transaction {
            database.crkoQueries.putMeta(PayloadVersionKey, com.crko.domain.CurrentSchemaVersion.toString())
            database.crkoQueries.putMeta(MigrationSourceKey, source)
            database.crkoQueries.putMeta(MigrationRecordsKey, recordCount.toString())
            database.crkoQueries.putMeta(MigrationTimeKey, migratedAt.toString())
        }
        writePersistenceEngine(stateStore, PersistenceEngine.Sqlite)
        stateStore.putString(DB_READY_KEY, migratedAt.toString())
        true
    }.getOrDefault(false)
}

/**
 * Compares the fields the migration promises to preserve, including list order, UIDs and the
 * calendar-level unknown zones. The `schemaVersion` is normalized on both sides by the decoders.
 */
private fun semanticEquals(expected: AppState, actual: AppState): Boolean =
    expected.calendars == actual.calendars &&
        expected.busyPeriods == actual.busyPeriods &&
        expected.schemaVersion == actual.schemaVersion
