package com.crko.storage

import app.cash.sqldelight.db.SqlDriver
import com.crko.domain.AppState
import com.crko.domain.withLocalCalendar
import com.crko.storage.db.CrkoDatabase
import kotlin.time.Clock

/**
 * Builds the SQLite storage. The driver is created exactly once per process; on its first open the
 * database is seeded (debug sample state or an empty calendar) and marked initialized in the meta
 * table, so the seed is applied exactly once even if the process dies mid-launch.
 *
 * A failure during opening, seeding or the first read returns an in-memory storage: the process
 * keeps running this session and the database file stays on disk untouched for diagnosis.
 */
internal fun createAppStateStorage(
    settingsStore: () -> KeyValueStore,
    seedProvider: (() -> AppState?)? = null,
    driverFactory: () -> SqlDriver? = { persistenceDriver() },
    idProvider: () -> String = ::newId,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): StateStorage {
    val driver = runCatching { driverFactory() }.getOrNull() ?: return MemoryStateStorage(settingsStore)
    val storage = runCatching {
        driver.applyPersistencePragmas()
        val database = CrkoDatabase(driver)
        seedDatabaseIfFirstLaunch(database, seedProvider, idProvider, nowMillis())
        SqliteStateStorage(database, settingsStore, nowMillis)
    }.getOrElse {
        runCatching { driver.close() }
        return MemoryStateStorage(settingsStore)
    }
    // The factory probes once to decide whether the database is usable; the repository's first
    // frame must still see the damage that probe found.
    storage.loadState()
    if (storage.loadDamage().unreadable) {
        runCatching { driver.close() }
        return MemoryStateStorage(settingsStore)
    }
    return storage
}

private fun seedDatabaseIfFirstLaunch(
    database: CrkoDatabase,
    seedProvider: (() -> AppState?)?,
    idProvider: () -> String,
    nowMillis: Long,
) {
    val queries = database.crkoQueries
    if (queries.getMeta(FirstInitKey).executeAsOneOrNull() != null) return
    val seed = (seedProvider?.invoke() ?: AppState()).ensureRecordIds(idProvider).withLocalCalendar()
    database.transaction {
        if (queries.getMeta(FirstInitKey).executeAsOneOrNull() != null) return@transaction
        replaceSqliteState(database, seed, nowMillis)
        queries.putMeta(FirstInitKey, nowMillis.toString())
    }
}
