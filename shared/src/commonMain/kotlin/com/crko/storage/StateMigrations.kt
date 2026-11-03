package com.crko.storage

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.BusyInterval
import com.crko.domain.Calendar
import com.crko.domain.CalendarMetadata
import com.crko.domain.CalendarSubscription
import com.crko.domain.CalendarSyncState
import com.crko.domain.CalendarTimeZone
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.DefaultLocalCalendarName
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.LocalCalendarId
import com.crko.domain.Todo
import com.crko.domain.normalizeTimeZoneId
import com.crko.domain.withLocalCalendar
import com.crko.migration.LegacyEvent
import com.crko.migration.LegacyTodo
import com.crko.migration.StageFiveAppState
import com.crko.migration.toCurrentEvent
import com.crko.migration.toCurrentTodo
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Raw payload kept once before it is migrated from the pre-stage-1 event layout. */
internal const val LEGACY_STATE_BACKUP_KEY = "state.v1.legacy-backup"

/** Raw payload kept once before a versioned older layout is migrated. */
internal const val MIGRATION_STATE_BACKUP_KEY = "state.v1.migration-backup"

/** Layout used by stage 2, the last version that stored `VTIMEZONE` text on each event. */
internal const val StageTwoSchemaVersion = 3

/** Layout used by stage 5, the last version that kept flat record lists. */
internal const val StageFiveSchemaVersion = 5

/**
 * The header of one stored calendar. It deliberately omits the record lists, so decoding it cannot
 * fail because one record is damaged; the lists are decoded one item at a time afterwards.
 */
@kotlinx.serialization.Serializable
private data class StoredCalendarHeader(
    val id: String = LocalCalendarId,
    val name: String = DefaultLocalCalendarName,
    val method: String? = null,
    val refreshMinutes: Int? = null,
    val timeZones: List<CalendarTimeZone> = emptyList(),
    val syncState: CalendarSyncState? = null,
)

internal class DecodedCurrentState(val state: AppState, val droppedRecords: Int)

/** Lenient decode of the current shape; null when the payload has no calendar container at all. */
internal fun decodeCurrentCalendars(root: JsonObject): DecodedCurrentState? {
    val elements = root["calendars"] as? JsonArray ?: return null
    var dropped = 0
    val calendars = elements.mapNotNull { element ->
        val objectElement = element as? JsonObject
        if (objectElement == null) {
            dropped++
            return@mapNotNull null
        }
        val header = runCatching {
            AppJson.decodeFromJsonElement(StoredCalendarHeader.serializer(), objectElement)
        }.getOrNull()
        if (header == null) {
            dropped++
            return@mapNotNull null
        }
        val (events, droppedEvents) = decodeEventRecords(objectElement["events"], linkedMapOf())
        val (todos, droppedTodos) = decodeTodoRecords(objectElement["todos"])
        val (journals, droppedJournals) = decodeRecords(objectElement["journals"], Journal.serializer())
        dropped += droppedEvents + droppedTodos + droppedJournals
        Calendar(
            id = header.id,
            name = header.name,
            method = header.method,
            refreshMinutes = header.refreshMinutes,
            timeZones = header.timeZones,
            events = events,
            todos = todos,
            journals = journals,
            syncState = header.syncState,
        )
    }
    val (busyPeriods, droppedBusy) = decodeRecords(
        root["busyPeriods"],
        BusyInterval.serializer(),
    )
    dropped += droppedBusy
    return DecodedCurrentState(
        state = AppState(
            calendars = calendars,
            busyPeriods = busyPeriods,
            schemaVersion = CurrentSchemaVersion,
        ).withLocalCalendar(),
        droppedRecords = dropped,
    )
}

/**
 * Lenient read of a stage-5 (flat) payload whose strict shape failed. Every list is decoded one item
 * at a time and the subscription list plus the calendar metadata are kept: dropping them instead sent
 * the records of a subscription to the local calendar, which moved them out of the scope of that
 * subscription's updates and deletions while nothing at all was reported as damaged.
 */
