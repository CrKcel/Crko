package com.crko.migration

import com.crko.domain.AppState
import com.crko.domain.BusyInterval
import com.crko.domain.Calendar
import com.crko.domain.CalendarMetadata
import com.crko.domain.CalendarSubscription
import com.crko.domain.CalendarTimeZone
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.DefaultLocalCalendarName
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.LocalCalendarId
import com.crko.domain.Todo
import com.crko.domain.toCalendar
import kotlinx.serialization.Serializable

/** The stage-5 flat payload, before records were moved into calendar containers. */
@Serializable
internal data class StageFiveAppState(
    val events: List<Event> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val journals: List<Journal> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val busyPeriods: List<BusyInterval> = emptyList(),
    val calendar: CalendarMetadata = CalendarMetadata(),
) {
    fun toCurrent(): AppState = legacyCalendarState(
        events = events,
        todos = todos,
        journals = journals,
        subscriptions = subscriptions,
        busyPeriods = busyPeriods,
        calendar = calendar,
    )
}

/** The whole stage-3/4 payload. */
@Serializable
internal data class StageFourAppState(
    val events: List<StageFourEvent> = emptyList(),
    val todos: List<StageFourTodo> = emptyList(),
    val journals: List<LegacyJournal> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val busyPeriods: List<BusyInterval> = emptyList(),
    val calendar: CalendarMetadata = CalendarMetadata(),
) {
    fun toCurrent(definitions: MutableMap<String, CalendarTimeZone>? = null): AppState {
        val events = events.mapNotNull { it.toCurrent(definitions) }
        val zones = mergeZoneDefinitions(calendar.timeZones, definitions.orEmpty().values)
        return legacyCalendarState(
            events = events,
            todos = todos.map { it.toCurrent() },
            journals = journals.map { it.toCurrent() },
            subscriptions = subscriptions,
            busyPeriods = busyPeriods,
            calendar = calendar.copy(timeZones = zones),
        )
    }
}

private fun mergeZoneDefinitions(
    existing: List<CalendarTimeZone>,
    extra: Collection<CalendarTimeZone>,
): List<CalendarTimeZone> {
    val merged = LinkedHashMap<String, CalendarTimeZone>()
    (existing + extra).forEach { zone ->
        if (zone.tzid.isNotBlank() && zone.definition.isNotBlank()) merged[zone.tzid] = zone
    }
    return merged.values.toList()
}

private const val LegacySubscriptionSourcePrefix = "sub:"

/** Old payloads marked subscription records with `sub:<subscription id>` as their import source. */
private fun legacySubscriptionId(importSource: String?): String? =
    importSource?.takeIf { it.startsWith(LegacySubscriptionSourcePrefix) }
        ?.removePrefix(LegacySubscriptionSourcePrefix)
        ?.takeIf { it.isNotBlank() }

/**
 * Lifts a flat pre-stage-5 payload into the calendar containers. This is the only place the old
 * `sub:<id>` provenance may act as calendar identity: afterwards each record lives in exactly one
 * calendar and the marker is no longer consulted.
 */
internal fun legacyCalendarState(
    events: List<Event>,
    todos: List<Todo>,
    journals: List<Journal>,
    subscriptions: List<CalendarSubscription>,
    busyPeriods: List<BusyInterval>,
    calendar: CalendarMetadata,
): AppState {
    val subscriptionCalendars = subscriptions.map { it.toCalendar() }
    val knownIds = subscriptionCalendars.mapTo(mutableSetOf()) { it.id }
    fun owner(importSource: String?): String? =
        legacySubscriptionId(importSource)?.takeIf { it in knownIds }

    val eventsByOwner = events.groupBy { owner(it.importSource) }
    val todosByOwner = todos.groupBy { owner(it.importSource) }
    val journalsByOwner = journals.groupBy { owner(it.importSource) }
    val local = Calendar(
        id = LocalCalendarId,
        name = calendar.name ?: DefaultLocalCalendarName,
        method = calendar.method,
        refreshMinutes = calendar.refreshMinutes,
        timeZones = calendar.timeZones,
        events = eventsByOwner[null].orEmpty(),
        todos = todosByOwner[null].orEmpty(),
        journals = journalsByOwner[null].orEmpty(),
    )
    val calendars = buildList {
        add(local)
        subscriptionCalendars.forEach { subscription ->
            add(
                subscription.copy(
                    events = eventsByOwner[subscription.id].orEmpty(),
                    todos = todosByOwner[subscription.id].orEmpty(),
                    journals = journalsByOwner[subscription.id].orEmpty(),
                ),
            )
        }
    }
    return AppState(
        calendars = calendars,
        busyPeriods = busyPeriods,
        schemaVersion = CurrentSchemaVersion,
    )
}

/** The whole pre-stage-2 payload. */
@Serializable
internal data class LegacyAppState(
    val events: List<LegacyEvent> = emptyList(),
    val todos: List<LegacyTodo> = emptyList(),
    val journals: List<LegacyJournal> = emptyList(),
    val subscriptions: List<CalendarSubscription> = emptyList(),
    val busyPeriods: List<BusyInterval> = emptyList(),
)
