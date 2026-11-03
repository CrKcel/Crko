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
