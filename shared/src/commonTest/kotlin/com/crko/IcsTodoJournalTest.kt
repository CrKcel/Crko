package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.RelatedTo
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.exportCalendar
import com.crko.domain.importCalendar
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.occursOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class IcsTodoJournalTest : IcsTestBase() {

    @Test
    fun vtodoImportAndExportMapTodoFields() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo-1
            SUMMARY:File taxes
            DESCRIPTION:Bring receipts
            DUE;VALUE=DATE:20261101
            STATUS:COMPLETED
            COMPLETED;VALUE=DATE:20261031
            PRIORITY:1
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals("File taxes", todo.title)
        assertEquals("Bring receipts", todo.note)
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 11, 1)), todo.due)
        assertEquals(1, todo.priority)
        assertTrue(todo.done)
        assertEquals("2026-10-31", todo.completedAt)
        assertEquals(TodoStatus.Completed, todo.status)

        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("BEGIN:VTODO\r\n"), exported)
        assertTrue(exported.contains("SUMMARY:File taxes\r\n"), exported)
        assertTrue(exported.contains("DUE;VALUE=DATE:20261101\r\n"), exported)
        assertTrue(exported.contains("STATUS:COMPLETED\r\n"), exported)
        assertTrue(exported.contains("PERCENT-COMPLETE:100\r\n"), exported)
        // RFC 5545 requires a UTC DATE-TIME, not a DATE value.
        assertTrue(exported.contains("COMPLETED:20261031T000000Z\r\n"), exported)
    }

    @Test
    fun vtodoCancelledIsPreserved() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:cancelled
            SUMMARY:Cancelled task
            STATUS:CANCELLED
            PERCENT-COMPLETE:25
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(TodoStatus.Cancelled, todo.status)
        assertEquals(25, todo.percentComplete)

        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("STATUS:CANCELLED\r\n"), exported)
        assertTrue(exported.contains("PERCENT-COMPLETE:25\r\n"), exported)
        assertFalse(exported.contains("COMPLETED:"), exported)
    }

    @Test
    fun vtodoCompletedKeepsItsUtcInstant() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:completed-time
            SUMMARY:Done
            STATUS:COMPLETED
            PERCENT-COMPLETE:100
            COMPLETED:20261031T123456Z
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(Instant.parse("2026-10-31T12:34:56Z"), todo.completed)
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("COMPLETED:20261031T123456Z\r\n"), exported)
    }

    @Test
    fun vtodoNonParentRelatedToKeepsItsDirection() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo-reltype
            SUMMARY:Linked either way
            RELATED-TO:parent-todo
            RELATED-TO;RELTYPE=CHILD:child-todo
            RELATED-TO;RELTYPE=SIBLING:sibling-todo
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(
            listOf(RelatedTo("parent-todo"), RelatedTo("child-todo", "CHILD"), RelatedTo("sibling-todo", "SIBLING")),
            todo.relatedTo,
        )
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        // The other relations keep their parameter instead of being re-written as parent links.
        assertTrue(exported.contains("RELATED-TO;RELTYPE=CHILD:child-todo\r\n"), exported)
        assertTrue(exported.contains("RELATED-TO;RELTYPE=SIBLING:sibling-todo\r\n"), exported)
        val back = importCalendar(exported, utc, "Untitled").todos.single()
        assertEquals(todo.relatedTo, back.relatedTo)
    }

    @Test
    fun aTimedTodoExdateKeepsItsOwnClock() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:timed-exdate
            SUMMARY:Weekly report
            DUE;TZID=Europe/Berlin:20261105T170000
            RRULE:FREQ=WEEKLY;BYDAY=TH;COUNT=4
            EXDATE;TZID=Europe/Berlin:20261112T170000
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(
            listOf(IcsTimeValue.Zoned(LocalDateTime(2026, 11, 12, 17, 0), "Europe/Berlin")),
            todo.excludedDates,
        )
        val exported = exportCalendar(events = emptyList(), todos = listOf(todo), now = now)
        // The exclusion is written in the series' own value type, not flattened to a bare date.
        assertTrue(exported.contains("EXDATE;TZID=Europe/Berlin:20261112T170000\r\n"), exported)
        val back = importCalendar(exported, utc, "Untitled").todos.single()
        assertEquals(todo.excludedDates, back.excludedDates)
    }

    @Test
    fun vtodoImportKeepsMasterAndCompletionInstanceUnderOneUid() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:weekly-todo
            SUMMARY:Water plants
            DUE;VALUE=DATE:20261102
            RRULE:FREQ=WEEKLY
            END:VTODO
            BEGIN:VTODO
            UID:weekly-todo
            RECURRENCE-ID;VALUE=DATE:20261102
            SUMMARY:Water plants
            DUE;VALUE=DATE:20261102
            STATUS:COMPLETED
            PERCENT-COMPLETE:100
            COMPLETED:20261102T120000Z
            END:VTODO
            """,
        )
        val todos = importCalendar(text, utc, "Untitled").todos
        assertEquals(2, todos.size)
        val master = todos.single { it.recurrenceId == null }
        val instance = todos.single { it.recurrenceId != null }
        assertEquals("weekly-todo", master.icsUid)
        assertEquals(master.icsUid, instance.icsUid)
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 11, 2)), instance.recurrenceId)
        assertTrue(instance.done)
        assertEquals(TodoStatus.Completed, instance.status)
        // The master keeps its rule; the completed slot is skipped by its projection.
        assertTrue(master.repeat != null)
        assertFalse(master.occursOn(LocalDate(2026, 11, 2), listOf(instance)))
        assertEquals(
            LocalDate(2026, 11, 9),
            master.nextOccurrenceDate(LocalDate(2026, 11, 2), listOf(instance)),
        )
    }

    @Test
    fun vtodoCompletionInstanceRoundTripsWithItsSlot() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:round-trip-todo
            SUMMARY:Invoice
            DUE;VALUE=DATE:20261102
            RRULE:FREQ=WEEKLY
            END:VTODO
            BEGIN:VTODO
            UID:round-trip-todo
            RECURRENCE-ID;VALUE=DATE:20261102
            SUMMARY:Invoice
            DUE;VALUE=DATE:20261102
            STATUS:COMPLETED
            PERCENT-COMPLETE:100
            COMPLETED:20261102T120000Z
            END:VTODO
            """,
        )
        val todos = importCalendar(text, utc, "Untitled").todos
        val instance = todos.single { it.recurrenceId != null }

        val exported = exportCalendar(events = emptyList(), todos = todos, now = now)
        assertTrue(exported.contains("RECURRENCE-ID;VALUE=DATE:20261102\r\n"), exported)
        assertTrue(exported.contains("COMPLETED:20261102T120000Z\r\n"), exported)
        // Master and instance share the UID exactly once each.
        assertEquals(2, Regex("UID:round-trip-todo\r\n").findAll(exported).count(), exported)

        val back = importCalendar(exported, utc, "Untitled").todos
        assertEquals(2, back.size)
        val backInstance = back.single { it.recurrenceId != null }
        assertEquals(instance.recurrenceId, backInstance.recurrenceId)
        assertEquals(instance.completed, backInstance.completed)
        assertEquals(instance.status, backInstance.status)
        assertEquals(instance.percentComplete, backInstance.percentComplete)
        assertEquals("round-trip-todo", backInstance.icsUid)
    }

    @Test
    fun aTimedInstanceSlotAlignsWithTheZonedMaster() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:zoned-todo
            SUMMARY:Report
            DUE;TZID=Europe/Berlin:20261105T170000
            RRULE:FREQ=WEEKLY
            END:VTODO
            BEGIN:VTODO
            UID:zoned-todo
            RECURRENCE-ID:20261112T160000Z
            SUMMARY:Report
            DUE;TZID=Europe/Berlin:20261112T170000
            STATUS:COMPLETED
            COMPLETED:20261112T160000Z
            END:VTODO
            """,
        )
        val todos = importCalendar(text, utc, "Untitled").todos
        val instance = todos.single { it.recurrenceId != null }
        // A UTC slot inside a TZID series is re-expressed in the master's own value type.
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 11, 12, 17, 0), "Europe/Berlin"),
            instance.recurrenceId,
        )

        val exported = exportCalendar(events = emptyList(), todos = todos, now = now)
        assertTrue(exported.contains("RECURRENCE-ID;TZID=Europe/Berlin:20261112T170000\r\n"), exported)
    }

    @Test
    fun anInstanceWithoutItsMasterExportsAsAPlainTodo() {
        val orphan = Todo(
            id = "orphan",
            title = "Orphan",
            icsUid = "gone-todo",
            due = IcsTimeValue.Date(LocalDate(2026, 11, 2)),
            recurrenceId = IcsTimeValue.Date(LocalDate(2026, 11, 2)),
            status = TodoStatus.Completed,
            percentComplete = 100,
        )
        val exported = exportCalendar(events = emptyList(), todos = listOf(orphan), now = now)
        assertTrue(exported.contains("BEGIN:VTODO\r\n"), exported)
        assertFalse(exported.contains("RECURRENCE-ID"), exported)
    }
}
