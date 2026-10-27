package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Event(
    /** Null for events that have not been saved yet. */
    val id: String?,
    val title: String,
    val date: String,
    /** Minutes after midnight. */
    val startMinutes: Int,
    /** Minutes after midnight, strictly greater than [startMinutes]. */
    val endMinutes: Int,
    val note: String = "",
)

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

val AppJson = Json {
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

val Event.startsAt: LocalDateTime
    get() = LocalDateTime(LocalDate.parse(date), LocalTime(startMinutes / 60, startMinutes % 60))

val Event.endsAt: LocalDateTime
    get() = LocalDateTime(LocalDate.parse(date), LocalTime(endMinutes / 60, endMinutes % 60))

val Event.day: LocalDate
    get() = LocalDate.parse(date)
