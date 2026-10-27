package com.crko.storage

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.PersistedSettings

/**
 * Minimal key-value persistence. Reads are synchronous so the first frame shows restored data.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Raw storage used by the app. */
expect fun appStore(): KeyValueStore

/** Settings storage, kept separate so a corrupt state payload never loses preferences. */
expect fun settingsStore(): KeyValueStore

/** Platform-provided identifier for newly created records. */
expect fun newId(): String

/** BCP-47 tag of the current system locale, e.g. "zh-Hans-CN" or "en-US". */
expect fun systemLocaleTag(): String

/** Whether the current platform can supply wallpaper-based Monet colors. */
expect fun platformSupportsMonet(): Boolean

/** Reads the current dark-mode preference from the platform configuration. */
expect fun systemDarkMode(): Boolean

private const val STATE_KEY = "state.v1"
private const val SETTINGS_KEY = "settings.v1"

/** Contract consumed by the state holder; allows tests to substitute in-memory storage. */
interface StateStorage {
    fun loadState(): AppState
    fun saveState(state: AppState)
    fun loadSettings(): PersistedSettings
    fun saveSettings(settings: PersistedSettings)
}

object AppStorage : StateStorage {
    override fun loadState(): AppState {
        val raw = appStore().getString(STATE_KEY) ?: return AppState()
        return decodeState(raw)
    }

    override fun saveState(state: AppState) {
        appStore().putString(STATE_KEY, AppJson.encodeToString(AppState.serializer(), state))
    }

    override fun loadSettings(): PersistedSettings {
        val raw = settingsStore().getString(SETTINGS_KEY) ?: return PersistedSettings()
        return decodeSettings(raw)
    }

    override fun saveSettings(settings: PersistedSettings) {
        settingsStore().putString(SETTINGS_KEY, AppJson.encodeToString(PersistedSettings.serializer(), settings))
    }
}

/** Invalid or older payloads fall back to an empty state instead of failing startup. */
internal fun decodeState(raw: String): AppState =
    runCatching { AppJson.decodeFromString(AppState.serializer(), raw) }.getOrElse { AppState() }

internal fun decodeSettings(raw: String): PersistedSettings =
    runCatching { AppJson.decodeFromString(PersistedSettings.serializer(), raw) }.getOrElse { PersistedSettings() }
