package com.crko

import com.crko.domain.Alarm
import com.crko.domain.AlarmTrigger
import com.crko.domain.Event
import com.crko.domain.IcsImport
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalCalendarId
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.effectiveUid
import com.crko.domain.plusDays
import com.crko.i18n.EnStrings
import com.crko.i18n.Strings
import com.crko.i18n.ZhStrings
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.EditScope
import com.crko.state.UndoResult
import com.crko.state.WriteResult
import com.crko.ui.common.actionText
import com.crko.ui.common.messageText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * F1.4: the most recent delete can be restored. The snapshot keeps the removed records, the calendar
 * that owned them and their positions; it lives in memory only and the next delete replaces it.
 */
class DeleteUndoTest {

    private fun Fixture.series(id: String = "series"): Event {
        holder.saveEvent(
            testEvent(id, "Standup", holder.today.toString(), 540, 555, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        return holder.appState.events.first { it.id == id }
    }

    private fun Fixture.dailyTodo(): Todo {
        holder.importCalendar(
            IcsImport(
                todos = listOf(
                    Todo(
                        id = "",
                        title = "Daily",
                        due = IcsTimeValue.Date(holder.today),
                        repeat = RepeatRule(RepeatFrequency.Daily),
                    ),
                ),
            ),
        )
        return holder.appState.todos.first { !it.isOccurrence }
    }

    private fun Fixture.eventsOf(calendarId: String): List<Event> =
        holder.appState.calendars.single { it.id == calendarId }.events

    private fun Fixture.localId(): String = LocalCalendarId

    @Test
    fun nothingIsUndoneBeforeAnyDelete() {
        val f = Fixture()
        assertEquals(UndoResult.NothingToUndo, f.holder.undoLastDelete())
    }

    @Test
    fun aDeletedEventComesBackInPlaceWithItsIdentity() {
        val f = Fixture()
        val first = testEvent("e1", "First", f.holder.today.toString(), 480, 540)
        val second = testEvent("e2", "Second", f.holder.today.toString(), 600, 660, icsUid = "uid-2")
        val third = testEvent("e3", "Third", f.holder.today.toString(), 720, 780)
        listOf(first, second, third).forEach { f.holder.saveEvent(it) }
        val before = f.eventsOf(f.localId())

        f.holder.deleteEvent("e2")
        assertEquals(listOf("First", "Third"), f.eventsOf(f.localId()).map { it.title })

        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        // Same records, same order, same ids and UIDs: an undo is not a re-import.
        assertEquals(before, f.eventsOf(f.localId()))
        assertEquals(listOf("e1", "e2", "e3"), f.eventsOf(f.localId()).map { it.id })
        assertEquals("uid-2", f.eventsOf(f.localId())[1].icsUid)
    }

    @Test
    fun aDeletedSeriesComesBackWithAllOfItsExceptions() {
        val f = Fixture()
        val series = f.series()
        f.holder.saveEvent(
            series.copy(title = "Moved").movedTo(f.holder.today.plusDays(1)),
            occurrenceDate = f.holder.today.plusDays(1),
            scope = EditScope.ThisEvent,
        )
        val before = f.eventsOf(f.localId())
        assertEquals(2, before.size)

        f.holder.deleteEvent(series, scope = EditScope.AllEvents)
        assertTrue(f.eventsOf(f.localId()).isEmpty())

        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        assertEquals(before, f.eventsOf(f.localId()))
        val restoredSeries = f.eventsOf(f.localId()).single { it.id == series.id }
        assertEquals(series.repeat, restoredSeries.repeat)
        assertEquals(series.icsUid, restoredSeries.icsUid)
        val restoredException = f.eventsOf(f.localId()).single { it.id != series.id }
        assertEquals(series.uid, restoredException.icsUid)
        assertNotNull(restoredException.recurrenceId)
    }

    @Test
    fun aDeletedSubtaskComesBackUnderItsParent() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parentUid = parent.effectiveUid)
        val child = f.holder.appState.todos.single { it.title == "Child" }

        f.holder.deleteTodo(child.id)
        assertEquals(listOf("Parent"), f.holder.appState.todos.map { it.title })

        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        val restored = f.holder.appState.todos.single { it.title == "Child" }
        assertEquals(child, restored)
        assertEquals(parent.effectiveUid, restored.relatedTo.single().uid)
        assertEquals(1, f.holder.activeTodoForest().single().children.size)
    }

