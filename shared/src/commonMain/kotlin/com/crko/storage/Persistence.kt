package com.crko.storage

/** Internal key of the JSON snapshot; kept as the rollback/export copy after SQLite takes over. */
internal const val STATE_KEY = "state.v1"
internal const val SETTINGS_KEY = "settings.v1"

/**
 * Which engine owns the state. `json` and `sqlite` are the only values; a missing key means the
 * post-F2.3 default (`sqlite`), so existing installs migrate on their first launch.
 */
internal const val PERSISTENCE_ENGINE_KEY = "persistence.engine"

/** Written in KeyValue only after a migration passed its read-back comparison. */
internal const val DB_READY_KEY = "db.v1.ready"

/** Marks that the SQLite state was exported back to `state.v1` for the JSON rollback path. */
internal const val ROLLBACK_EXPORTED_KEY = "db.v1.rollback-exported"

internal enum class PersistenceEngine(val flag: String) {
    Json("json"),
    Sqlite("sqlite");

    companion object {
        fun parse(value: String?): PersistenceEngine? =
            entries.firstOrNull { it.flag == value?.trim()?.lowercase() }
    }
}

/**
 * A file outside the key-value stores for the 18 MiB legacy payload and the migration flags that
 * must survive even when the JSON engine is still in charge. Implementations fall back to memory
 * before a platform context exists (host tests, early receivers).
 */
interface PersistenceBackup {
    /** The frozen `state.v1.pre-sqlite` copy, or null when there is none. */
    fun readPreSqlite(): String?

    /**
     * Stores the raw legacy payload before anything else touches it. Implementations use an atomic
     * replacement and never truncate an existing copy; failure aborts the migration.
     */
    fun writePreSqlite(raw: String)

    /** Preserves the current database as a corruption copy right before schema recovery. */
    fun quarantineDatabase(): String? = null
}

/** Platform-provided legacy-payload backup file. */
expect fun persistenceBackup(): PersistenceBackup

/** Backup used when the platform cannot provide a directory yet; replaced before the first real write. */
internal object InMemoryPersistenceBackup : PersistenceBackup {
    private var raw: String? = null

    override fun readPreSqlite(): String? = raw

    override fun writePreSqlite(raw: String) {
        this.raw = raw
    }
}

/** Reads the engine flag, defaulting to SQLite after PR-9. */
internal fun readPersistenceEngine(store: KeyValueStore): PersistenceEngine =
    PersistenceEngine.parse(store.getString(PERSISTENCE_ENGINE_KEY)) ?: PersistenceEngine.Sqlite

internal fun writePersistenceEngine(store: KeyValueStore, engine: PersistenceEngine) {
    store.putString(PERSISTENCE_ENGINE_KEY, engine.flag)
}
