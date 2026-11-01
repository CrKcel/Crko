package com.crko.storage

import com.crko.dev.sampleState
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.PersistedSettings
import com.crko.domain.Todo
import com.crko.domain.parseStoredDate
import com.crko.domain.today
import com.crko.isDebugBuild
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

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

/**
 * The key-value backed storage. It takes the stores lazily so the damaged-payload handling can be
 * driven from a test without a platform `SharedPreferences`/`NSUserDefaults`.
 */
class FileStateStorage(
    private val stateStore: () -> KeyValueStore,
    private val settingsStore: () -> KeyValueStore,
    private val seedSampleOnFirstLaunch: Boolean = isDebugBuild(),
) : StateStorage {
    private var lastDamage: LoadDamage = LoadDamage.None

    override fun loadState(): AppState {
        val raw = stateStore().getString(STATE_KEY)
        if (raw == null) {
            if (!seedSampleOnFirstLaunch) return AppState()
            // First launch of a debug build: seed sample content, then persist it so edits stick.
            val sample = sampleState(today())
            saveState(sample)
            return sample
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

/** Outcome of reading a payload: what could be restored, plus what had to be given up. */
internal data class StateDecode(val state: AppState, val damage: LoadDamage)

/** Invalid or older payloads fall back to an empty state instead of failing startup. */
internal fun decodeState(raw: String): AppState = decodeStateWithReport(raw).state

/**
 * Reads a stored payload so that one unreadable record cannot discard the whole calendar: the
 * records that still parse are kept and the rest is counted as damage.
 */
internal fun decodeStateWithReport(raw: String): StateDecode {
    runCatching { AppJson.decodeFromString(AppState.serializer(), raw) }.getOrNull()
        ?.let { return decodeWithSanity(it, 0) }
    val root = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return StateDecode(AppState(), LoadDamage(unreadable = true))
    val (events, droppedEvents) = decodeRecords(root["events"], Event.serializer())
    val (todos, droppedTodos) = decodeRecords(root["todos"], Todo.serializer())
    val (journals, droppedJournals) = decodeRecords(root["journals"], Journal.serializer())
    return decodeWithSanity(
        AppState(events = events, todos = todos, journals = journals),
        droppedEvents + droppedTodos + droppedJournals,
    )
}

/**
 * Drops events whose anchor date cannot parse. Such a record decodes fine (the field is a plain
 * string) but would crash the first calendar query that touches it, so it counts as unreadable;
 * the stored backup still holds the original payload.
 */
private fun decodeWithSanity(state: AppState, droppedSoFar: Int): StateDecode {
    var dropped = droppedSoFar
    val events = state.events.mapNotNull { event ->
        if (parseStoredDate(event.date) == null) {
            dropped++
            null
        } else {
            event
        }
    }
    return StateDecode(
        state = if (dropped == droppedSoFar) state else state.copy(events = events),
        damage = LoadDamage(droppedRecords = dropped),
    )
}

private fun <T> decodeRecords(element: JsonElement?, serializer: KSerializer<T>): Pair<List<T>, Int> {
    if (element == null) return emptyList<T>() to 0
    val array = element as? JsonArray ?: return emptyList<T>() to 1
    var dropped = 0
    val kept = array.mapNotNull { item ->
        runCatching { AppJson.decodeFromJsonElement(serializer, item) }.getOrNull()
            .also { if (it == null) dropped++ }
    }
    return kept to dropped
}

internal fun decodeSettings(raw: String): PersistedSettings =
    runCatching { AppJson.decodeFromString(PersistedSettings.serializer(), raw) }.getOrElse { PersistedSettings() }