    @Test
    fun deletingARepeatingTodoTakesItsInstancesAndUndoRestoresThem() {
        val f = Fixture()
        val master = f.dailyTodo()
        val today = f.holder.today
        assertEquals(WriteResult.Success, f.holder.completeTodoOccurrence(master.id, today))
        val instance = f.holder.appState.todos.single { it.isOccurrence }
        val before = f.holder.appState.todos

        f.holder.deleteTodo(master.id)
        // The completed instance shares the master's UID and leaves with it.
        assertTrue(f.holder.appState.todos.isEmpty())

        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        assertEquals(before, f.holder.appState.todos)
        assertEquals(instance, f.holder.appState.todos.single { it.isOccurrence })
        assertEquals(TodoStatus.Completed, instance.status)
    }

    @Test
    fun aDeletedJournalComesBackAtItsPosition() {
        val f = Fixture()
        f.holder.addJournal("First", "a", f.holder.today)
        f.holder.addJournal("Second", "b", f.holder.today.plusDays(-1))
        f.holder.addJournal("Third", "c", null)
        val before = f.holder.appState.journals
        val second = before.single { it.title == "Second" }

        f.holder.deleteJournal(second.id)
        assertEquals(listOf("First", "Third"), f.holder.appState.journals.map { it.title })

        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        assertEquals(before, f.holder.appState.journals)
    }

    @Test
    fun onlyTheMostRecentDeleteCanBeUndone() {
        val f = Fixture()
        f.holder.saveEvent(testEvent("e1", "First", f.holder.today.toString(), 480, 540))
        f.holder.addJournal("Second", "", f.holder.today)
        f.holder.deleteEvent("e1")
        f.holder.deleteJournal(f.holder.appState.journals.single().id)
        assertTrue(f.holder.appState.events.isEmpty())
        assertTrue(f.holder.appState.journals.isEmpty())

        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        // The journal is back, the earlier event delete is not.
        assertEquals(1, f.holder.appState.journals.size)
        assertTrue(f.holder.appState.events.isEmpty())
        assertEquals(UndoResult.NothingToUndo, f.holder.undoLastDelete())
    }

    @Test
    fun restoreKeepsTheCalendarAndPositionOfTheRecords() {
        val f = Fixture()
        val work = assertNotNull(f.holder.createLocalCalendar("Work", null))
        f.holder.saveEvent(
            testEvent("w1", "Work first", f.holder.today.toString(), 480, 540),
            calendarId = work.id,
        )
        f.holder.saveEvent(
            testEvent("w2", "Work second", f.holder.today.toString(), 600, 660),
            calendarId = work.id,
        )
        f.holder.saveEvent(testEvent("h1", "Home", f.holder.today.toString(), 720, 780))
        f.holder.deleteEvent("w2")

        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        // Back into its own calendar at its own index; the other calendar never changed.
        assertEquals(listOf("w1", "w2"), f.eventsOf(work.id).map { it.id })
        assertEquals(listOf("h1"), f.eventsOf(f.localId()).map { it.id })
    }

    @Test
    fun anIdThatExistsAgainIsNotOverwritten() {
        val f = Fixture()
        f.holder.saveEvent(testEvent("e1", "Original", f.holder.today.toString(), 480, 540))
        f.holder.deleteEvent("e1")
        f.holder.saveEvent(testEvent("e1", "New record", f.holder.today.toString(), 600, 660))

        assertEquals(UndoResult.Unavailable, f.holder.undoLastDelete())
        // The live record keeps its content; nothing was resurrected next to it.
        assertEquals(listOf("New record"), f.holder.appState.events.map { it.title })

        // The snapshot survives a failed attempt, so removing the clash makes it work again.
        f.holder.deleteEvent("e1")
        assertEquals(UndoResult.Success, f.holder.undoLastDelete())
        assertEquals(listOf("New record"), f.holder.appState.events.map { it.title })
    }

