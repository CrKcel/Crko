package com.crko.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.IcsImport
import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsWarning
import com.crko.domain.MaxEndMinutes
import com.crko.domain.PersistedSettings
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.daysTo
import com.crko.domain.localDate
import com.crko.domain.normalizedForZone
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.occurringOn
import com.crko.domain.parseRepeatUntil
import com.crko.domain.plusDays
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
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

enum class DarkModeSetting { System, Light, Dark }

enum class EditScope { ThisEvent, ThisAndFuture, AllEvents }

/** User-visible notifications; localized at display time so they follow language switches. */
sealed interface AppMessage {
    data object Saved : AppMessage
    data object Deleted : AppMessage
    data object TodoAdded : AppMessage
    data class EventsImported(
        val events: Int,
        val todos: Int = 0,
        val warnings: List<IcsWarning> = emptyList(),
    ) : AppMessage
    data class ImportFailed(val failure: IcsImportFailure) : AppMessage
    data object Exported : AppMessage
    data object ExportFailed : AppMessage

    /**
     * Stored data could not be read completely. [droppedRecords] is zero when nothing at all could
     * be parsed; the damaged payload itself was kept by the storage.
     */
    data class StorageRecovered(val droppedRecords: Int) : AppMessage
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

    private var lastZone: TimeZone = TimeZone.currentSystemDefault()

    private val messageChannel = Channel<AppMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    init {
        if (settings.agendaScrollHour < 0) {
            updateSettings { it.copy(agendaScrollHour = 8) }
        }
        // Imported TZID events keep their ICS source, so wall-clock fields follow a moved device.
        val stored = appState
        val rebased = stored.copy(
            events = stored.events.normalizedForZone(lastZone),
        )
        if (rebased != stored) {
            appState = rebased
            storage.saveState(rebased)
        }
        // A payload that could only be read partially used to look like an empty calendar with no
        // explanation; the damaged copy is kept by the storage, so say what happened.
        val damage = storage.loadDamage()
        if (!damage.isEmpty) notify(AppMessage.StorageRecovered(damage.droppedRecords))
    }

