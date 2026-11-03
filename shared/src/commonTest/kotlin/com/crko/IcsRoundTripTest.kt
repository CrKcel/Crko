package com.crko

import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RelatedTo
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.TodoStatus
import com.crko.domain.absoluteTrigger
import com.crko.domain.exportCalendar
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.importIcs
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.occursOn
import com.crko.domain.triggerMinutes
import com.crko.migration.legacyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class IcsRoundTripTest : IcsTestBase() {

    @Test
    fun exportImportRoundTripPreservesEventsAndSeries() {
        val original = listOf(
            baseEvent.copy(
                recurrence = Recurrence(
                    rule = RepeatRule(RepeatFrequency.Weekly),
                    exdates = listOf(IcsTimeValue.Floating(LocalDateTime(2026, 11, 3, 9, 0))),
                ),
                icsUid = "uid-1",
            ),
            baseEvent.copy(
                id = "e2",
                title = "每天, 会议; 讨论",
                start = IcsTimeValue.Floating(LocalDateTime(2026, 10, 28, 0, 0)),
                end = IcsTimeValue.Floating(LocalDateTime(2026, 10, 28, 23, 59)),
                note = "first\nsecond\\third",
                recurrence = Recurrence(
                    rule = RepeatRule(
                        RepeatFrequency.Weekly,
                        byDay = listOf(0, 3, 5).map { OrdinalWeekday(0, it) },
                    ),
                ),
                icsUid = null,
            ),
        )
        val text = exportIcs(original, now)
        val parsed = importIcs(text, utc, "Untitled")
        assertEquals(2, parsed.size)
        assertEquals(original[0].title, parsed[0].title)
        assertEquals(original[0].date, parsed[0].date)
        assertEquals(original[0].startMinutes, parsed[0].startMinutes)
        assertEquals(original[0].endMinutes, parsed[0].endMinutes)
        assertEquals(original[0].repeat, parsed[0].repeat)
        assertEquals(original[0].excludedDates, parsed[0].excludedDates)
        assertEquals("uid-1", parsed[0].icsUid)
        assertEquals(original[1].title, parsed[1].title)
        assertEquals(original[1].note, parsed[1].note)
        assertEquals(original[1].repeat, parsed[1].repeat)
        assertEquals("e2@crko", parsed[1].icsUid)
    }

    @Test
    fun secondsArePreservedOnImportAndExport() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:seconds
            SUMMARY:Precise
            DTSTART:20261027T090045
            DTEND:20261027T103015
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(45, event.startSeconds)
        assertEquals(15, event.endSeconds)
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("DTSTART:20261027T090045\r\n"), exported)
        assertTrue(exported.contains("DTEND:20261027T103015\r\n"), exported)
        val back = importIcs(exported, utc, "Untitled").single()
        assertEquals(45, back.startSeconds)
        assertEquals(15, back.endSeconds)
    }

    @Test
    fun midnightEndRoundTripsAsTheNextDay() {
        val event = legacyEvent(
            id = "m1",
            title = "Late",
            date = "2026-10-27",
            startMinutes = 23 * 60,
            endMinutes = 0,
            endDate = "2026-10-28",
        )
        val text = exportIcs(listOf(event), now)
        assertTrue(text.contains("DTSTART:20261027T230000\r\n"), text)
        assertTrue(text.contains("DTEND:20261028T000000\r\n"), text)
        val back = importIcs(text, utc, "Untitled").single()
        assertEquals(0, back.endMinutes)
        assertEquals("2026-10-28", back.endDate)
        assertTrue(back.occursOn(LocalDate(2026, 10, 27)))
        assertTrue(!back.occursOn(LocalDate(2026, 10, 28)))
    }

    @Test
    fun positiveAndAbsoluteAlarmTriggersRoundTrip() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:alarms
            SUMMARY:Alarms
            DTSTART:20261027T090000
            DTEND:20261027T100000
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:+PT15M
            X-WR-ALARMUID:alarm-1
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;VALUE=DATE-TIME:20261027T083000Z
            END:VALARM
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(2, event.alarms.size)
        assertEquals(-15, event.alarms[0].triggerMinutes)
        assertEquals(
            IcsTimeValue.Utc(Instant.parse("2026-10-27T08:30:00Z")),
            event.alarms[1].absoluteTrigger,
        )
        assertTrue(event.alarms[0].extraProperties.any { it.startsWith("X-WR-ALARMUID") })

        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("TRIGGER:+PT15M\r\n"), exported)
        assertTrue(exported.contains("TRIGGER;VALUE=DATE-TIME:20261027T083000Z\r\n"), exported)
        assertTrue(exported.contains("X-WR-ALARMUID:alarm-1\r\n"), exported)
    }

    @Test
    fun unknownEnumValuesSurviveTheRoundTrip() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:x-enum
            SUMMARY:Extended
            DTSTART:20261027T090000
            DTEND:20261027T100000
            STATUS:X-VACATION
            CLASS:X-INTERNAL
            TRANSP:X-UNRELATED
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertFalse(IcsWarning.InvalidValue in imported.warnings, imported.warnings.toString())
        val event = imported.events.single()
        // The model maps them to its defaults; the raw lines carry the file's own values out again.
        assertEquals(EventStatus.Confirmed, event.status)
        assertNull(event.eventClass)
        assertEquals(EventTransparency.Opaque, event.transparency)
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("STATUS:X-VACATION\r\n"), exported)
        assertTrue(exported.contains("CLASS:X-INTERNAL\r\n"), exported)
        assertTrue(exported.contains("TRANSP:X-UNRELATED\r\n"), exported)
        assertEquals(1, Regex("^STATUS:", RegexOption.MULTILINE).findAll(exported).count())
        val back = importCalendar(exported, utc, "Untitled")
        assertTrue(back.events.single().extraProperties.any { it.startsWith("STATUS") })
    }

    @Test
    fun anUnknownTodoStatusSurvivesTheRoundTrip() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:x-todo
            SUMMARY:Extended todo
            DUE;VALUE=DATE:20261028
            STATUS:X-WAITING
            END:VTODO
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(TodoStatus.NeedsAction, imported.todos.single().status)
        val exported = exportCalendar(events = emptyList(), todos = imported.todos, now = now)
        assertTrue(exported.contains("STATUS:X-WAITING\r\n"), exported)
        assertFalse(exported.contains("STATUS:NEEDS-ACTION\r\n"), exported)
        val done = exportCalendar(
            events = emptyList(),
            todos = imported.todos.map { it.copy(status = TodoStatus.Completed) },
            now = now,
        )
        assertTrue(done.contains("STATUS:COMPLETED\r\n"), done)
        assertFalse(done.contains("X-WAITING"), done)
    }

    @Test
    fun textPropertyParamsRoundTrip() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:localized
            SUMMARY;LANGUAGE=de:Grüße
            DESCRIPTION;ALTREP="cid:part1":Ein Text
            LOCATION;LANGUAGE=de:Berlin
            DTSTART:20261027T090000
            DTEND:20261027T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("Grüße", event.title)
        assertEquals("Ein Text", event.note)
        assertEquals("Berlin", event.location)
        assertEquals("LANGUAGE=de", event.textParameters["SUMMARY"])
        assertEquals("ALTREP=\"cid:part1\"", event.textParameters["DESCRIPTION"])
        assertEquals("LANGUAGE=de", event.textParameters["LOCATION"])
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("SUMMARY;LANGUAGE=de:Grüße\r\n"), exported)
        assertTrue(exported.contains("LOCATION;LANGUAGE=de:Berlin\r\n"), exported)
        // Editing the text keeps the property's parameters with the new value.
        val edited = exportIcs(listOf(event.copy(title = "Neuer Titel")), now)
        assertTrue(edited.contains("SUMMARY;LANGUAGE=de:Neuer Titel\r\n"), edited)
    }

    @Test
    fun vtodoDueTimeAndAlarmRoundTrip() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo-timed
            SUMMARY:Call bank
            DUE:20261101T093000Z
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Reminder
            END:VALARM
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(IcsTimeValue.Utc(Instant.parse("2026-11-01T09:30:00Z")), todo.due)
        assertEquals(1, todo.alarms.size)
        assertEquals(30, todo.alarms.single().triggerMinutes)
        assertEquals("Reminder", todo.alarms.single().description)

        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("DUE:20261101T093000Z\r\n"), exported)
        assertTrue(exported.contains("BEGIN:VALARM\r\n"), exported)
        assertTrue(exported.contains("TRIGGER:-PT30M\r\n"), exported)
    }

    @Test
    fun vtodoInProcessAndPercentRoundTrip() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:in-process
            SUMMARY:Writing
            STATUS:IN-PROCESS
            PERCENT-COMPLETE:40
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(TodoStatus.InProcess, todo.status)
        assertEquals(40, todo.percentComplete)
        assertFalse(todo.done)

        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("STATUS:IN-PROCESS\r\n"), exported)
        assertTrue(exported.contains("PERCENT-COMPLETE:40\r\n"), exported)
        assertFalse(exported.contains("COMPLETED:"), exported)
    }

    @Test
    fun bysecondRoundTripsWithoutLosingItsValue() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:by-second
            SUMMARY:Second aligned
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=2;BYSECOND=0,30
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(listOf(0, 30), event.repeat?.bySecond)
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains(";BYSECOND=0,30\r\n"), exported)
    }

    @Test
    fun monthlyOrdinalBydayStillRoundTrips() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:monthly-ordinal
            SUMMARY:Last friday
            DTSTART:20261030T090000
            DTEND:20261030T100000
            RRULE:FREQ=MONTHLY;BYDAY=-1FR
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(listOf(com.crko.domain.OrdinalWeekday(-1, 4)), event.repeat?.byDay)
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("RRULE:FREQ=MONTHLY;BYDAY=-1FR\r\n"), exported)
    }

    @Test
    fun eventWithoutEndHasZeroDurationAndRoundTripsWithoutDtend() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:zero
            SUMMARY:Checkpoint
            DTSTART:20261027T090000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(540, event.startMinutes)
        assertEquals(540, event.endMinutes)
        val exported = exportIcs(listOf(event), now)
        assertFalse(exported.contains("DTEND"), exported)
        val back = importIcs(exported, utc, "Untitled").single()
        assertEquals(event.startMinutes, back.startMinutes)
        assertEquals(event.endMinutes, back.endMinutes)
    }

    @Test
    fun vjournalRoundTrips() {
        val text = calendar(
            """
            BEGIN:VJOURNAL
            UID:journal-1
            DTSTAMP:20261027T090000Z
            SUMMARY:Meeting notes
            DESCRIPTION:Decisions and follow-ups
            DTSTART;VALUE=DATE:20261027
            CATEGORIES:Work,Notes
            URL:https://example.com/notes
            END:VJOURNAL
            """,
        )
        val journal = importCalendar(text, utc, "Untitled").journals.single()
        assertEquals("journal-1", journal.icsUid)
        assertEquals("Meeting notes", journal.title)
        assertEquals("2026-10-27", journal.date)
        assertEquals(listOf("Work", "Notes"), journal.categories)
        val exported = exportCalendar(emptyList(), now = now, journals = listOf(journal))
        assertTrue(exported.contains("BEGIN:VJOURNAL\r\n"), exported)
        assertTrue(exported.contains("DTSTART;VALUE=DATE:20261027\r\n"), exported)
        val back = importCalendar(exported, utc, "Untitled").journals.single()
        assertEquals(journal.title, back.title)
        assertEquals(journal.note, back.note)
        assertEquals(journal.date, back.date)
    }

    @Test
    fun vtodoExtendedFieldsRoundTrip() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo-extended
            SUMMARY:Prepare report
            DTSTART;VALUE=DATE:20261026
            LOCATION:Office
            CATEGORIES:Work,Reports
            URL:https://example.com/report
            RELATED-TO:parent-todo
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 26)), todo.start)
        assertEquals("Office", todo.location)
        assertEquals(listOf("Work", "Reports"), todo.categories)
        assertEquals("https://example.com/report", todo.url)
        assertEquals(listOf(RelatedTo("parent-todo")), todo.relatedTo)
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("DTSTART;VALUE=DATE:20261026\r\n"), exported)
        assertTrue(exported.contains("LOCATION:Office\r\n"), exported)
        assertTrue(exported.contains("CATEGORIES:Work,Reports\r\n"), exported)
        assertTrue(exported.contains("RELATED-TO:parent-todo\r\n"), exported)
        val back = importCalendar(exported, utc, "Untitled").todos.single()
        assertEquals(todo.start, back.start)
        assertEquals(todo.location, back.location)
        assertEquals(todo.categories, back.categories)
        assertEquals(todo.relatedTo, back.relatedTo)
    }

    @Test
    fun todoRecurrenceImportsAndExports() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:weekly-report
            SUMMARY:Weekly report
            DTSTART;VALUE=DATE:20261005
            DUE;VALUE=DATE:20261006
            RRULE:FREQ=WEEKLY;BYDAY=MO
            RDATE;VALUE=DATE:20261224
            EXDATE;VALUE=DATE:20261012
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        val rule = todo.repeat
        assertEquals(RepeatFrequency.Weekly, rule?.frequency)
        assertEquals(listOf(OrdinalWeekday(0, 0)), rule?.byDay)
        assertEquals(
            listOf(IcsTimeValue.Date(LocalDate(2026, 12, 24))),
            todo.rDates.map { it.value },
        )
        assertEquals(listOf(IcsTimeValue.Date(LocalDate(2026, 10, 12))), todo.excludedDates)
        // The series expands under its due date, one day after each Monday start; the excluded
        // Monday disappears and the RDATE Thursday appears.
        assertTrue(todo.occursOn(LocalDate(2026, 10, 6)))
        assertFalse(todo.occursOn(LocalDate(2026, 10, 13)))
        assertTrue(todo.occursOn(LocalDate(2026, 10, 20)))
        assertTrue(todo.occursOn(LocalDate(2026, 12, 25)))
        assertEquals(LocalDate(2026, 12, 25), todo.nextOccurrenceDate(LocalDate(2026, 12, 24)))
        // …and the lines are written back when the calendar is exported.
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n"), exported)
        assertTrue(exported.contains("RDATE;VALUE=DATE:20261224\r\n"), exported)
        assertTrue(exported.contains("EXDATE;VALUE=DATE:20261012\r\n"), exported)
    }
}
