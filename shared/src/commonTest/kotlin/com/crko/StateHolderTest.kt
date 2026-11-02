package com.crko

import com.crko.domain.Alarm
import com.crko.domain.AlarmRelated
import com.crko.domain.AlarmTrigger
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Attachment
import com.crko.domain.Event
import com.crko.domain.legacyEvent
import com.crko.domain.IcsImport
import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.domain.Journal
import com.crko.domain.MaxEndMinutes
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceDate
import com.crko.domain.RecurrenceRange
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.absoluteTrigger
import com.crko.domain.attachmentMimeType
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.domain.relatedToEnd
import com.crko.domain.recurrenceSlotOn
import com.crko.domain.movedByWallDays
import com.crko.domain.triggerMinutes
import com.crko.domain.wallDate
import com.crko.domain.wallDateTime
import com.crko.domain.PersistedSettings
import com.crko.domain.TodoPriority
import com.crko.domain.effectiveUid
import com.crko.i18n.AppLanguage
import com.crko.i18n.EnStrings
import com.crko.i18n.resolveLanguage
import com.crko.i18n.systemLanguage
import com.crko.state.AppStateHolder
import com.crko.state.EditScope
import com.crko.state.ImportOutcome
import com.crko.state.WriteResult
import com.crko.storage.StateStorage
import com.crko.domain.daysTo
import com.crko.domain.decodedBytes
import com.crko.ui.common.importSummaryText
import com.crko.ui.schedule.endMinutesAfterStartChange
import com.crko.ui.schedule.updateReminderAlarms
import com.crko.ui.transfer.importSelection
import com.crko.ui.transfer.toIcsImport
import com.crko.ui.more.journalWithEdits
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.minutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