internal fun decodeStageFiveCalendars(root: JsonObject): DecodedCurrentState {
    val definitions = collectLegacyTimeZoneDefinitions(root)
        .associateByTo(LinkedHashMap()) { normalizeTimeZoneId(it.tzid) }
    val (events, droppedEvents) = decodeEventRecords(root["events"], definitions)
    val (todos, droppedTodos) = decodeTodoRecords(root["todos"])
    val (journals, droppedJournals) = decodeRecords(root["journals"], Journal.serializer())
    val (subscriptions, droppedSubscriptions) = decodeRecords(
        root["subscriptions"],
        CalendarSubscription.serializer(),
    )
    val (busyPeriods, droppedBusy) = decodeRecords(
        root["busyPeriods"],
        BusyInterval.serializer(),
    )
    val metadata = (root["calendar"] as? JsonObject)?.let { element ->
        runCatching { AppJson.decodeFromJsonElement(CalendarMetadata.serializer(), element) }.getOrNull()
    }
    val zones = LinkedHashMap<String, CalendarTimeZone>()
    (metadata?.timeZones.orEmpty() + definitions.values).forEach { zone ->
        val id = normalizeTimeZoneId(zone.tzid)
        if (id.isNotEmpty() && zone.definition.isNotBlank() && id !in zones) zones[id] = zone.copy(tzid = id)
    }
    val state = StageFiveAppState(
        events = events,
        todos = todos,
        journals = journals,
        subscriptions = subscriptions,
        busyPeriods = busyPeriods,
        calendar = CalendarMetadata(
            name = metadata?.name,
            refreshMinutes = metadata?.refreshMinutes,
            method = metadata?.method,
            timeZones = zones.values.toList(),
        ),
    ).toCurrent().withLocalCalendar()
    return DecodedCurrentState(
        state = state,
        droppedRecords = droppedEvents + droppedTodos + droppedJournals + droppedSubscriptions + droppedBusy,
    )
}

/**
 * Lifts the `VTIMEZONE` text a pre-stage-3 record carried onto the calendar level. Both shapes the
 * old model used are read: the event's own `timeZoneDefinition` and the `definition` nested in a
 * typed time value. Every value of every record is walked, not only the events': a `VTODO` rule's
 * `UNTIL` and its added `RDATE` needed their offsets just as much, and a `TZID` left without the
 * block that declares it resolves to no instant at all once the payload is read back.
 */
internal fun collectLegacyTimeZoneDefinitions(root: JsonObject): List<CalendarTimeZone> {
    val definitions = linkedMapOf<String, CalendarTimeZone>()

    fun register(tzid: String?, definition: String?) {
        val id = tzid?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return
        val text = definition?.takeIf { it.isNotBlank() } ?: return
        definitions[id] = CalendarTimeZone(id, text)
    }

    fun content(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

    fun zoned(element: JsonElement?): JsonObject? =
        (element as? JsonObject)?.takeIf { content(it["type"]) == "zoned" }

    fun walk(element: JsonElement?) {
        when (element) {
            is JsonObject -> {
                zoned(element)?.let { register(content(it["tzid"]), content(it["definition"])) }
                element.values.forEach(::walk)
            }
            is JsonArray -> element.forEach(::walk)
            else -> Unit
        }
    }
    walk(root)

    // A pre-stage-1 event kept the zone id and the definition as two fields beside its own date.
    (root["events"] as? JsonArray)?.forEach { element ->
        val event = element as? JsonObject ?: return@forEach
        val definition = content(event["timeZoneDefinition"]) ?: return@forEach
        register(content(zoned(event["start"])?.get("tzid")) ?: content(event["timeZoneId"]), definition)
    }
    return definitions.values.toList()
}

/** One event at a time: the current shape first, then either pre-stage-2 layout. */
internal fun decodeEventRecords(
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
internal fun decodeTodoRecords(element: JsonElement?): Pair<List<Todo>, Int> {
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

internal fun <T> decodeRecords(element: JsonElement?, serializer: KSerializer<T>): Pair<List<T>, Int> {
    if (element == null) return emptyList<T>() to 0
    val array = element as? JsonArray ?: return emptyList<T>() to 1
    var dropped = 0
    val kept = array.mapNotNull { item ->
        runCatching { AppJson.decodeFromJsonElement(serializer, item) }.getOrNull()
            .also { if (it == null) dropped++ }
    }
    return kept to dropped
}
