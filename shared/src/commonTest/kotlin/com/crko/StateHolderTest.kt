package com.crko

import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.plusDays
import com.crko.domain.PersistedSettings
import com.crko.domain.TodoPriority
import com.crko.i18n.AppLanguage
import com.crko.i18n.resolveLanguage
import com.crko.i18n.systemLanguage
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.storage.StateStorage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.test.Test
import kotlin.test.assertEquals
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
    override fun loadState() = state
    override fun saveState(state: AppState) {
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
    fun eventValidationRejectsBlankTitlesAndNonPositiveDurations() {
        val f = Fixture()
        val base = Event(null, "Meeting", "2026-10-27", 540, 600)
        assertEquals(WriteResult.EmptyTitle, f.holder.saveEvent(base.copy(title = "   ")))
        assertEquals(WriteResult.InvalidTimeRange, f.holder.saveEvent(base.copy(endMinutes = 540)))
        assertEquals(WriteResult.InvalidTimeRange, f.holder.saveEvent(base.copy(endMinutes = 500)))
        assertEquals(WriteResult.Success, f.holder.saveEvent(base))
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
        assertEquals(listOf("Ship"), f.holder.completedTodos().map { it.title })
        f.holder.setTodoDone(id, false)
        assertEquals(1, f.holder.activeTodos().size)
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
}
