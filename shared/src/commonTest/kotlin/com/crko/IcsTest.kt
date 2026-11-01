package com.crko

import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.IcsWarning
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.exportCalendar
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.importIcs
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsTest {

    private val utc = TimeZone.UTC
    private val shanghai = TimeZone.of("Asia/Shanghai")
    private val now = LocalDateTime(2026, 10, 27, 9, 0).toInstant(utc)

    private val baseEvent = Event(
        id = "e1",
        title = "Team sync",
        date = "2026-10-27",
        startMinutes = 9 * 60,
        endMinutes = 10 * 60,
    )

    private fun calendar(vararg events: String): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        events.forEach { append(it.trimIndent().replace("\n", "\r\n")).append("\r\n") }
        append("END:VCALENDAR\r\n")
    }

    @Test
    fun exportWritesFloatingTimesAndCrlf() {
        val text = exportIcs(listOf(baseEvent), now)
        assertEquals(
            "BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//Crko//Crko Calendar//EN\r\n" +
                "CALSCALE:GREGORIAN\r\n" +
                "BEGIN:VEVENT\r\n" +
                "UID:e1@crko\r\n" +
                "DTSTAMP:20261027T090000Z\r\n" +
                "DTSTART:20261027T090000\r\n" +
                "DTEND:20261027T100000\r\n" +
                "SUMMARY:Team sync\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR\r\n",
            text,
        )
    }

    @Test
    fun exportEscapesTextAndWritesRecurrence() {
        val text = exportIcs(
            listOf(
                baseEvent.copy(
                    note = "Line one\nLine two; with, comma",
                    repeat = RepeatRule(RepeatFrequency.Custom, listOf(0, 2, 4)),
                    excludedDates = listOf("2026-11-02", "2026-10-28"),
                    icsUid = "external-1",
                ),
            ),
            now,
        )
        assertTrue(text.contains("UID:external-1\r\n"))
        assertTrue(text.contains("RRULE:FREQ=WEEKLY;BYDAY=MO,WE,FR\r\n"))
        assertTrue(text.contains("EXDATE:20261028T090000,20261102T090000\r\n"))
        assertTrue(text.contains("DESCRIPTION:Line one\\nLine two\\; with\\, comma\r\n"))
    }

    @Test
    fun longUtf8LinesFoldAtSeventyFiveOctetsAndUnfoldOnImport() {
        val note = "日程🚀".repeat(40)
        val text = exportIcs(listOf(baseEvent.copy(note = note)), now)
        assertTrue(text.split("\r\n").all { it.encodeToByteArray().size <= 75 })
        assertEquals(note, importIcs(text, utc, "Untitled").single().note)
    }

    @Test
    fun importParsesFloatingEventAndUnescapesText() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:abc-1
            SUMMARY:Standup
            DTSTART:20261027T090000
            DTEND:20261027T093000
            DESCRIPTION:Line one\nLine two
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(
            Event(
                id = null,
                title = "Standup",
                date = "2026-10-27",
                startMinutes = 540,
                endMinutes = 570,
                note = "Line one\nLine two",
                icsUid = "abc-1",
            ),
            event,
        )
    }

    @Test
    fun importConvertsUtcAndTzidTimesToTheDeviceZone() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:utc-1
            SUMMARY:UTC
            DTSTART:20261027T010000Z
            DTEND:20261027T023000Z
            END:VEVENT
            BEGIN:VEVENT
            UID:tz-1
            SUMMARY:Tzid
            DTSTART;TZID=Asia/Shanghai:20261027T090000
            DTEND;TZID=Asia/Shanghai:20261027T100000
            END:VEVENT
            """,
        )
        val events = importIcs(text, shanghai, "Untitled")
        assertEquals(540, events[0].startMinutes)
        assertEquals(630, events[0].endMinutes)
        assertEquals("2026-10-27", events[0].date)
        assertEquals(540, events[1].startMinutes)
        assertEquals(600, events[1].endMinutes)
    }

    @Test
    fun importMapsWeeklyByDayAndExcludedDates() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:series-1
            SUMMARY:Class
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=WEEKLY;BYDAY=TU,TH
            EXDATE:20261029T090000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(RepeatRule(RepeatFrequency.Custom, listOf(1, 3)), event.repeat)
        assertEquals(listOf("2026-10-29"), event.excludedDates)
    }

    @Test
    fun importKeepsBoundedDailySeriesAsOneSeries() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:count-1
            SUMMARY:Meditation
            DTSTART:20261027T090000
            DTEND:20261027T093000
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(3, event.repeat?.count)
        assertTrue(event.occursOn(LocalDate(2026, 10, 27)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 28)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 29)))
        assertTrue(!event.occursOn(LocalDate(2026, 10, 30)))
    }

    @Test
    fun importKeepsUntilAndIntervalOnTheSeries() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:interval-1
            SUMMARY:Every other day
            DTSTART:20261027T090000
            DTEND:20261027T093000
            RRULE:FREQ=DAILY;INTERVAL=2;UNTIL=20261031T090000Z
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(2, event.repeat?.interval)
        assertEquals("2026-10-31T09:00:00Z", event.repeat?.until)
        assertTrue(event.occursOn(LocalDate(2026, 10, 27)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 29)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 31)))
        assertTrue(!event.occursOn(LocalDate(2026, 11, 2)))
    }

    @Test
    fun importKeepsAllDayRangeOnOneRecord() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:holiday
            SUMMARY:Holiday
            DTSTART;VALUE=DATE:20261027
            DTEND;VALUE=DATE:20261029
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertTrue(event.allDay)
        assertEquals("2026-10-27", event.date)
        assertEquals("2026-10-28", event.endDate)
        assertTrue(event.occursOn(LocalDate(2026, 10, 27)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 28)))
        assertTrue(!event.occursOn(LocalDate(2026, 10, 29)))
    }

    @Test
    fun importAllowsAnEventToEndAtMidnight() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:late
            SUMMARY:Late
            DTSTART:20261027T220000
            DURATION:PT2H
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(22 * 60, event.startMinutes)
        assertEquals(0, event.endMinutes)
        assertEquals("2026-10-28", event.endDate)
        assertTrue(event.occursOn(LocalDate(2026, 10, 27)))
        assertTrue(!event.occursOn(LocalDate(2026, 10, 28)))
    }

    @Test
    fun importAppliesRecurrenceOverridesAndExcludesTheOriginalSlot() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:series-2
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY
            END:VEVENT
            BEGIN:VEVENT
            UID:series-2
            RECURRENCE-ID:20261028T090000
            SUMMARY:Daily (moved)
            DTSTART:20261028T110000
            DTEND:20261028T120000
            END:VEVENT
            """,
        )
        val events = importIcs(text, utc, "Untitled")
        assertEquals(2, events.size)
        assertEquals(RepeatRule(RepeatFrequency.Daily), events[0].repeat)
        assertEquals(listOf("2026-10-28"), events[0].excludedDates)
        assertEquals("Daily (moved)", events[1].title)
        assertEquals("2026-10-28", events[1].date)
        assertEquals(660, events[1].startMinutes)
        assertEquals("series-2#override-2026-10-28", events[1].icsUid)
    }

    @Test
    fun importSkipsCancelledEventsButKeepsTheSkipOnTheSeries() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:series-3
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY
            END:VEVENT
            BEGIN:VEVENT
            UID:series-3
            RECURRENCE-ID:20261028T090000
            STATUS:CANCELLED
            END:VEVENT
            """,
        )
        val events = importIcs(text, utc, "Untitled")
        assertEquals(1, events.size)
        assertEquals(listOf("2026-10-28"), events.single().excludedDates)
    }

    @Test
    fun importKeepsMonthlySeries() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:monthly
            SUMMARY:Monthly
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=MONTHLY;COUNT=5
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("2026-10-27", event.date)
        assertEquals(RepeatFrequency.Monthly, event.repeat?.frequency)
        assertTrue(event.occursOn(LocalDate(2026, 11, 27)))
        assertTrue(!event.occursOn(LocalDate(2027, 4, 27)))
        assertNull(event.endDate)
    }

    @Test
    fun blankSummaryUsesTheProvidedFallback() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:no-title
            DTSTART:20261027T090000
            DTEND:20261027T100000
            END:VEVENT
            """,
        )
        assertEquals("无标题日程", importIcs(text, utc, "无标题日程").single().title)
    }

    @Test
    fun exportImportRoundTripPreservesEventsAndSeries() {
        val original = listOf(
            baseEvent.copy(
                repeat = RepeatRule(RepeatFrequency.Weekly),
                excludedDates = listOf("2026-11-03"),
                icsUid = "uid-1",
            ),
            baseEvent.copy(
                id = "e2",
                title = "每天, 会议; 讨论",
                date = "2026-10-28",
                startMinutes = 0,
                endMinutes = MaxEndMinutes,
                note = "first\nsecond\\third",
                repeat = RepeatRule(RepeatFrequency.Custom, listOf(0, 3, 5)),
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
    fun foldedSummaryAndQuotedTzidAreUnfoldedAndParsed() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:folded
            SUMMARY:Quarterly planning workshop with the
              entire product team
            DTSTART;TZID="Asia/Shanghai":20261027T090000
            DTEND;TZID="Asia/Shanghai":20261027T173000
            END:VEVENT
            """,
        )
        val event = importIcs(text, shanghai, "Untitled").single()
        assertEquals(
            "Quarterly planning workshop with the entire product team",
            event.title,
        )
        assertEquals(540, event.startMinutes)
        assertEquals(17 * 60 + 30, event.endMinutes)
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
    fun exportAllDayUsesDateValuesAndAnExclusiveEnd() {
        val event = Event(
            id = "a1",
            title = "Trip",
            date = "2026-10-27",
            startMinutes = 0,
            endMinutes = 0,
            endDate = "2026-10-28",
            allDay = true,
        )
        val text = exportIcs(listOf(event), now)
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261027\r\n"), text)
        assertTrue(text.contains("DTEND;VALUE=DATE:20261029\r\n"), text)
        val back = importIcs(text, utc, "Untitled").single()
        assertTrue(back.allDay)
        assertEquals("2026-10-28", back.endDate)
    }

    @Test
    fun midnightEndRoundTripsAsTheNextDay() {
        val event = Event(
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
    fun tzidSourceIsPreservedWithoutASyntheticTimezone() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:berlin
            SUMMARY:Berlin
            DTSTART;TZID=Europe/Berlin:20261027T090000
            DTEND;TZID=Europe/Berlin:20261027T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("Europe/Berlin", event.timeZoneId)
        assertEquals("20261027T090000", event.sourceStart)
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("DTSTART;TZID=Europe/Berlin:20261027T090000\r\n"), exported)
        // A synthetic fixed-offset block would be wrong across DST; clients resolve the IANA ID.
        assertFalse(exported.contains("BEGIN:VTIMEZONE"), exported)
    }

    @Test
    fun customVtimezoneOffsetIsResolved() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:Custom/Plus3
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0300
            TZOFFSETTO:+0300
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:custom-zone
            SUMMARY:Custom zone
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DTEND;TZID=Custom/Plus3:20261027T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(6 * 60, event.startMinutes)
        assertEquals(7 * 60, event.endMinutes)
        assertEquals("Custom/Plus3", event.timeZoneId)
        assertTrue(event.timeZoneDefinition?.contains("TZOFFSETTO:+0300") == true)
    }

    @Test
    fun rdateWithoutRruleBecomesOneOffEventsWithTheSameDuration() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:rdate-only
            SUMMARY:Standalone
            DTSTART:20261027T090000
            DTEND:20261027T103000
            RDATE:20261105T140000
            END:VEVENT
            """,
        )
        val events = importIcs(text, utc, "Untitled")
        assertEquals(2, events.size)
        val extra = events.first { it.date == "2026-11-05" }
        assertEquals(14 * 60, extra.startMinutes)
        assertEquals(15 * 60 + 30, extra.endMinutes)
        assertEquals("rdate-only#rdate-0", extra.icsUid)
    }

    @Test
    fun rdateIsPreservedAsAnExtraOccurrence() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:rdate
            SUMMARY:Extra
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;UNTIL=20261028T090000Z
            RDATE:20261105T090000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(listOf("2026-11-05"), event.repeat?.rDates)
        assertTrue(event.occursOn(LocalDate(2026, 11, 5)))
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("RDATE:20261105T090000\r\n"), exported)
    }

    @Test
    fun importMapsExtendedPropertiesAndAlarms() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:rich
            SUMMARY:Rich
            DTSTART:20261027T090000
            DTEND:20261027T100000
            LOCATION:Room 4
            CATEGORIES:Work,Focus
            STATUS:TENTATIVE
            TRANSP:TRANSPARENT
            CLASS:PRIVATE
            PRIORITY:1
            URL:https://example.com/x
            ORGANIZER:mailto:host@example.com
            ATTENDEE:mailto:guest@example.com
            CREATED:20261001T080000Z
            LAST-MODIFIED:20261002T080000Z
            SEQUENCE:4
            X-CUSTOM:kept
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Heads up
            END:VALARM
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("Room 4", event.location)
        assertEquals(listOf("Work", "Focus"), event.categories)
        assertEquals(EventStatus.Tentative, event.status)
        assertEquals(EventTransparency.Transparent, event.transparency)
        assertEquals(1, event.priority)
        assertEquals("https://example.com/x", event.url)
        assertEquals("mailto:host@example.com", event.organizer)
        assertEquals(listOf("mailto:guest@example.com"), event.attendees)
        assertEquals(4, event.sequence)
        assertEquals(15, event.alarms.single().triggerMinutes)
        assertTrue(event.extraProperties.any { it.startsWith("X-CUSTOM") })
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("LOCATION:Room 4\r\n"), exported)
        assertTrue(exported.contains("CATEGORIES:Work,Focus\r\n"), exported)
        assertTrue(exported.contains("STATUS:TENTATIVE\r\n"), exported)
        assertTrue(exported.contains("TRANSP:TRANSPARENT\r\n"), exported)
        assertTrue(exported.contains("CLASS:PRIVATE\r\n"), exported)
        assertTrue(exported.contains("PRIORITY:1\r\n"), exported)
        assertTrue(exported.contains("SEQUENCE:4\r\n"), exported)
        assertTrue(exported.contains("BEGIN:VALARM\r\n"), exported)
        assertTrue(exported.contains("TRIGGER:-PT15M\r\n"), exported)
        assertTrue(exported.contains("X-CUSTOM:kept\r\n"), exported)
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
        assertEquals("20261027T083000Z", event.alarms[1].absoluteTrigger)
        assertTrue(event.alarms[0].extraProperties.any { it.startsWith("X-WR-ALARMUID") })

        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("TRIGGER:+PT15M\r\n"), exported)
        assertTrue(exported.contains("TRIGGER;VALUE=DATE-TIME:20261027T083000Z\r\n"), exported)
        assertTrue(exported.contains("X-WR-ALARMUID:alarm-1\r\n"), exported)
    }

    @Test
    fun organizerAndAttendeeParametersArePreserved() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:people
            SUMMARY:Meeting
            DTSTART:20261027T090000
            DTEND:20261027T100000
            ORGANIZER;CN=Host:mailto:host@example.com
            ATTENDEE;CN=Guest;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:guest@example.com
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("mailto:host@example.com", event.organizer)
        assertEquals(listOf("mailto:guest@example.com"), event.attendees)

        val exported = exportIcs(listOf(event), now).replace("\r\n ", "")
        assertTrue(
            exported.contains("ORGANIZER;CN=Host:mailto:host@example.com\r\n"),
            exported,
        )
        assertTrue(
            exported.contains("ATTENDEE;CN=Guest;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED:mailto:guest@example.com\r\n"),
            exported,
        )
    }

    @Test
    fun multipleRrulesKeepTheFirstAndReportAWarning() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:two-rules
            SUMMARY:Two
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=2
            RRULE:FREQ=WEEKLY;COUNT=2
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(RepeatFrequency.Daily, imported.events.single().repeat?.frequency)
        assertTrue(IcsWarning.MultipleRrules in imported.warnings)
    }

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
        assertEquals("2026-11-01", todo.due)
        assertEquals(TodoPriority.High, todo.priority)
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
        assertEquals("2026-10-31T12:34:56Z", todo.completedTime)
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        assertTrue(exported.contains("COMPLETED:20261031T123456Z\r\n"), exported)
    }

    @Test
    fun multiDayAllDaySeriesKeepsItsSpan() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:offsite
            SUMMARY:Offsite
            DTSTART;VALUE=DATE:20261027
            DTEND;VALUE=DATE:20261029
            RRULE:FREQ=WEEKLY;COUNT=2
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertTrue(event.allDay)
        assertEquals("2026-10-28", event.endDate)
        assertEquals(2, event.repeat?.count)
        assertTrue(event.occursOn(LocalDate(2026, 10, 28)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 3)))
        assertTrue(event.occursOn(LocalDate(2026, 11, 4)))
        assertTrue(!event.occursOn(LocalDate(2026, 11, 5)))
    }

    @Test
    fun thisAndFutureOverrideSplitsTheSeriesAndRoundTrips() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:split
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=10
            END:VEVENT
            BEGIN:VEVENT
            UID:split
            RECURRENCE-ID;RANGE=THISANDFUTURE:20261030T090000
            SUMMARY:Later
            DTSTART:20261030T110000
            DTEND:20261030T120000
            RRULE:FREQ=DAILY
            END:VEVENT
            """,
        )
        val events = importIcs(text, utc, "Untitled")
        assertEquals(2, events.size)
        val oldSeries = events.first { it.title == "Daily" }
        assertEquals("2026-10-29", oldSeries.repeat?.until)
        assertTrue(oldSeries.occursOn(LocalDate(2026, 10, 29)))
        assertFalse(oldSeries.occursOn(LocalDate(2026, 10, 30)))
        val future = events.first { it.title == "Later" }
        assertTrue(future.thisAndFuture)
        assertEquals("2026-10-30", future.date)
        assertEquals(11 * 60, future.startMinutes)
        assertEquals("split", future.seriesUid)
        assertTrue(future.occursOn(LocalDate(2026, 11, 5)))

        val exported = exportIcs(events, now)
        // Split segments are exported as two independent series, so every RECURRENCE-ID in the
        // file belongs to a master that still contains that instance.
        assertFalse(exported.contains("RANGE=THISANDFUTURE"), exported)
        assertTrue(exported.contains("UID:split#future-2026-10-30\r\n"), exported)
        val reparsed = importIcs(exported, utc, "Untitled")
        assertEquals(2, reparsed.size)
        val later = reparsed.first { it.title == "Later" }
        assertTrue(later.repeat != null)
        assertFalse(later.thisAndFuture)
        assertTrue(later.occursOn(LocalDate(2026, 11, 5)))
    }

    @Test
    fun thisAndFutureWithoutOwnRuleKeepsTheOriginalCount() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:counted-split
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=10
            END:VEVENT
            BEGIN:VEVENT
            UID:counted-split
            RECURRENCE-ID;RANGE=THISANDFUTURE:20261030T090000
            SUMMARY:Later
            DTSTART:20261030T110000
            DTEND:20261030T120000
            END:VEVENT
            """,
        )
        val future = importIcs(text, utc, "Untitled").first { it.title == "Later" }
        assertEquals(7, future.repeat?.count)
        assertTrue(future.occursOn(LocalDate(2026, 11, 5)))
        assertFalse(future.occursOn(LocalDate(2026, 11, 6)))
    }

    @Test
    fun thisAndFutureKeepsExdatesAfterTheSplit() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:excluded-split
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=10
            EXDATE:20261101T090000
            END:VEVENT
            BEGIN:VEVENT
            UID:excluded-split
            RECURRENCE-ID;RANGE=THISANDFUTURE:20261030T090000
            SUMMARY:Later
            DTSTART:20261030T110000
            DTEND:20261030T120000
            END:VEVENT
            """,
        )
        val future = importIcs(text, utc, "Untitled").first { it.title == "Later" }
        assertFalse(future.occursOn(LocalDate(2026, 11, 1)))
        assertTrue(future.occursOn(LocalDate(2026, 11, 2)))
    }

    @Test
    fun exceptionAfterThisAndFutureBelongsToTheFutureSeries() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:future-override
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=10
            END:VEVENT
            BEGIN:VEVENT
            UID:future-override
            RECURRENCE-ID;RANGE=THISANDFUTURE:20261030T090000
            SUMMARY:Later
            DTSTART:20261030T110000
            DTEND:20261030T120000
            END:VEVENT
            BEGIN:VEVENT
            UID:future-override
            RECURRENCE-ID:20261102T090000
            SUMMARY:Moved
            DTSTART:20261103T140000
            DTEND:20261103T150000
            END:VEVENT
            """,
        )
        val events = importIcs(text, utc, "Untitled")
        val future = events.first { it.title == "Later" }
        val moved = events.first { it.title == "Moved" }
        assertEquals(future.uid, moved.seriesUid)
        assertTrue("2026-11-02" in future.excludedDates)
        assertTrue(events.none { it.title == "Later" && it.occursOn(LocalDate(2026, 11, 2)) })
        assertEquals(1, events.count { it.occursOn(LocalDate(2026, 11, 3)) && it.title == "Moved" })
        assertEquals(future.uid, moved.uid?.substringBefore("#override-"))
    }

    @Test
    fun cancelledThisAndFutureCapsTheFutureSegment() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:cancelled-future
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=10
            END:VEVENT
            BEGIN:VEVENT
            UID:cancelled-future
            RECURRENCE-ID;RANGE=THISANDFUTURE:20261030T090000
            SUMMARY:Later
            DTSTART:20261030T110000
            DTEND:20261030T120000
            END:VEVENT
            BEGIN:VEVENT
            UID:cancelled-future
            RECURRENCE-ID;RANGE=THISANDFUTURE:20261103T090000
            STATUS:CANCELLED
            END:VEVENT
            """,
        )
        val future = importIcs(text, utc, "Untitled").first { it.title == "Later" }
        assertTrue(future.occursOn(LocalDate(2026, 11, 2)))
        assertFalse(future.occursOn(LocalDate(2026, 11, 3)))
    }

    @Test
    fun allDayExceptionUsesADateRecurrenceId() {
        val series = Event(
            id = "all-day-series",
            title = "Holiday",
            date = "2026-10-27",
            startMinutes = 0,
            endMinutes = 0,
            allDay = true,
            repeat = RepeatRule(RepeatFrequency.Weekly),
            icsUid = "all-day-series",
        )
        val exception = Event(
            id = "all-day-override",
            title = "Holiday moved",
            date = "2026-10-28",
            startMinutes = 0,
            endMinutes = 0,
            allDay = true,
            seriesUid = "all-day-series",
            recurrenceId = "2026-11-03",
            icsUid = "all-day-series#override",
        )
        val exported = exportIcs(listOf(series, exception), now)
        assertTrue(exported.contains("RECURRENCE-ID;VALUE=DATE:20261103\r\n"), exported)
        assertFalse(exported.contains("RECURRENCE-ID:20261103T"), exported)
    }

    @Test
    fun invalidTimeZoneFallsBackToFloatingWithAWarning() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:bad-zone
            SUMMARY:Bad zone
            DTSTART;TZID=Not/AZone:20261027T090000
            DTEND;TZID=Not/AZone:20261027T100000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(9 * 60, imported.events.single().startMinutes)
        assertTrue(IcsWarning.UnresolvedTimeZone in imported.warnings)
    }

    @Test
    fun allDayRdateAndExdateUseDateValues() {
        val event = Event(
            id = "all-day-rule",
            title = "Weekly",
            date = "2026-10-27",
            startMinutes = 0,
            endMinutes = 0,
            allDay = true,
            repeat = RepeatRule(
                frequency = RepeatFrequency.Weekly,
                rDates = listOf("2026-11-05"),
            ),
            excludedDates = listOf("2026-11-03"),
        )
        val text = exportIcs(listOf(event), now)
        assertTrue(text.contains("RDATE;VALUE=DATE:20261105\r\n"), text)
        assertTrue(text.contains("EXDATE;VALUE=DATE:20261103\r\n"), text)
    }

    @Test
    fun floatingUntilKeepsTheTimeOfDayOnImportAndExport() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:until-time
            SUMMARY:Until time
            DTSTART:20261025T100000
            DTEND:20261025T110000
            RRULE:FREQ=DAILY;UNTIL=20261027T090000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("2026-10-27T09:00", event.repeat?.until)
        assertTrue(event.occursOn(LocalDate(2026, 10, 26)))
        assertFalse(event.occursOn(LocalDate(2026, 10, 27)))
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("UNTIL=20261027T090000\r\n"), exported)
    }

    @Test
    fun utcUntilIsExportedAsTheSameInstant() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:until-instant
            SUMMARY:Until instant
            DTSTART:20261025T100000Z
            DTEND:20261025T110000Z
            RRULE:FREQ=DAILY;UNTIL=20261027T090000Z
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("UNTIL=20261027T090000Z\r\n"), exported)
    }
}
