package com.crko.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.MaxEndMinutes
import com.crko.domain.PersistedSettings
import com.crko.domain.RepeatFrequency
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.occursOn
import com.crko.domain.occurringOn
import com.crko.domain.today
import com.crko.i18n.AppLanguage
import com.crko.i18n.Strings
import com.crko.i18n.languageFromTag
import com.crko.i18n.languageTag
import com.crko.i18n.resolveLanguage
import com.crko.i18n.stringsFor
import com.crko.storage.AppStorage
import com.crko.storage.StateStorage
import com.crko.storage.newId
import com.crko.storage.systemLocaleTag
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

enum class DarkModeSetting { System, Light, Dark }

/** User-visible notifications; localized at display time so they follow language switches. */
sealed interface AppMessage {
    data object Saved : AppMessage
    data object Deleted : AppMessage
    data object TodoAdded : AppMessage
}

enum class PaletteSetting { Monet, Monochrome, Custom }

/** Result of a mutation attempt; the UI maps [WriteResult] onto localized text. */
sealed interface WriteResult {
    data object Success : WriteResult
    data object EmptyTitle : WriteResult
    data object InvalidTimeRange : WriteResult
    data object InvalidRepeat : WriteResult
}

@Stable
class AppStateHolder(
    private val scope: CoroutineScope,
    initialSystemLocale: String = systemLocaleTag(),
    private val clock: Clock = Clock.System,
    private val idProvider: () -> String = ::newId,
    private val storage: StateStorage = AppStorage,
) {
    var appState: AppState by mutableStateOf(storage.loadState())
        private set

    var settings: PersistedSettings by mutableStateOf(storage.loadSettings())
        private set

    var systemLocale: String by mutableStateOf(initialSystemLocale)

    val languageSetting: AppLanguage get() = languageFromTag(settings.language)
    val resolvedLanguage: AppLanguage get() = resolveLanguage(languageSetting, systemLocale)
    private val currentStrings: Strings get() = stringsFor(resolvedLanguage)
    val strings: Strings get() = currentStrings

    val darkModeSetting: DarkModeSetting
        get() = runCatching { DarkModeSetting.valueOf(settings.darkMode) }.getOrDefault(DarkModeSetting.System)
    val paletteSetting: PaletteSetting
        get() = runCatching { PaletteSetting.valueOf(settings.palette) }.getOrDefault(PaletteSetting.Monet)

    var today: LocalDate by mutableStateOf(today(clock))
        private set

    private val messageChannel = Channel<AppMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        if (settings.agendaScrollHour < 0) {
            updateSettings { it.copy(agendaScrollHour = 8) }
        }
    }

    fun refreshToday() {
        today = today(clock)
    }

    // ----- settings -----

    private fun updateSettings(transform: (PersistedSettings) -> PersistedSettings) {
        val next = transform(settings)
        settings = next
        storage.saveSettings(next)
    }

    fun setLanguage(language: AppLanguage) = updateSettings { it.copy(language = languageTag(language)) }

    fun setDarkMode(mode: DarkModeSetting) = updateSettings { it.copy(darkMode = mode.name) }

    fun setPalette(palette: PaletteSetting) = updateSettings { it.copy(palette = palette.name) }

    fun setKeyColor(argb: Long) = updateSettings { it.copy(keyColor = argb) }

    fun setDefaultHour(hour: Int) = updateSettings { it.copy(agendaScrollHour = hour.coerceIn(0, 23)) }

    fun setAgendaView(view: String) = updateSettings { it.copy(agendaView = view) }

    // ----- events -----

    /** Events that occur on a date, including occurrences of repeating events, ordered by start time. */
    fun eventsOn(date: LocalDate): List<Event> = appState.events.occurringOn(date)

    fun saveEvent(event: Event): WriteResult {
        if (event.title.isBlank()) return WriteResult.EmptyTitle
        if (event.endMinutes <= event.startMinutes) return WriteResult.InvalidTimeRange
        val repeat = event.repeat?.let { rule ->
            if (rule.frequency == RepeatFrequency.Custom) {
                val days = rule.weekdays.filter { it in 0..6 }.distinct().sorted()
                if (days.isEmpty()) return WriteResult.InvalidRepeat
                rule.copy(weekdays = days)
            } else {
                rule
            }
        }
        val normalized = event.copy(
            title = event.title.trim(),
            endMinutes = event.endMinutes.coerceAtMost(MaxEndMinutes),
            repeat = repeat,
            // Skips only make sense for a series; a one-off event must not inherit stale ones.
            excludedDates = if (repeat == null) emptyList() else event.excludedDates.distinct(),
        )
        val events = appState.events
        val exists = events.any { it.id == normalized.id }
        val updated = if (exists) events.map { if (it.id == normalized.id) normalized else it } else events + normalized
        persist(appState.copy(events = updated))
        return WriteResult.Success
    }

    fun deleteEvent(id: String?) {
        if (id == null) return
        persist(appState.copy(events = appState.events.filterNot { it.id == id }))
    }

    /**
     * Clears a single day: one-off events are removed while repeating events only skip that date,
     * so clearing today never wipes a whole series.
     */
    fun deleteEventsOn(date: LocalDate) {
        val key = date.toString()
        val updated = appState.events.mapNotNull { event ->
            when {
                !event.occursOn(date) -> event
                event.repeat == null -> null
                else -> event.copy(excludedDates = event.excludedDates + key)
            }
        }
        persist(appState.copy(events = updated))
    }

    // ----- todos -----

    fun activeTodos(): List<Todo> = appState.todos
        .filterNot { it.done }
        .sortedWith(compareBy<Todo> { it.due == null }.thenBy { it.due ?: "" }.thenByDescending { it.priority.ordinal == TodoPriority.High.ordinal }.thenByDescending { it.createdAt })

    fun completedTodos(): List<Todo> = appState.todos
        .filter { it.done }
        .sortedByDescending { it.createdAt }

    fun addTodo(title: String, priority: TodoPriority, due: LocalDate?): WriteResult {
        if (title.isBlank()) return WriteResult.EmptyTitle
        val todo = Todo(
            id = idProvider(),
            title = title.trim(),
            priority = priority,
            due = due?.toString(),
            createdAt = clock.now().toEpochMilliseconds(),
        )
        persist(appState.copy(todos = appState.todos + todo))
        return WriteResult.Success
    }

    fun setTodoDone(id: String, done: Boolean) {
        persist(appState.copy(todos = appState.todos.map { if (it.id == id) it.copy(done = done) else it }))
    }

    fun deleteTodo(id: String) {
        persist(appState.copy(todos = appState.todos.filterNot { it.id == id }))
    }

    // ----- statistics -----

    fun completedToday(): Int = appState.todos.count { it.done && it.due == today.toString() }

    fun notify(message: AppMessage) {
        scope.launch { messageChannel.send(message) }
    }

    private fun persist(next: AppState) {
        appState = next
        storage.saveState(next)
    }

}