private class FakeClock(private val millis: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

private class MemoryStorage(
    var state: AppState = AppState(),
    var settings: PersistedSettings = PersistedSettings(),
) : StateStorage {
    var writes = 0

    override fun loadState() = state
    override fun saveState(state: AppState) {
        writes++
        this.state = state
    }

    override fun loadSettings() = settings
    override fun saveSettings(settings: PersistedSettings) {
        this.settings = settings
    }
}

private class Fixture(
    val storage: MemoryStorage = MemoryStorage(),
    locale: String = "en-US",
) {
    var ids = 0
    private val scope = TestScope(StandardTestDispatcher())
    // 2026-10-27T09:00:00Z == 17:00 in Singapore.
    val holder = AppStateHolder(
        scope = scope,
        initialSystemLocale = locale,
        clock = FakeClock(1_793_101_200_000),
        idProvider = { "id-${ids++}" },
        storage = storage,
    )
}

class StateHolderTest {

    @Test
    fun eventValidationRejectsBlankTitlesAndNegativeDurations() {
        val f = Fixture()
        val base = legacyEvent(null, "Meeting", "2026-10-27", 540, 600)
        assertEquals(WriteResult.EmptyTitle, f.holder.saveEvent(base.copy(title = "   ")))
        // A zero-length timed event is valid RFC 5545; only an end before the start is rejected.
        assertEquals(WriteResult.Success, f.holder.saveEvent(base.copy(end = null)))
        assertEquals(
            WriteResult.InvalidTimeRange,
            f.holder.saveEvent(base.copy(end = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 8, 20)))),
        )
        assertEquals("Meeting", f.holder.eventsOn(f.holder.today).single().title)
    }

    @Test
    fun savingAnExistingIdUpdatesInsteadOfDuplicating() {
        val f = Fixture()
        val event = legacyEvent("e1", "Draft", "2026-10-27", 60, 120)
        f.holder.saveEvent(event)
        f.holder.saveEvent(event.copy(title = "Final").withTimes(event.wallDay, 90, 150))
        val stored = f.holder.appState.events.single()
        assertEquals("Final", stored.title)
        assertEquals(90, stored.startMinutes)
        assertEquals(1, f.storage.state.events.size)
    }

    @Test
    fun deletingAnUnsavedEventIsANoOp() {
        val f = Fixture()
        f.holder.saveEvent(legacyEvent("e1", "Keep", "2026-10-27", 60, 120))
        f.holder.deleteEvent(null)
        assertEquals(1, f.holder.appState.events.size)
        f.holder.deleteEvent("e1")
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun endTimeIsClampedBeforeMidnight() {
        val f = Fixture()
        f.holder.saveEvent(legacyEvent("e1", "Late", "2026-10-27", 1400, 2000))
        // 24:00 is not representable as LocalTime; the editor would crash recomposing it.
        assertEquals(MaxEndMinutes, f.holder.appState.events.single().endMinutes)
    }

    @Test
    fun dailyRepeatOccursOnEveryDayFromTheStartDate() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("e1", "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        assertEquals(1, f.holder.eventsOn(start).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(1)).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(30)).size)
        assertTrue(f.holder.eventsOn(start.plusDays(-1)).isEmpty())
        // The stored series stays a single record.
        assertEquals(1, f.holder.appState.events.size)
    }

    @Test
    fun weeklyRepeatOccursOnTheAnchorWeekday() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("e1", "Review", start.toString(), 600, 660, repeat = RepeatRule(RepeatFrequency.Weekly)),
        )
        assertTrue(f.holder.eventsOn(start.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(start.plusDays(7)).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(21)).size)
    }

    @Test
    fun customRepeatUsesTheSelectedWeekdays() {
        val f = Fixture()
        val start = f.holder.today
        // Tuesday, repeated on Monday and Thursday.
        f.holder.saveEvent(
            legacyEvent(
                "e1",
                "Gym",
                start.toString(),
                420,
                480,
                repeat = RepeatRule(
                    RepeatFrequency.Weekly,
                    byDay = listOf(OrdinalWeekday(0, 0), OrdinalWeekday(0, 3)),
                ),
            ),
        )
        assertTrue(f.holder.eventsOn(start.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(start.plusDays(2)).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(6)).size)
    }

    @Test
    fun weeklyRepeatWithoutSelectedDaysUsesTheStartWeekday() {
        val f = Fixture()
        val result = f.holder.saveEvent(
            legacyEvent("e1", "Gym", "2026-10-27", 420, 480, repeat = RepeatRule(RepeatFrequency.Weekly)),
        )
        assertEquals(WriteResult.Success, result)
        assertEquals(1, f.holder.appState.events.size)
        assertTrue(f.holder.appState.events.single().occursOn(LocalDate(2026, 11, 3)))
    }

    @Test
    fun clearingADaySkipsOneOccurrenceAndKeepsTheSeries() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("e1", "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        f.holder.deleteEventsOn(start.plusDays(1))
        assertTrue(f.holder.eventsOn(start.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(start.plusDays(2)).size)
        assertEquals(1, f.holder.appState.events.size)
    }

    @Test
    fun clearingADayRemovesOneOffEvents() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(legacyEvent("e1", "Dentist", start.toString(), 540, 600))
        f.holder.deleteEventsOn(start)
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun activeTodosSortDueDatesFirstThenPriority() {
        val f = Fixture()
        f.holder.addTodo("No due low", TodoPriority.Low, null)
        f.holder.addTodo("Later", TodoPriority.High, f.holder.today.plusDays(2))
        f.holder.addTodo("Due high", TodoPriority.High, f.holder.today)
        f.holder.addTodo("Due normal", TodoPriority.Normal, f.holder.today)
        assertEquals(
            listOf("Due high", "Due normal", "Later", "No due low"),
            f.holder.activeTodos().map { it.title },
        )
    }

    @Test
    fun completingATodoMovesItOutOfTheActiveList() {
        val f = Fixture()
        f.holder.addTodo("Ship", TodoPriority.Normal, null)
        val id = f.holder.activeTodos().single().id
        f.holder.setTodoDone(id, true)
        assertTrue(f.holder.activeTodos().isEmpty())
        val completed = f.holder.completedTodos().single()
        assertEquals("Ship", completed.title)
        assertEquals(TodoStatus.Completed, completed.status)
        assertEquals(100, completed.percentComplete)
        assertTrue(completed.completed != null)
        f.holder.setTodoDone(id, false)
        val reopened = f.holder.activeTodos().single()
        assertEquals(TodoStatus.NeedsAction, reopened.status)
        assertEquals(0, reopened.percentComplete)
        assertNull(reopened.completed)
    }

    @Test
    fun activeTodosOrderNormalBeforeLowWithTheSameDueDate() {
        val f = Fixture()
        f.holder.addTodo("Low", TodoPriority.Low, f.holder.today)
        f.holder.addTodo("Normal", TodoPriority.Normal, f.holder.today)
        assertEquals(listOf("Normal", "Low"), f.holder.activeTodos().map { it.title })
    }

    @Test
    fun subtasksNestUnderTheirParentInTheForest() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parent.effectiveUid)
        f.holder.addTodo("Top-level", TodoPriority.Normal, null)

        val roots = f.holder.activeTodoForest()
        // The subtask is nested, not a second root, and a done parent moves its subtree along.
        assertEquals(listOf("Parent", "Top-level"), roots.map { it.todo.title })
        val parent1 = roots.first()
        assertEquals("Child", parent1.children.single().todo.title)
        assertEquals(1, parent1.subtaskCount)
    }

    @Test
    fun aCompletedSubtaskStaysNestedWithItsProgressCounted() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parent.effectiveUid)
        val child = f.holder.activeTodos().first { it.title == "Child" }
        f.holder.setTodoDone(child.id, true)

        val root = f.holder.activeTodoForest().single()
        assertEquals(1, root.subtaskCount)
        assertEquals(1, root.doneSubtaskCount)
        assertTrue(root.children.single().todo.done)
    }

    @Test
    fun deletingATodoLeavesItsSubtasksAtTheTopLevel() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parent.effectiveUid)
        f.holder.deleteTodo(parent.id)

        val forest = f.holder.activeTodoForest()
        assertEquals(listOf("Child"), forest.map { it.todo.title })
    }

    @Test
    fun journalsAreAddedUpdatedAndDeleted() {
        val f = Fixture()
        assertEquals(WriteResult.EmptyTitle, f.holder.addJournal("  ", "  ", null))
        f.holder.addJournal("Trip", "Notes from the road", f.holder.today)
        f.holder.addJournal("", "Quick thought", null)

        val sorted = f.holder.journalsSorted()
        // The blank/blank add was rejected; a dated journal sorts before the undated one.
        assertEquals(listOf("Trip", ""), sorted.map { it.title })
        assertEquals(f.holder.today.toString(), sorted.first().date)

        val updated = sorted.first().copy(title = "Trip 2026", note = "Revised")
        assertEquals(WriteResult.Success, f.holder.updateJournal(updated))
        val stored = f.holder.journalsSorted().first()
        assertEquals("Trip 2026", stored.title)
        assertEquals(1, stored.sequence)

        f.holder.deleteJournal(stored.id)
        assertEquals(1, f.holder.journalsSorted().size)
        // Updating or deleting an unknown id is a safe no-op.
        assertEquals(WriteResult.Success, f.holder.updateJournal(stored))
        f.holder.deleteJournal("missing")
        assertEquals(1, f.holder.journalsSorted().size)
    }

    @Test
    fun editingAJournalKeepsItsInstantOnlyWhileTheDateStays() {
        val imported = Journal(
            id = "j1",
            title = "Trip",
            note = "Notes",
            start = IcsTimeValue.Utc(Instant.parse("2026-10-27T09:30:00Z")),
        )
        // An untouched date keeps the imported instant.
        val kept = journalWithEdits(imported, "Trip 2026", "Revised", LocalDate.parse("2026-10-27"))
        assertEquals(IcsTimeValue.Utc(Instant.parse("2026-10-27T09:30:00Z")), kept.start)
        // A changed or cleared date drops the instant instead of exporting it over the picked date.
        val moved = journalWithEdits(imported, "Trip 2026", "Revised", LocalDate.parse("2026-11-01"))
        assertEquals(IcsTimeValue.Date(LocalDate.parse("2026-11-01")), moved.start)
        assertEquals("2026-11-01", moved.date)
        assertNull(journalWithEdits(imported, "Trip 2026", "Revised", null).start)
        // The title is trimmed; a journal without an instant is untouched by the rule.
        assertEquals("Trip 2026", kept.title)
        assertEquals("T", journalWithEdits(imported.copy(start = null), "  T  ", "n", null).title)
    }

    @Test
    fun editingAJournalWithoutTouchingItsShownDateKeepsTheInstant() {
        // The editor is seeded with the journal's displayed date and hands back exactly what it showed,
        // so "the date did not move" has to be decided in that same space. Compared against the value's
        // own wall date, a journal whose instant falls on another day in the display zone lost its time
        // and its zone on every save.
        val imported = Journal(
            id = "j2",
            title = "Late note",
            note = "Written before midnight",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 23, 30), "America/New_York"),
        )
        val shown = imported.date?.let { LocalDate.parse(it) }
        val edited = journalWithEdits(imported, "Late note", "Revised", shown)
        assertEquals(imported.start, edited.start)
        assertEquals(imported.date, edited.date)
        assertEquals("Revised", edited.note)
    }

    @Test
    fun settingsSurviveReload() {
        val storage = MemoryStorage()
        Fixture(storage).let {
            it.holder.setLanguage(AppLanguage.Chinese)
            it.holder.setDarkMode(com.crko.state.DarkModeSetting.Dark)
            it.holder.setPalette(com.crko.state.PaletteSetting.Monochrome)
            it.holder.setKeyColor(0xFF00FF00)
        }
        val reloaded = Fixture(storage)
        assertEquals(AppLanguage.Chinese, reloaded.holder.languageSetting)
        assertEquals(com.crko.state.DarkModeSetting.Dark, reloaded.holder.darkModeSetting)
        assertEquals(com.crko.state.PaletteSetting.Monochrome, reloaded.holder.paletteSetting)
        assertEquals(0xFF00FF00, reloaded.holder.settings.keyColor)
    }

    @Test
    fun systemLanguageFallsBackToEnglishForOtherLocales() {
        assertEquals(AppLanguage.Chinese, systemLanguage("zh-Hans-CN"))
        assertEquals(AppLanguage.Chinese, systemLanguage("ZH-TW"))
        assertEquals(AppLanguage.English, systemLanguage("ja-JP"))
        assertEquals(AppLanguage.English, systemLanguage("fr-FR"))
        assertEquals(AppLanguage.Chinese, resolveLanguage(AppLanguage.System, "zh-CN"))
        assertEquals(AppLanguage.English, resolveLanguage(AppLanguage.English, "zh-CN"))
    }

    @Test
    fun defaultScrollHourIsInitializedOnce() {
        val storage = MemoryStorage()
        val holder = Fixture(storage).holder
        assertEquals(8, holder.settings.agendaScrollHour)
        holder.setDefaultHour(6)
        assertEquals(6, Fixture(storage).holder.settings.agendaScrollHour)
    }

    @Test
    fun importingEventsAssignsIdsAndSkipsKnownUids() {
        val f = Fixture()
        val imported = legacyEvent(null, "Imported", "2026-10-27", 540, 600, icsUid = "ics-1")
        assertEquals(1, f.holder.importEvents(listOf(imported)))
        assertEquals(0, f.holder.importEvents(listOf(imported)))
        val stored = f.holder.appState.events.single()
        assertEquals("id-0", stored.id)
        assertEquals("ics-1", stored.icsUid)
    }

    @Test
    fun reimportingAnExportOfNativeEventsDoesNotDuplicateThem() {
        val f = Fixture()
        f.holder.saveEvent(legacyEvent("native-1", "Native", "2026-10-27", 540, 600))
        val exported = legacyEvent(null, "Native", "2026-10-27", 540, 600, icsUid = "native-1@crko")
        assertEquals(0, f.holder.importEvents(listOf(exported)))
        assertEquals(1, f.holder.appState.events.size)
    }

    private fun Fixture.dailySeries(id: String = "series", count: Int? = null): Event {
        val start = holder.today
        holder.saveEvent(
            legacyEvent(id, "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily, count = count)),
        )
        return holder.appState.events.first { it.id == id }
    }

    @Test
    fun savingOneOccurrenceCreatesAnOverrideAndSkipsTheOriginalSlot() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(1)
        val result = f.holder.saveEvent(
            series.copy(title = "Moved").withTimes(occurrence, 660, 720),
            occurrenceDate = occurrence,
            scope = EditScope.ThisEvent,
        )
        assertEquals(WriteResult.Success, result)
        val events = f.holder.appState.events
        assertEquals(2, events.size)
        val updatedSeries = events.first { it.id == series.id }
        assertTrue(occurrence.toString() in updatedSeries.excludedDates)
        val override = f.holder.eventsOn(occurrence).single()
        assertEquals("Moved", override.title)
        assertEquals(660, override.startMinutes)
        assertEquals(series.uid, override.icsUid)
        assertEquals(series.recurrenceSlotOn(occurrence), override.recurrenceId)
        assertEquals("Standup", f.holder.eventsOn(occurrence.plusDays(1)).single().title)
    }

    @Test
    fun savingThisAndFutureSplitsTheSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        val split = f.holder.today.plusDays(2)
        f.holder.saveEvent(
            series.copy(title = "New standup").withTimes(split, 600, 615),
            occurrenceDate = split,
            scope = EditScope.ThisAndFuture,
        )
        val events = f.holder.appState.events
        assertEquals(2, events.size)
        val oldSeries = events.first { it.id == series.id }
        assertEquals(IcsTimeValue.Date(split.plusDays(-1)), oldSeries.repeat?.until)
        assertEquals("Standup", f.holder.eventsOn(split.plusDays(-1)).single().title)
        val newSeries = events.first { it.id != series.id }
        assertEquals(split.toString(), newSeries.date)
        assertEquals("New standup", f.holder.eventsOn(split).single().title)
        assertEquals("New standup", f.holder.eventsOn(split.plusDays(1)).single().title)
    }

    @Test
    fun savingThisAndFuturePartitionsRDatesAcrossTheSplit() {
        val f = Fixture()
        val split = f.holder.today.plusDays(2)
        val before = split.plusDays(-1)
        val after = split.plusDays(10)
        f.holder.saveEvent(
            Event(
                "series",
                "Standup",
                IcsTimeValue.Floating(LocalDateTime(f.holder.today, LocalTime(9, 0))),
                IcsTimeValue.Floating(LocalDateTime(f.holder.today, LocalTime(9, 15))),
                recurrence = Recurrence(
                    rule = RepeatRule(RepeatFrequency.Daily),
                    rdates = listOf(
                        RecurrenceDate(IcsTimeValue.Date(before)),
                        RecurrenceDate(IcsTimeValue.Date(after)),
                    ),
                ),
            ),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "New standup").movedTo(split),
            occurrenceDate = split,
            scope = EditScope.ThisAndFuture,
        )
        val events = f.holder.appState.events
        val oldSeries = events.first { it.id == series.id }
        val newSeries = events.first { it.id != series.id }
        // Each extra date belongs to the segment that covers it; keeping both on one side would
        // duplicate the occurrence or bring it back as a phantom on export.
        assertEquals(
            listOf(IcsTimeValue.Date(before)),
            oldSeries.recurrence?.rdates?.map { it.value },
        )
        assertEquals(
            listOf(IcsTimeValue.Date(after)),
            newSeries.recurrence?.rdates?.map { it.value },
        )
        assertEquals("Standup", f.holder.eventsOn(split.plusDays(-1)).single().title)
        assertEquals("New standup", f.holder.eventsOn(split.plusDays(10)).single().title)
    }

    @Test
    fun deletingOneOccurrenceKeepsTheSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        f.holder.deleteEvent(series, occurrenceDate = f.holder.today.plusDays(1), scope = EditScope.ThisEvent)
        assertEquals(1, f.holder.appState.events.size)
        assertTrue(f.holder.eventsOn(f.holder.today.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(f.holder.today.plusDays(2)).size)
    }

    @Test
    fun deletingThisAndFutureCutsTheSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        val cut = f.holder.today.plusDays(2)
        f.holder.deleteEvent(series, occurrenceDate = cut, scope = EditScope.ThisAndFuture)
        assertEquals(1, f.holder.appState.events.size)
        val stored = f.holder.appState.events.single()
        assertEquals(IcsTimeValue.Date(cut.plusDays(-1)), stored.repeat?.until)
        assertEquals(1, f.holder.eventsOn(cut.plusDays(-1)).size)
        assertTrue(f.holder.eventsOn(cut).isEmpty())
    }

    @Test
    fun deletingTheWholeSeriesAlsoRemovesItsOverrides() {
        val f = Fixture()
        val series = f.dailySeries()
        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(f.holder.today.plusDays(1)),
            occurrenceDate = f.holder.today.plusDays(1),
            scope = EditScope.ThisEvent,
        )
        assertEquals(2, f.holder.appState.events.size)
        f.holder.deleteEvent(series, scope = EditScope.AllEvents)
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun allDayEventKeepsAnInclusiveEndDate() {
        val f = Fixture()
        val start = f.holder.today
        val result = f.holder.saveEvent(
            legacyEvent(
                id = "trip",
                title = "Trip",
                date = start.toString(),
                startMinutes = 0,
                endMinutes = 0,
                endDate = start.plusDays(2).toString(),
                allDay = true,
            ),
        )
        assertEquals(WriteResult.Success, result)
        val stored = f.holder.appState.events.single()
        assertTrue(stored.allDay)
        assertEquals(0, stored.startMinutes)
        assertEquals(start.plusDays(2).toString(), stored.endDate)
        assertTrue(stored.occursOn(start))
        assertTrue(stored.occursOn(start.plusDays(2)))
        assertFalse(stored.occursOn(start.plusDays(3)))
    }

    @Test
    fun eventMayEndExactlyAtMidnight() {
        val f = Fixture()
        val start = f.holder.today
        val result = f.holder.saveEvent(
            legacyEvent("late", "Late", start.toString(), 23 * 60, 0, endDate = start.plusDays(1).toString()),
        )
        assertEquals(WriteResult.Success, result)
        val stored = f.holder.appState.events.single()
        assertEquals(0, stored.endMinutes)
        assertEquals(start.plusDays(1).toString(), stored.endDate)
        assertTrue(stored.occursOn(start))
        assertFalse(stored.occursOn(start.plusDays(1)))
    }

    @Test
    fun importedTodosGetIdsAndAreDeduplicatedByUid() {
        val f = Fixture()
        val todo = Todo("", "Imported", icsUid = "todo-uid")
        assertEquals(ImportOutcome(todos = 1), f.holder.importCalendar(IcsImport(todos = listOf(todo))))
        assertEquals(ImportOutcome(), f.holder.importCalendar(IcsImport(todos = listOf(todo))))
        val stored = f.holder.appState.todos.single()
        assertTrue(stored.id.isNotBlank())
        assertEquals("todo-uid", stored.icsUid)
    }

    @Test
    fun zonedEventKeepsItsRfcValueAndProjectsForDisplay() {
        val event = Event(
            id = "zoned",
            title = "Zoned",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 7, 27, 9, 0), "Europe/Berlin"),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 7, 27, 10, 0), "Europe/Berlin"),
        )
        // The persisted value is the one the calendar file describes, not a device-zone projection.
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 7, 27, 9, 0), "Europe/Berlin"), event.start)
        // Display projection moves with the viewer's zone (Berlin summer time is UTC+2).
        assertEquals(LocalDateTime(2026, 7, 27, 7, 0), event.localStart(TimeZone.UTC))
        assertEquals(LocalDateTime(2026, 7, 27, 8, 0), event.localEnd(TimeZone.UTC))
    }

    @Test
    fun movingASeriesKeepsItsZoneAndWallClock() {
        val series = Event(
            id = "series",
            title = "Series",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 0, 30), "Asia/Shanghai"),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 1, 0), "Asia/Shanghai"),
            recurrence = Recurrence(rule = RepeatRule(RepeatFrequency.Daily)),
        )
        val moved = series.movedByWallDays(2)
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 29, 0, 30), "Asia/Shanghai"), moved.start)
        assertEquals("Asia/Shanghai", moved.timeZoneId)
    }

    @Test
    fun editingARecurringOccurrenceForTheWholeSeriesKeepsTheAnchor() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(3)
        f.holder.saveEvent(
            series.copy(title = "Renamed").withTimes(occurrence, 600, 615),
            occurrenceDate = occurrence,
            scope = EditScope.AllEvents,
        )
        val stored = f.holder.appState.events.single()
        assertEquals(f.holder.today.toString(), stored.date)
        assertEquals("Renamed", stored.title)
        assertEquals(600, stored.startMinutes)
        assertTrue(stored.occursOn(f.holder.today.plusDays(10)))
    }

    @Test
    fun clearingRepeatOnTheWholeSeriesRemovesExceptions() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(1)
        f.holder.saveEvent(series.copy(title = "Moved").movedTo(occurrence), occurrence, EditScope.ThisEvent)
        assertEquals(2, f.holder.appState.events.size)
        f.holder.saveEvent(series.copy(recurrence = null).movedTo(occurrence), occurrence, EditScope.AllEvents)
        val stored = f.holder.appState.events.single()
        assertNull(stored.repeat)
        assertTrue(stored.excludedDates.isEmpty())
        assertTrue(f.holder.eventsOn(f.holder.today).isNotEmpty())
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun thisAndFutureKeepsTheRuleButDropsItsOldBound() {
        val f = Fixture()
        val series = f.dailySeries(count = 5)
        val split = f.holder.today.plusDays(2)
        f.holder.saveEvent(series.copy(title = "New").movedTo(split), split, EditScope.ThisAndFuture)
        val newSeries = f.holder.appState.events.first { it.id != series.id }
        assertNull(newSeries.repeat?.count)
        assertNull(newSeries.repeat?.until)
        assertEquals(split.toString(), newSeries.date)
        assertTrue(newSeries.occursOn(split.plusDays(30)))
    }

    @Test
    fun movingOneOccurrenceMovesItsStartDate() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(legacyEvent("week", "Weekly", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Weekly)))
        val series = f.holder.appState.events.single()
        val occurrence = start.plusDays(7)
        val moved = start.plusDays(2)
        f.holder.saveEvent(series.copy(title = "Moved").movedTo(moved), occurrence, EditScope.ThisEvent)
        val override = f.holder.eventsOn(moved).single()
        assertEquals("Moved", override.title)
        assertEquals(series.recurrenceSlotOn(occurrence), override.recurrenceId)
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun movingOneOccurrenceToTheSeriesAnchorIsApplied() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("week", "Weekly", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Weekly)),
        )
        val series = f.holder.appState.events.single()
        val occurrence = start.plusDays(7)
        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(start),
            occurrence,
            EditScope.ThisEvent,
        )

        val override = f.holder.appState.events.first { it.title == "Moved" }
        assertEquals(start.toString(), override.date)
        assertEquals(series.recurrenceSlotOn(occurrence), override.recurrenceId)
        assertTrue(f.holder.eventsOn(start).any { it.title == "Moved" })
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun changingTheSeriesDateShiftsExceptionsAndExclusions() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("daily", "Daily", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(start.plusDays(2)),
            start.plusDays(1),
            EditScope.ThisEvent,
        )

        f.holder.saveEvent(
            series.copy(title = "Shifted").movedTo(start.plusDays(1)),
            start,
            EditScope.AllEvents,
        )

        val shiftedSeries = f.holder.appState.events.first { it.title == "Shifted" }
        val shiftedException = f.holder.appState.events.first { it.title == "Moved" }
        assertEquals(start.plusDays(1).toString(), shiftedSeries.date)
        assertEquals(listOf(start.plusDays(2).toString()), shiftedSeries.excludedDates)
        assertEquals(start.plusDays(3).toString(), shiftedException.date)
        assertEquals(start.plusDays(2), shiftedException.recurrenceId?.wallDate)
    }

    @Test
    fun movingTheWholeSeriesFromALaterOccurrenceUsesTheDateDelta() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent("daily", "Daily", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        val series = f.holder.appState.events.single()

        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(start),
            start.plusDays(3),
            EditScope.AllEvents,
        )

        val stored = f.holder.appState.events.single()
        assertEquals(start.plusDays(-3).toString(), stored.date)
        assertEquals(1, f.holder.eventsOn(start).size)
    }

    @Test
    fun changingTheSeriesDateShiftsAnUnchangedUntil() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent(
                "bounded",
                "Bounded",
                start.toString(),
                540,
                555,
                repeat = RepeatRule(RepeatFrequency.Daily, until = IcsTimeValue.Date(start.plusDays(5))),
            ),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "Shifted").movedTo(start.plusDays(1)),
            start,
            EditScope.AllEvents,
        )
        val stored = f.holder.appState.events.single()
        assertEquals(start.plusDays(1).toString(), stored.date)
        assertEquals(IcsTimeValue.Date(start.plusDays(6)), stored.repeat?.until)
    }

    @Test
    fun changingTheSeriesDateShiftsImportedFutureSegments() {
        val f = Fixture()
        val today = f.holder.today
        f.holder.importEvents(
            listOf(
                Event(
                    id = null,
                    title = "Base",
                    start = IcsTimeValue.Floating(LocalDateTime(today, LocalTime(9, 0))),
                    end = IcsTimeValue.Floating(LocalDateTime(today, LocalTime(10, 0))),
                    recurrence = Recurrence(rule = RepeatRule(RepeatFrequency.Daily)),
                    icsUid = "base-uid",
                ),
                Event(
                    id = null,
                    title = "Future",
                    start = IcsTimeValue.Floating(LocalDateTime(today.plusDays(2), LocalTime(9, 0))),
                    end = IcsTimeValue.Floating(LocalDateTime(today.plusDays(2), LocalTime(10, 0))),
                    recurrence = Recurrence(rule = RepeatRule(RepeatFrequency.Daily, count = 5)),
                    icsUid = "base-uid",
                    recurrenceId = IcsTimeValue.Floating(LocalDateTime(today.plusDays(2), LocalTime(9, 0))),
                    recurrenceRange = RecurrenceRange.ThisAndFuture,
                ),
            ),
        )
        val base = f.holder.appState.events.first { it.icsUid == "base-uid" }
        val futureUid = f.holder.appState.events.first { it.title == "Future" }.uid

        f.holder.saveEvent(
            base.copy(title = "Shifted").movedTo(today.plusDays(1)),
            today,
            EditScope.AllEvents,
        )

        val shifted = f.holder.appState.events.first { it.title == "Future" }
        assertEquals(today.plusDays(3).toString(), shifted.date)
        assertEquals(today.plusDays(3), shifted.recurrenceId?.wallDate)
        assertEquals(futureUid, shifted.uid)
        assertEquals("base-uid", shifted.icsUid)
    }

    @Test
    fun editingMetadataKeepsTheImportedSourceButChangingTimeDropsIt() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.importEvents(
            listOf(
                legacyEvent(
                    id = null,
                    title = "Zoned",
                    date = start.toString(),
                    startMinutes = 9 * 60,
                    endMinutes = 10 * 60,
                    sourceStart = "20261027T090000",
                    sourceEnd = "20261027T100000",
                    timeZoneId = "Europe/Berlin",
                ),
            ),
        )
        val stored = f.holder.appState.events.single()
        f.holder.saveEvent(stored.copy(title = "Renamed"))
        val renamed = f.holder.appState.events.single()
        assertEquals("Europe/Berlin", renamed.timeZoneId)
        assertEquals("20261027T090000", renamed.sourceStart)

        f.holder.saveEvent(
            renamed.copy(
                start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 11, 0), "Europe/Berlin"),
                end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 12, 0), "Europe/Berlin"),
            ),
        )
        val retimed = f.holder.appState.events.single()
        // Editing the wall clock must keep the imported TZID so the next export stays zoned.
        assertEquals("Europe/Berlin", retimed.timeZoneId)
        assertEquals("20261027T110000", retimed.sourceStart)
    }

    @Test
    fun editingTheMiddleDayOfARepeatingMultiDayEventKeepsOneOccurrence() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            legacyEvent(
                "trip",
                "Trip",
                start.toString(),
                0,
                0,
                endDate = start.plusDays(2).toString(),
                allDay = true,
                repeat = RepeatRule(RepeatFrequency.Weekly),
            ),
        )
        val series = f.holder.appState.events.single()
        val middle = start.plusDays(1)
        val occurrence = series.occurrenceStartOn(middle)!!
        assertEquals(start, occurrence)
        f.holder.saveEvent(
            series.copy(title = "Edited").withTimes(
                start,
                series.startMinutes,
                series.endMinutes,
                endDate = start.plusDays(2),
            ),
            occurrence,
            EditScope.ThisEvent,
        )
        assertEquals(1, f.holder.eventsOn(middle).size)
        assertEquals("Edited", f.holder.eventsOn(middle).single().title)
        assertEquals(series.recurrenceSlotOn(occurrence), f.holder.eventsOn(middle).single().recurrenceId)
    }

    @Test
    fun completedTodayUsesTheCompletionDate() {
        val f = Fixture()
        f.holder.addTodo("Due tomorrow", TodoPriority.Normal, f.holder.today.plusDays(1))
        f.holder.setTodoDone(f.holder.appState.todos.single().id, true)
        assertEquals(1, f.holder.completedToday())
    }

    @Test
    fun importingAFileWritesEventsAndTodosTogether() {
        val f = Fixture()
        val import = IcsImport(
            events = listOf(legacyEvent(null, "Imported", "2026-10-27", 540, 600, icsUid = "file-1")),
            todos = listOf(Todo(id = "", title = "File todo", icsUid = "file-2")),
        )
        val writesBefore = f.storage.writes
        assertEquals(ImportOutcome(events = 1, todos = 1), f.holder.importCalendar(import))
        // Two writes meant a calendar left half-imported when the process died in between.
        assertEquals(1, f.storage.writes - writesBefore)
        assertEquals(1, f.holder.appState.events.size)
        assertEquals(1, f.holder.appState.todos.size)
        val afterFirst = f.storage.writes
        assertEquals(ImportOutcome(), f.holder.importCalendar(IcsImport(events = import.events, todos = import.todos)))
        // Nothing was new, so nothing was serialised either.
        assertEquals(afterFirst, f.storage.writes)
    }

    @Test
    fun reimportingTheSameFileStillCollapsesOwnExportsAndDuplicates() {
        val f = Fixture()
        val first = legacyEvent(null, "Meeting", "2026-10-27", 540, 600, icsUid = "uid-a")
        assertEquals(1, f.holder.importEvents(listOf(first, first.copy())))
        assertEquals(1, f.holder.appState.events.size)
        // An event created here is exported as "<id>@crko"; re-importing must not duplicate it.
        val stored = f.holder.appState.events.single()
        assertEquals(0, f.holder.importEvents(listOf(stored.copy(id = null, icsUid = "${stored.id}@crko"))))
        assertEquals(1, f.holder.appState.events.size)
    }

    @Test
    fun importingTwoChangesOnTheSameOccurrenceDateKeepsBoth() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:twice\r\nSUMMARY:Series\r\nDTSTART:20261027T090000\r\n" +
            "DTEND:20261027T100000\r\nRRULE:FREQ=DAILY;COUNT=5\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:twice\r\nRECURRENCE-ID:20261029T090000\r\nDTSTART:20261029T110000\r\n" +
            "DTEND:20261029T120000\r\nSUMMARY:First change\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:twice\r\nRECURRENCE-ID:20261029T130000\r\nDTSTART:20261029T150000\r\n" +
            "DTEND:20261029T160000\r\nSUMMARY:Second change\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val parsed = importCalendar(text, TimeZone.UTC, "Untitled")
        val f = Fixture()
        val (events, _) = f.holder.importCalendar(parsed)
        // Both changes share one occurrence date, so identical storage keys dropped the second one.
        assertEquals(3, events)
        assertEquals(
            listOf("First change", "Second change"),
            f.holder.appState.events.filter { it.recurrenceId != null }.map { it.title },
        )
    }

    @Test
    fun reimportingANewerCopyUpdatesTheStoredRecord() {
        val f = Fixture()
        val first = legacyEvent(
            null,
            "Old",
            "2026-10-27",
            540,
            600,
            icsUid = "ext-1",
            sequence = 1,
            dtStamp = "2026-10-01T00:00:00Z",
        )
        assertEquals(1, f.holder.importEvents(listOf(first)))
        val storedId = f.holder.appState.events.single().id

        val newer = first.copy(id = null, title = "New", sequence = 2, dtStamp = "2026-10-02T00:00:00Z")
        assertEquals(1, f.holder.importEvents(listOf(newer)))
        val stored = f.holder.appState.events.single()
        assertEquals("New", stored.title)
        assertEquals(storedId, stored.id)

        val stale = first.copy(id = null, title = "Stale", sequence = 0, dtStamp = "2026-09-01T00:00:00Z")
        assertEquals(0, f.holder.importEvents(listOf(stale)))
        assertEquals("New", f.holder.appState.events.single().title)
    }

    @Test
    fun reimportingACancelledCopyRemovesTheLocalSeries() {
        val f = Fixture()
        val series = legacyEvent(null, "Cancelled", "2026-10-27", 540, 600, icsUid = "cancel-uid")
        val exception = legacyEvent(
            null,
            "Moved",
            "2026-10-28",
            600,
            660,
            icsUid = "cancel-uid",
        ).copy(recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 28)))
        assertEquals(2, f.holder.importEvents(listOf(series, exception)))
        f.holder.importCalendar(IcsImport(cancelledUids = setOf("cancel-uid")))
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun theImportFeedbackCountsJournalsAndRemovals() {
        val f = Fixture()
        val journal = Journal(id = "", title = "Note", icsUid = "j-1")
        assertEquals(ImportOutcome(journals = 1), f.holder.importCalendar(IcsImport(journals = listOf(journal))))
        assertEquals(1, f.holder.importEvents(listOf(legacyEvent(null, "Gone", "2026-10-27", 540, 600, icsUid = "gone"))))
        assertEquals(ImportOutcome(removed = 1), f.holder.importCalendar(IcsImport(cancelledUids = setOf("gone"))))
    }

    @Test
    fun reimportingTheSameSourceRemovesRecordsTheFileNoLongerLists() {
        val f = Fixture()
        val kept = legacyEvent(null, "Kept", "2026-10-27", 540, 600, icsUid = "kept-1")
        val gone = legacyEvent(null, "Gone", "2026-10-28", 540, 600, icsUid = "gone-1")
        assertEquals(ImportOutcome(events = 2), f.holder.importCalendar(IcsImport(events = listOf(kept, gone)), "plan.ics"))
        // The file still carries one of the two records, so the other one was removed upstream.
        assertEquals(
            ImportOutcome(removed = 1),
            f.holder.importCalendar(IcsImport(events = listOf(kept)), "plan.ics"),
        )
        assertEquals(listOf("Kept"), f.holder.appState.events.map { it.title })
    }

    @Test
    fun recordsFromOtherSourcesSurviveAReimport() {
        val f = Fixture()
        f.holder.importEvents(listOf(legacyEvent(null, "Mine", "2026-10-27", 540, 600, icsUid = "mine-1")))
        f.holder.importCalendar(
            IcsImport(events = listOf(legacyEvent(null, "Other", "2026-10-28", 540, 600, icsUid = "other-1"))),
            "other.ics",
        )
        // An empty file of a source says nothing about records the source does not own.
        assertEquals(ImportOutcome(), f.holder.importCalendar(IcsImport(), "other.ics"))
        assertEquals(2, f.holder.appState.events.size)
    }

    @Test
    fun locallyCreatedRecordsSurviveSourceReconciliation() {
        val f = Fixture()
        assertEquals(1, f.holder.importEvents(listOf(legacyEvent(null, "Mine", "2026-10-27", 540, 600))))
        val first = legacyEvent(null, "Imported", "2026-10-28", 540, 600, icsUid = "imp-1")
        f.holder.importCalendar(IcsImport(events = listOf(first)), "plan.ics")
        val second = legacyEvent(null, "Imported", "2026-10-29", 540, 600, icsUid = "imp-2")
        assertEquals(
            ImportOutcome(events = 1, removed = 1),
            f.holder.importCalendar(IcsImport(events = listOf(second)), "plan.ics"),
        )
        assertEquals(listOf("Imported", "Mine"), f.holder.appState.events.map { it.title }.sorted())
    }

    @Test
    fun uncheckingAPreviewRowDoesNotRemoveTheRecordTheFileStillLists() {
        val f = Fixture()
        val first = legacyEvent(null, "First", "2026-10-27", 540, 600, icsUid = "e-1")
        val second = legacyEvent(null, "Second", "2026-10-28", 540, 600, icsUid = "e-2")
        assertEquals(
            ImportOutcome(events = 2),
            f.holder.importCalendar(IcsImport(events = listOf(first, second)), "plan.ics"),
        )
        // The re-imported file still describes both records, but the user unchecked the first one:
        // the update is skipped, not turned into an upstream removal.
        val import = IcsImport(
            events = listOf(second.copy(sequence = 1, dtStamp = "2026-10-30T00:00:00Z")),
            describedUids = setOf("e-1", "e-2"),
        )
        assertEquals(ImportOutcome(events = 1), f.holder.importCalendar(import, "plan.ics"))
        assertEquals(listOf("First", "Second"), f.holder.appState.events.map { it.title }.sorted())
    }

    @Test
    fun thePreviewSelectionSkipsAnUpdateWithoutDeletingTheRecord() {
        val f = Fixture()
        val file = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:e-1\r\nDTSTAMP:20261001T000000Z\r\n" +
            "DTSTART:20261027T090000\r\nDTEND:20261027T100000\r\nSUMMARY:First\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:e-2\r\nDTSTAMP:20261001T000000Z\r\n" +
            "DTSTART:20261028T090000\r\nDTEND:20261028T100000\r\nSUMMARY:Second\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val parsed = importCalendar(file, TimeZone.UTC, "Untitled")
        val selection = importSelection(parsed)
        assertEquals(2, selection.rows.size)
        assertEquals(
            ImportOutcome(events = 2),
            f.holder.importCalendar(selection.toIcsImport(listOf(true, true)), "plan.ics"),
        )
        // The same file arrives again and the user unchecks the first row: only the second record
        // is updated, and the unchecked one stays exactly as it was.
        val newer = importSelection(parsed.copy(events = parsed.events.map { it.copy(sequence = 3) }))
        assertEquals(
            ImportOutcome(events = 1),
            f.holder.importCalendar(newer.toIcsImport(listOf(false, true)), "plan.ics"),
        )
        assertEquals(listOf("First", "Second"), f.holder.appState.events.map { it.title }.sorted())
        assertEquals(3, f.holder.appState.events.single { it.title == "Second" }.sequence)
        assertEquals(0, f.holder.appState.events.single { it.title == "First" }.sequence)
    }

    @Test
    fun decliningACancellationDoesNotRemoveTheSeriesThroughTheVanishedPass() {
        val f = Fixture()
        val cancelled = legacyEvent(null, "Cancelled", "2026-10-27", 540, 600, icsUid = "c-1")
        val live = legacyEvent(null, "Live", "2026-10-28", 540, 600, icsUid = "c-2")
        f.holder.importCalendar(IcsImport(events = listOf(cancelled, live)), "plan.ics")
        // The new file cancels one series and keeps the other; the user declines the removal row,
        // so the declined series must survive the reconciliation of the same import as well.
        assertEquals(
            ImportOutcome(),
            f.holder.importCalendar(
                IcsImport(events = listOf(live), describedUids = setOf("c-1", "c-2")),
                "plan.ics",
            ),
        )
        assertEquals(listOf("Cancelled", "Live"), f.holder.appState.events.map { it.title }.sorted())
    }

    @Test
    fun aRenamedSourceTakesOverProvenanceEvenWhenNothingElseChanges() {
        val f = Fixture()
        val original = legacyEvent(
            null,
            "Same",
            "2026-10-27",
            540,
            600,
            icsUid = "p-1",
            dtStamp = "2026-10-01T00:00:00Z",
        )
        f.holder.importCalendar(IcsImport(events = listOf(original)), "plan.ics")
        // The renamed file carries the same stale copy: nothing is imported, but provenance has to
        // follow it anyway, or a later reconciliation against the new name would see no owner.
        assertEquals(
            ImportOutcome(),
            f.holder.importCalendar(
                IcsImport(events = listOf(original.copy(id = null))),
                "plan-renamed.ics",
            ),
        )
        assertEquals("plan-renamed.ics", f.storage.state.events.single().importSource)
    }

    @Test
    fun embeddedAttachmentsAreStoredAsFilesAndReEncodedOnExport() {
        val f = Fixture()
        val attachment = Attachment(
            value = "VGVzdA==",
            parameters = linkedMapOf(
                "FMTTYPE" to "text/plain",
                "ENCODING" to "BASE64",
                "FILENAME" to "notes.txt",
            ),
            binary = true,
        )
        val event = legacyEvent(null, "With file", "2026-10-27", 540, 600, icsUid = "att-1", attachments = listOf(attachment))
        f.holder.importCalendar(IcsImport(events = listOf(event)), "plan.ics")
        val stored = f.holder.appState.events.single().attachments.single()
        assertEquals("", stored.data)
        assertTrue(!stored.storedFile.isNullOrBlank(), stored.toString())
        // The payload no longer lives inside the serialized state, or imports would accumulate it.
        val serialized = AppJson.encodeToString(AppState.serializer(), f.storage.state)
        assertFalse(serialized.contains("VGVzdA=="), serialized)
        // Export re-encodes the payload from the stored file, parameters included.
        val exported = exportIcs(listOf(f.holder.appState.events.single()))
        assertTrue(
            exported.contains("ATTACH;FMTTYPE=text/plain;ENCODING=BASE64;FILENAME=notes.txt:VGVzdA=="),
            exported,
        )
    }

    @Test
    fun attachmentMimeTypesAreDerivedFromFmttypeOrFileName() {
        assertEquals("image/png", attachmentMimeType(Attachment(parameters = mapOf("FMTTYPE" to "image/png"))))
        assertEquals("image/png", attachmentMimeType(Attachment(parameters = mapOf("FILENAME" to "picture.png"))))
        assertEquals("text/plain", attachmentMimeType(Attachment(parameters = mapOf("FILENAME" to "readme.txt"), binary = true)))
        // Without any hint the payload is an opaque binary download.
        assertEquals("application/octet-stream", attachmentMimeType(Attachment(binary = true)))
    }

    @Test
    fun reimportingAnOwnExportExceptionDoesNotReplaceItsMaster() {
        val f = Fixture()
        val master = legacyEvent(
            null,
            "Base",
            "2026-10-27",
            540,
            600,
            icsUid = "native-1@crko",
        )
        val exception = legacyEvent(
            null,
            "Moved",
            "2026-10-28",
            600,
            660,
            icsUid = "native-1@crko",
        ).copy(recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 28)))
        assertEquals(2, f.holder.importEvents(listOf(master, exception)))
        val updated = exception.copy(
            id = null,
            title = "Moved again",
            sequence = 2,
            dtStamp = "2026-10-02T00:00:00Z",
        )
        assertEquals(1, f.holder.importEvents(listOf(updated)))
        val events = f.holder.appState.events
        assertEquals(2, events.size)
        assertEquals("Base", events.first { it.recurrenceId == null }.title)
        assertEquals("Moved again", events.first { it.recurrenceId != null }.title)
    }

    @Test
    fun changingTheReminderKeepsOtherImportedAlarms() {
        val alarms = listOf(
            Alarm(trigger = AlarmTrigger.Relative((-15).minutes)),
            Alarm(action = "EMAIL", trigger = AlarmTrigger.Relative((-30).minutes), description = "mail"),
            Alarm(
                trigger = AlarmTrigger.Absolute(
                    IcsTimeValue.Utc(Instant.parse("2026-10-27T09:00:00Z")),
                ),
            ),
        )
        val updated = updateReminderAlarms(alarms, original = 15, reminder = 60)
        assertEquals(3, updated.size)
        assertEquals(60, updated[0].triggerMinutes)
        assertEquals("EMAIL", updated[1].action)
        assertEquals(
            IcsTimeValue.Utc(Instant.parse("2026-10-27T09:00:00Z")),
            updated[2].absoluteTrigger,
        )

        val removed = updateReminderAlarms(alarms, original = 15, reminder = null)
        assertEquals(2, removed.size)
        assertTrue(removed.none { it.absoluteTrigger == null && it.triggerMinutes == 15 })
    }

    @Test
    fun editingTheReminderKeepsTheEndRelatedBase() {
        val endBased = listOf(
            Alarm(trigger = AlarmTrigger.Relative((-30).minutes, AlarmRelated.End)),
        )
        // Changing only the minutes leaves the `RELATED=END` base alone.
        val kept = updateReminderAlarms(endBased, original = 30, reminder = 45, originalAtEnd = true, atEnd = true)
        assertTrue(kept.single().relatedToEnd)
        assertEquals(45, kept.single().triggerMinutes)
        // Switching the base is an explicit rewrite.
        val rebased = updateReminderAlarms(endBased, original = 30, reminder = 30, originalAtEnd = true, atEnd = false)
        assertFalse(rebased.single().relatedToEnd)
        // Same minutes and same base: the list is returned untouched.
        assertSame(endBased, updateReminderAlarms(endBased, original = 30, reminder = 30, originalAtEnd = true, atEnd = true))
        // A newly added reminder adopts the selected base.
        val added = updateReminderAlarms(emptyList(), original = null, reminder = 15, originalAtEnd = false, atEnd = true)
        assertTrue(added.single().relatedToEnd)
    }

    @Test
    fun movingAZeroDurationStartKeepsTheZeroLength() {
        // The end of a zero-length event follows the new start instead of stretching to an hour.
        assertEquals(
            660,
            endMinutesAfterStartChange(zeroDuration = true, sameDay = true, pickedMinutes = 660, endMinutes = 600),
        )
        // A start pushed past the end on the same day drags the end one hour ahead.
        assertEquals(
            750,
            endMinutesAfterStartChange(zeroDuration = false, sameDay = true, pickedMinutes = 690, endMinutes = 660),
        )
        assertEquals(
            MaxEndMinutes,
            endMinutesAfterStartChange(zeroDuration = false, sameDay = true, pickedMinutes = 1439, endMinutes = 600),
        )
        // A start inside the existing range, or a multi-day event, keeps the end.
        assertNull(
            endMinutesAfterStartChange(zeroDuration = false, sameDay = true, pickedMinutes = 600, endMinutes = 660),
        )
        assertNull(
            endMinutesAfterStartChange(zeroDuration = false, sameDay = false, pickedMinutes = 700, endMinutes = 660),
        )
    }

    @Test
    fun reimportingAFileKeepsAnExceptionTheUserCreatedLocally() {
        val f = Fixture()
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:series-1\r\nSUMMARY:Standup\r\n" +
            "DTSTART:20261027T090000\r\nDTEND:20261027T093000\r\n" +
            "RRULE:FREQ=DAILY;COUNT=5\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:series-1\r\nRECURRENCE-ID:20261029T090000\r\n" +
            "DTSTART:20261029T110000\r\nDTEND:20261029T113000\r\n" +
            "SUMMARY:Standup moved\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val imported = com.crko.domain.importCalendar(text, TimeZone.UTC, "Untitled")
        f.holder.importCalendar(imported)
        assertEquals(2, f.holder.appState.events.size)

        // The user moves the 10-30 occurrence: that exception exists only in local storage.
        val series = f.holder.appState.events.first { it.recurrenceId == null }
        f.holder.saveEvent(
            series.copy(id = series.id, title = "Standup local"),
            occurrenceDate = LocalDate(2026, 10, 30),
            scope = EditScope.ThisEvent,
        )
        assertEquals(3, f.holder.appState.events.size)

        // Importing the same file again must not silently drop the local exception: a record that is
        // simply absent from the file is not evidence that it was deleted there.
        f.holder.importCalendar(imported)
        val titles = f.holder.appState.events.map { it.title }
        assertTrue(titles.contains("Standup local"), titles.toString())
        assertEquals(3, f.holder.appState.events.size)
    }

    @Test
    fun theImportMessageNamesOnlyTheFirstFewBrokenRecords() {
        val details = (1..300).map { index ->
            IcsWarningDetail(IcsWarning.MissingStart, line = index, record = "Broken $index")
        }
        val text = importSummaryText(
            strings = EnStrings,
            events = 1,
            todos = 0,
            journals = 0,
            removed = 0,
            warnings = listOf(IcsWarning.MissingStart),
            details = details,
        )
        assertTrue(text.contains("(297 more)"), text)
        assertTrue(text.length < 1_000, "message is ${text.length} characters")
        assertEquals(3, Regex("line \\d").findAll(text).count(), text)
    }

    @Test
    fun embeddedAttachmentsTolerateFoldedAndUnpaddedBase64() {
        assertEquals("Test", Attachment(binary = true, value = "VGVzdA==").decodedBytes()?.decodeToString())
        assertEquals("Test", Attachment(binary = true, value = "VGVzdA").decodedBytes()?.decodeToString())
        assertEquals("Test", Attachment(binary = true, value = "VGVz\r\n dA=").decodedBytes()?.decodeToString())
        assertNull(Attachment(binary = true, value = "!!!").decodedBytes())
    }
}

/** Moves a floating fixture to [date], keeping its wall clock and length. */
private fun Event.movedTo(date: LocalDate): Event {
    val delta = start.wallDateTime.date.daysTo(date)
    return movedByWallDays(delta)
}

/** Floating fixture at [date] with new wall-clock minutes; end equal to start is zero length. */
private fun Event.withTimes(
    date: LocalDate,
    startMinutes: Int,
    endMinutes: Int,
    endDate: LocalDate = date,
): Event = copy(
    start = IcsTimeValue.Floating(LocalDateTime(date, LocalTime(startMinutes / 60, startMinutes % 60))),
    end = if (endDate == date && endMinutes == startMinutes) {
        null
    } else {
        IcsTimeValue.Floating(LocalDateTime(endDate, LocalTime(endMinutes / 60, endMinutes % 60)))
    },
)
