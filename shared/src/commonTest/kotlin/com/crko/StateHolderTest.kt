package com.crko

import com.crko.domain.Alarm
import com.crko.domain.AppState
import com.crko.domain.Attachment
import com.crko.domain.Event
import com.crko.domain.IcsImport
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.importCalendar
import com.crko.domain.normalizedForZone
import com.crko.domain.occurrenceStartOn
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.domain.PersistedSettings
import com.crko.domain.TodoPriority
import com.crko.i18n.AppLanguage
import com.crko.i18n.EnStrings
import com.crko.i18n.resolveLanguage
import com.crko.i18n.systemLanguage
import com.crko.state.AppStateHolder
import com.crko.state.EditScope
import com.crko.state.WriteResult
import com.crko.storage.StateStorage
import com.crko.ui.common.importSummaryText
import com.crko.ui.schedule.decodedBytes
import com.crko.ui.schedule.updateReminderAlarms
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        val base = Event(null, "Meeting", "2026-10-27", 540, 600)
        assertEquals(WriteResult.EmptyTitle, f.holder.saveEvent(base.copy(title = "   ")))
        // A zero-length timed event is valid RFC 5545; only an end before the start is rejected.
        assertEquals(WriteResult.Success, f.holder.saveEvent(base.copy(endMinutes = 540)))
        assertEquals(WriteResult.InvalidTimeRange, f.holder.saveEvent(base.copy(endMinutes = 500)))
        assertEquals("Meeting", f.holder.eventsOn(f.holder.today).single().title)
    }

    @Test
    fun savingAnExistingIdUpdatesInsteadOfDuplicating() {
        val f = Fixture()
        val event = Event("e1", "Draft", "2026-10-27", 60, 120)
        f.holder.saveEvent(event)
        f.holder.saveEvent(event.copy(title = "Final", startMinutes = 90, endMinutes = 150))
        val stored = f.holder.appState.events.single()
        assertEquals("Final", stored.title)
        assertEquals(90, stored.startMinutes)
        assertEquals(1, f.storage.state.events.size)
    }

    @Test
    fun deletingAnUnsavedEventIsANoOp() {
        val f = Fixture()
        f.holder.saveEvent(Event("e1", "Keep", "2026-10-27", 60, 120))
        f.holder.deleteEvent(null)
        assertEquals(1, f.holder.appState.events.size)
        f.holder.deleteEvent("e1")
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun endTimeIsClampedBeforeMidnight() {
        val f = Fixture()
        f.holder.saveEvent(Event("e1", "Late", "2026-10-27", 1400, 2000))
        // 24:00 is not representable as LocalTime; the editor would crash recomposing it.
        assertEquals(MaxEndMinutes, f.holder.appState.events.single().endMinutes)
    }

    @Test
    fun dailyRepeatOccursOnEveryDayFromTheStartDate() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            Event("e1", "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
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
            Event("e1", "Review", start.toString(), 600, 660, repeat = RepeatRule(RepeatFrequency.Weekly)),
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
            Event(
                "e1",
                "Gym",
                start.toString(),
                420,
                480,
                repeat = RepeatRule(RepeatFrequency.Custom, listOf(0, 3)),
            ),
        )
        assertTrue(f.holder.eventsOn(start.plusDays(1)).isEmpty())
        assertEquals(1, f.holder.eventsOn(start.plusDays(2)).size)
        assertEquals(1, f.holder.eventsOn(start.plusDays(6)).size)
    }

    @Test
    fun customRepeatRequiresAtLeastOneWeekday() {
        val f = Fixture()
        val result = f.holder.saveEvent(
            Event("e1", "Gym", "2026-10-27", 420, 480, repeat = RepeatRule(RepeatFrequency.Custom)),
        )
        assertEquals(WriteResult.InvalidRepeat, result)
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun clearingADaySkipsOneOccurrenceAndKeepsTheSeries() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            Event("e1", "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
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
        f.holder.saveEvent(Event("e1", "Dentist", start.toString(), 540, 600))
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
        assertTrue(completed.completedTime != null)
        f.holder.setTodoDone(id, false)
        val reopened = f.holder.activeTodos().single()
        assertEquals(TodoStatus.NeedsAction, reopened.status)
        assertEquals(0, reopened.percentComplete)
        assertNull(reopened.completedTime)
    }

    @Test
    fun activeTodosOrderNormalBeforeLowWithTheSameDueDate() {
        val f = Fixture()
        f.holder.addTodo("Low", TodoPriority.Low, f.holder.today)
        f.holder.addTodo("Normal", TodoPriority.Normal, f.holder.today)
        assertEquals(listOf("Normal", "Low"), f.holder.activeTodos().map { it.title })
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
        val imported = Event(null, "Imported", "2026-10-27", 540, 600, icsUid = "ics-1")
        assertEquals(1, f.holder.importEvents(listOf(imported)))
        assertEquals(0, f.holder.importEvents(listOf(imported)))
        val stored = f.holder.appState.events.single()
        assertEquals("id-0", stored.id)
        assertEquals("ics-1", stored.icsUid)
    }

    @Test
    fun reimportingAnExportOfNativeEventsDoesNotDuplicateThem() {
        val f = Fixture()
        f.holder.saveEvent(Event("native-1", "Native", "2026-10-27", 540, 600))
        val exported = Event(null, "Native", "2026-10-27", 540, 600, icsUid = "native-1@crko")
        assertEquals(0, f.holder.importEvents(listOf(exported)))
        assertEquals(1, f.holder.appState.events.size)
    }

    private fun Fixture.dailySeries(id: String = "series", count: Int? = null): Event {
        val start = holder.today
        holder.saveEvent(
            Event(id, "Standup", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily, count = count)),
        )
        return holder.appState.events.first { it.id == id }
    }

    @Test
    fun savingOneOccurrenceCreatesAnOverrideAndSkipsTheOriginalSlot() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(1)
        val result = f.holder.saveEvent(
            series.copy(title = "Moved", date = occurrence.toString(), startMinutes = 660, endMinutes = 720),
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
        assertEquals(series.uid, override.seriesUid)
        assertEquals(occurrence.toString(), override.recurrenceId)
        assertEquals("Standup", f.holder.eventsOn(occurrence.plusDays(1)).single().title)
    }

    @Test
    fun savingThisAndFutureSplitsTheSeries() {
        val f = Fixture()
        val series = f.dailySeries()
        val split = f.holder.today.plusDays(2)
        f.holder.saveEvent(
            series.copy(title = "New standup", date = split.toString(), startMinutes = 600, endMinutes = 615),
            occurrenceDate = split,
            scope = EditScope.ThisAndFuture,
        )
        val events = f.holder.appState.events
        assertEquals(2, events.size)
        val oldSeries = events.first { it.id == series.id }
        assertEquals(split.plusDays(-1).toString(), oldSeries.repeat?.until)
        assertEquals("Standup", f.holder.eventsOn(split.plusDays(-1)).single().title)
        val newSeries = events.first { it.id != series.id }
        assertEquals(split.toString(), newSeries.date)
        assertEquals("New standup", f.holder.eventsOn(split).single().title)
        assertEquals("New standup", f.holder.eventsOn(split.plusDays(1)).single().title)
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
        assertEquals(cut.plusDays(-1).toString(), stored.repeat?.until)
        assertEquals(1, f.holder.eventsOn(cut.plusDays(-1)).size)
        assertTrue(f.holder.eventsOn(cut).isEmpty())
    }

    @Test
    fun deletingTheWholeSeriesAlsoRemovesItsOverrides() {
        val f = Fixture()
        val series = f.dailySeries()
        f.holder.saveEvent(
            series.copy(title = "Moved", date = f.holder.today.plusDays(1).toString()),
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
            Event(
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
            Event("late", "Late", start.toString(), 23 * 60, 0, endDate = start.plusDays(1).toString()),
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
        assertEquals(0 to 1, f.holder.importCalendar(IcsImport(todos = listOf(todo))))
        assertEquals(0 to 0, f.holder.importCalendar(IcsImport(todos = listOf(todo))))
        val stored = f.holder.appState.todos.single()
        assertTrue(stored.id.isNotBlank())
        assertEquals("todo-uid", stored.icsUid)
    }

    @Test
    fun zonedSourceIsRebasedToTheCurrentDeviceZone() {
        val event = Event(
            id = "zoned",
            title = "Zoned",
            date = "2026-07-27",
            startMinutes = 9 * 60,
            endMinutes = 10 * 60,
            sourceStart = "20260727T090000",
            sourceEnd = "20260727T100000",
            timeZoneId = "Europe/Berlin",
        )
        val rebased = event.normalizedForZone(TimeZone.UTC)
        assertEquals(7 * 60, rebased.startMinutes)
        assertEquals(8 * 60, rebased.endMinutes)
        assertEquals("2026-07-27", rebased.date)
        assertEquals("Europe/Berlin", rebased.timeZoneId)
    }

    @Test
    fun zoneRebaseMovesExceptionRecurrenceIdWithItsSeries() {
        val series = Event(
            id = "series",
            title = "Series",
            date = "2026-10-27",
            startMinutes = 30,
            endMinutes = 60,
            sourceStart = "20261027T003000",
            sourceEnd = "20261027T010000",
            timeZoneId = "Asia/Shanghai",
            repeat = RepeatRule(RepeatFrequency.Daily),
            excludedDates = listOf("2026-10-28"),
        )
        val exception = Event(
            id = "override",
            title = "Moved",
            date = "2026-10-28",
            startMinutes = 30,
            endMinutes = 60,
            sourceStart = "20261028T003000",
            sourceEnd = "20261028T010000",
            timeZoneId = "Asia/Shanghai",
            seriesUid = series.uid,
            recurrenceId = "2026-10-28",
        )

        val rebased = listOf(series, exception).normalizedForZone(TimeZone.UTC)

        assertEquals("2026-10-26", rebased[0].date)
        assertEquals(listOf("2026-10-27"), rebased[0].excludedDates)
        assertEquals("2026-10-27", rebased[1].date)
        assertEquals("2026-10-27", rebased[1].recurrenceId)
    }

    @Test
    fun zoneRebaseMovesAppCreatedExceptionsWithItsSeries() {
        val series = Event(
            id = "series",
            title = "Series",
            date = "2026-10-27",
            startMinutes = 30,
            endMinutes = 60,
            sourceStart = "20261027T003000",
            sourceEnd = "20261027T010000",
            timeZoneId = "Asia/Shanghai",
            repeat = RepeatRule(RepeatFrequency.Daily),
            excludedDates = listOf("2026-10-28"),
        )
        val exception = Event(
            id = "override",
            title = "Moved",
            date = "2026-10-28",
            startMinutes = 600,
            endMinutes = 660,
            seriesUid = series.uid,
            recurrenceId = "2026-10-28",
        )

        val rebased = listOf(series, exception).normalizedForZone(TimeZone.UTC)

        assertEquals("2026-10-26", rebased[0].date)
        assertEquals(listOf("2026-10-27"), rebased[0].excludedDates)
        assertEquals("2026-10-27", rebased[1].date)
        assertEquals("2026-10-27", rebased[1].recurrenceId)
        assertEquals(600, rebased[1].startMinutes)
    }

    @Test
    fun editingARecurringOccurrenceForTheWholeSeriesKeepsTheAnchor() {
        val f = Fixture()
        val series = f.dailySeries()
        val occurrence = f.holder.today.plusDays(3)
        f.holder.saveEvent(
            series.copy(title = "Renamed", date = occurrence.toString(), startMinutes = 600, endMinutes = 615),
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
        f.holder.saveEvent(series.copy(title = "Moved", date = occurrence.toString()), occurrence, EditScope.ThisEvent)
        assertEquals(2, f.holder.appState.events.size)
        f.holder.saveEvent(series.copy(repeat = null, date = occurrence.toString()), occurrence, EditScope.AllEvents)
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
        f.holder.saveEvent(series.copy(title = "New", date = split.toString()), split, EditScope.ThisAndFuture)
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
        f.holder.saveEvent(Event("week", "Weekly", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Weekly)))
        val series = f.holder.appState.events.single()
        val occurrence = start.plusDays(7)
        val moved = start.plusDays(2)
        f.holder.saveEvent(series.copy(title = "Moved", date = moved.toString()), occurrence, EditScope.ThisEvent)
        val override = f.holder.eventsOn(moved).single()
        assertEquals("Moved", override.title)
        assertEquals(occurrence.toString(), override.recurrenceId)
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun movingOneOccurrenceToTheSeriesAnchorIsApplied() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            Event("week", "Weekly", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Weekly)),
        )
        val series = f.holder.appState.events.single()
        val occurrence = start.plusDays(7)
        f.holder.saveEvent(
            series.copy(title = "Moved", date = start.toString()),
            occurrence,
            EditScope.ThisEvent,
        )

        val override = f.holder.appState.events.first { it.title == "Moved" }
        assertEquals(start.toString(), override.date)
        assertEquals(occurrence.toString(), override.recurrenceId)
        assertTrue(f.holder.eventsOn(start).any { it.title == "Moved" })
        assertTrue(f.holder.eventsOn(occurrence).isEmpty())
    }

    @Test
    fun changingTheSeriesDateShiftsExceptionsAndExclusions() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            Event("daily", "Daily", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "Moved", date = start.plusDays(2).toString()),
            start.plusDays(1),
            EditScope.ThisEvent,
        )

        f.holder.saveEvent(
            series.copy(title = "Shifted", date = start.plusDays(1).toString()),
            start,
            EditScope.AllEvents,
        )

        val shiftedSeries = f.holder.appState.events.first { it.title == "Shifted" }
        val shiftedException = f.holder.appState.events.first { it.title == "Moved" }
        assertEquals(start.plusDays(1).toString(), shiftedSeries.date)
        assertEquals(listOf(start.plusDays(2).toString()), shiftedSeries.excludedDates)
        assertEquals(start.plusDays(3).toString(), shiftedException.date)
        assertEquals(start.plusDays(2).toString(), shiftedException.recurrenceId)
    }

    @Test
    fun movingTheWholeSeriesFromALaterOccurrenceUsesTheDateDelta() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            Event("daily", "Daily", start.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        val series = f.holder.appState.events.single()

        f.holder.saveEvent(
            series.copy(title = "Moved", date = start.toString()),
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
            Event(
                "bounded",
                "Bounded",
                start.toString(),
                540,
                555,
                repeat = RepeatRule(RepeatFrequency.Daily, until = start.plusDays(5).toString()),
            ),
        )
        val series = f.holder.appState.events.single()
        f.holder.saveEvent(
            series.copy(title = "Shifted", date = start.plusDays(1).toString()),
            start,
            EditScope.AllEvents,
        )
        val stored = f.holder.appState.events.single()
        assertEquals(start.plusDays(1).toString(), stored.date)
        assertEquals(start.plusDays(6).toString(), stored.repeat?.until)
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
                    date = today.toString(),
                    startMinutes = 540,
                    endMinutes = 600,
                    repeat = RepeatRule(RepeatFrequency.Daily),
                    icsUid = "base-uid",
                ),
                Event(
                    id = null,
                    title = "Future",
                    date = today.plusDays(2).toString(),
                    startMinutes = 540,
                    endMinutes = 600,
                    repeat = RepeatRule(RepeatFrequency.Daily, count = 5),
                    icsUid = "base-uid#future-x",
                    seriesUid = "base-uid",
                    recurrenceId = today.plusDays(2).toString(),
                    thisAndFuture = true,
                ),
            ),
        )
        val base = f.holder.appState.events.first { it.icsUid == "base-uid" }
        val futureUid = f.holder.appState.events.first { it.title == "Future" }.uid

        f.holder.saveEvent(
            base.copy(title = "Shifted", date = today.plusDays(1).toString()),
            today,
            EditScope.AllEvents,
        )

        val shifted = f.holder.appState.events.first { it.title == "Future" }
        assertEquals(today.plusDays(3).toString(), shifted.date)
        assertEquals(today.plusDays(3).toString(), shifted.recurrenceId)
        assertEquals(futureUid, shifted.uid)
    }

    @Test
    fun editingMetadataKeepsTheImportedSourceButChangingTimeDropsIt() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.importEvents(
            listOf(
                Event(
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

        f.holder.saveEvent(renamed.copy(startMinutes = 11 * 60, endMinutes = 12 * 60))
        val retimed = f.holder.appState.events.single()
        assertNull(retimed.timeZoneId)
        assertNull(retimed.sourceStart)
    }

    @Test
    fun editingTheMiddleDayOfARepeatingMultiDayEventKeepsOneOccurrence() {
        val f = Fixture()
        val start = f.holder.today
        f.holder.saveEvent(
            Event(
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
            series.copy(title = "Edited", date = start.toString(), endDate = start.plusDays(2).toString()),
            occurrence,
            EditScope.ThisEvent,
        )
        assertEquals(1, f.holder.eventsOn(middle).size)
        assertEquals("Edited", f.holder.eventsOn(middle).single().title)
        assertEquals(start.toString(), f.holder.eventsOn(middle).single().recurrenceId)
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
            events = listOf(Event(null, "Imported", "2026-10-27", 540, 600, icsUid = "file-1")),
            todos = listOf(Todo(id = "", title = "File todo", icsUid = "file-2")),
        )
        val writesBefore = f.storage.writes
        assertEquals(1 to 1, f.holder.importCalendar(import))
        // Two writes meant a calendar left half-imported when the process died in between.
        assertEquals(1, f.storage.writes - writesBefore)
        assertEquals(1, f.holder.appState.events.size)
        assertEquals(1, f.holder.appState.todos.size)
        val afterFirst = f.storage.writes
        assertEquals(0 to 0, f.holder.importCalendar(IcsImport(events = import.events, todos = import.todos)))
        // Nothing was new, so nothing was serialised either.
        assertEquals(afterFirst, f.storage.writes)
    }

    @Test
    fun reimportingTheSameFileStillCollapsesOwnExportsAndDuplicates() {
        val f = Fixture()
        val first = Event(null, "Meeting", "2026-10-27", 540, 600, icsUid = "uid-a")
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
        val first = Event(
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
        val series = Event(null, "Cancelled", "2026-10-27", 540, 600, icsUid = "cancel-uid")
        val exception = Event(
            null,
            "Moved",
            "2026-10-28",
            600,
            660,
            icsUid = "cancel-uid#override-2026-10-28",
            seriesUid = "cancel-uid",
            recurrenceId = "2026-10-28",
        )
        assertEquals(2, f.holder.importEvents(listOf(series, exception)))
        f.holder.importCalendar(IcsImport(cancelledUids = setOf("cancel-uid")))
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun reimportingAnOwnExportExceptionDoesNotReplaceItsMaster() {
        val f = Fixture()
        val master = Event(
            null,
            "Base",
            "2026-10-27",
            540,
            600,
            icsUid = "native-1@crko",
        )
        val exception = Event(
            null,
            "Moved",
            "2026-10-28",
            600,
            660,
            icsUid = "native-1@crko#override-2026-10-28",
            seriesUid = "native-1@crko",
            recurrenceId = "2026-10-28",
        )
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
            Alarm(triggerMinutes = 15),
            Alarm(action = "EMAIL", triggerMinutes = 30, description = "mail"),
            Alarm(triggerMinutes = 15, absoluteTrigger = "20261027T090000Z"),
        )
        val updated = updateReminderAlarms(alarms, original = 15, reminder = 60)
        assertEquals(3, updated.size)
        assertEquals(60, updated[0].triggerMinutes)
        assertEquals("EMAIL", updated[1].action)
        assertEquals("20261027T090000Z", updated[2].absoluteTrigger)

        val removed = updateReminderAlarms(alarms, original = 15, reminder = null)
        assertEquals(2, removed.size)
        assertTrue(removed.none { it.absoluteTrigger == null && it.triggerMinutes == 15 })
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
            warnings = listOf(IcsWarning.MissingStart),
            details = details,
        )
        assertTrue(text.contains("(297 more)"), text)
        assertTrue(text.length < 1_000, "message is ${text.length} characters")
        assertEquals(3, Regex("line \\d").findAll(text).count(), text)
    }

    @Test
    fun embeddedAttachmentsTolerateFoldedAndUnpaddedBase64() {
        assertEquals("Test", Attachment(binary = true, data = "VGVzdA==").decodedBytes()?.decodeToString())
        assertEquals("Test", Attachment(binary = true, data = "VGVzdA").decodedBytes()?.decodeToString())
        assertEquals("Test", Attachment(binary = true, data = "VGVz\r\n dA=").decodedBytes()?.decodeToString())
        assertNull(Attachment(binary = true, data = "!!!").decodedBytes())
    }
}
