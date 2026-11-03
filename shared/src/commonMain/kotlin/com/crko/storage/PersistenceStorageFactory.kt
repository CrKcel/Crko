package com.crko.storage

import app.cash.sqldelight.db.SqlDriver
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.storage.db.CrkoDatabase
import kotlin.time.Clock

/**
 * Builds the storage selected by `persistence.engine`. The JSON storage stays constructed in every
 * path so it can act as the migration source and as the rollback target.
 *
 * The driver is created exactly once per process. A failure during opening, migration or the first
 * read leaves `state.v1` untouched, writes the engine flag back to `json`, and returns the JSON
 * storage; the corrupt database is preserved on disk for diagnosis.
 */
internal fun createAppStateStorage(
    stateStore: () -> KeyValueStore,
    settingsStore: () -> KeyValueStore,
    seedProvider: (() -> AppState?)? = null,
    driverFactory: () -> SqlDriver? = { persistenceDriver() },
    backup: PersistenceBackup = persistenceBackup(),
    idProvider: () -> String = ::newId,
    nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
): StateStorage {
    val state = stateStore()
    val settings = settingsStore()
    val json = FileStateStorage({ state }, { settings }, seedProvider)

    return when (readPersistenceEngine(state)) {
        PersistenceEngine.Json -> rollbackExportIfRequested(
            stateStore = state,
            settingsStore = settings,
            driverFactory = driverFactory,
            json = json,
            idProvider = idProvider,
            nowMillis = nowMillis,
        ) ?: json
        PersistenceEngine.Sqlite -> openSqlite(
            stateStore = state,
            settingsStore = settings,
            seedProvider = seedProvider,
            driverFactory = driverFactory,
            backup = backup,
            idProvider = idProvider,
            nowMillis = nowMillis,
        ) ?: json
    }
}

private fun openSqlite(
    stateStore: KeyValueStore,
    settingsStore: KeyValueStore,
    seedProvider: (() -> AppState?)?,
    driverFactory: () -> SqlDriver?,
    backup: PersistenceBackup,
    idProvider: () -> String,
    nowMillis: () -> Long,
): StateStorage? {
    val driver = runCatching { driverFactory() }.getOrNull() ?: return null
    val storage = runCatching {
        driver.applyPersistencePragmas()
        val database = CrkoDatabase(driver)
        val migrated = JsonToSqliteMigrator(
            database = database,
            stateStore = stateStore,
            backup = backup,
            seedProvider = seedProvider,
            idProvider = idProvider,
            nowMillis = nowMillis,
        ).migrateIfNeeded()
        if (!migrated) {
            writePersistenceEngine(stateStore, PersistenceEngine.Json)
            return@runCatching null
        }
        val sqlite = SqliteStateStorage(database, { settingsStore }, nowMillis)
        sqlite.loadState()
        if (sqlite.loadDamage().unreadable) {
            writePersistenceEngine(stateStore, PersistenceEngine.Json)
            return@runCatching null
        }
        sqlite
    }.getOrElse {
        writePersistenceEngine(stateStore, PersistenceEngine.Json)
        null
    }
    if (storage == null) runCatching { driver.close() }
    return storage
}

/**
 * A user-selected JSON rollback must first export the SQLite state, otherwise the switched engine
 * would keep writing an old frozen snapshot. The export is verified with the normal JSON decoder
 * before `db.v1.rollback-exported` is written; if it fails, SQLite stays active.
 */
private fun rollbackExportIfRequested(
    stateStore: KeyValueStore,
    settingsStore: KeyValueStore,
    driverFactory: () -> SqlDriver?,
    json: FileStateStorage,
    idProvider: () -> String,
    nowMillis: () -> Long,
): StateStorage? {
    if (stateStore.getString(DB_READY_KEY) == null ||
        stateStore.getString(ROLLBACK_EXPORTED_KEY) != null
    ) {
        return null
    }
    val driver = runCatching { driverFactory() }.getOrNull() ?: return null
    val sqlite = runCatching {
        driver.applyPersistencePragmas()
        SqliteStateStorage(CrkoDatabase(driver), { settingsStore }, nowMillis)
    }.getOrElse {
        runCatching { driver.close() }
        return null
    }

    val loaded = sqlite.loadState()
    if (sqlite.loadDamage().unreadable) {
        // The database cannot be exported; with `state.v1` still present the JSON engine remains
        // the safe source. The corrupt file is not deleted.
        writePersistenceEngine(stateStore, PersistenceEngine.Json)
        runCatching { driver.close() }
        return null
    }

    val exported = runCatching {
        val normalized = loaded.ensureRecordIds(idProvider)
        // Verify the exact JSON shape before replacing state.v1: an unreadable export must leave the
        // frozen migration copy in place and let SQLite continue owning the data.
        val serialized = AppJson.encodeToString(AppState.serializer(), normalized)
        val verified = decodeStateWithReport(serialized)
        if (verified.damage.unreadable || verified.state != normalized) {
            return@runCatching false
        }
        json.saveState(normalized)
        // Read the written bytes back through the same path the JSON engine uses at startup.
        json.loadState() == normalized
    }.getOrDefault(false)

    if (!exported) {
        // Keep SQLite active: losing the edits made after the migration would be worse than failing
        // this export. The caller reports no message because the state facade has no message channel.
        writePersistenceEngine(stateStore, PersistenceEngine.Sqlite)
        return sqlite
    }
    stateStore.putString(ROLLBACK_EXPORTED_KEY, nowMillis().toString())
    writePersistenceEngine(stateStore, PersistenceEngine.Json)
    runCatching { driver.close() }
    return null
}
