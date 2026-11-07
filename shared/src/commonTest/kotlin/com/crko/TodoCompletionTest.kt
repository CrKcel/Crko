package com.crko

import com.crko.domain.IcsImport
import com.crko.domain.IcsTimeValue
import com.crko.domain.RecurrenceDate
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.effectiveUid
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.domain.wallDate
import com.crko.domain.wallTime
import com.crko.state.WriteResult
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F1.3: completing a repeating todo stores an occurrence instance and keeps the series alive. */
class TodoCompletionTest {

    private fun Fixture.dailySeries(
        count: Int? = null,
        until: IcsTimeValue? = null,
        excluded: List<IcsTimeValue> = emptyList(),
    ): Todo {
        holder.importCalendar(
            IcsImport(
                todos = listOf(
                    Todo(
                        id = "",
                        title = "Daily",
                        due = IcsTimeValue.Date(holder.today),
                        repeat = RepeatRule(RepeatFrequency.Daily, count = count, until = until),
                        excludedDates = excluded,
                    ),
                ),
            ),
        )
        return holder.appState.todos.single()
    }

    private fun Fixture.instances(uid: String): List<Todo> =
        holder.appState.todos.filter { it.isOccurrence && it.effectiveUid == uid }

    @Test
    fun completingOneOccurrenceKeepsTheMasterAndMovesTheProjection() {
        val f = Fixture()
        val master = f.dailySeries()
        val today = f.holder.today

        assertEquals(WriteResult.Success, f.holder.completeTodoOccurrence(master.id, today))

        val stored = f.holder.appState.todos
        assertEquals(2, stored.size)
        // The master is byte-for-byte the record it was: its rule, due date and revision stay.
        assertEquals(master, stored.single { it.id == master.id })

        val instance = stored.single { it.isOccurrence }
        assertEquals(master.effectiveUid, instance.icsUid)
        assertEquals(IcsTimeValue.Date(today), instance.recurrenceId)
        assertEquals(IcsTimeValue.Date(today), instance.due)
        assertEquals(TodoStatus.Completed, instance.status)
        assertEquals(100, instance.percentComplete)
        assertTrue(instance.done)
        assertTrue(instance.completed != null)
        assertEquals(master.sequence + 1, instance.sequence)

        // The active list still shows the series, under its next occurrence.
        val active = f.holder.activeTodos().single()
        assertEquals(master.id, active.id)
        assertEquals(today.plusDays(1), f.holder.nextTodoOccurrence(active))
        assertEquals(1, f.holder.completedTodos().size)
    }

    @Test
    fun completingTheSameSlotTwiceUpdatesTheInstanceInsteadOfAddingAnother() {
        val f = Fixture()
        val master = f.dailySeries()

        f.holder.completeTodoOccurrence(master.id, f.holder.today)
        val first = f.instances(master.effectiveUid).single()
        f.holder.completeTodoOccurrence(master.id, f.holder.today)

        val instances = f.instances(master.effectiveUid)
        assertEquals(1, instances.size)
        assertEquals(first.id, instances.single().id)
        assertEquals(first.sequence + 1, instances.single().sequence)
    }

    @Test
    fun anRdateOnlySeriesCompletesOccurrencesInsteadOfItself() {
        // RDATE without a rule is a series: completing an added date must store an instance and
        // keep the master open, not close the whole record like a one-off.
        val f = Fixture()
        val added = f.holder.today.plusDays(7)
        f.holder.importCalendar(
            IcsImport(
                todos = listOf(
                    Todo(
                        id = "",
                        title = "Added",
                        due = IcsTimeValue.Date(f.holder.today),
                        rDates = listOf(RecurrenceDate(IcsTimeValue.Date(added))),
                    ),
                ),
            ),
        )
        val master = f.holder.appState.todos.single()

        assertEquals(WriteResult.Success, f.holder.completeTodoOccurrence(master.id, added))

        val stored = f.holder.appState.todos
        assertEquals(2, stored.size)
        assertEquals(master, stored.single { it.id == master.id })
        assertFalse(stored.single { it.id == master.id }.done)
        val instance = stored.single { it.isOccurrence }
        assertEquals(IcsTimeValue.Date(added), instance.recurrenceId)
        assertTrue(instance.done)

        // The series stays active under its next projection, with the completed slot recorded.
        val active = f.holder.activeTodos().single()
        assertEquals(master.id, active.id)
        assertEquals(1, f.holder.completedTodos().size)
    }

    @Test
    fun aWeeklyCountSeriesServesEveryOccurrenceAndThenStops() {
        val f = Fixture()
        val today = f.holder.today
        f.holder.importCalendar(
            IcsImport(
                todos = listOf(
                    Todo(
                        id = "",
                        title = "Weekly",
                        due = IcsTimeValue.Date(today),
                        repeat = RepeatRule(RepeatFrequency.Weekly, count = 2),
                    ),
                ),
            ),
        )
        val master = f.holder.appState.todos.single()

        f.holder.completeTodoOccurrence(master.id, today)
        assertEquals(today.plusDays(7), f.holder.nextTodoOccurrence(master))

        f.holder.completeTodoOccurrence(master.id, today.plusDays(7))
        assertEquals(2, f.instances(master.effectiveUid).size)
        // COUNT=2 is exhausted; the stored due stays what the todo is shown under.
        assertEquals(today, f.holder.nextTodoOccurrence(master))
        assertEquals(false, f.holder.activeTodos().single().done)
    }

