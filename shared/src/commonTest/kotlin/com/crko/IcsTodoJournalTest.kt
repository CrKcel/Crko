package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.RelatedTo
import com.crko.domain.TodoStatus
import com.crko.domain.exportCalendar
import com.crko.domain.importCalendar
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
}