    fun refreshToday() {
        val zone = TimeZone.currentSystemDefault()
        if (zone != lastZone) {
            lastZone = zone
            val rebased = appState.copy(
                events = appState.events.normalizedForZone(zone),
            )
            if (rebased != appState) persist(rebased)
        }
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

    /**
     * Saves [event]. For a repeating event, [scope] decides whether the change applies to this
     * occurrence, this and all later occurrences, or the whole series.
     */
    fun saveEvent(
        event: Event,
        occurrenceDate: LocalDate? = null,
        scope: EditScope = EditScope.AllEvents,
    ): WriteResult {
        val normalized = normalize(event) ?: return validationError(event)
        val existing = appState.events.firstOrNull { it.id == normalized.id }
        val validated = preserveSource(normalized, existing, occurrenceDate)
        val now = clock.now()
        return when {
            scope == EditScope.ThisEvent && existing?.repeat != null -> {
                saveThisOccurrence(existing, validated, occurrenceDate, now)
            }
            scope == EditScope.ThisAndFuture && existing?.repeat != null -> {
                saveThisAndFuture(existing, validated, occurrenceDate, now)
            }
            existing?.repeat != null && occurrenceDate != null -> {
                saveWholeSeries(existing, validated, occurrenceDate, now)
            }
            else -> {
                val saved = validated.copy(
                    created = existing?.created ?: validated.created ?: now.toString(),
                    lastModified = now.toString(),
                    sequence = (existing?.sequence ?: validated.sequence) + if (existing == null) 0 else 1,
                )
                val updated = if (existing != null) {
                    appState.events.map { if (it.id == saved.id) saved else it }
                } else {
                    appState.events + saved
                }
                persist(appState.copy(events = updated))
                WriteResult.Success
            }
        }
    }

    private fun saveWholeSeries(
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate,
        now: kotlin.time.Instant,
    ): WriteResult {
        // The editor shows the occurrence's date, so the delta is relative to that occurrence.
        val shift = occurrenceDate.daysTo(edited.day)
        val anchor = series.day.plusDays(shift)
        val span = edited.day.daysTo(edited.endDay)
        val shiftedExclusions = when {
            edited.repeat == null -> emptyList()
            shift == 0 -> series.excludedDates
            else -> series.excludedDates.mapNotNull { shiftDateText(it, shift) }
        }
        // If the repeat rule itself was not edited, its dates move with the series too. This keeps
        // imported THISANDFUTURE boundaries and extra dates aligned with the new anchor.
        val savedRepeat = edited.repeat?.let { rule ->
            if (shift != 0 && sameRepeatRule(rule, series.repeat)) {
                rule.copy(
                    until = rule.until?.let { shiftRepeatUntil(it, shift) },
                    rDates = rule.rDates.mapNotNull { shiftDateText(it, shift) },
                )
            } else {
                rule
            }
        }
        val saved = edited.copy(
            date = anchor.toString(),
            endDate = anchor.plusDays(span).takeIf { span != 0 }?.toString(),
            repeat = savedRepeat,
            excludedDates = shiftedExclusions,
            created = series.created ?: now.toString(),
            lastModified = now.toString(),
            sequence = series.sequence + 1,
        )
        // Exceptions (and imported THISANDFUTURE segments) belong to the same timeline. Moving the
        // series anchor shifts their dates and RECURRENCE-IDs with it, so they cannot collide with
        // or become detached from the moved series.
        val seriesUid = series.uid
        val affected = if (seriesUid == null) {
            emptySet()
        } else {
            buildSet {
                add(seriesUid)
                var changed = true
                while (changed) {
                    changed = false
                    appState.events.forEach { event ->
                        val parent = event.seriesUid
                        val uid = event.uid
                        if (parent != null && parent in this && uid != null) {
                            changed = add(uid) || changed
                        }
                    }
                }
            }
        }
        val updated = appState.events.mapNotNull { current ->
            when {
                current.id == series.id -> saved
                // Without a repeat the series no longer owns its exceptions.
                edited.repeat == null && current.seriesUid in affected -> null
                shift != 0 && current.seriesUid in affected -> current.shiftedBy(shift).copy(
                    lastModified = now.toString(),
                    sequence = current.sequence + 1,
                )
                else -> current
            }
        }
        persist(appState.copy(events = updated))
        return WriteResult.Success
    }

    private fun saveThisOccurrence(
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate?,
        now: kotlin.time.Instant,
    ): WriteResult {
        val occurrence = occurrenceDate ?: edited.day
        val occurrenceKey = occurrence.toString()
        val seriesUid = series.uid
        val existingOverride = appState.events.firstOrNull {
            it.seriesUid == seriesUid && it.recurrenceId == occurrenceKey
        }
        // The editor starts on the occurrence date, so a different date is an intentional move.
        val moved = edited.day != occurrence
        val span = edited.day.daysTo(edited.endDay)
        val targetDate = if (moved) edited.day else occurrence
        val targetEnd = if (moved) edited.endDay else occurrence.plusDays(span)
        // The imported source text describes the first occurrence, so only that override can keep it.
        val keepSource = targetDate == series.day
        val override = edited.copy(
            id = existingOverride?.id ?: idProvider(),
            // RECURRENCE-ID stays on the original slot; DTSTART may move to another date.
            date = targetDate.toString(),
            endDate = targetEnd.takeIf { it != targetDate }?.toString(),
            repeat = null,
            excludedDates = emptyList(),
            seriesUid = seriesUid,
            recurrenceId = occurrenceKey,
            thisAndFuture = false,
            sourceStart = if (keepSource) edited.sourceStart else null,
            sourceEnd = if (keepSource) edited.sourceEnd else null,
            timeZoneId = if (keepSource) edited.timeZoneId else null,
            timeZoneDefinition = if (keepSource) edited.timeZoneDefinition else null,
            icsUid = seriesUid?.let { "$it#override-$occurrenceKey" } ?: existingOverride?.icsUid,
            created = existingOverride?.created ?: now.toString(),
            lastModified = now.toString(),
            sequence = (existingOverride?.sequence ?: 0) + if (existingOverride == null) 0 else 1,
        )
        val updatedSeries = series.copy(
            excludedDates = (series.excludedDates + occurrenceKey).distinct(),
            lastModified = now.toString(),
            sequence = series.sequence + 1,
        )
        val events = appState.events.map {
            when {
                it.id == series.id -> updatedSeries
                it.id == existingOverride?.id -> override
                else -> it
            }
        }.let { list ->
            if (existingOverride == null) list + override else list
        }
        persist(appState.copy(events = events))
        return WriteResult.Success
    }

    private fun saveThisAndFuture(
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate?,
        now: kotlin.time.Instant,
    ): WriteResult {
        val occurrence = occurrenceDate ?: edited.day
        if (occurrence <= series.day) {
            return saveWholeSeries(series, edited, occurrence, now)
        }
        val previous = occurrence.plusDays(-1)
        val oldSeries = series.copy(
            repeat = series.repeat?.copy(until = previous.toString(), count = null),
            excludedDates = series.excludedDates.filter { it < occurrence.toString() },
            lastModified = now.toString(),
            sequence = series.sequence + 1,
        )
        val futureRepeat = when {
            edited.repeat == null -> null
            sameRepeatRule(edited.repeat, series.repeat) ->
                // The old series already consumed part of the rule; a new bound would be wrong.
                edited.repeat.copy(until = null, count = null)
            else -> edited.repeat
        }
        val moved = edited.day != occurrence
        val span = edited.day.daysTo(edited.endDay)
        val targetDate = if (moved) edited.day else occurrence
        val targetEnd = if (moved) edited.endDay else occurrence.plusDays(span)
        val newId = idProvider()
        val newSeries = edited.copy(
            id = newId,
            date = targetDate.toString(),
            endDate = targetEnd.takeIf { it != targetDate }?.toString(),
            repeat = futureRepeat,
            excludedDates = edited.excludedDates.filter { it >= occurrence.toString() },
            icsUid = null,
            seriesUid = null,
            recurrenceId = null,
            thisAndFuture = false,
            sourceStart = null,
            sourceEnd = null,
            timeZoneId = null,
            timeZoneDefinition = null,
            created = now.toString(),
            lastModified = now.toString(),
            sequence = 0,
        )
        val newUid = newSeries.uid
        val events = appState.events.mapNotNull { current ->
            when {
                current.id == series.id -> oldSeries
                current.seriesUid == series.uid &&
                    current.recurrenceId != null &&
                    current.recurrenceId >= occurrence.toString() ->
                    if (futureRepeat == null) {
                        null
                    } else {
                        current.copy(
                            seriesUid = newUid,
                            icsUid = newUid?.let { "$it#override-${current.recurrenceId}" },
                            lastModified = now.toString(),
                        )
                    }
                else -> current
            }
        } + newSeries
        persist(appState.copy(events = events))
        return WriteResult.Success
    }

    /** Removes an event by id, without touching the rest of a series. */
    fun deleteEvent(id: String?) {
        if (id == null) return
        persist(appState.copy(events = appState.events.filterNot { it.id == id }))
    }

    /**
     * Deletes [event] using [scope]. For a repeating series this can skip one occurrence, cut the
     * series at an occurrence, or remove the series together with all of its exceptions.
     */
    fun deleteEvent(
        event: Event,
        occurrenceDate: LocalDate? = null,
        scope: EditScope = EditScope.AllEvents,
    ) {
        val stored = appState.events.firstOrNull { it.id == event.id } ?: event
        val occurrence = occurrenceDate
            ?: stored.recurrenceId?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: stored.day
        val occurrenceKey = occurrence.toString()

        // Exceptions and one-off events are removed on their own; their series stays untouched.
        if (stored.repeat == null) {
            val parent = stored.seriesUid?.let { uid -> appState.events.firstOrNull { it.uid == uid } }
            if (parent == null) {
                appState.events.firstOrNull { it.id == stored.id }?.let { deleteEvent(it.id) }
                return
            }
            val parentUid = parent.uid
            val updated = appState.events.filterNot { current ->
                current.id == stored.id ||
                    (current.seriesUid == parentUid && current.recurrenceId == occurrenceKey)
            }.map { current ->
                if (current.id == parent.id) {
                    current.copy(excludedDates = (current.excludedDates + occurrenceKey).distinct())
                } else {
                    current
                }
            }
            persist(appState.copy(events = updated))
            return
        }

        val seriesUid = stored.uid
        val belongsToSeries: (Event) -> Boolean = { current ->
            current.seriesUid == seriesUid ||
                (stored.recurrenceId != null &&
                    current.seriesUid == stored.seriesUid &&
                    current.recurrenceId != null &&
                    current.recurrenceId >= stored.recurrenceId)
        }
        val updated = when (scope) {
            EditScope.ThisEvent -> appState.events.filterNot { current ->
                current.seriesUid == seriesUid && current.recurrenceId == occurrenceKey
            }.map { current ->
                if (current.id == stored.id) {
                    current.copy(excludedDates = (current.excludedDates + occurrenceKey).distinct())
                } else {
                    current
                }
            }
            EditScope.ThisAndFuture -> {
                if (occurrence <= stored.day) {
                    appState.events.filterNot { it.id == stored.id || belongsToSeries(it) }
                } else {
                    appState.events.mapNotNull { current ->
                        when {
                            current.id == stored.id -> current.copy(
                                repeat = current.repeat?.copy(
                                    until = occurrence.plusDays(-1).toString(),
                                    count = null,
                                ),
                            )
                            belongsToSeries(current) &&
                                current.recurrenceId != null &&
                                current.recurrenceId >= occurrenceKey -> null
                            else -> current
                        }
                    }
                }
            }
            EditScope.AllEvents -> appState.events.filterNot { it.id == stored.id || belongsToSeries(it) }
        }
        persist(appState.copy(events = updated))
    }

    /**
     * Appends events parsed from an ICS file, skipping ones whose UID is already stored so the
     * same file can be imported twice. Returns how many events were actually added.
     */
    fun importEvents(events: List<Event>): Int {
        val added = newEvents(events)
        if (added.isNotEmpty()) persist(appState.copy(events = appState.events + added))
        return added.size
    }

    /** Appends todos from an ICS file, deduplicating by UID. */
    fun importTodos(todos: List<Todo>): Int {
        val added = newTodos(todos)
        if (added.isNotEmpty()) persist(appState.copy(todos = appState.todos + added))
        return added.size
    }

    /**
     * Imports both halves of a file in a single write: two separate writes could leave a stored
     * calendar with the events of a half-finished import, and serialised the whole state twice.
     */
    fun importCalendar(import: IcsImport): Pair<Int, Int> {
        if (import.events.isEmpty() && import.todos.isEmpty()) return 0 to 0
        val addedEvents = newEvents(import.events)
        val addedTodos = newTodos(import.todos)
        if (addedEvents.isEmpty() && addedTodos.isEmpty()) return 0 to 0
        persist(
            appState.copy(
                events = appState.events + addedEvents,
                todos = appState.todos + addedTodos,
            ),
        )
        return addedEvents.size to addedTodos.size
    }

    private fun newEvents(events: List<Event>): List<Event> {
        val knownUids = appState.events.mapNotNull { it.icsUid?.takeIf(String::isNotBlank) }.toMutableSet()
        val knownIds = appState.events.mapNotNull { it.id }.toSet()
        return events
            .filterNot { isAlreadyStored(it.icsUid, knownUids, knownIds) }
            .map { it.copy(id = idProvider()) }
    }

    private fun newTodos(todos: List<Todo>): List<Todo> {
        val knownUids = appState.todos.mapNotNull { it.icsUid?.takeIf(String::isNotBlank) }.toMutableSet()
        val knownIds = appState.todos.map { it.id }.toSet()
        return todos
            .filterNot { isAlreadyStored(it.icsUid, knownUids, knownIds) }
            .map { it.copy(id = idProvider()) }
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
                else -> {
                    val occurrence = event.occurrenceStartOn(date) ?: date
                    event.copy(excludedDates = (event.excludedDates + occurrence.toString()).distinct())
                }
            }
        }
        persist(appState.copy(events = updated))
    }

    // ----- todos -----

    fun activeTodos(): List<Todo> = appState.todos
        .filterNot { it.done }
        .sortedWith(
            compareBy<Todo> { it.due == null }
                .thenBy { it.due ?: "" }
                .thenBy { it.priority.ordinal }
                .thenByDescending { it.createdAt },
        )

    fun completedTodos(): List<Todo> = appState.todos
        .filter { it.done }
        .sortedByDescending { it.createdAt }

    fun addTodo(title: String, priority: TodoPriority, due: LocalDate?): WriteResult {
        if (title.isBlank()) return WriteResult.EmptyTitle
        val now = clock.now()
        val todo = Todo(
            id = idProvider(),
            title = title.trim(),
            priority = priority,
            due = due?.toString(),
            createdAt = now.toEpochMilliseconds(),
            lastModified = now.toString(),
        )
        persist(appState.copy(todos = appState.todos + todo))
        return WriteResult.Success
    }

    fun setTodoDone(id: String, done: Boolean) {
        val now = clock.now()
        persist(
            appState.copy(
                todos = appState.todos.map { todo ->
                    if (todo.id == id) {
                        todo.copy(
                            done = done,
                            completedAt = if (done) today.toString() else null,
                            completedTime = if (done) now.toString() else null,
                            status = if (done) TodoStatus.Completed else TodoStatus.NeedsAction,
                            percentComplete = if (done) 100 else 0,
                            lastModified = now.toString(),
                            sequence = todo.sequence + 1,
                        )
                    } else {
                        todo
                    }
                },
            ),
        )
    }

    fun deleteTodo(id: String) {
        persist(appState.copy(todos = appState.todos.filterNot { it.id == id }))
    }

    // ----- statistics -----

    fun completedToday(): Int = appState.todos.count { it.done && it.completedAt == today.toString() }

    fun notify(message: AppMessage) {
        scope.launch { messageChannel.send(message) }
    }

    private fun validationError(event: Event): WriteResult = when {
        event.title.isBlank() -> WriteResult.EmptyTitle
        event.repeat?.frequency == RepeatFrequency.Custom &&
            event.repeat.weekdays.isEmpty() -> WriteResult.InvalidRepeat
        else -> WriteResult.InvalidTimeRange
    }

    /** Compares rules semantically: an editor round trip may reduce `UNTIL` to its calendar date. */
    private fun sameRepeatRule(a: RepeatRule?, b: RepeatRule?): Boolean {
        if (a == null || b == null) return a == b
        return a.copy(until = null) == b.copy(until = null) &&
            a.until?.let(::parseRepeatUntil)?.localDate() == b.until?.let(::parseRepeatUntil)?.localDate()
    }

    /**
     * Keeps the imported TZID/source values when an edit did not touch the wall-clock fields.
     * Otherwise they would no longer describe the event and are dropped.
     */
    private fun preserveSource(event: Event, existing: Event?, occurrenceDate: LocalDate?): Event {
        if (existing == null) {
            return event.copy(sourceStart = null, sourceEnd = null, timeZoneId = null, timeZoneDefinition = null)
        }
        val sameDate = if (existing.repeat != null && occurrenceDate != null) {
            event.day == occurrenceDate
        } else {
            event.day == existing.day
        }
        val sameTime = event.allDay == existing.allDay &&
            event.startMinutes == existing.startMinutes &&
            event.endMinutes == existing.endMinutes &&
            event.startSeconds == existing.startSeconds &&
            event.endSeconds == existing.endSeconds &&
            event.day.daysTo(event.endDay) == existing.day.daysTo(existing.endDay)
        if (!sameDate || !sameTime) {
            return event.copy(sourceStart = null, sourceEnd = null, timeZoneId = null, timeZoneDefinition = null)
        }
        return event.copy(
            sourceStart = existing.sourceStart,
            sourceEnd = existing.sourceEnd,
            timeZoneId = existing.timeZoneId,
            timeZoneDefinition = existing.timeZoneDefinition,
        )
    }

    private fun normalize(event: Event): Event? {
        if (event.title.isBlank()) return null
        val repeat = event.repeat?.let { rule ->
            if (rule.frequency == RepeatFrequency.Custom) {
                val days = rule.weekdays.filter { it in 0..6 }.distinct().sorted()
                if (days.isEmpty()) return null
                rule.copy(weekdays = days)
            } else {
                rule
            }.copy(
                interval = rule.interval.coerceAtLeast(1),
                count = rule.count?.takeIf { it > 0 },
                until = rule.until?.takeIf { parseRepeatUntil(it) != null },
            )
        }
        val allDay = event.allDay
        val start = if (allDay) 0 else event.startMinutes.coerceIn(0, MaxEndMinutes)
        val end = if (allDay) 0 else event.endMinutes.coerceIn(0, MaxEndMinutes)
        val startSeconds = if (allDay) 0 else event.startSeconds.coerceIn(0, 59)
        val endSeconds = if (allDay) 0 else event.endSeconds.coerceIn(0, 59)
        val endDay = event.endDay
        if (!allDay && endDay == event.day && end <= start) return null
        if (!allDay && endDay < event.day) return null
        return event.copy(
            title = event.title.trim(),
            startMinutes = start,
            endMinutes = end,
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            endDate = endDay.takeIf { it != event.day }?.toString(),
            allDay = allDay,
            repeat = repeat,
            // Skips only make sense for a series; a one-off event must not inherit stale ones.
            excludedDates = if (repeat == null) emptyList() else event.excludedDates.distinct(),
            status = if (event.status == EventStatus.Cancelled) EventStatus.Confirmed else event.status,
        )
    }

    private fun persist(next: AppState) {
        appState = next
        storage.saveState(next)
    }
}