    @Test
    fun anUntilSeriesNeverProjectsBeyondItsEnd() {
        val f = Fixture()
        val today = f.holder.today
        f.holder.importCalendar(
            IcsImport(
                todos = listOf(
                    Todo(
                        id = "",
                        title = "Until",
                        due = IcsTimeValue.Date(today),
                        repeat = RepeatRule(RepeatFrequency.Daily, until = IcsTimeValue.Date(today.plusDays(1))),
                    ),
                ),
            ),
        )
        val master = f.holder.appState.todos.single()

        f.holder.completeTodoOccurrence(master.id, today)
        assertEquals(today.plusDays(1), f.holder.nextTodoOccurrence(master))

        f.holder.completeTodoOccurrence(master.id, today.plusDays(1))
        assertEquals(2, f.instances(master.effectiveUid).size)
        assertTrue(!master.occursOn(today.plusDays(2), f.instances(master.effectiveUid)))
    }

    @Test
    fun anExcludedSlotCannotBeCompletedAndTheNextOneSkipsIt() {
        val f = Fixture()
        val today = f.holder.today
        val master = f.dailySeries(excluded = listOf(IcsTimeValue.Date(today.plusDays(1))))

        f.holder.completeTodoOccurrence(master.id, today.plusDays(1))
        assertTrue(f.instances(master.effectiveUid).isEmpty())

        f.holder.completeTodoOccurrence(master.id, today)
        assertEquals(today.plusDays(2), f.holder.nextTodoOccurrence(master))
    }

    @Test
    fun aZonedSeriesKeepsItsTimeZoneOnTheInstance() {
        val f = Fixture()
        val today = f.holder.today
        val due = IcsTimeValue.Zoned(LocalDateTime(today, LocalTime(9, 0)), "Europe/Berlin")
        f.holder.importCalendar(
            IcsImport(
                todos = listOf(
                    Todo(
                        id = "",
                        title = "Zoned",
                        due = due,
                        repeat = RepeatRule(RepeatFrequency.Weekly, count = 3),
                    ),
                ),
            ),
        )
        val master = f.holder.appState.todos.single()

        val shown = f.holder.nextTodoOccurrence(master) ?: error("no occurrence")
        f.holder.completeTodoOccurrence(master.id, shown)

        val instance = f.instances(master.effectiveUid).single()
        val slot = assertIs<IcsTimeValue.Zoned>(instance.recurrenceId)
        assertEquals("Europe/Berlin", slot.tzid)
        assertEquals(LocalTime(9, 0), slot.wallTime)
        // The slot is the master's own DUE, moved to the completed occurrence.
        assertEquals(due.wallDate, slot.wallDate)
        val instanceDue = assertIs<IcsTimeValue.Zoned>(instance.due)
        assertEquals("Europe/Berlin", instanceDue.tzid)
        assertEquals(LocalTime(9, 0), instanceDue.wallTime)
    }

    @Test
    fun everyCompletedOccurrenceCountsInTheDayStatistics() {
        val f = Fixture()
        val master = f.dailySeries()
        val today = f.holder.today

        f.holder.completeTodoOccurrence(master.id, today)
        f.holder.completeTodoOccurrence(master.id, today.plusDays(1))

        assertEquals(2, f.holder.completedToday())
    }

    @Test
    fun completingTheSeriesClosesIt() {
        val f = Fixture()
        val master = f.dailySeries()
        f.holder.completeTodoOccurrence(master.id, f.holder.today)

        assertEquals(WriteResult.Success, f.holder.completeTodoSeries(master.id))

        val stored = f.holder.appState.todos.single { it.id == master.id }
        assertEquals(TodoStatus.Completed, stored.status)
        assertEquals(100, stored.percentComplete)
        assertEquals(master.repeat, stored.repeat)
        assertTrue(f.holder.activeTodos().isEmpty())
        assertNull(f.holder.activeTodoForest().firstOrNull())
    }

    @Test
    fun stoppingRecurrenceKeepsTheRecordWithoutProjectingAgain() {
        val f = Fixture()
        val master = f.dailySeries()
        val today = f.holder.today

        assertEquals(WriteResult.Success, f.holder.stopTodoRecurrence(master.id))

        val stored = f.holder.appState.todos.single()
        assertEquals(master.id, stored.id)
        assertEquals(master.icsUid, stored.icsUid)
        assertEquals(master.due, stored.due)
        assertNull(stored.repeat)
        assertEquals(emptyList(), stored.rDates)
        assertEquals(emptyList(), stored.excludedDates)
        assertEquals(master.sequence + 1, stored.sequence)
        // No occurrence projection is left: only the stored due date remains.
        assertEquals(today, f.holder.nextTodoOccurrence(stored))
        assertTrue(!stored.occursOn(today.plusDays(1)))
    }

    @Test
    fun completionOnlyNeedsStoredRecordsSoDeletingOneInstanceIsFinal() {
        // F1.4 (undo) is not implemented; completion therefore works from the stored records alone.
        val f = Fixture()
        val master = f.dailySeries()
        val today = f.holder.today
        f.holder.completeTodoOccurrence(master.id, today)
        val instance = f.instances(master.effectiveUid).single()

        f.holder.deleteTodo(instance.id)

        assertTrue(f.holder.completedTodos().isEmpty())
        // The slot is free again, so the series shows today once more.
        assertEquals(today, f.holder.nextTodoOccurrence(f.holder.activeTodos().single()))
        f.holder.completeTodoOccurrence(master.id, today)
        assertEquals(1, f.instances(master.effectiveUid).size)
    }
}
