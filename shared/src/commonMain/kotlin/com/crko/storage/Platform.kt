package com.crko.storage

import com.crko.dev.sampleState
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.CalendarMetadata
import com.crko.domain.CalendarTimeZone
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.LegacyAppState
import com.crko.domain.LegacyEvent
import com.crko.domain.LegacyTodo
import com.crko.domain.StageFourAppState
import com.crko.domain.PersistedSettings
import com.crko.domain.Todo
import com.crko.domain.normalizeTimeZoneId
import com.crko.domain.registerIcsTimeZones
import com.crko.domain.toCurrentEvent
import com.crko.domain.toCurrentTodo
import com.crko.domain.today
import com.crko.isDebugBuild
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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

/**
 * Holds the payloads of embedded ICS attachments as files, outside the serialized state. The
 * reference stored with a record is the plain file name inside the store's own directory.
 */
interface AttachmentStore {
    /** Writes [bytes] under a collision-free name derived from [fileName]; null when the write failed. */
    fun save(fileName: String, bytes: ByteArray): String?

    /** Bytes of the file [name] written earlier, or null when it is gone. */
    fun read(name: String): ByteArray?
}

/** Platform-provided attachment file store. */
expect fun attachmentStore(): AttachmentStore

/** Attachment store used by the app, resolved lazily so tests can run without a platform. */
object AppAttachmentStore : AttachmentStore {
    override fun save(fileName: String, bytes: ByteArray): String? = attachmentStore().save(fileName, bytes)

    override fun read(name: String): ByteArray? = attachmentStore().read(name)
}

/** Fallback for host tests and for the moment a platform cannot provide its directory yet. */
internal object InMemoryAttachmentStore : AttachmentStore {
    private var counter = 0

    private val files = mutableMapOf<String, ByteArray>()

    override fun save(fileName: String, bytes: ByteArray): String {
        // Single-threaded in practice; imports run on one dispatcher at a time.
        counter += 1
        val name = "$counter-$fileName"
        files[name] = bytes
        return name
    }

    override fun read(name: String): ByteArray? = files[name]
}

/** Platform-provided identifier for newly created records. */
expect fun newId(): String

/** BCP-47 tag of the current system locale, e.g. "zh-Hans-CN" or "en-US". */
expect fun systemLocaleTag(): String

/** Whether the current platform can supply wallpaper-based Monet colors. */
expect fun platformSupportsMonet(): Boolean

