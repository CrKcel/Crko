package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.OrdinalWeekday
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.occurrenceDate
import com.crko.domain.occurrenceStartsBetween
import com.crko.domain.occursOn
import com.crko.domain.recurrenceSlotOn
import com.crko.domain.wallDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Todo series expansion; split out of `RecurrenceTest` together with the `TodoRecurrence.kt` file. */
class TodoRecurrenceTest {

    private fun days(vararg values: Int): List<OrdinalWeekday> = values.map { OrdinalWeekday(0, it) }

    @Test
    fun recurringTodoAppearsOnEachOccurrence() {
        val todo = Todo(
            id = "t1",
            title = "Report",
            start = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            due = IcsTimeValue.Date(LocalDate(2026, 10, 6)),
            repeat = RepeatRule(RepeatFrequency.Weekly, byDay = days(0)),
        )
        // The todo shows under its due date, one day after each Monday start.
        assertTrue(todo.occursOn(LocalDate(2026, 10, 6)))
        assertTrue(todo.occursOn(LocalDate(2026, 10, 13)))
        assertFalse(todo.occursOn(LocalDate(2026, 10, 7)))
        assertFalse(todo.occursOn(LocalDate(2026, 10, 15)))
        assertEquals(LocalDate(2026, 10, 13), todo.nextOccurrenceDate(LocalDate(2026, 10, 9)))
    }

    @Test
    fun todoExdateSkipsTheOccurrenceAndTheNextOneIsFound() {
        val todo = Todo(
            id = "t2",
            title = "Chore",
            due = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            repeat = RepeatRule(RepeatFrequency.Weekly, byDay = days(0)),
            excludedDates = listOf(IcsTimeValue.Date(LocalDate(2026, 10, 12))),
        )
        assertFalse(todo.occursOn(LocalDate(2026, 10, 12)))
        assertEquals(LocalDate(2026, 10, 19), todo.nextOccurrenceDate(LocalDate(2026, 10, 12)))
    }

    @Test
    fun todoOccurrenceStartsCarryTheDueOffset() {
        val todo = Todo(
            id = "t3",
            title = "Dose",
            start = IcsTimeValue.Date(LocalDate(2026, 10, 1)),
            due = IcsTimeValue.Date(LocalDate(2026, 10, 2)),
            repeat = RepeatRule(RepeatFrequency.Daily, interval = 2, count = 2),
        )
        assertEquals(
            listOf(LocalDate(2026, 10, 1), LocalDate(2026, 10, 3)),
            todo.occurrenceStartsBetween(LocalDate(2026, 10, 1), LocalDate(2026, 10, 10)),
        )
        assertEquals(LocalDate(2026, 10, 4), todo.occurrenceDate(LocalDate(2026, 10, 3)))
    }

    @Test
    fun anEndedTodoSeriesFallsBackToItsStoredDue() {
        // COUNT=2 daily from 10-01: occurrences show on 10-02 and 10-03; once the series is over
        // the stored due stays what the todo is shown and sorted under.
        val todo = Todo(
            id = "t4",
            title = "Series",
            start = IcsTimeValue.Date(LocalDate(2026, 10, 1)),
            due = IcsTimeValue.Date(LocalDate(2026, 10, 2)),
            repeat = RepeatRule(RepeatFrequency.Daily, count = 2),
        )
        assertEquals(LocalDate(2026, 10, 3), todo.nextOccurrenceDate(LocalDate(2026, 10, 3)))
        assertEquals(LocalDate(2026, 10, 2), todo.nextOccurrenceDate(LocalDate(2026, 10, 4)))
        assertEquals(LocalDate(2026, 10, 2), todo.nextOccurrenceDate(LocalDate(2026, 11, 30)))
    }

    @Test
    fun storedInstanceSlotsAreSkippedByTheProjection() {
        val master = Todo(
            id = "t5",
            title = "Dose",
            due = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            repeat = RepeatRule(RepeatFrequency.Daily),
        )
        val instance = Todo(
            id = "t5-done",
            title = "Dose",
            due = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            icsUid = "t5@crko",
            status = TodoStatus.Completed,
        )
        assertFalse(master.occursOn(LocalDate(2026, 10, 5), listOf(instance)))
        assertTrue(master.occursOn(LocalDate(2026, 10, 6), listOf(instance)))
        assertEquals(
            LocalDate(2026, 10, 6),
            master.nextOccurrenceDate(LocalDate(2026, 10, 5), listOf(instance)),
        )
        // An instance of another series leaves the projection alone.
        assertTrue(master.occursOn(LocalDate(2026, 10, 5), listOf(instance.copy(icsUid = "other"))))
    }

    @Test
    fun recurrenceSlotKeepsTheMasterValueTypeAndDueOffset() {
        val todo = Todo(
            id = "t6",
            title = "Report",
            start = IcsTimeValue.Floating(LocalDateTime(2026, 10, 1, 9, 0)),
            due = IcsTimeValue.Floating(LocalDateTime(2026, 10, 1, 18, 0)),
            repeat = RepeatRule(RepeatFrequency.Weekly),
        )
        val slot = todo.recurrenceSlotOn(LocalDate(2026, 10, 8))
        assertEquals(IcsTimeValue.Floating(LocalDateTime(2026, 10, 8, 9, 0)), slot)
        // An instance's due sits on the display date the occurrence is shown under.
        assertEquals(LocalDate(2026, 10, 8), todo.occurrenceDate(slot!!.wallDate))
    }
}
