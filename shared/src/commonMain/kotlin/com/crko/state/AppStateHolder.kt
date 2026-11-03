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
import com.crko.domain.IcsImport
import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.domain.IcsPartStat
import com.crko.domain.Journal
import com.crko.domain.PersistedSettings
import com.crko.domain.SubscriptionFailure
import com.crko.domain.Todo
import com.crko.domain.TodoNode
import com.crko.domain.TodoPriority
import com.crko.domain.exportCalendar
import com.crko.domain.occurringOn
import com.crko.domain.icsTimeZoneConflicts
import com.crko.domain.toCalendar
import com.crko.domain.resolveSubscriptionUrl
import com.crko.domain.targetCalendar
import com.crko.domain.today
import com.crko.i18n.AppLanguage
import com.crko.i18n.Strings
import com.crko.storage.AttachmentRepository
import com.crko.storage.AppStorage
import com.crko.storage.CalendarReadException
import com.crko.storage.StateStorage
import com.crko.storage.SubscriptionHttp
import com.crko.storage.importCalendarChunks
import com.crko.storage.newId
import com.crko.storage.systemLocaleTag
import com.crko.state.`import`.AttachmentMaterializer
import com.crko.state.`import`.ImportService
import com.crko.state.calendar.CalendarDeleteResult
import com.crko.state.calendar.CalendarService
import com.crko.state.event.EventDeletion
import com.crko.state.event.EventService
import com.crko.state.journal.JournalService
import com.crko.state.persistence.StateRepository
import com.crko.state.search.SearchFilters
import com.crko.state.search.SearchHit
import com.crko.state.search.SearchService
import com.crko.state.settings.SettingsController
import com.crko.state.stats.StatsService
import com.crko.state.subscription.SubscriptionSyncService
import com.crko.state.todo.TodoDraft
import com.crko.state.todo.TodoService
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Instant

enum class DarkModeSetting { System, Light, Dark }

enum class EditScope { ThisEvent, ThisAndFuture, AllEvents }

/** User-visible notifications; localized at display time so they follow language switches. */
sealed interface AppMessage {
    data object Saved : AppMessage
    /** A delete happened; [undoable] is true when its records wait in the in-memory undo snapshot. */
    data class Deleted(val undoable: Boolean) : AppMessage

    /** The most recent delete was put back into its calendar. */
    data object DeleteUndone : AppMessage
    /** Undo found nothing to restore, or a record with one of the ids exists again. */
    data object DeleteUndoFailed : AppMessage
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

/** Outcome of reading a picked calendar file; the UI maps [Failed] onto a message. */
sealed interface CalendarFileParse {
    data class Parsed(val import: IcsImport) : CalendarFileParse
    data class Failed(val failure: IcsImportFailure) : CalendarFileParse
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
     * Dispatcher that serializes disk writes. Injectable so tests can keep the writes on their
     * scheduler; the default keeps JSON encoding off the caller (UI) thread.
     */
    private val persistenceDispatcher: CoroutineDispatcher = Dispatchers.Default,
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
    private val repository = StateRepository(
        scope = scope,
        storage = storage,
        ioDispatcher = persistenceDispatcher,
        onStateChanged = { scheduleReminders() },
    )

    /** Restored state, kept in Compose state by the repository and written back off the UI thread. */
    val appState: AppState get() = repository.state

    var today: LocalDate by mutableStateOf(today(clock))
        private set

    private val settingsController = SettingsController(repository, initialSystemLocale)

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
        override val defaultCalendarId: String? get() = settingsController.settings.defaultCalendarId
        override fun setDefaultCalendarId(id: String?) = settingsController.setDefaultCalendarId(id)
    }

    private val calendarService = CalendarService(access = stateAccess, idProvider = idProvider)

    /**
     * Snapshot of the most recent delete. Deliberately not part of [AppState]: the undo stack is not
     * persisted, so it is empty again after a process restart.
     */
    private val deleteUndo = DeleteUndoStore()

    private val importService = ImportService(
        access = stateAccess,
        attachments = attachmentMaterializer,
        clock = clock,
        idProvider = idProvider,
    )

    private val eventService = EventService(
        access = stateAccess,
        clock = clock,
        idProvider = idProvider,
        ensureReminderPermission = ::ensureReminderPermission,
    )