private const val STATE_KEY = "state.v1"
private const val SETTINGS_KEY = "settings.v1"
private const val CORRUPT_STATE_KEY = "state.v1.corrupt"
/** Raw payload kept once before it is migrated from the pre-stage-1 event layout. */
private const val LEGACY_STATE_BACKUP_KEY = "state.v1.legacy-backup"

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
        // A payload written before stage 1 has no `schemaVersion`; keep the original once so a
        // migration that drops an unrecoverable field still has a copy to recover from.
        if (!raw.contains("\"schemaVersion\"") && stateStore().getString(LEGACY_STATE_BACKUP_KEY) == null) {
            stateStore().putString(LEGACY_STATE_BACKUP_KEY, raw)
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
 * records that still parse are kept and the rest is counted as damage. A payload written before
 * stage 1 is decoded through [LegacyEvent] and migrated in place.
 */
internal fun decodeStateWithReport(raw: String): StateDecode {
    val root = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return StateDecode(AppState(), LoadDamage(unreadable = true))
    val version = (root["schemaVersion"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    // Only a payload already in the current layout may be decoded directly; an older one would
    // silently lose the fields this version moved into `calendar` under `ignoreUnknownKeys`.
    if (version == CurrentSchemaVersion) {
        runCatching { AppJson.decodeFromString(AppState.serializer(), raw) }.getOrNull()
            ?.let { state ->
                registerIcsTimeZones(state.calendar.timeZones)
                return StateDecode(state, LoadDamage.None)
            }
    }
    // Stage 3/4 payloads already carry typed recurrence values; decode their component shape and
    // convert the pre-stage-4 participants, alarms, attachments, todos and journals.
    if (version == 4 || version == StageTwoSchemaVersion) {
        runCatching { AppJson.decodeFromJsonElement(StageFourAppState.serializer(), root) }.getOrNull()
            ?.let { previous ->
                val definitions = if (version == StageTwoSchemaVersion) {
                    collectLegacyTimeZoneDefinitions(root)
                        .associateByTo(LinkedHashMap()) { normalizeTimeZoneId(it.tzid) }
                } else {
                    LinkedHashMap()
                }
                val state = previous.toCurrent(definitions)
                registerIcsTimeZones(state.calendar.timeZones)
                return StateDecode(state, LoadDamage.None)
            }
    }
    val definitions = linkedMapOf<String, CalendarTimeZone>()
    runCatching {
        AppJson.decodeFromJsonElement(LegacyAppState.serializer(), root)
    }.getOrNull()?.let { legacy ->
        val events = legacy.events.mapNotNull { it.toCurrentEvent(definitions) }
        val state = AppState(
            events = events,
            todos = legacy.todos.map { it.toCurrentTodo() },
            journals = legacy.journals.map { it.toCurrent() },
            subscriptions = legacy.subscriptions,
            busyPeriods = legacy.busyPeriods,
            calendar = CalendarMetadata(timeZones = definitions.values.toList()),
            schemaVersion = CurrentSchemaVersion,
        )
        registerIcsTimeZones(state.calendar.timeZones)
        return StateDecode(
            state = state,
            damage = LoadDamage(droppedRecords = legacy.events.size - events.size),
        )
    }
    val (events, droppedEvents) = decodeEventRecords(root["events"], definitions)
    val (todos, droppedTodos) = decodeTodoRecords(root["todos"])
    val (journals, droppedJournals) = decodeRecords(root["journals"], Journal.serializer())
    val state = AppState(
        events = events,
        todos = todos,
        journals = journals,
        calendar = CalendarMetadata(timeZones = definitions.values.toList()),
    )
    registerIcsTimeZones(state.calendar.timeZones)
    return StateDecode(
        state,
        LoadDamage(droppedRecords = droppedEvents + droppedTodos + droppedJournals),
    )
}

/** Layout used by stage 2, the last version that stored `VTIMEZONE` text on each event. */
private const val StageTwoSchemaVersion = 3

/**
 * Lifts the `VTIMEZONE` text a pre-stage-3 event carried onto the calendar level. Both shapes the
 * old model used are read: the event's own `timeZoneDefinition` and the `definition` nested in a
 * typed time value, so a custom zone survives whether the payload predates or postdates stage 1.
 */
private fun collectLegacyTimeZoneDefinitions(root: JsonObject): List<CalendarTimeZone> {
    val definitions = linkedMapOf<String, CalendarTimeZone>()

    fun register(tzid: String?, definition: String?) {
        val id = tzid?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return
        val text = definition?.takeIf { it.isNotBlank() } ?: return
        definitions[id] = CalendarTimeZone(id, text)
    }

    fun zoned(element: JsonElement?): JsonObject? =
        (element as? JsonObject)?.takeIf {
            (it["type"] as? JsonPrimitive)?.contentOrNull == "zoned"
        }

    fun tzidOf(element: JsonElement?): String? =
        zoned(element)?.get("tzid")?.let { (it as? JsonPrimitive)?.contentOrNull }

    fun collectValue(element: JsonElement?) {
        register(tzidOf(element), zoned(element)?.get("definition")?.let { (it as? JsonPrimitive)?.contentOrNull })
    }

    (root["events"] as? JsonArray)?.forEach { element ->
        val event = element as? JsonObject ?: return@forEach
        val definition = (event["timeZoneDefinition"] as? JsonPrimitive)?.contentOrNull
        if (definition != null) register(tzidOf(event["start"]) ?: tzidOf(event["end"]), definition)
        collectValue(event["start"])
        collectValue(event["end"])
        collectValue(event["recurrenceId"])
        val recurrence = event["recurrence"] as? JsonObject
        collectValue((recurrence?.get("rule") as? JsonObject)?.get("until"))
        (recurrence?.get("rdates") as? JsonArray)?.forEach { rdate ->
            collectValue((rdate as? JsonObject)?.get("value"))
        }
        (recurrence?.get("exdates") as? JsonArray)?.forEach(::collectValue)
    }
    return definitions.values.toList()
}

/** One event at a time: the current shape first, then either pre-stage-2 layout. */
private fun decodeEventRecords(
    element: JsonElement?,
    definitions: MutableMap<String, CalendarTimeZone>,
): Pair<List<Event>, Int> {
    if (element == null) return emptyList<Event>() to 0
    val array = element as? JsonArray ?: return emptyList<Event>() to 1
    var dropped = 0
    val kept = array.mapNotNull { item ->
        runCatching { AppJson.decodeFromJsonElement(Event.serializer(), item) }.getOrNull()
            ?: runCatching {
                AppJson.decodeFromJsonElement(LegacyEvent.serializer(), item).toCurrentEvent(definitions)
            }.getOrNull()
                .also { if (it == null) dropped++ }
    }
    return kept to dropped
}

/** One todo at a time: the current shape first, then the stage-1 shape with its old rule. */
private fun decodeTodoRecords(element: JsonElement?): Pair<List<Todo>, Int> {
    if (element == null) return emptyList<Todo>() to 0
    val array = element as? JsonArray ?: return emptyList<Todo>() to 1
    var dropped = 0
    val kept = array.mapNotNull { item ->
        runCatching { AppJson.decodeFromJsonElement(Todo.serializer(), item) }.getOrNull()
            ?: runCatching {
                AppJson.decodeFromJsonElement(LegacyTodo.serializer(), item).toCurrentTodo()
            }.getOrNull()
                .also { if (it == null) dropped++ }
    }
    return kept to dropped
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
