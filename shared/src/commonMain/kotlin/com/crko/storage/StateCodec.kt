package com.crko.storage

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.CalendarMetadata
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.Journal
import com.crko.domain.LegacyAppState
import com.crko.domain.LegacyEvent
import com.crko.domain.LegacyTodo
import com.crko.domain.PersistedSettings
import com.crko.domain.StageFiveAppState
import com.crko.domain.StageFourAppState
import com.crko.domain.legacyCalendarState
import com.crko.domain.normalizeTimeZoneId
import com.crko.domain.replaceIcsTimeZones
import com.crko.domain.toCurrentEvent
import com.crko.domain.toCurrentTodo
import com.crko.domain.withLocalCalendar
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Outcome of reading a payload: what could be restored, plus what had to be given up. */
internal data class StateDecode(val state: AppState, val damage: LoadDamage)

/** Invalid or older payloads fall back to an empty state instead of failing startup. */
internal fun decodeState(raw: String): AppState = decodeStateWithReport(raw).state

/**
 * Reads a stored payload so that one unreadable record cannot discard the whole calendar: the
 * records that still parse are kept and the rest is counted as damage. A payload written before
 * stage 1 is decoded through [LegacyEvent] and migrated in place, and every pre-stage-5 payload is
 * lifted into the calendar containers.
 */
internal fun decodeStateWithReport(raw: String): StateDecode {
    val root = runCatching { AppJson.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        ?: return StateDecode(AppState(), LoadDamage(unreadable = true))
    val version = (root["schemaVersion"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    // Only a payload already in the current layout may be decoded directly; an older one would
    // silently lose the fields this version moved into the calendar containers.
    if (version == CurrentSchemaVersion) {
        runCatching { AppJson.decodeFromString(AppState.serializer(), raw) }.getOrNull()
            ?.let { state ->
                val normalized = state.withLocalCalendar()
                replaceIcsTimeZones(normalized.calendars)
                return StateDecode(normalized, LoadDamage.None)
            }
        // One broken record must not discard the other calendars: decode the container headers and
        // every record on its own, counting only what could not be read.
        decodeCurrentCalendars(root)?.let { decoded ->
            replaceIcsTimeZones(decoded.state.calendars)
            return StateDecode(decoded.state, LoadDamage(droppedRecords = decoded.droppedRecords))
        }
        // A payload that announces the current layout but has no usable `calendars` array is not a
        // legacy shape. Falling through to the flat readers used to answer with an empty state and no
        // damage, so the next edit overwrote the only copy without even a corrupt backup.
        if (root.containsKey("calendars")) {
            return StateDecode(AppState(), LoadDamage(unreadable = true))
        }
    }
    // Stage-5 payloads still kept flat lists; lift them into one local calendar plus one calendar per
    // subscription, attributing old `sub:<id>` provenance to its subscription.
    if (version == StageFiveSchemaVersion) {
        runCatching { AppJson.decodeFromJsonElement(StageFiveAppState.serializer(), root) }.getOrNull()
            ?.let { previous ->
                val state = previous.toCurrent().withLocalCalendar()
                replaceIcsTimeZones(state.calendars)
                return StateDecode(state, LoadDamage.None)
            }
        // One damaged record must not cost the whole payload: the flat lists are read item by item, so
        // the subscriptions, their `sub:<id>` records and the calendar-level zones survive.
        val lenient = decodeStageFiveCalendars(root)
        replaceIcsTimeZones(lenient.state.calendars)
        return StateDecode(lenient.state, LoadDamage(droppedRecords = lenient.droppedRecords))
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
                val state = previous.toCurrent(definitions).withLocalCalendar()
                replaceIcsTimeZones(state.calendars)
                return StateDecode(state, LoadDamage.None)
            }
    }
    // A stage-1 payload kept the `VTIMEZONE` text of an unknown zone inside the typed value's
    // `definition`, which the current `Zoned` no longer carries: recover it here, or the zone id
    // survives while the offset it stood for is gone and every instant of the record is wrong.
    val definitions = collectLegacyTimeZoneDefinitions(root)
        .associateByTo(LinkedHashMap()) { normalizeTimeZoneId(it.tzid) }
    runCatching {
        AppJson.decodeFromJsonElement(LegacyAppState.serializer(), root)
    }.getOrNull()?.let { legacy ->
        val events = legacy.events.mapNotNull { it.toCurrentEvent(definitions) }
        val state = legacyCalendarState(
            events = events,
            todos = legacy.todos.map { it.toCurrentTodo() },
            journals = legacy.journals.map { it.toCurrent() },
            subscriptions = legacy.subscriptions,
            busyPeriods = legacy.busyPeriods,
            calendar = CalendarMetadata(timeZones = definitions.values.toList()),
        ).withLocalCalendar()
        replaceIcsTimeZones(state.calendars)
        return StateDecode(
            state = state,
            damage = LoadDamage(droppedRecords = legacy.events.size - events.size),
        )
    }
    val (events, droppedEvents) = decodeEventRecords(root["events"], definitions)
    val (todos, droppedTodos) = decodeTodoRecords(root["todos"])
    val (journals, droppedJournals) = decodeRecords(root["journals"], Journal.serializer())
    val state = legacyCalendarState(
        events = events,
        todos = todos,
        journals = journals,
        subscriptions = emptyList(),
        busyPeriods = emptyList(),
        calendar = CalendarMetadata(timeZones = definitions.values.toList()),
    ).withLocalCalendar()
    replaceIcsTimeZones(state.calendars)
    return StateDecode(
        state,
        LoadDamage(droppedRecords = droppedEvents + droppedTodos + droppedJournals),
    )
}

internal fun decodeSettings(raw: String): PersistedSettings =
    runCatching { AppJson.decodeFromString(PersistedSettings.serializer(), raw) }.getOrElse { PersistedSettings() }
