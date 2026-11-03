package com.crko.storage

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.PersistedSettings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val STATE_KEY = "state.v1"
private const val SETTINGS_KEY = "settings.v1"
private const val CORRUPT_STATE_KEY = "state.v1.corrupt"

/** What went wrong while reading a stored payload, if anything. */
data class LoadDamage(
    /** The payload could not be read at all, so the app started from an empty calendar. */
    val unreadable: Boolean = false,
    /** Records that could not be read and are therefore missing from the restored state. */
    val droppedRecords: Int = 0,
) {
    val isEmpty: Boolean get() = !unreadable && droppedRecords == 0

    companion object {
        val None = LoadDamage()
    }
}

/** Contract consumed by the state holder; allows tests to substitute in-memory storage. */
interface StateStorage {
    fun loadState(): AppState
    fun saveState(state: AppState)
    fun loadSettings(): PersistedSettings
    fun saveSettings(settings: PersistedSettings)

    /** Damage from the last [loadState]; storages that cannot report any keep the default. */
    fun loadDamage(): LoadDamage = LoadDamage.None
}

/** The declared schema version of a stored payload, or null when it is unversioned/unreadable. */
internal fun payloadSchemaVersion(raw: String): Int? {
    val root = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    return (root["schemaVersion"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}

/**
 * The key-value backed storage. It takes the stores lazily so the damaged-payload handling can be
 * driven from a test without a platform `SharedPreferences`/`NSUserDefaults`.
 */
class FileStateStorage(
    private val stateStore: () -> KeyValueStore,
    private val settingsStore: () -> KeyValueStore,
    /**
     * Supplies the content persisted on first launch. The composition root passes the debug sample
     * state so this package stays independent of `com.crko.dev`; release builds pass null.
     */
    private val seedProvider: (() -> AppState?)? = null,
) : StateStorage {
    private var lastDamage: LoadDamage = LoadDamage.None

    override fun loadState(): AppState {
        val raw = stateStore().getString(STATE_KEY)
        if (raw == null) {
            val seed = seedProvider?.invoke() ?: return AppState()
            // First launch: seed content, then persist it so edits stick.
            saveState(seed)
            return seed
        }
        // A payload written before stage 1 has no `schemaVersion`; keep the original once so a
        // migration that drops an unrecoverable field still has a copy to recover from.
        if (!raw.contains("\"schemaVersion\"") && stateStore().getString(LEGACY_STATE_BACKUP_KEY) == null) {
            stateStore().putString(LEGACY_STATE_BACKUP_KEY, raw)
        }
        // Versioned older payloads are migrated on read too; keep the first one for the same reason.
        // Only the first copy is kept: later ones would just describe a state that is already
        // migrated.
        val version = payloadSchemaVersion(raw)
        if (version != null && version < CurrentSchemaVersion &&
            stateStore().getString(MIGRATION_STATE_BACKUP_KEY) == null
        ) {
            stateStore().putString(MIGRATION_STATE_BACKUP_KEY, raw)
        }
        val decoded = decodeStateWithReport(raw)
        lastDamage = decoded.damage
        if (!decoded.damage.isEmpty && stateStore().getString(CORRUPT_STATE_KEY) == null) {
            // A failed read used to fall back to an empty state that the next edit wrote straight
            // over the original, losing everything without a trace. Keep the damaged payload so
            // there is still something to recover those records from. Only the first copy is kept:
            // later ones already describe the shrunken state.
            stateStore().putString(CORRUPT_STATE_KEY, raw)
        }
        return decoded.state
    }

    override fun loadDamage(): LoadDamage = lastDamage

    override fun saveState(state: AppState) {
        stateStore().putString(STATE_KEY, AppJson.encodeToString(AppState.serializer(), state))
    }

    override fun loadSettings(): PersistedSettings {
        val raw = settingsStore().getString(SETTINGS_KEY) ?: return PersistedSettings()
        return decodeSettings(raw)
    }

    override fun saveSettings(settings: PersistedSettings) {
        settingsStore().putString(SETTINGS_KEY, AppJson.encodeToString(PersistedSettings.serializer(), settings))
    }
}

object AppStorage : StateStorage by FileStateStorage(::appStore, ::settingsStore)
