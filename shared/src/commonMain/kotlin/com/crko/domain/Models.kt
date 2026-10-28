package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class RepeatFrequency { Daily, Weekly, Custom }

@Serializable
data class RepeatRule(
    val frequency: RepeatFrequency,
    /** Monday-first weekday indices (0 = Monday) used when [frequency] is [RepeatFrequency.Custom]. */
    val weekdays: List<Int> = emptyList(),
)

@Serializable
data class Event(
    /** Null for events that have not been saved yet. */
    val id: String?,
    val title: String,
    /** The date of the first occurrence, also the anchor for a repeating event. */
    val date: String,
    /** Minutes after midnight. */
    val startMinutes: Int,
    /** Minutes after midnight, strictly greater than [startMinutes]. */
    val endMinutes: Int,
    val note: String = "",
    /** Null for a one-off event. */
    val repeat: RepeatRule? = null,
    /** Dates on which a repeating event is skipped, e.g. a single day cleared from the agenda. */
    val excludedDates: List<String> = emptyList(),
) {
    /** Parsed [date], cached because [occursOn] runs for every visible calendar cell. */
    val day: LocalDate by lazy(LazyThreadSafetyMode.NONE) { LocalDate.parse(date) }
}

/**
 * Latest minute of day an event may end at (23:59). The editor renders times as LocalTime,
 * which cannot represent 24:00, so an end at 1440 would crash on recompose.
 */
const val MaxEndMinutes = 23 * 60 + 59

enum class TodoPriority { High, Normal, Low }

@Serializable
data class Todo(
    val id: String,
    val title: String,
    val done: Boolean = false,
    val priority: TodoPriority = TodoPriority.Normal,
    /** Creation time in epoch milliseconds; used only for ordering. */
    val createdAt: Long = 0L,
    val due: String? = null,
)

@Serializable
data class AppState(
    val events: List<Event> = emptyList(),
    val todos: List<Todo> = emptyList(),
)

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class PersistedSettings(
    val language: String = "",
    val darkMode: String = "",
    val palette: String = "",
    val keyColor: Long = 0xFF3B6CF5,
    val agendaView: String = "",
    val agendaScrollHour: Int = -1,
)

/** Whether this event happens on [date]; repeating events also cover every later matching date. */
fun Event.occursOn(date: LocalDate): Boolean {
    val anchor = day
    if (date < anchor) return false
    // Skip the string allocation when the series has no exceptions.
    if (excludedDates.isNotEmpty() && date.toString() in excludedDates) return false
    val rule = repeat ?: return date == anchor
    return when (rule.frequency) {
        RepeatFrequency.Daily -> true
        RepeatFrequency.Weekly -> date.dayOfWeek == anchor.dayOfWeek
        RepeatFrequency.Custom -> date.mondayIndex in rule.weekdays
    }
}

/** Events happening on [date], ordered by start time. */
fun List<Event>.occurringOn(date: LocalDate): List<Event> =
    filter { it.occursOn(date) }.sortedBy { it.startMinutes }
