package com.crko.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppState
import com.crko.domain.Attachment
import com.crko.domain.Calendar
import com.crko.domain.CalendarSubscription
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.IcsImport
import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.domain.IcsPartStat
import com.crko.domain.IcsTimeValue
import com.crko.domain.Journal
import com.crko.domain.PersistedSettings
import com.crko.domain.RepeatRule
import com.crko.domain.boundedBefore
import com.crko.domain.SubscriptionFailure
import com.crko.domain.Todo
import com.crko.domain.TodoNode
import com.crko.domain.TodoPriority
import com.crko.domain.daysTo
import com.crko.domain.instantOrNull
import com.crko.domain.movedByWallDays
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.occurringOn
import com.crko.domain.plusDays
import com.crko.domain.plusWallDays
import com.crko.domain.recurrenceSlotOn
import com.crko.domain.icsTimeZoneConflicts
import com.crko.domain.replaceIcsTimeZones
import com.crko.domain.shiftedByWallDays
import com.crko.domain.toCalendar
import com.crko.domain.wallDate
import com.crko.domain.withExclusion
import com.crko.domain.withLocalCalendar
import com.crko.domain.resolveSubscriptionUrl
import com.crko.domain.today
import com.crko.domain.wallDateFor
import com.crko.domain.wallDateTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import com.crko.i18n.AppLanguage
import com.crko.i18n.Strings
import com.crko.storage.AttachmentRepository
import com.crko.storage.AppStorage
import com.crko.storage.StateStorage
import com.crko.storage.SubscriptionHttp
import com.crko.storage.newId
import com.crko.storage.systemLocaleTag
import com.crko.state.`import`.AttachmentMaterializer
import com.crko.state.`import`.ImportService
import com.crko.state.journal.JournalService
import com.crko.state.settings.SettingsController
import com.crko.state.stats.StatsService
import com.crko.state.subscription.SubscriptionSyncService
import com.crko.state.todo.TodoService
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

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
        val journals: Int = 0,
        val removed: Int = 0,
        val warnings: List<IcsWarning> = emptyList(),
        val warningDetails: List<IcsWarningDetail> = emptyList(),
    ) : AppMessage
    data class ImportFailed(val failure: IcsImportFailure) : AppMessage
    data object Exported : AppMessage
    data object ExportFailed : AppMessage

    /** An attendee answered an invitation; [partStat] is the answer. */
    data class ItipReplyProcessed(val attendee: String, val partStat: IcsPartStat) : AppMessage

    /** A `REPLY` named no record this calendar knows (or an older revision than the stored one). */
    data class ItipReplyUnknown(val attendee: String) : AppMessage
    data class SubscriptionSynced(
        val name: String,
        val events: Int,
        val todos: Int = 0,
        val removed: Int = 0,
    ) : AppMessage

    data class SubscriptionUnchanged(val name: String) : AppMessage
    data class SubscriptionFailed(val name: String, val failure: SubscriptionFailure) : AppMessage

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
    /**
     * HTTP sender for calendar subscriptions, wired by the composition root. Null by default so
     * tests can inject a fake or run without any network access.
     */
    private val subscriptionHttp: SubscriptionHttp? = null,
    /**
     * Dispatcher that parses fetched feeds off the caller. Injectable so tests keep every step on
     * the test scheduler instead of racing the real default dispatcher.
     */
    private val parseDispatcher: CoroutineDispatcher? = null,
    /**
     * Reads and writes materialized `ATTACH` payloads. Injectable so tests can keep files in memory
     * and so the domain package never needs a storage import.
     */
    private val attachmentRepository: AttachmentRepository = AttachmentRepository(),
) {
    var appState: AppState by mutableStateOf(storage.loadState().withLocalCalendar())
        private set

    var today: LocalDate by mutableStateOf(today(clock))
        private set

    private val settingsController = SettingsController(storage, initialSystemLocale)

    /** User preferences; loading, defaults and persistence live in the controller. */
    val settings: PersistedSettings get() = settingsController.settings

    var systemLocale: String
        get() = settingsController.systemLocale
        set(value) {
            settingsController.systemLocale = value
        }

    val languageSetting: AppLanguage get() = settingsController.languageSetting
    val resolvedLanguage: AppLanguage get() = settingsController.resolvedLanguage
    val strings: Strings get() = settingsController.strings
    private val currentStrings: Strings get() = settingsController.strings

    val darkModeSetting: DarkModeSetting get() = settingsController.darkModeSetting
    val paletteSetting: PaletteSetting get() = settingsController.paletteSetting

    private val attachmentMaterializer = AttachmentMaterializer(attachmentRepository)

    private val stateAccess = object : StateAccess {
        override val state: AppState get() = appState
        override fun persist(next: AppState) = this@AppStateHolder.persist(next)
        override fun notify(message: AppMessage) = this@AppStateHolder.notify(message)
    }

    private val importService = ImportService(
        access = stateAccess,
        attachments = attachmentMaterializer,
        clock = clock,
        idProvider = idProvider,
    )

    private val subscriptionSync = SubscriptionSyncService(
        access = stateAccess,
        scope = scope,
        clock = clock,
        http = subscriptionHttp,
        attachmentRepository = attachmentRepository,
        parseDispatcher = parseDispatcher,
        idProvider = idProvider,
        // Read on every use so the untitled-event placeholder follows a language switch.
        strings = { currentStrings },
    )

    private val todoService = TodoService(
        access = stateAccess,
        clock = clock,
        idProvider = idProvider,
        today = { today },
    )

    private val journalService = JournalService(
        access = stateAccess,
        clock = clock,
        idProvider = idProvider,
    )

    private val statsService = StatsService(access = stateAccess, today = { today })

    private val messageChannel = Channel<AppMessage>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()

    /**
     * `TZID` names that two or more calendars declare with different definitions. RFC 5545 scopes a
     * `TZID` to its own `VCALENDAR`, so record-level projections resolve such a name through the
     * first declaring calendar and may be wrong for the others; callers can surface a warning from
     * this set. Per-calendar exports are always exact because each `Calendar` keeps its own blocks.
     */
    val timeZoneConflicts: Set<String> get() = icsTimeZoneConflicts()

    private var reminderJob: Job? = null
    private val reminderMutex = Mutex()

    init {
        // Unknown zones are stored once at the calendar level; registering them per calendar here
        // makes the restored events resolve their wall clock and absolute instant on the first frame,
        // and keeps two calendars naming the same TZID apart.
        replaceIcsTimeZones(appState.calendars)
        // A payload that could only be read partially used to look like an empty calendar with no
        // explanation; the damaged copy is kept by the storage, so say what happened.
        val damage = storage.loadDamage()
        if (!damage.isEmpty) notify(AppMessage.StorageRecovered(damage.droppedRecords))
        scheduleReminders()
    }

    fun refreshToday() {
        // Event times are stored in their own RFC time value, so a device zone change only changes
        // the display projection; the persisted records no longer need a rewrite.
        today = today(clock)
    }

    /** Index of the calendar new local records go into; a payload always keeps at least one. */
    private val localCalendarIndex: Int
        get() = appState.calendars.indexOfFirst { it.isLocal }.takeIf { it >= 0 } ?: 0

    /** Index of the calendar that owns the record selected by [contains]. */
    private fun calendarIndexContaining(contains: (Calendar) -> Boolean): Int? =
        appState.calendars.indexOfFirst(contains).takeIf { it >= 0 }

    /** Replaces one calendar, leaving every other calendar's records untouched. */
    private fun AppState.replacingCalendar(index: Int, transform: (Calendar) -> Calendar): AppState {
        if (index !in calendars.indices) return this
        val updated = calendars.toMutableList()
        updated[index] = transform(updated[index])
        return copy(calendars = updated)
    }

    // ----- settings (delegated to SettingsController) -----

    fun setLanguage(language: AppLanguage) = settingsController.setLanguage(language)

    fun setDarkMode(mode: DarkModeSetting) = settingsController.setDarkMode(mode)

    fun setPalette(palette: PaletteSetting) = settingsController.setPalette(palette)

    fun setKeyColor(argb: Long) = settingsController.setKeyColor(argb)

    fun setDefaultHour(hour: Int) = settingsController.setDefaultHour(hour)

    fun setAgendaView(view: String) = settingsController.setAgendaView(view)

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
        // The editor leaves new records without an id; assigning it here keeps identifier creation
        // out of the UI, which no longer needs to know how a platform makes one.
        val identified = if (normalized.id == null) normalized.copy(id = idProvider()) else normalized
        val existing = appState.events.firstOrNull { it.id == identified.id }
        val validated = identified
        val now = clock.now()
        if (validated.alarms.isNotEmpty()) ensureReminderPermission()
        // An edit belongs to the calendar that owns the stored record; a new record goes local.
        val calendarIndex = existing?.let { stored ->
            calendarIndexContaining { calendar -> calendar.events.any { it.id == stored.id } }
        } ?: localCalendarIndex
        val storedEvents = appState.calendars[calendarIndex].events
        return when {
            scope == EditScope.ThisEvent && existing?.repeat != null -> {
                saveThisOccurrence(storedEvents, existing, validated, occurrenceDate, now, calendarIndex)
            }
            scope == EditScope.ThisAndFuture && existing?.repeat != null -> {
                saveThisAndFuture(storedEvents, existing, validated, occurrenceDate, now, calendarIndex)
            }
            existing?.repeat != null && occurrenceDate != null -> {
                saveWholeSeries(storedEvents, existing, validated, occurrenceDate, now, calendarIndex)
            }
            else -> {
                val saved = validated.copy(
                    created = existing?.created ?: validated.created ?: now.toString(),
                    lastModified = now.toString(),
                    dtStamp = now.toString(),
                    sequence = (existing?.sequence ?: validated.sequence) + if (existing == null) 0 else 1,
                )
                val updated = if (existing != null) {
                    storedEvents.map { if (it.id == saved.id) saved else it }
                } else {
                    storedEvents + saved
                }
                persist(appState.replacingCalendar(calendarIndex) { it.copy(events = updated) })
                WriteResult.Success
            }
        }
    }

    private fun saveWholeSeries(
        storedEvents: List<Event>,
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate,
        now: kotlin.time.Instant,
        calendarIndex: Int,
    ): WriteResult {
        // The editor shows the occurrence's date, so the delta is relative to that occurrence.
        val shift = occurrenceDate.daysTo(edited.day)
        val anchorWall = series.wallDay.plusDays(shift)
        val shiftToAnchor = edited.wallDay.daysTo(anchorWall)
        // If the repeat rule itself was not edited, its typed dates move with the series too. This
        // keeps `UNTIL`, `RDATE` and `EXDATE` aligned with the new anchor. The stored set is merged
        // in because an editor loaded before an exception was added still carries the old one.
        val savedRecurrence = edited.recurrence?.let { set ->
            val merged = if (sameRepeatRule(set.rule, series.repeat)) {
                set.copy(
                    rdates = (series.recurrence?.rdates.orEmpty() + set.rdates).distinct(),
                    exdates = (series.recurrence?.exdates.orEmpty() + set.exdates).distinct(),
                )
            } else {
                set
            }
            if (shift == 0) merged else merged.shiftedByWallDays(shift)
        }
        val saved = edited.movedByWallDays(shiftToAnchor).copy(
            recurrence = savedRecurrence,
            created = series.created ?: now.toString(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = series.sequence + 1,
        )
        // Exceptions belong to the same timeline through their shared UID. Moving the series anchor
        // shifts their dates and RECURRENCE-IDs with it, so they cannot collide with or become
        // detached from the moved series.
        val seriesUid = series.uid
        val updated = storedEvents.mapNotNull { current ->
            val isException = seriesUid != null && current.icsUid == seriesUid && current.recurrenceId != null
            when {
                current.id == series.id -> saved
                // Without a recurrence the series no longer owns its exceptions.
                edited.recurrence == null && isException -> null
                shift != 0 && isException -> current.shiftedBy(shift).copy(
                    lastModified = now.toString(),
                    sequence = current.sequence + 1,
                )
                else -> current
            }
        }
        persist(appState.replacingCalendar(calendarIndex) { it.copy(events = updated) })
        return WriteResult.Success
    }

    private fun saveThisOccurrence(
        storedEvents: List<Event>,
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate?,
        now: kotlin.time.Instant,
        calendarIndex: Int,
    ): WriteResult {
        val occurrence = occurrenceDate ?: edited.day
        val seriesUid = series.uid
        // The full slot, not just its date, so two exceptions on one day stay distinguishable.
        val slot = series.recurrenceSlotOn(occurrence)
        val existingOverride = storedEvents.firstOrNull {
            it.icsUid == seriesUid && it.recurrenceId == slot
        }
        // The editor starts on the occurrence date, so a different date is an intentional move.
        val moved = edited.day != occurrence
        val shiftToSlot = if (moved) 0 else edited.wallDay.daysTo(series.wallDateFor(occurrence))
        val placed = edited.movedByWallDays(shiftToSlot)
        val override = placed.copy(
            id = existingOverride?.id ?: idProvider(),
            // RECURRENCE-ID stays on the original slot; DTSTART may move to another date.
            recurrence = null,
            // The exception shares the master's UID, which is what RFC 5545 requires.
            icsUid = seriesUid,
            recurrenceId = slot,
            recurrenceRange = null,
            created = existingOverride?.created ?: now.toString(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = (existingOverride?.sequence ?: 0) + if (existingOverride == null) 0 else 1,
        )
        val updatedSeries = series.copy(
            recurrence = series.recurrence.withExclusion(slot),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = series.sequence + 1,
        )
        val events = storedEvents.map {
            when {
                it.id == series.id -> updatedSeries
                it.id == existingOverride?.id -> override
                else -> it
            }
        }.let { list ->
            if (existingOverride == null) list + override else list
        }
        persist(appState.replacingCalendar(calendarIndex) { it.copy(events = events) })
        return WriteResult.Success
    }

    private fun saveThisAndFuture(
        storedEvents: List<Event>,
        series: Event,
        edited: Event,
        occurrenceDate: LocalDate?,
        now: kotlin.time.Instant,
        calendarIndex: Int,
    ): WriteResult {
        val occurrence = occurrenceDate ?: edited.day
        if (occurrence <= series.day) {
            return saveWholeSeries(storedEvents, series, edited, occurrence, now, calendarIndex)
        }
        // RDATEs and EXDATEs belong to whichever segment covers their slot. A value kept on the
        // wrong side either duplicates the other segment or becomes a phantom occurrence on export.
        val oldRecurrence = series.recurrence?.boundedBefore(occurrence)?.first
        val oldSeries = series.copy(
            recurrence = oldRecurrence,
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = series.sequence + 1,
        )
        val futureRecurrence = when {
            edited.recurrence == null -> null
            sameRepeatRule(edited.repeat, series.repeat) ->
                // The old series already consumed part of the rule; a new bound would be wrong.
                edited.recurrence.copy(
                    rule = edited.recurrence.rule?.copy(until = null, count = null, raw = null),
                    rdates = edited.recurrence.rdates.filter { it.value.wallDate >= occurrence },
                    exdates = edited.recurrence.exdates.filter { it.wallDate >= occurrence },
                )
            else -> edited.recurrence
        }
        val moved = edited.day != occurrence
        val slotWall = series.wallDateFor(occurrence)
        val shiftToSlot = if (moved) 0 else edited.wallDay.daysTo(slotWall)
        val newId = idProvider()
        val newSeries = edited.movedByWallDays(shiftToSlot).copy(
            id = newId,
            recurrence = futureRecurrence,
            // A genuine new UID: this is a separate series rather than an exception, never a
            // synthetic id glued onto the old one.
            icsUid = null,
            recurrenceId = null,
            recurrenceRange = null,
            created = now.toString(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            sequence = 0,
        )
        val newUid = newSeries.uid
        val events = storedEvents.mapNotNull { current ->
            val isException = current.icsUid == series.uid && current.recurrenceId != null
            when {
                current.id == series.id -> oldSeries
                isException && current.recurrenceId.wallDate >= occurrence ->
                    if (futureRecurrence == null) {
                        null
                    } else {
                        current.copy(
                            icsUid = newUid,
                            lastModified = now.toString(),
                        )
                    }
                else -> current
            }
        } + newSeries
        persist(appState.replacingCalendar(calendarIndex) { it.copy(events = events) })
        return WriteResult.Success
    }

    /** Removes an event by id, without touching the rest of a series. */
    fun deleteEvent(id: String?) {
        if (id == null) return
        val index = calendarIndexContaining { calendar -> calendar.events.any { it.id == id } } ?: return
        persist(appState.replacingCalendar(index) { calendar ->
            calendar.copy(events = calendar.events.filterNot { it.id == id })
        })
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
        val storedCalendarIndex = calendarIndexContaining { calendar ->
            calendar.events.any { it.id == event.id }
        } ?: localCalendarIndex
        val storedEvents = appState.calendars[storedCalendarIndex].events
        val stored = storedEvents.firstOrNull { it.id == event.id } ?: event
        // An exception's slot is its RECURRENCE-ID even when the record was moved to another day;
        // for a series the opened date has to resolve to the start of the occurrence covering it,
        // so deleting a multi-day occurrence from its second day still hits the right slot.
        val occurrence = stored.recurrenceId?.wallDate
            ?: occurrenceDate?.let { stored.occurrenceStartOn(it) ?: it }
            ?: stored.day
        val storedSlot = stored.recurrenceId
        val occurrenceSlot = storedSlot ?: stored.recurrenceSlotOn(occurrence)

        // An exception is removed on its own; the master only gains its slot as an exclusion.
        if (storedSlot != null) {
            val master = storedEvents.firstOrNull {
                it.id != stored.id && it.icsUid == stored.icsUid && it.recurrenceId == null
            }
            val updated = storedEvents.filterNot { it.id == stored.id }.map { current ->
                if (master != null && current.id == master.id) {
                    current.copy(recurrence = current.recurrence.withExclusion(storedSlot))
                } else {
                    current
                }
            }
            persist(appState.replacingCalendar(storedCalendarIndex) { it.copy(events = updated) })
            return
        }

        // A one-off event is removed as is.
        if (stored.recurrence == null) {
            deleteEvent(stored.id)
            return
        }

        val seriesUid = stored.uid
        val belongsToSeries: (Event) -> Boolean = { current ->
            seriesUid != null && current.icsUid == seriesUid && current.recurrenceId != null
        }
        val updated = when (scope) {
            EditScope.ThisEvent -> storedEvents
                .filterNot { current -> belongsToSeries(current) && current.recurrenceId == occurrenceSlot }
                .map { current ->
                    if (current.id == stored.id) {
                        current.copy(recurrence = current.recurrence.withExclusion(occurrenceSlot))
                    } else {
                        current
                    }
                }
            EditScope.ThisAndFuture -> {
                if (occurrence <= stored.day) {
                    storedEvents.filterNot { it.id == stored.id || belongsToSeries(it) }
                } else {
                    storedEvents.mapNotNull { current ->
                        when {
                            current.id == stored.id -> current.copy(
                                recurrence = current.recurrence?.boundedBefore(occurrence)?.first,
                            )
                            belongsToSeries(current) && current.recurrenceId!!.wallDate >= occurrence -> null
                            else -> current
                        }
                    }
                }
            }
            EditScope.AllEvents -> storedEvents.filterNot { it.id == stored.id || belongsToSeries(it) }
        }
        persist(appState.replacingCalendar(storedCalendarIndex) { it.copy(events = updated) })
    }

    /** Merges events parsed from an ICS file through the import service. */
    fun importEvents(events: List<Event>): Int = importService.importEvents(events)

    /** Merges todos from an ICS file through the import service. */
    fun importTodos(todos: List<Todo>): Int = importService.importTodos(todos)

    /**
     * Merges everything one calendar message contained in a single write. [source] is the picked
     * file's name; records keep it, so a later import of the same source can tell "removed
     * upstream" apart from a partial export. iTIP replies and cancellations keep their semantics.
     */
    fun importCalendar(
        import: IcsImport,
        source: String? = null,
        calendarId: String? = null,
    ): ImportOutcome = importService.importCalendar(import, source, calendarId)

    /**
     * Clears a single day: one-off events are removed while repeating events only skip that date,
     * so clearing today never wipes a whole series.
     */
    fun deleteEventsOn(date: LocalDate) {
        val calendars = appState.calendars.map { calendar ->
            calendar.copy(
                events = calendar.events.mapNotNull { event ->
                    when {
                        !event.occursOn(date) -> event
                        event.recurrenceId != null -> null
                        event.recurrence == null -> null
                        else -> {
                            val occurrence = event.occurrenceStartOn(date) ?: date
                            event.copy(recurrence = event.recurrence.withExclusion(event.recurrenceSlotOn(occurrence)))
                        }
                    }
                },
            )
        }
        persist(appState.copy(calendars = calendars))
    }

    // ----- todos (delegated to TodoService) -----

    /** Active todos, ordered by projected due date, then priority, then creation time. */
    fun activeTodos(): List<Todo> = todoService.activeTodos()

    fun completedTodos(): List<Todo> = todoService.completedTodos()

    /**
     * The `RELATED-TO` sub-task forest whose roots are the active todos. Subtrees stay whole under
     * each root — a finished subtask keeps its place, struck through, next to its pending siblings.
     */
    fun activeTodoForest(): List<TodoNode> = todoService.activeTodoForest()

    /** The same forest rooted at the completed top-level todos. */
    fun completedTodoForest(): List<TodoNode> = todoService.completedTodoForest()

    fun addTodo(title: String, priority: TodoPriority, due: LocalDate?, parentUid: String? = null): WriteResult =
        todoService.addTodo(title, priority, due, parentUid)

    fun setTodoDone(id: String, done: Boolean) = todoService.setTodoDone(id, done)

    fun deleteTodo(id: String) = todoService.deleteTodo(id)

    // ----- journals (delegated to JournalService) -----

    /** Journals sorted newest first; undated ones keep their import order at the end. */
    fun journalsSorted(): List<Journal> = journalService.journalsSorted()

    fun addJournal(title: String, note: String, date: LocalDate?): WriteResult =
        journalService.addJournal(title, note, date)

    fun updateJournal(journal: Journal): WriteResult = journalService.updateJournal(journal)

    fun deleteJournal(id: String) = journalService.deleteJournal(id)

    // ----- subscriptions -----

    /**
     * Adds a subscription for [url] as its own calendar, so its records never mix with local ones.
     * Null when the URL has no scheme the app can fetch, so the caller can point at the field
     * instead of storing something that can never sync.
     */
    fun addSubscription(name: String, url: String): CalendarSubscription? {
        val resolved = resolveSubscriptionUrl(url) ?: return null
        val fallbackName = resolved.substringAfter("://").substringBefore('/')
            .ifBlank { resolved }.takeIf { it.isNotBlank() } ?: resolved
        val subscription = CalendarSubscription(
            id = idProvider(),
            name = name.trim().ifBlank { fallbackName },
            url = url.trim(),
        )
        persist(appState.copy(calendars = appState.calendars + subscription.toCalendar()))
        return subscription
    }

    /** Removes a subscription calendar and only the records it owned; local calendars stay intact. */
    fun removeSubscription(id: String) {
        persist(appState.copy(calendars = appState.calendars.filterNot { it.id == id && !it.isLocal }))
    }

    /** Subscriptions whose automatic refresh interval has elapsed at [now]. */
    fun subscriptionsDue(now: Instant = clock.now()): List<CalendarSubscription> =
        subscriptionSync.subscriptionsDue(now)

    /**
     * Syncs one subscription through the platform HTTP bridge. A sync that is still running is not
     * started again, and without a bridge the call does nothing.
     */
    fun syncSubscription(id: String) = subscriptionSync.syncSubscription(id)

    /** Syncs every subscription whose refresh interval has elapsed. */
    fun syncDueSubscriptions() = subscriptionSync.syncDueSubscriptions()

    /** Whether [id] currently has a sync running. */
    fun isSyncing(id: String): Boolean = subscriptionSync.isSyncing(id)

    // ----- statistics (delegated to StatsService) -----

    fun completedToday(): Int = statsService.completedToday()

    fun notify(message: AppMessage) {
        scope.launch { messageChannel.send(message) }
    }

    private fun validationError(event: Event): WriteResult = when {
        event.title.isBlank() -> WriteResult.EmptyTitle
        else -> WriteResult.InvalidTimeRange
    }

    /** Compares rules semantically: an editor round trip may reduce `UNTIL` to its calendar date. */
    private fun sameRepeatRule(a: RepeatRule?, b: RepeatRule?): Boolean {
        if (a == null || b == null) return a == b
        return a.copy(until = null, raw = null) == b.copy(until = null, raw = null) &&
            a.until?.wallDate == b.until?.wallDate
    }

    /**
     * Validates and normalises an edit. The time values already carry RFC semantics, so only the
     * fields that can be out of range (seconds, repeat counters) need coercing here.
     */
    private fun normalize(event: Event): Event? {
        if (event.title.isBlank()) return null
        val recurrence = event.recurrence?.let { set ->
            set.copy(
                rule = set.rule?.copy(
                    interval = set.rule.interval.coerceAtLeast(1),
                    count = set.rule.count?.takeIf { it > 0 },
                ),
                rdates = set.rdates.distinct(),
                exdates = set.exdates.distinct(),
            )
        }?.takeIf { it.rule != null || it.rdates.isNotEmpty() || it.rawRule != null }
        val start = normalizeTime(event.start) ?: return null
        val end = event.end?.let { normalizeTime(it) ?: return null }
        if (end != null && !end.isAfter(start)) return null
        val normalizedEnd = if (start is IcsTimeValue.Date) {
            (end as? IcsTimeValue.Date)?.takeIf { it.date > start.date }
                ?: IcsTimeValue.Date(start.date.plusDays(1))
        } else {
            // A date-only end on a timed event cannot be represented; reject rather than silently
            // change the event's length.
            if (end is IcsTimeValue.Date) return null
            end
        }
        return event.copy(
            title = event.title.trim(),
            start = start,
            end = normalizedEnd,
            recurrence = recurrence,
            // A one-off event must not inherit stale recurrence values.
            status = if (event.status == EventStatus.Cancelled) EventStatus.Confirmed else event.status,
        )
    }

    private fun normalizeTime(value: IcsTimeValue): IcsTimeValue? {
        fun clean(dateTime: LocalDateTime): LocalDateTime =
            LocalDateTime(dateTime.date, LocalTime(dateTime.hour, dateTime.minute, dateTime.second.coerceIn(0, 59)))
        return when (value) {
            is IcsTimeValue.Date -> value
            is IcsTimeValue.Floating -> IcsTimeValue.Floating(clean(value.dateTime))
            is IcsTimeValue.Zoned -> value.copy(dateTime = clean(value.dateTime))
            is IcsTimeValue.Utc -> IcsTimeValue.Utc(clean(value.instant.toLocalDateTime(TimeZone.UTC)).toInstant(TimeZone.UTC))
        }
    }

    private fun IcsTimeValue.isAfter(other: IcsTimeValue): Boolean {
        val start = other.instantOrNull(TimeZone.UTC)
        val end = instantOrNull(TimeZone.UTC)
        return if (start != null && end != null) end > start else wallDateTime > other.wallDateTime
    }

    private fun persist(next: AppState) {
        // The lookup cache is a derived index of the stored calendars: rebuilding it per write drops
        // the definitions of a deleted calendar and of an import preview that was never applied.
        replaceIcsTimeZones(next.calendars)
        appState = next
        storage.saveState(next)
        scheduleReminders()
    }

    /**
     * Keeps the platform notifications in line with the state: every persisted change (save,
     * delete, import, day rollover rebases) replaces the scheduled set. One job at a time — and
     * the mutex keeps that promise even though cancelling a job does not stop a reschedule that
     * is already running, so an older pass can never land after a newer one.
     */
    private fun scheduleReminders() {
        val scheduler = reminders ?: return
        reminderJob?.cancel()
        reminderJob = scope.launch {
            reminderMutex.withLock {
                runCatching { scheduler.reschedule(appState, currentStrings) }
            }
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

    /**
     * Bytes of [attachment] for previews and downloads in the UI. Reading a materialized payload is
     * a storage concern, so the UI asks the state instead of importing `com.crko.storage`.
     */
    fun attachmentBytes(attachment: Attachment): ByteArray? = attachmentRepository.readBytes(attachment)

    /** Payload of the stored attachment file [storedFile]; used when exporting a calendar. */
    fun readStoredAttachment(storedFile: String): ByteArray? = attachmentRepository.read(storedFile)
}

/** Moves an event (and its associated recurrence values) by whole calendar days. */
private fun Event.shiftedBy(days: Int): Event {
    if (days == 0) return this
    return movedByWallDays(days).copy(
        recurrence = recurrence?.shiftedByWallDays(days),
        recurrenceId = recurrenceId?.plusWallDays(days),
    )
}
