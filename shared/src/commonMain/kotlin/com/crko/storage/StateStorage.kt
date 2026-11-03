package com.crko.storage

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.PersistedSettings

internal const val SETTINGS_KEY = "settings.v1"

/** What went wrong while reading the stored state, if anything. */
data class LoadDamage(
    /** The state could not be read at all, so the app started from an empty calendar. */
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

internal fun decodeSettings(raw: String): PersistedSettings =
    runCatching { AppJson.decodeFromString(PersistedSettings.serializer(), raw) }.getOrElse { PersistedSettings() }

/**
 * The in-process fallback for a launch whose database could not be opened or read: the app runs on
 * memory for this session instead of crashing, and the database file on disk stays untouched for
 * diagnosis. Nothing survives the process.
 */
internal class MemoryStateStorage(
    private val settingsStore: () -> KeyValueStore,
) : StateStorage {
    private var state: AppState = AppState()
    private var lastDamage: LoadDamage = LoadDamage.None

    override fun loadState(): AppState {
        lastDamage = LoadDamage(unreadable = true)
        return state
    }

    override fun loadDamage(): LoadDamage = lastDamage

    override fun saveState(state: AppState) {
        this.state = state
        lastDamage = LoadDamage.None
    }

    override fun loadSettings(): PersistedSettings {
        val raw = settingsStore().getString(SETTINGS_KEY) ?: return PersistedSettings()
        return decodeSettings(raw)
    }

    override fun saveSettings(settings: PersistedSettings) {
        settingsStore().putString(SETTINGS_KEY, AppJson.encodeToString(PersistedSettings.serializer(), settings))
    }
}

object AppStorage : StateStorage {
    private var seedProvider: (() -> AppState?)? = null

    /**
     * Debug builds install their sample seed before the first storage read. The setter is ignored
     * after the delegate exists; background receivers that ran first keep their empty first launch,
     * which is the same behavior the non-debug app has.
     */
    fun configureSeedProvider(provider: (() -> AppState?)?) {
        seedProvider = provider
    }

    private val delegate by lazy {
        createAppStateStorage(
            settingsStore = ::settingsStore,
            seedProvider = seedProvider,
        )
    }

    override fun loadState(): AppState = delegate.loadState()

    override fun saveState(state: AppState) = delegate.saveState(state)

    override fun loadSettings(): PersistedSettings = delegate.loadSettings()

    override fun saveSettings(settings: PersistedSettings) = delegate.saveSettings(settings)

    override fun loadDamage(): LoadDamage = delegate.loadDamage()
}
