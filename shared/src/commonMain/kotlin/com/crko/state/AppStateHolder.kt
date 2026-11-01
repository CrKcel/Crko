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
import com.crko.domain.IcsWarningDetail
import com.crko.domain.Journal
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
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.occursOn
import com.crko.domain.occurringOn
import com.crko.domain.parseRepeatUntil
import com.crko.domain.parseStoredDate
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
import kotlinx.coroutines.Job
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
        val warningDetails: List<IcsWarningDetail> = emptyList(),
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
    /**
     * Platform notification bridge, wired by the app composition root ([com.crko.App]). Null by
     * default so tests never touch platform notification centers.
     */
    private val reminders: ReminderScheduler? = null,
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

    private var reminderJob: Job? = null

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
        scheduleReminders()
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
        if (validated.alarms.isNotEmpty()) ensureReminderPermission()
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
                    dtStamp = now.toString(),
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
            dtStamp = now.toString(),
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
            dtStamp = now.toString(),
            sequence = (existingOverride?.sequence ?: 0) + if (existingOverride == null) 0 else 1,
        )
        val updatedSeries = series.copy(
            excludedDates = (series.excludedDates + occurrenceKey).distinct(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
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
        // RDATEs belong to whichever segment covers their date, like the exclusions below. A date
        // kept on the wrong side either duplicates the other segment or comes back as a phantom
        // occurrence when the calendar is exported; an unparseable one is inert and stays put.
        val oldRule = series.repeat
        val oldSeries = series.copy(
            repeat = oldRule?.copy(
                until = previous.toString(),
                count = null,
                rDates = oldRule.rDates.filter { text -> parseStoredDate(text)?.let { it < occurrence } ?: true },
            ),
            excludedDates = series.excludedDates.filter { it < occurrence.toString() },
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = series.sequence + 1,
        )
        val futureRepeat = when {
            edited.repeat == null -> null
            sameRepeatRule(edited.repeat, series.repeat) ->
                // The old series already consumed part of the rule; a new bound would be wrong.
                edited.repeat.copy(
                    until = null,
                    count = null,
                    rDates = edited.repeat.rDates.filter { text -> parseStoredDate(text)?.let { it >= occurrence } == true },
                )
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
            dtStamp = now.toString(),
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
        // An exception's slot is its RECURRENCE-ID even when the record was moved to another day;
        // for a series the opened date has to resolve to the start of the occurrence covering it,
        // so deleting a multi-day occurrence from its second day still hits the right slot.
        val occurrence = stored.recurrenceId?.let { parseStoredDate(it) }
            ?: occurrenceDate?.let { stored.occurrenceStartOn(it) ?: it }
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
     * Merges events parsed from an ICS file. A known UID is replaced when the file carries a newer
     * `SEQUENCE`/`DTSTAMP`, so re-importing an updated file does not keep stale copies.
     */
    fun importEvents(events: List<Event>): Int {
        val merged = mergeEvents(events, emptySet())
        if (merged.changed > 0 || merged.items != appState.events) {
            persist(appState.copy(events = merged.items))
        }
        return merged.changed
    }

    /** Merges todos from an ICS file, updating known UIDs newer than the stored copy. */
    fun importTodos(todos: List<Todo>): Int {
        val merged = mergeTodos(todos)
        if (merged.changed > 0 || merged.items != appState.todos) {
            persist(appState.copy(todos = merged.items))
        }
        return merged.changed
    }

    /**
     * Imports both halves of a file in a single write: two separate writes could leave a stored
     * calendar with the events of a half-finished import, and serialised the whole state twice.
     */
    fun importCalendar(import: IcsImport): Pair<Int, Int> {
        if (import.events.isEmpty() && import.todos.isEmpty() && import.journals.isEmpty() &&
            import.cancelledUids.isEmpty()
        ) {
            return 0 to 0
        }
        val events = mergeEvents(import.events, import.cancelledUids)
        val todos = mergeTodos(import.todos)
        val journals = mergeJournals(import.journals)
        val eventsChanged = events.changed > 0 || events.items != appState.events
        val todosChanged = todos.changed > 0 || todos.items != appState.todos
        val journalsChanged = journals.changed > 0 || journals.items != appState.journals
        if (!eventsChanged && !todosChanged && !journalsChanged) return 0 to 0
        persist(
            appState.copy(
                events = events.items,
                todos = todos.items,
                journals = journals.items,
            ),
        )
        return events.changed to todos.changed
    }

    private class MergeResult<T>(val items: List<T>, val changed: Int)

    private fun mergeEvents(events: List<Event>, cancelledUids: Set<String>): MergeResult<Event> {
        val merged = appState.events.toMutableList()
        if (cancelledUids.isNotEmpty()) {
            merged.removeAll { event -> cancelledUids.any { uid -> matchesUid(event, uid) } }
        }
        var changed = 0
        val seen = mutableSetOf<String>()
        events.forEach { incoming ->
            val uid = incoming.icsUid
            if (uid == null) {
                merged += incoming.copy(id = idProvider())
                changed++
                return@forEach
            }
            if (!seen.add(uid)) return@forEach
            val exact = merged.indexOfFirst { it.icsUid == uid }
            val index = if (exact >= 0) {
                exact
            } else if ('#' !in uid) {
                merged.indexOfFirst { matchesOwnExport(it.id, uid) }
            } else {
                -1
            }
            if (index >= 0) {
                val existing = merged[index]
                if (isNewer(incoming.sequence, incoming.dtStamp, incoming.lastModified, existing.sequence, existing.dtStamp, existing.lastModified)) {
                    merged[index] = incoming.copy(id = existing.id)
                    changed++
                }
            } else {
                merged += incoming.copy(id = idProvider())
                changed++
            }
        }
        // A record that simply stopped appearing in the file is NOT treated as deleted: without
        // import provenance there is no way to tell "removed in the other calendar" apart from a
        // partial export or an exception the user created locally, and guessing costs data.
        return MergeResult(merged, changed)
    }

    private fun mergeTodos(todos: List<Todo>): MergeResult<Todo> {
        val merged = appState.todos.toMutableList()
        var changed = 0
        val seen = mutableSetOf<String>()
        todos.forEach { incoming ->
            val uid = incoming.icsUid
            if (uid == null) {
                merged += incoming.copy(id = idProvider())
                changed++
                return@forEach
            }
            if (!seen.add(uid)) return@forEach
            val exact = merged.indexOfFirst { it.icsUid == uid }
            val index = if (exact >= 0) exact else if ('#' !in uid) merged.indexOfFirst { matchesOwnExport(it.id, uid) } else -1
            if (index >= 0) {
                val existing = merged[index]
                if (isNewer(incoming.sequence, incoming.dtStamp, incoming.lastModified, existing.sequence, existing.dtStamp, existing.lastModified)) {
                    merged[index] = incoming.copy(id = existing.id)
                    changed++
                }
            } else {
                merged += incoming.copy(id = idProvider())
                changed++
            }
        }
        return MergeResult(merged, changed)
    }

    private fun mergeJournals(journals: List<Journal>): MergeResult<Journal> {
        val merged = appState.journals.toMutableList()
        var changed = 0
        val seen = mutableSetOf<String>()
        journals.forEach { incoming ->
            val uid = incoming.icsUid
            if (uid == null) {
                merged += incoming.copy(id = idProvider())
                changed++
                return@forEach
            }
            if (!seen.add(uid)) return@forEach
            val exact = merged.indexOfFirst { it.icsUid == uid }
            val index = if (exact >= 0) exact else if ('#' !in uid) merged.indexOfFirst { matchesOwnExport(it.id, uid) } else -1
            if (index >= 0) {
                val existing = merged[index]
                if (isNewer(incoming.sequence, incoming.dtStamp, incoming.lastModified, existing.sequence, existing.dtStamp, existing.lastModified)) {
                    merged[index] = incoming.copy(id = existing.id)
                    changed++
                }
            } else {
                merged += incoming.copy(id = idProvider())
                changed++
            }
        }
        return MergeResult(merged, changed)
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

    fun activeTodos(): List<Todo> {
        // A series is shown under its next occurrence, so it sorts by that date instead of the
        // stale stored due; computed once per todo rather than per comparison.
        val today = today
        val shownDue = appState.todos.associate { todo ->
            todo.id to if (todo.repeat != null) {
                todo.nextOccurrenceDate(today)?.toString()
            } else {
                todo.due
            }
        }
        return appState.todos
            .filterNot { it.done }
            .sortedWith(
                compareBy<Todo> { shownDue[it.id] == null }
                    .thenBy { shownDue[it.id] ?: "" }
                    .thenBy { it.priority.ordinal }
                    .thenByDescending { it.createdAt },
            )
    }

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
            dtStamp = now.toString(),
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
                            dtStamp = now.toString(),
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
        if (!allDay && endDay == event.day && end < start) return null
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
        scheduleReminders()
    }

    /**
     * Keeps the platform notifications in line with the state: every persisted change (save,
     * delete, import, day rollover rebases) replaces the scheduled set. One job at a time, so a
     * burst of writes cannot interleave half-updated schedules.
     */
    private fun scheduleReminders() {
        val scheduler = reminders ?: return
        reminderJob?.cancel()
        reminderJob = scope.launch {
            runCatching { scheduler.reschedule(appState, currentStrings) }
        }
    }

    /**
     * Asks for notification permission the first time the user gives an event a reminder. Scheduling
     * itself stays silent about permissions so startup never shows a prompt.
     */
    private fun ensureReminderPermission() {
        val scheduler = reminders ?: return
        scope.launch {
            runCatching { scheduler.ensurePermission() }
        }
    }
}

/**
 * Whether [event] belongs to [uid], including this app's own export ids (`<id>@crko`) and the
 * `#override-`, `#future-` and `#prior-` copies derived from a series UID.
 */
private fun matchesUid(event: Event, uid: String): Boolean {
    val eventUid = event.icsUid
    if (eventUid == uid || event.seriesUid == uid) return true
    if (eventUid != null && eventUid.substringBefore('#') == uid) return true
    if (event.seriesUid?.substringBefore('#') == uid) return true
    return '#' !in uid && matchesOwnExport(event.id, uid)
}

/** Whether [uid] is the own-export id (`<recordId>@crko`) of [recordId]. */
private fun matchesOwnExport(recordId: String?, uid: String): Boolean {
    if (!uid.endsWith("@crko")) return false
    return uid.removeSuffix("@crko") == recordId
}

/** `SEQUENCE` wins, then `DTSTAMP`, then `LAST-MODIFIED`; missing metadata never overwrites. */
private fun isNewer(
    sequence: Int,
    dtStamp: String?,
    lastModified: String?,
    existingSequence: Int,
    existingDtStamp: String?,
    existingLastModified: String?,
): Boolean {
    if (sequence != existingSequence) return sequence > existingSequence
    val incoming = parseInstantOrNull(dtStamp ?: lastModified) ?: return false
    val existing = parseInstantOrNull(existingDtStamp ?: existingLastModified)
    return existing == null || incoming > existing
}

private fun parseInstantOrNull(value: String?): kotlin.time.Instant? =
    value?.let { runCatching { kotlin.time.Instant.parse(it) }.getOrNull() }

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