/**
 * Whether an imported record is already stored, either under the same `icsUid` or as one of this
 * app's own exports (`<id>@crko`, optionally suffixed with `#override-...`). [knownUids] grows as it
 * is consulted, so records repeated inside one file are collapsed the same way.
 */
private fun isAlreadyStored(uid: String?, knownUids: MutableSet<String>, knownIds: Set<String>): Boolean {
    val key = uid?.takeIf { it.isNotBlank() } ?: return false
    if (!knownUids.add(key)) return true
    val base = key.substringBefore('#')
    return base.endsWith("@crko") && base.removeSuffix("@crko") in knownIds
}

private fun shiftDateText(text: String, days: Int): String? =
    runCatching { LocalDate.parse(text) }.getOrNull()?.plusDays(days)?.toString()

private fun shiftRepeatUntil(text: String, days: Int): String? {
    val parsed = parseRepeatUntil(text) ?: return text
    return when {
        parsed.instant != null -> (parsed.instant + days.days).toString()
        parsed.localDateTime != null -> LocalDateTime(
            parsed.localDateTime.date.plusDays(days),
            parsed.localDateTime.time,
        ).toString()
        else -> parsed.date.plusDays(days).toString()
    }
}

/** Moves an event (and its associated series/exception dates) by whole calendar days. */
private fun Event.shiftedBy(days: Int): Event {
    if (days == 0) return this
    val newDay = day.plusDays(days)
    val newEnd = endDay.plusDays(days)
    return copy(
        date = newDay.toString(),
        endDate = newEnd.takeIf { it != newDay }?.toString(),
        excludedDates = excludedDates.mapNotNull { shiftDateText(it, days) },
        recurrenceId = recurrenceId?.let { shiftDateText(it, days) ?: it },
        repeat = repeat?.copy(
            until = repeat.until?.let { shiftRepeatUntil(it, days) },
            rDates = repeat.rDates.mapNotNull { shiftDateText(it, days) },
        ),
        sourceStart = null,
        sourceEnd = null,
        timeZoneId = null,
        timeZoneDefinition = null,
    )
}