    @Test
    fun aSkippedOccurrenceKeepsTheSeriesAndLeavesNothingToUndo() {
        val f = Fixture()
        val series = f.series()
        f.holder.deleteEvent(series, occurrenceDate = f.holder.today.plusDays(1), scope = EditScope.ThisEvent)
        // No record was removed: the master only gained an EXDATE.
        assertEquals(1, f.holder.appState.events.size)
        assertEquals(UndoResult.NothingToUndo, f.holder.undoLastDelete())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun undoReschedulesReminders() = runTest {
        val scheduler = RecordingReminderScheduler()
        val holder = AppStateHolder(
            scope = this,
            initialSystemLocale = "en-US",
            clock = FakeClock(1_793_101_200_000),
            idProvider = { "id-1" },
            storage = MemoryStorage(),
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
            reminders = scheduler,
        )
        // Two days out with a reminder, so the delete/restore pair moves real keys whatever the
        // host zone does with the fixture's fixed instant.
        holder.saveEvent(
            testEvent(
                "e1",
                "Meeting",
                holder.today.plusDays(2).toString(),
                540,
                600,
                alarms = listOf(Alarm(trigger = AlarmTrigger.Relative((-15).minutes))),
            ),
        )
        holder.deleteEvent("e1")
        advanceUntilIdle()
        val afterDelete = scheduler.passes.size

        assertEquals(UndoResult.Success, holder.undoLastDelete())
        advanceUntilIdle()

        assertTrue(scheduler.passes.size > afterDelete)
        val restored = scheduler.passes.last()
        assertIs<RecordingReminderScheduler.Pass.Execute>(restored)
        assertTrue(restored.registered.all { it.startsWith("e:e1:") })
        assertEquals(1, holder.appState.events.size)
    }

    @Test
    fun theUndoSnapshotIsNotPersisted() {
        val storage = MemoryStorage()
        val first = Fixture(storage)
        first.holder.saveEvent(testEvent("e1", "Meeting", first.holder.today.toString(), 540, 600))
        first.holder.deleteEvent("e1")
        assertTrue(first.holder.appState.events.isEmpty())

        // A fresh holder reads the saved state, which never contained the undo stack.
        val reloaded = Fixture(storage)
        assertTrue(reloaded.holder.appState.events.isEmpty())
        assertEquals(UndoResult.NothingToUndo, reloaded.holder.undoLastDelete())
    }

    @Test
    fun theDeleteMessageOffersUndoOnlyWhileRecordsWait() {
        assertEquals(EnStrings.undo, actionText(EnStrings, AppMessage.Deleted(undoable = true)))
        assertNull(actionText(EnStrings, AppMessage.Deleted(undoable = false)))
        assertNull(actionText(EnStrings, AppMessage.Saved))
        assertEquals(ZhStrings.deleted, messageText(ZhStrings, AppMessage.Deleted(undoable = true)))
        assertEquals(EnStrings.deleteUndone, messageText(EnStrings, AppMessage.DeleteUndone))
        assertEquals(ZhStrings.deleteUndoFailed, messageText(ZhStrings, AppMessage.DeleteUndoFailed))
    }

    @Test
    fun deletingUnknownRecordsKeepsNothingToUndo() {
        val f = Fixture()
        f.holder.deleteEvent("missing")
        f.holder.deleteTodo("missing")
        f.holder.deleteJournal("missing")
        assertEquals(UndoResult.NothingToUndo, f.holder.undoLastDelete())
        assertTrue(f.holder.appState.events.isEmpty())
        assertTrue(f.holder.appState.todos.isEmpty())
        assertTrue(f.holder.appState.journals.isEmpty())
    }
}
