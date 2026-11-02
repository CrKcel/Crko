package com.crko

import com.crko.domain.Attachment
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.IcsWarning
import com.crko.domain.MaxEndMinutes
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceDate
import com.crko.domain.RecurrenceRange
import com.crko.domain.RepeatFrequency
import com.crko.domain.IcsTimeValue
import com.crko.domain.RepeatRule
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.exportCalendar
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.importIcs
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.occurrenceDurationOn
import com.crko.domain.occurrenceRangesOn
import com.crko.domain.occursOn
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.plusDays
import com.crko.domain.safeAttachmentFileName
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
                    recurrence = Recurrence(
                        rule = RepeatRule(
                            RepeatFrequency.Weekly,
                            byDay = listOf(0, 2, 4).map { OrdinalWeekday(0, it) },
                        ),
                        exdates = listOf("2026-11-02", "2026-10-28").map { IcsTimeValue.Date(LocalDate.parse(it)) },
                    ),
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
                sourceLine = 3,
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
        assertEquals(
            RepeatRule(
                RepeatFrequency.Weekly,
                byDay = listOf(OrdinalWeekday(0, 1), OrdinalWeekday(0, 3)),
            ),
            event.repeat,
        )
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
        assertEquals(
            IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-10-31T09:00:00Z")),
            event.repeat?.until,
        )
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
        // The exception shares the master's UID and is distinguished by its typed slot.
        assertEquals("series-2", events[1].icsUid)
        assertEquals(
            IcsTimeValue.Floating(LocalDateTime(2026, 10, 28, 9, 0)),
            events[1].recurrenceId,
        )
    }

    @Test
    fun aCancelledExceptionIsKeptWithItsStatusAndSkipsTheSlot() {
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
        assertEquals(2, events.size)
        val master = events.first { it.recurrenceId == null }
        assertEquals(listOf("2026-10-28"), master.excludedDates)
        val cancelled = events.first { it.recurrenceId != null }
        assertEquals(EventStatus.Cancelled, cancelled.status)
        assertEquals(master.icsUid, cancelled.icsUid)
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
        assertEquals(6 * 60, event.localStart(utc).hour * 60 + event.localStart(utc).minute)
        assertEquals(7 * 60, event.localEnd(utc)?.let { it.hour * 60 + it.minute })
        assertEquals("Custom/Plus3", event.timeZoneId)
        assertTrue(event.timeZoneDefinition?.contains("TZOFFSETTO:+0300") == true)
    }

    @Test
    fun rdateWithoutRruleStaysAnAddedOccurrenceWithTheSeriesDuration() {
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
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(
            IcsTimeValue.Floating(LocalDateTime(2026, 11, 5, 14, 0)),
            event.recurrence?.rdates?.single()?.value,
        )
        assertTrue(event.occursOn(LocalDate(2026, 11, 5)))
        assertEquals(90, event.occurrenceDurationOn(LocalDate(2026, 11, 5)).inWholeMinutes)
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
        assertEquals(
            listOf(IcsTimeValue.Floating(LocalDateTime(2026, 11, 5, 9, 0))),
            event.recurrence?.rdates?.map { it.value },
        )
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
        assertEquals("2026-11-01", todo.due)
        assertEquals("2026-11-01T09:30:00Z", todo.dueTime)
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
    fun thisAndFutureStaysAnExceptionAndRoundTripsItsRange() {
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
        val master = events.first { it.title == "Daily" }
        assertEquals(10, master.repeat?.count)
        assertTrue(master.occursOn(LocalDate(2026, 10, 29)))
        assertFalse(master.occursOn(LocalDate(2026, 10, 30)))
        val future = events.first { it.title == "Later" }
        assertTrue(future.thisAndFuture)
        assertEquals("2026-10-30", future.date)
        assertEquals(11 * 60, future.startMinutes)
        assertEquals(master.uid, future.icsUid)
        assertEquals(
            IcsTimeValue.Floating(LocalDateTime(2026, 10, 30, 9, 0)),
            future.recurrenceId,
        )

        val exported = exportIcs(events, now)
        // The range is written back instead of being split into a synthetic second series.
        assertTrue(exported.contains("RECURRENCE-ID;RANGE=THISANDFUTURE:20261030T090000"), exported)
        assertFalse(exported.contains("#future-"), exported)
        val reparsed = importIcs(exported, utc, "Untitled")
        assertEquals(2, reparsed.size)
        val later = reparsed.first { it.title == "Later" }
        assertTrue(later.thisAndFuture)
        assertEquals(master.uid, later.icsUid)
    }

    @Test
    fun thisAndFutureWithoutOwnRuleKeepsTheMasterCount() {
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
        val events = importIcs(text, utc, "Untitled")
        val master = events.first { it.title == "Daily" }
        val future = events.first { it.title == "Later" }
        assertEquals(10, master.repeat?.count)
        assertTrue(future.thisAndFuture)
        // The master still describes every instance; the exception replaces its own slot only.
        assertTrue(master.occursOn(LocalDate(2026, 11, 5)))
    }

    @Test
    fun thisAndFutureKeepsTheMasterExdates() {
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
        val events = importIcs(text, utc, "Untitled")
        val master = events.first { it.title == "Daily" }
        assertFalse(master.occursOn(LocalDate(2026, 11, 1)))
        assertTrue(master.occursOn(LocalDate(2026, 11, 2)))
    }

    @Test
    fun exceptionAfterThisAndFutureSharesTheSameUid() {
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
        val master = events.first { it.title == "Daily" }
        val future = events.first { it.title == "Later" }
        val moved = events.first { it.title == "Moved" }
        // Master, THISANDFUTURE exception and ordinary exception all share the RFC UID.
        assertEquals(master.uid, future.icsUid)
        assertEquals(master.uid, moved.icsUid)
        assertTrue("2026-11-02" in master.excludedDates)
        assertFalse(master.occursOn(LocalDate(2026, 11, 2)))
        assertEquals(1, events.count { it.occursOn(LocalDate(2026, 11, 3)) && it.title == "Moved" })
    }

    @Test
    fun cancelledThisAndFutureIsKeptAsACancelledException() {
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
        val events = importIcs(text, utc, "Untitled")
        val master = events.first { it.title == "Daily" }
        val cancelled = events.first { it.status == EventStatus.Cancelled }
        assertEquals(RecurrenceRange.ThisAndFuture, cancelled.recurrenceRange)
        // The cancelled copy keeps its SEQUENCE-carrying identity, and the master skips its slot.
        assertFalse(master.occursOn(LocalDate(2026, 11, 3)))
        assertTrue(master.occursOn(LocalDate(2026, 11, 2)))
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
            start = IcsTimeValue.Date(LocalDate(2026, 10, 28)),
            end = IcsTimeValue.Date(LocalDate(2026, 10, 29)),
            icsUid = "all-day-series",
            recurrenceId = IcsTimeValue.Date(LocalDate(2026, 11, 3)),
        )
        val exported = exportIcs(listOf(series, exception), now)
        assertTrue(exported.contains("RECURRENCE-ID;VALUE=DATE:20261103\r\n"), exported)
        assertFalse(exported.contains("RECURRENCE-ID:20261103T"), exported)
    }

    @Test
    fun windowsNamesAndDomainPrefixedTzidsResolveToIanaZones() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:windows-zone
            SUMMARY:Windows name
            DTSTART;TZID=China Standard Time:20261027T090000
            DTEND;TZID=China Standard Time:20261027T100000
            END:VEVENT
            BEGIN:VEVENT
            UID:prefixed-zone
            SUMMARY:Prefixed id
            DTSTART;TZID=/mozilla.org/20050126_1/America/New_York:20261027T090000
            DTEND;TZID=/mozilla.org/20050126_1/America/New_York:20261027T100000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertFalse(IcsWarning.UnresolvedTimeZone in imported.warnings, imported.warnings.toString())
        val windows = imported.events.first { it.icsUid == "windows-zone" }
        assertEquals("Asia/Shanghai", windows.timeZoneId)
        assertEquals(60, windows.localStart(utc).hour * 60 + windows.localStart(utc).minute)
        val prefixed = imported.events.first { it.icsUid == "prefixed-zone" }
        assertEquals("America/New_York", prefixed.timeZoneId)
        assertEquals(13 * 60, prefixed.localStart(utc).hour * 60 + prefixed.localStart(utc).minute)
        assertEquals("20261027T090000", prefixed.sourceStart)
    }

    @Test
    fun rdatePeriodsKeepTheirOwnDuration() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:rdate-periods
            SUMMARY:Periods
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RDATE;VALUE=PERIOD:20261105T140000Z/PT1H,20261106T090000Z/20261106T120000Z
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        val rdates = event.recurrence?.rdates.orEmpty()
        assertEquals(2, rdates.size)
        assertEquals(
            IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-11-05T14:00:00Z")),
            rdates[0].value,
        )
        assertEquals(60, rdates[0].duration?.inWholeMinutes)
        assertEquals(
            IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-11-06T09:00:00Z")),
            rdates[1].value,
        )
        assertEquals(180, rdates[1].duration?.inWholeMinutes)
        assertEquals(60, event.occurrenceDurationOn(LocalDate(2026, 11, 5)).inWholeMinutes)
        assertEquals(180, event.occurrenceDurationOn(LocalDate(2026, 11, 6)).inWholeMinutes)
    }

    @Test
    fun recurringRdatePeriodKeepsItsOwnLength() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:rdate-period-series
            SUMMARY:Series
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=2
            RDATE;VALUE=PERIOD:20261105T140000Z/PT30M
            END:VEVENT
            """,
        )
        val series = importIcs(text, utc, "Untitled").single()
        val period = series.recurrence?.rdates?.single()
        assertNotNull(period)
        assertEquals(IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-11-05T14:00:00Z")), period.value)
        assertEquals(30, period.duration?.inWholeMinutes)
        assertEquals(30, series.occurrenceDurationOn(LocalDate(2026, 11, 5)).inWholeMinutes)
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
            start = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
            end = IcsTimeValue.Date(LocalDate(2026, 10, 28)),
            recurrence = Recurrence(
                rule = RepeatRule(RepeatFrequency.Weekly),
                rdates = listOf(RecurrenceDate(IcsTimeValue.Date(LocalDate(2026, 11, 5)))),
                exdates = listOf(IcsTimeValue.Date(LocalDate(2026, 11, 3))),
            ),
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
        assertEquals(IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 9, 0)), event.repeat?.until)
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

    @Test
    fun exportedTimezoneDeclarationMatchesTheIdThePropertiesReference() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:/MSYSZ1-2
            BEGIN:STANDARD
            DTSTART:19700101T030000
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:slashed-zone
            SUMMARY:Slashed
            DTSTART;TZID=/MSYSZ1-2:20261027T090000
            DTEND;TZID=/MSYSZ1-2:20261027T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("MSYSZ1-2", event.timeZoneId)
        val exported = exportIcs(listOf(event), now)
        // The block used to keep its leading slash while the property lost it, leaving a TZID
        // reference nothing in the file could resolve.
        assertTrue(exported.contains("TZID:MSYSZ1-2\r\n"), exported)
        assertTrue(exported.contains("DTSTART;TZID=MSYSZ1-2:20261027T090000\r\n"), exported)
        val back = importCalendar(exported, utc, "Untitled")
        assertFalse(IcsWarning.UnresolvedTimeZone in back.warnings, back.warnings.toString())
        assertEquals(event.startMinutes, back.events.single().startMinutes)
    }

    @Test
    fun uriValuedPropertiesKeepCommasAndSemicolonsLiteral() {
        val event = baseEvent.copy(
            url = "https://example.com/d?team=a,b;ref=1",
            organizer = "mailto:host@example.com?subject=A;B",
            attendees = listOf("mailto:guest@example.com?opt=1,2"),
            geo = "37.386013;-122.082932",
        )
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("URL:https://example.com/d?team=a,b;ref=1\r\n"), exported)
        assertTrue(exported.contains("ORGANIZER:mailto:host@example.com?subject=A;B\r\n"), exported)
        assertTrue(exported.contains("ATTENDEE:mailto:guest@example.com?opt=1,2\r\n"), exported)
        assertTrue(exported.contains("GEO:37.386013;-122.082932\r\n"), exported)
        // TEXT properties still escape, so the two must not be confused.
        assertTrue(exported.contains("SUMMARY:Team sync\r\n"), exported)
    }

    @Test
    fun controlCharactersAreStrippedFromUriValues() {
        val exported = exportIcs(listOf(baseEvent.copy(url = "https://e.co/a\r\nEVIL:yes")), now)
        // Removing CR/LF is what keeps the value from becoming an extra content line.
        assertFalse(exported.split("\r\n").any { it == "EVIL:yes" }, exported)
        assertEquals("URL:https://e.co/aEVIL:yes", exported.split("\r\n").first { it.startsWith("URL:") })
    }

    @Test
    fun previouslyEscapedUrlsStillImportUnescaped() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:old-escape
            SUMMARY:Old
            DTSTART:20261027T090000
            URL:https://example.com/a\,b
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("https://example.com/a,b", event.url)
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
    fun exruleIsKeptButReportedAsIgnored() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:exrule
            SUMMARY:Old exclusion rule
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=2
            EXRULE:FREQ=WEEKLY;COUNT=1
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertTrue(IcsWarning.ExruleIgnored in imported.warnings, imported.warnings.toString())
        val exported = exportIcs(imported.events, now)
        assertTrue(exported.contains("EXRULE:FREQ=WEEKLY;COUNT=1\r\n"), exported)
    }

    @Test
    fun unknownSubcomponentWarnsWithoutDroppingTheEvent() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:with-venue
            SUMMARY:At a venue
            DTSTART:20261027T090000
            DTEND:20261027T100000
            BEGIN:VVENUE
            UID:venue-1
            END:VVENUE
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(1, imported.events.size)
        assertTrue(IcsWarning.UnknownSubcomponent in imported.warnings, imported.warnings.toString())
    }

    @Test
    fun exdateWithAnotherTimeOfDayIsReported() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:exdate-mismatch
            SUMMARY:Mismatched exclusion
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE:20261028T080000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val event = imported.events.single()
        assertTrue(IcsWarning.ExdateMismatch in imported.warnings, imported.warnings.toString())
        assertTrue(event.excludedDates.isEmpty(), event.excludedDates.toString())
    }

    @Test
    fun aTimedExdateOnlySkipsThatStartOfTheDay() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:twice-daily-exdate
            SUMMARY:Rounds
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;BYHOUR=9,17
            EXDATE:20261028T170000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(
            listOf(IcsTimeValue.Floating(LocalDateTime(2026, 10, 28, 17, 0))),
            event.recurrence?.exdates,
        )
        // The 17:00 instance is gone, the 09:00 one on the same day stays.
        assertEquals(listOf(9 * 60 to 10 * 60), event.occurrenceRangesOn(LocalDate(2026, 10, 28)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 28)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 29)))
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("EXDATE:20261028T170000\r\n"), exported)
    }

    @Test
    fun exportsNeverWriteSyntheticSeriesUids() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:native-series
            SUMMARY:Daily
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            BEGIN:VEVENT
            UID:native-series
            RECURRENCE-ID:20261028T090000
            SUMMARY:Moved
            DTSTART:20261028T110000
            DTEND:20261028T120000
            END:VEVENT
            """,
        )
        val exported = exportIcs(importIcs(text, utc, "Untitled"), now)
        listOf("#override-", "#future-", "#prior-").forEach { marker ->
            assertFalse(exported.contains(marker), "found $marker\n$exported")
        }
        assertEquals(2, exported.split("\r\n").count { it == "UID:native-series" })
        assertTrue(exported.contains("RECURRENCE-ID:20261028T090000\r\n"), exported)
    }

    @Test
    fun exportWritesTheCalendarName() {
        val exported = exportCalendar(listOf(baseEvent), emptyList(), now, calendarName = "Crko")
        assertTrue(exported.contains("X-WR-CALNAME:Crko\r\n"), exported)
    }

    @Test
    fun ordinalBydayIsDroppedForWeeklyRulesWithAWarning() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:weekly-ordinal
            SUMMARY:Weekly ordinal
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=WEEKLY;BYDAY=MO,2TU
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val event = imported.events.single()
        assertTrue(IcsWarning.UnsupportedRecurrence in imported.warnings, imported.warnings.toString())
        assertEquals(listOf(OrdinalWeekday(0, 0)), event.repeat?.byDay)
        val exported = exportIcs(listOf(event), now)
        // RFC 5545 gives an ordinal no meaning for WEEKLY, and other clients reject the rule.
        assertTrue(exported.contains("RRULE:FREQ=WEEKLY;BYDAY=MO\r\n"), exported)
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
    fun importSynthesizesAnEndSourceSoTheZonesNeverMix() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:mixed-zone
            SUMMARY:Mixed
            DTSTART;TZID=Europe/Berlin:20261027T090000
            DTEND:20261027T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("Europe/Berlin", event.timeZoneId)
        // Import derives the end text from the duration, so an absolute start is never paired with
        // a floating end on the way out.
        assertEquals("20261027T100000", event.sourceEnd)
        assertTrue(exportIcs(listOf(event), now).contains("DTEND;TZID=Europe/Berlin:20261027T100000\r\n"))
    }

    @Test
    fun zoneAwareEndIsWrittenWhenStoredDataHasNoEndSource() {
        // Reachable through the tolerant state decoder, which can restore a record whose end text is
        // missing; RFC 5545 forbids mixing an absolute DTSTART with a floating DTEND.
        val event = baseEvent.copy(
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Europe/Berlin"),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 10, 0), "Europe/Berlin"),
        )
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("DTSTART;TZID=Europe/Berlin:20261027T090000\r\n"), exported)
        assertTrue(exported.contains("DTEND;TZID=Europe/Berlin:20261027T100000\r\n"), exported)
        assertFalse(exported.contains("DTEND:20261027T100000\r\n"), exported)
    }

    @Test
    fun exceptionWithoutItsMasterIsExportedAsAStandaloneEvent() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:orphan
            RECURRENCE-ID:20261029T090000
            DTSTART:20261029T110000
            DTEND:20261029T120000
            SUMMARY:Orphan
            END:VEVENT
            """,
        )
        val events = importIcs(text, utc, "Untitled")
        assertEquals(1, events.size)
        val exported = exportIcs(events, now)
        // The referenced series is not in this file, so the id would dangle for every receiver.
        assertFalse(exported.contains("RECURRENCE-ID"), exported)
        assertTrue(exported.contains("DTSTART:20261029T110000\r\n"), exported)
    }

    @Test
    fun aStrayEndLineDoesNotDiscardTheEventsAfterIt() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:after-stray\r\nSUMMARY:Kept\r\nDTSTART:20261027T090000\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("Kept", event.title)
    }

    @Test
    fun theNewestCopyOfADuplicatedUidWinsWhicheverOrderTheFileUses() {
        val older = "BEGIN:VEVENT\r\nUID:dup\r\nDTSTAMP:20261001T000000Z\r\nSEQUENCE:1\r\n" +
            "DTSTART:20261027T090000\r\nSUMMARY:Stale\r\nEND:VEVENT\r\n"
        val newer = "BEGIN:VEVENT\r\nUID:dup\r\nDTSTAMP:20261020T000000Z\r\nSEQUENCE:4\r\n" +
            "DTSTART:20261028T090000\r\nSUMMARY:Current\r\nEND:VEVENT\r\n"
        val first = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$older$newer" +
            "BEGIN:VEVENT\r\nUID:dup\r\nRECURRENCE-ID:20261030T090000\r\nDTSTART:20261030T110000\r\nSUMMARY:Moved\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val last = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n$newer$older" +
            "BEGIN:VEVENT\r\nUID:dup\r\nRECURRENCE-ID:20261030T090000\r\nDTSTART:20261030T110000\r\nSUMMARY:Moved\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        for (text in listOf(first, last)) {
            val events = importIcs(text, utc, "Untitled")
            val master = events.first { it.recurrenceId == null }
            assertEquals("Current", master.title, text)
            assertEquals("2026-10-28", master.date, text)
            assertEquals(4, master.sequence, text)
            // The stale copy must not leak in as an extra event or an exception.
            assertEquals(2, events.size, text)
        }
    }

    @Test
    fun aStaleCancelledCopyDoesNotWipeTheLiveSeries() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:cancel-order
            DTSTAMP:20261001T000000Z
            SEQUENCE:1
            STATUS:CANCELLED
            DTSTART:20261027T090000
            SUMMARY:Dead copy
            END:VEVENT
            BEGIN:VEVENT
            UID:cancel-order
            DTSTAMP:20261020T000000Z
            SEQUENCE:2
            STATUS:CONFIRMED
            DTSTART:20261027T090000
            SUMMARY:Live
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("Live", event.title)
    }

    @Test
    fun theNewestCancelledCopyStillRemovesTheSeries() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:cancel-last
            DTSTAMP:20261001T000000Z
            SEQUENCE:1
            STATUS:CONFIRMED
            DTSTART:20261027T090000
            SUMMARY:Live
            END:VEVENT
            BEGIN:VEVENT
            UID:cancel-last
            DTSTAMP:20261020T000000Z
            SEQUENCE:5
            STATUS:CANCELLED
            DTSTART:20261027T090000
            SUMMARY:Cancelled for real
            END:VEVENT
            """,
        )
        assertTrue(importIcs(text, utc, "Untitled").isEmpty())
    }

    @Test
    fun theImportReportsEveryUidTheFileDescribes() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:event-1
            DTSTART:20261027T090000
            DTEND:20261027T100000
            SUMMARY:Live
            END:VEVENT
            BEGIN:VEVENT
            UID:event-1
            DTSTAMP:20261020T000000Z
            SEQUENCE:5
            STATUS:CANCELLED
            DTSTART:20261027T090000
            SUMMARY:Cancelled
            END:VEVENT
            BEGIN:VTODO
            UID:todo-1
            SUMMARY:Task
            DUE;VALUE=DATE:20261101
            END:VTODO
            BEGIN:VJOURNAL
            UID:journal-1
            DTSTART;VALUE=DATE:20261027
            DESCRIPTION:Note
            END:VJOURNAL
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(setOf("event-1", "todo-1", "journal-1"), imported.describedUids)
        // A cancelled series is still described: declining its removal in the preview must not
        // turn the skipped choice into a removal through the vanished-record pass.
        assertEquals(setOf("event-1"), imported.cancelledUids)
    }

    @Test
    fun twoChangesOnOneDateAreDistinguishedByTheirFullRecurrenceId() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:twice-daily
            SUMMARY:Series
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=5
            END:VEVENT
            BEGIN:VEVENT
            UID:twice-daily
            RECURRENCE-ID:20261029T090000
            DTSTART:20261029T110000
            DTEND:20261029T120000
            SUMMARY:LaterA
            END:VEVENT
            BEGIN:VEVENT
            UID:twice-daily
            RECURRENCE-ID:20261029T130000
            DTSTART:20261029T150000
            DTEND:20261029T160000
            SUMMARY:LaterB
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val events = imported.events
        assertEquals(3, events.size)
        // Both exceptions share the UID; their full RECURRENCE-ID values keep them apart.
        assertEquals(setOf("twice-daily"), events.mapNotNull { it.icsUid }.toSet())
        val slots = events.mapNotNull { it.recurrenceId }
        assertEquals(2, slots.size)
        assertEquals(slots.size, slots.distinct().size, slots.toString())
        assertFalse(IcsWarning.AmbiguousRecurrenceId in imported.warnings, imported.warnings.toString())
        val reparsed = importCalendar(exportIcs(events, now), utc, "Untitled")
        assertEquals(3, reparsed.events.size)
        assertEquals(2, reparsed.events.mapNotNull { it.recurrenceId }.distinct().size)
    }

    @Test
    fun eventsWithoutAUsableStartAreReported() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:hyphen
            SUMMARY:Hyphenated
            DTSTART;VALUE=DATE:2026-10-27
            END:VEVENT
            BEGIN:VEVENT
            UID:no-start
            SUMMARY:No start at all
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertTrue(imported.events.isEmpty())
        assertTrue(
            imported.warningDetails.any {
                it.warning == IcsWarning.InvalidValue && it.record == "Hyphenated"
            },
            imported.warningDetails.toString(),
        )
        assertTrue(IcsWarning.MissingStart in imported.warnings, imported.warnings.toString())
    }

    @Test
    fun byHourExpandsTheDayIntoSeveralStarts() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:twice-a-day
            SUMMARY:Rounds
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=4;BYHOUR=9,17
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val event = imported.events.single()
        assertEquals(listOf(9, 17), event.repeat?.byHour)
        // The rule is honoured: the day holds both rounds, each keeping the anchor's length. COUNT
        // stays day-granular like the rest of the model, so the series covers four days.
        assertFalse(IcsWarning.UnsupportedRecurrence in imported.warnings, imported.warnings.toString())
        assertEquals(
            listOf(9 * 60 to 10 * 60, 17 * 60 to 18 * 60),
            event.occurrenceRangesOn(LocalDate(2026, 10, 28)),
        )
        assertFalse(event.occursOn(LocalDate(2026, 10, 31)))
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
        assertEquals(listOf("2026-10-12"), todo.excludedDates)
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

    @Test
    fun unsupportedFrequencyKeepsItsRawRuleForExport() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:hourly
            SUMMARY:Payroll
            DTSTART:20261027T090000
            DTEND:20261027T093000
            RRULE:FREQ=HOURLY;COUNT=3
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertTrue(IcsWarning.UnsupportedRecurrence in imported.warnings, imported.warnings.toString())
        val event = imported.events.single()
        assertNull(event.repeat)
        assertEquals("FREQ=HOURLY;COUNT=3", event.recurrence?.rawRule)
        assertTrue(exportIcs(imported.events, now).contains("RRULE:FREQ=HOURLY;COUNT=3"))
    }

    @Test
    fun xWrTimezoneInterpretsFloatingTimesInThatZone() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nX-WR-TIMEZONE:Europe/Berlin\r\n" +
            "BEGIN:VEVENT\r\nUID:xwr\r\nSUMMARY:Floating\r\nDTSTART:20261027T090000\r\n" +
            "DTEND:20261027T100000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals("Europe/Berlin", event.timeZoneId)
        // 27 October 2026 is after the DST change, so Berlin is UTC+1.
        assertEquals(8 * 60, event.localStart(utc).hour * 60 + event.localStart(utc).minute)
        assertEquals(9 * 60, event.localEnd(utc)?.let { it.hour * 60 + it.minute })
        assertEquals("20261027T090000", event.sourceStart)
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("DTSTART;TZID=Europe/Berlin:20261027T090000\r\n"), exported)
        assertTrue(exported.contains("DTEND;TZID=Europe/Berlin:20261027T100000\r\n"), exported)
    }

    @Test
    fun customZoneRdateTransitionsAndSecondOffsetsAreApplied() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:Custom/Rdate
            BEGIN:STANDARD
            DTSTART:20260101T000000
            TZOFFSETFROM:+0000
            TZOFFSETTO:+0000
            END:STANDARD
            BEGIN:STANDARD
            DTSTART:20300101T000000
            TZOFFSETFROM:+0000
            TZOFFSETTO:+010030
            RDATE:20261027T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:rdate-zone
            SUMMARY:Shifted
            DTSTART;TZID=Custom/Rdate:20261027T090000
            DTEND;TZID=Custom/Rdate:20261027T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        // The RDATE transition applies +01:00:30 before the observance's own 2030 DTSTART.
        assertEquals(7 * 60 + 59, event.localStart(utc).hour * 60 + event.localStart(utc).minute)
        assertEquals(30, event.localStart(utc).second)
        assertEquals(8 * 60 + 59, event.localEnd(utc)?.let { it.hour * 60 + it.minute })
        assertEquals(30, event.localEnd(utc)?.second)
    }

    @Test
    fun observanceUntilAndCountStopTransitions() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:Custom/Bounded
            BEGIN:STANDARD
            DTSTART:20200101T000000
            TZOFFSETFROM:+0000
            TZOFFSETTO:+0000
            RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:20200101T000000
            TZOFFSETFROM:+0000
            TZOFFSETTO:+0100
            RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU;UNTIL=20200330T000000Z
            END:DAYLIGHT
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:bounded-zone
            SUMMARY:Summer
            DTSTART;TZID=Custom/Bounded:20260701T120000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        // UNTIL ended the daylight transitions in 2020; an ignored bound would add March 2026
        // and move this event one hour earlier.
        assertEquals(12 * 60, event.localStart(utc).hour * 60 + event.localStart(utc).minute)
    }

    @Test
    fun thisAndPriorIsKeptAsACompatibilityException() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:prior
            SUMMARY:Base
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=5
            END:VEVENT
            BEGIN:VEVENT
            UID:prior
            RECURRENCE-ID;RANGE=THISANDPRIOR:20261029T090000
            DTSTART:20261029T090000
            DTEND:20261029T100000
            SUMMARY:Past
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val events = imported.events
        assertEquals(2, events.size, events.toString())
        val past = events.first { it.title == "Past" }
        assertEquals(RecurrenceRange.ThisAndPrior, past.recurrenceRange)
        assertEquals(
            IcsTimeValue.Floating(LocalDateTime(2026, 10, 29, 9, 0)),
            past.recurrenceId,
        )
        // THISANDPRIOR is not RFC 5545, so the import says so while keeping the range for export.
        assertTrue(IcsWarning.UnsupportedRecurrence in imported.warnings, imported.warnings.toString())
        val master = events.first { it.title == "Base" }
        assertTrue(master.occursOn(LocalDate(2026, 10, 31)))
        assertFalse(master.occursOn(LocalDate(2026, 10, 29)))
        assertTrue(
            exportIcs(events, now).contains("RECURRENCE-ID;RANGE=THISANDPRIOR:20261029T090000"),
        )
    }

    @Test
    fun thisAndPriorExceptionKeepsItsTimeZoneForTheNextExport() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:prior-zone
            SUMMARY:Base
            DTSTART;TZID=Asia/Shanghai:20261027T090000
            DTEND;TZID=Asia/Shanghai:20261027T100000
            RRULE:FREQ=DAILY;COUNT=5
            END:VEVENT
            BEGIN:VEVENT
            UID:prior-zone
            RECURRENCE-ID;RANGE=THISANDPRIOR;TZID=Asia/Shanghai:20261029T090000
            DTSTART;TZID=Asia/Shanghai:20261029T100000
            DTEND;TZID=Asia/Shanghai:20261029T110000
            SUMMARY:Past
            END:VEVENT
            """,
        )
        val events = importCalendar(text, utc, "Untitled").events
        val past = events.first { it.title == "Past" }
        val master = events.first { it.title == "Base" }
        // The exception and the master keep the zone they were read in.
        assertEquals("Asia/Shanghai", past.timeZoneId)
        assertEquals("20261029T100000", past.sourceStart)
        assertEquals("20261029T110000", past.sourceEnd)
        assertEquals("Asia/Shanghai", master.timeZoneId)
        assertEquals("20261027T090000", master.sourceStart)
        val exported = exportIcs(events, now)
        assertTrue(exported.contains("DTSTART;TZID=Asia/Shanghai:20261029T100000\r\n"), exported)
        assertTrue(exported.contains("DTSTART;TZID=Asia/Shanghai:20261027T090000\r\n"), exported)
        // The round trip keeps the wall clock of both records in the same zone.
        val back = importCalendar(exported, utc, "Untitled").events
        assertEquals(past.date to past.startMinutes, back.first { it.title == "Past" }.let { it.date to it.startMinutes })
        assertEquals(master.date to master.startMinutes, back.first { it.title == "Base" }.let { it.date to it.startMinutes })
        assertEquals("20261029T100000", back.first { it.title == "Past" }.sourceStart)
        assertEquals("20261027T090000", back.first { it.title == "Base" }.sourceStart)
    }

    @Test
    fun aCancelledMasterIsListedForThePreview() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:gone
            DTSTAMP:20261027T090000Z
            SEQUENCE:2
            STATUS:CANCELLED
            SUMMARY:Cancelled series
            DTSTART:20261027T090000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(0, imported.events.size)
        assertEquals(setOf("gone"), imported.cancelledUids)
        val cancelled = imported.cancelled.single()
        assertEquals("gone", cancelled.uid)
        assertEquals("Cancelled series", cancelled.summary)
        assertEquals(3, cancelled.line)
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
        assertEquals("2026-10-26", todo.start)
        assertEquals("Office", todo.location)
        assertEquals(listOf("Work", "Reports"), todo.categories)
        assertEquals("https://example.com/report", todo.url)
        assertEquals(listOf("parent-todo"), todo.relatedTo)
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
        // Only the default PARENT relation drives the sub-task hierarchy.
        assertEquals(listOf("parent-todo"), todo.relatedTo)
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        // The other relations keep their parameter instead of being re-written as parent links.
        assertTrue(exported.contains("RELATED-TO;RELTYPE=CHILD:child-todo\r\n"), exported)
        assertTrue(exported.contains("RELATED-TO;RELTYPE=SIBLING:sibling-todo\r\n"), exported)
        val back = importCalendar(exported, utc, "Untitled").todos.single()
        assertEquals(todo.relatedTo, back.relatedTo)
        assertEquals(
            todo.extraProperties.filter { it.startsWith("RELATED-TO") },
            back.extraProperties.filter { it.startsWith("RELATED-TO") },
        )
    }

    @Test
    fun quotedPrintableCalendarValuesAreDecoded() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:qp\r\n" +
            "SUMMARY;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:=E6=97=A5=E7=A8=8B\r\n" +
            "DTSTART:20261027T090000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals("日程", importIcs(text, utc, "Untitled").single().title)
    }

    @Test
    fun attachmentsAreParsedExportedAndShownByName() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:attach
            SUMMARY:Trip
            DTSTART:20261027T090000
            ATTACH;FMTTYPE=image/png;FILENAME=ticket.png:https://example.com/ticket.png
            ATTACH;ENCODING=BASE64;FMTTYPE=text/plain:VGVzdA==
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(2, event.attachments.size, event.attachments.toString())
        assertEquals("ticket.png", event.attachments[0].name)
        assertEquals("https://example.com/ticket.png", event.attachments[0].uri)
        assertEquals("image/png", event.attachments[0].mimeType)
        assertFalse(event.attachments[0].binary)
        assertTrue(event.attachments[1].binary)
        assertEquals("VGVzdA==", event.attachments[1].data)
        assertEquals("text/plain", event.attachments[1].mimeType)
        val exported = exportIcs(listOf(event), now)
        assertEquals(2, exported.split("ATTACH").size - 1, exported)
        val back = importIcs(exported, utc, "Untitled").single()
        assertEquals(event.attachments, back.attachments)
    }

    @Test
    fun todoAndJournalResolveTheirTimesAgainstTheFilesOwnVtimezone() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:TESTZONE
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0800
            TZOFFSETTO:+0800
            TZNAME:CST
            END:STANDARD
            END:VTIMEZONE
            """,
            """
            BEGIN:VTODO
            UID:todo-zone
            SUMMARY:Zone due
            DUE;TZID=TESTZONE:20261101T180000
            DTSTART;TZID=TESTZONE:20261030T180000
            END:VTODO
            BEGIN:VJOURNAL
            UID:journal-zone
            SUMMARY:Notes
            DTSTART;TZID=TESTZONE:20261101T180000
            END:VJOURNAL
            """,
        )
        val import = importCalendar(text, utc, "Untitled")
        assertEquals("2026-11-01T10:00:00Z", import.todos.single().dueTime)
        assertEquals("2026-10-30T10:00:00Z", import.todos.single().startTime)
        assertEquals("2026-11-01T10:00:00Z", import.journals.single().startTime)
    }

    @Test
    fun aFloatingTodoTimeBelongsToTheDeviceRatherThanToUtc() {
        val newYork = TimeZone.of("America/New_York")
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo-floating
            SUMMARY:Floating due
            DUE:20261101T040000
            END:VTODO
            """,
        )
        val todo = importCalendar(text, newYork, "Untitled").todos.single()
        assertEquals("2026-11-01", todo.due)
        // Reading the same record back must land on the same day: stamping the wall clock as UTC
        // would turn 04:00 into the previous evening and move the deadline.
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        val back = importCalendar(exported, newYork, "Untitled").todos.single()
        assertEquals(todo.due, back.due)
        assertEquals(todo.dueTime, back.dueTime)
    }

    @Test
    fun quotedPrintableFoldingWithATrailingEqualsIsJoinedBeforeDecoding() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:qp-fold\r\n" +
            "SUMMARY;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:=E6=97=A5=\r\n=E7=A8=8B\r\n" +
            "DTSTART:20261027T090000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals("日程", importIcs(text, utc, "Untitled").single().title)
    }

    @Test
    fun cachedTimezoneTransitionsCoverTheObservancesOwnAnchorYear() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:CACHE
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0000
            TZOFFSETTO:+0000
            TZNAME:WT
            RRULE:FREQ=MONTHLY;BYMONTHDAY=15
            END:STANDARD
            BEGIN:DAYLIGHT
            DTSTART:19700110T000000
            TZOFFSETFROM:+0000
            TZOFFSETTO:+0100
            TZNAME:X
            END:DAYLIGHT
            END:VTIMEZONE
            """,
            """
            BEGIN:VEVENT
            UID:cache-1970
            SUMMARY:Anchor year
            DTSTART;TZID=CACHE:19700120T120000
            END:VEVENT
            BEGIN:VEVENT
            UID:cache-1971
            SUMMARY:Next year
            DTSTART;TZID=CACHE:19710120T120000
            END:VEVENT
            """,
        )
        val events = importCalendar(text, utc, "Untitled").events
        // The zero-offset observance repeats on the 15th of every month, including January 1970, and
        // that transition is later than the daylight start of 1970-01-10. Dropping the anchor year
        // from the generated transitions would leave the +0100 start as the newest one.
        assertEquals(720, events.first { it.title == "Anchor year" }.localStart(utc).hour * 60 + events.first { it.title == "Anchor year" }.localStart(utc).minute)
        assertEquals(720, events.first { it.title == "Next year" }.localStart(utc).hour * 60 + events.first { it.title == "Next year" }.localStart(utc).minute)
    }

    @Test
    fun warningDetailsStayBoundedAndNameTheRecord() {
        val blocks = (1..300).map { index ->
            "BEGIN:VEVENT\r\nUID:no-start-$index\r\nSUMMARY:Broken $index\r\nEND:VEVENT\r\n"
        }
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + blocks.joinToString("") + "END:VCALENDAR\r\n"
        val import = importCalendar(text, utc, "Untitled")
        assertTrue(import.warningDetails.isNotEmpty(), import.warnings.toString())
        assertTrue(import.warningDetails.size <= 20, import.warningDetails.size.toString())
        assertEquals(1, import.warningDetails.distinctBy { it.warning }.size)
        assertNotNull(import.warningDetails.first().record)
        assertTrue(import.warnings.contains(IcsWarning.MissingStart), import.warnings.toString())
    }

    @Test
    fun anOpenEndedZoneStopsGeneratingAtTheHorizonAndKeepsTheLastOffset() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:HORIZON
            BEGIN:DAYLIGHT
            DTSTART:19700401T000000
            TZOFFSETFROM:+0100
            TZOFFSETTO:+0200
            TZNAME:D
            RRULE:FREQ=YEARLY;BYMONTH=4;BYMONTHDAY=1
            END:DAYLIGHT
            BEGIN:STANDARD
            DTSTART:19701001T000000
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            TZNAME:S
            RRULE:FREQ=YEARLY;BYMONTH=10;BYMONTHDAY=1
            END:STANDARD
            END:VTIMEZONE
            """,
            """
            BEGIN:VEVENT
            UID:horizon
            SUMMARY:Far future
            DTSTART;TZID=HORIZON:30001201T120000
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        // A rule without UNTIL or COUNT would otherwise generate three thousand years of
        // transitions; the last one before the horizon (+0100) is what the value keeps.
        assertEquals(660, event.localStart(utc).hour * 60 + event.localStart(utc).minute)
    }

    @Test
    fun attachmentNamesNeverCarryAPath() {
        assertEquals("Secrets.pdf", safeAttachmentFileName(Attachment(name = "../../Documents/Secrets.pdf")))
        assertEquals("config", safeAttachmentFileName(Attachment(name = "..\\..\\windows\\config")))
        assertEquals("note.txt", safeAttachmentFileName(Attachment(name = "/etc/note.txt")))
        assertEquals("attachment", safeAttachmentFileName(Attachment(name = "....")))
        assertEquals("a".repeat(80), safeAttachmentFileName(Attachment(name = "a".repeat(400))))
        assertEquals("report.pdf", safeAttachmentFileName(Attachment(uri = "https://x.test/a/b/report.pdf")))
        val cleaned = safeAttachmentFileName(Attachment(name = "evil\u0000name"))
        assertTrue(cleaned.none { it.code < 0x20 }, cleaned)
    }
}