    private val eventDeletion = EventDeletion(access = stateAccess, undo = deleteUndo)

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
        undo = deleteUndo,
    )

    private val journalService = JournalService(
        access = stateAccess,
        clock = clock,
        idProvider = idProvider,
        undo = deleteUndo,
    )

    private val statsService = StatsService(access = stateAccess, today = { today })

    private val searchService = SearchService(access = stateAccess)

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
        // A payload that could only be read partially used to look like an empty calendar with no
        // explanation; the damaged copy is kept by the storage, so say what happened.
        val damage = repository.loadDamage
        if (!damage.isEmpty) notify(AppMessage.StorageRecovered(damage.droppedRecords))
        scheduleReminders()
    }

    fun refreshToday() {
        // Event times are stored in their own RFC time value, so a device zone change only changes
        // the display projection; the persisted records no longer need a rewrite.
        today = today(clock)
    }

    // ----- settings (delegated to SettingsController) -----

    fun setLanguage(language: AppLanguage) = settingsController.setLanguage(language)

    fun setDarkMode(mode: DarkModeSetting) = settingsController.setDarkMode(mode)

    fun setPalette(palette: PaletteSetting) = settingsController.setPalette(palette)

    fun setKeyColor(argb: Long) = settingsController.setKeyColor(argb)

    fun setDefaultHour(hour: Int) = settingsController.setDefaultHour(hour)

    fun setAgendaView(view: String) = settingsController.setAgendaView(view)

    // ----- calendars (delegated to CalendarService) -----

    /** Calendars the user manages locally; subscriptions have their own section. */
    val localCalendars: List<Calendar> get() = appState.calendars.filter { it.isLocal }

    /**
     * Calendar new local records and imported files go into. Null only when the payload has no
     * calendar at all, which storage never produces.
     */
    val newRecordCalendarId: String? get() = appState.targetCalendar(settings.defaultCalendarId)?.id

    /** Explicitly selected default, or null while the first local calendar is implied. */
    val defaultCalendarId: String? get() = settings.defaultCalendarId

    fun createLocalCalendar(name: String, color: Long?): Calendar? =
        calendarService.createLocalCalendar(name, color)

    fun renameCalendar(id: String, name: String): Boolean = calendarService.renameCalendar(id, name)

    fun deleteCalendar(id: String): CalendarDeleteResult = calendarService.deleteCalendar(id)

    fun setCalendarVisible(id: String, visible: Boolean): Boolean =
        calendarService.setCalendarVisible(id, visible)

    fun setCalendarColor(id: String, color: Long?): Boolean = calendarService.setCalendarColor(id, color)

    fun setDefaultCalendar(id: String): Boolean = calendarService.setDefaultCalendar(id)

    // ----- events (delegated to EventService) -----

    /** Events that occur on a date, including occurrences of repeating events, ordered by start time. */
    fun eventsOn(date: LocalDate): List<Event> = appState.visibleEvents.occurringOn(date)

    /**
     * Saves [event]. For a repeating event, [scope] decides whether the change applies to this
     * occurrence, this and all later occurrences, or the whole series. [calendarId] picks the
     * calendar of a new record.
     */
    fun saveEvent(
        event: Event,
        occurrenceDate: LocalDate? = null,
        scope: EditScope = EditScope.AllEvents,
        calendarId: String? = null,
    ): WriteResult = eventService.saveEvent(event, occurrenceDate, scope, calendarId)

    /** Removes an event by id, without touching the rest of a series. */
    fun deleteEvent(id: String?) = reportDelete(eventDeletion.deleteEvent(id))

    /**
     * Deletes [event] using [scope]. For a repeating series this can skip one occurrence, cut the
     * series at an occurrence, or remove the series together with all of its exceptions.
     */
    fun deleteEvent(
        event: Event,
        occurrenceDate: LocalDate? = null,
        scope: EditScope = EditScope.AllEvents,
    ) = reportDelete(eventDeletion.deleteEvent(event, occurrenceDate, scope))

    /**
     * Clears a single day: one-off events are removed while repeating events only skip that date,
     * so clearing today never wipes a whole series. Clearing a day keeps no undo target.
     */
    fun deleteEventsOn(date: LocalDate) = eventDeletion.deleteEventsOn(date)

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
     * Reads a calendar file picked by the host. Decoding and unfolding run on the parse dispatcher,
     * so the UI never has to choose a background thread for them.
     */
    suspend fun parseCalendarFile(chunks: List<ByteArray>): CalendarFileParse =
        withContext(parseDispatcher ?: persistenceDispatcher) {
            runCatching {
                importCalendarChunks(
                    chunks,
                    TimeZone.currentSystemDefault(),
                    currentStrings.untitledEvent,
                )
            }.fold(
                onSuccess = { CalendarFileParse.Parsed(it) },
                onFailure = { error ->
                    CalendarFileParse.Failed(
                        (error as? CalendarReadException)?.failure ?: IcsImportFailure.InvalidFormat,
                    )
                },
            )
        }

    /**
     * Applies a parsed file off the caller thread: materializing attachments and merging records is
     * file and CPU work, so the UI only awaits the outcome.
     */
    suspend fun applyCalendarImport(import: IcsImport, source: String? = null): ImportOutcome =
        withContext(persistenceDispatcher) { importCalendar(import, source) }

    /**
     * ICS text of one calendar, or of every calendar when [calendarId] is null. The all-calendar
     * export keeps its existing metadata and zone merge; a single calendar exports exactly its own
     * records and definitions.
     */
    suspend fun exportCalendarIcs(calendarId: String? = null): String {
        val current = appState
        val selected = calendarId?.let { id -> current.calendars.firstOrNull { it.id == id } }
        val metadata = current.calendar
        val events = selected?.events ?: current.events
        val todos = selected?.todos ?: current.todos
        val journals = selected?.journals ?: current.journals
        val calendarName = selected?.name ?: metadata.name ?: "Crko"
        val refreshMinutes = selected?.refreshMinutes ?: metadata.refreshMinutes
        val timeZones = selected?.timeZones ?: metadata.timeZones
        return withContext(persistenceDispatcher) {
            exportCalendar(
                events,
                todos,
                calendarName = calendarName,
                journals = journals,
                refreshMinutes = refreshMinutes,
                timeZones = timeZones,
                storedAttachment = ::readStoredAttachment,
            )
        }
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

    fun addTodo(
        title: String,
        priority: TodoPriority,
        due: LocalDate?,
        parentUid: String? = null,
        calendarId: String? = null,
    ): WriteResult = todoService.addTodo(title, priority, due, parentUid, calendarId)

    /** Adds a todo from the editor draft; [parentUid] keeps a subtask next to its parent. */
    fun addTodo(
        draft: TodoDraft,
        parentUid: String? = null,
        calendarId: String? = null,
    ): WriteResult = todoService.addTodo(draft, parentUid, calendarId)

    /** Applies the editor draft to an existing todo, keeping its `UID`, links and recurrence. */
    fun updateTodo(id: String, draft: TodoDraft): WriteResult = todoService.updateTodo(id, draft)

    fun setTodoDone(id: String, done: Boolean) = todoService.setTodoDone(id, done)

    /** Display date the todo lists show for [todo]: the next occurrence, skipping completed slots. */
    fun nextTodoOccurrence(todo: Todo): LocalDate? = todoService.nextOccurrence(todo)

    /**
     * Completes the occurrence of a repeating todo shown on [date]. The series keeps its rule and
     * projects its next occurrence; the completed slot becomes an instance of the same `UID`.
     */
    fun completeTodoOccurrence(id: String, date: LocalDate): WriteResult =
        todoService.completeOccurrence(id, date)

    /** Completes a whole series; no further occurrence is projected. */
    fun completeTodoSeries(id: String): WriteResult = todoService.completeSeries(id)

    /** Clears the recurrence rule of [id] while keeping the record itself. */
    fun stopTodoRecurrence(id: String): WriteResult = todoService.stopRecurrence(id)

    fun deleteTodo(id: String) = reportDelete(todoService.deleteTodo(id))

    // ----- journals (delegated to JournalService) -----

    /** Journals sorted newest first; undated ones keep their import order at the end. */
    fun journalsSorted(): List<Journal> = journalService.journalsSorted()

    fun addJournal(title: String, note: String, date: LocalDate?, calendarId: String? = null): WriteResult =
        journalService.addJournal(title, note, date, calendarId)

    fun updateJournal(journal: Journal): WriteResult = journalService.updateJournal(journal)

    fun deleteJournal(id: String) = reportDelete(journalService.deleteJournal(id))

    // ----- search (delegated to SearchService) -----

    /**
     * Global search over the visible calendars. The full scan happens here, in the state layer, so
     * a screen only renders what comes back. A blank [query] returns nothing.
     */
    fun search(query: String?, filters: SearchFilters = SearchFilters()): List<SearchHit> =
        searchService.search(query, filters)

    /** Categories the search filter can offer, derived from the visible records. */
    fun searchCategories(limit: Int = 20): List<String> = searchService.categories(limit)

    // ----- undo -----

    /**
     * Puts the records removed by the most recent delete back into their original calendar and
     * positions. The write goes through the repository, so reminders are rescheduled for the
     * restored records. A record whose id exists again is never overwritten: the restore fails and
     * the snapshot stays available for another attempt. Nothing is restored when the snapshot's
     * calendar has been deleted since. The snapshot is memory only, so after a process restart
     * there is nothing to undo.
     */
    fun undoLastDelete(): UndoResult {
        val snapshot = deleteUndo.last ?: return UndoResult.NothingToUndo
        return when (val restored = snapshot.restore(appState)) {
            is DeleteRestore.Restored -> {
                deleteUndo.clear()
                persist(restored.state)
                UndoResult.Success
            }
            DeleteRestore.Conflict, DeleteRestore.CalendarMissing -> UndoResult.Unavailable
        }
    }

    /** Announces a delete; only removed records leave something for [undoLastDelete] to restore. */
    private fun reportDelete(outcome: DeleteOutcome) = when (outcome) {
        DeleteOutcome.Nothing -> Unit
        DeleteOutcome.Removed -> notify(AppMessage.Deleted(undoable = true))
        DeleteOutcome.Changed -> notify(AppMessage.Deleted(undoable = false))
    }

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

    private fun persist(next: AppState) = repository.persist(next)

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
