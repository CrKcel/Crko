package com.crko

import com.crko.domain.Event
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.exportIcs
import com.crko.domain.importIcs
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun importExpandsCountLimitedSeriesIntoOneOffEvents() {
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
        val events = importIcs(text, utc, "Untitled")
        assertEquals(listOf("2026-10-27", "2026-10-28", "2026-10-29"), events.map { it.date })
        assertTrue(events.all { it.repeat == null })
        assertEquals("count-1#2026-10-28", events[1].icsUid)
    }

    @Test
    fun importExpandsUntilAndIntervalBoundedSeries() {
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
        val events = importIcs(text, utc, "Untitled")
        assertEquals(listOf("2026-10-27", "2026-10-29", "2026-10-31"), events.map { it.date })
        assertTrue(events.all { it.repeat == null })
    }

    @Test
    fun importKeepsAllDayEventsAsFullDayGaps() {
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
        val events = importIcs(text, utc, "Untitled")
        assertEquals(listOf("2026-10-27", "2026-10-28"), events.map { it.date })
        assertTrue(events.all { it.startMinutes == 0 && it.endMinutes == MaxEndMinutes })
    }

    @Test
    fun importUsesDurationAndClampsAtMidnight() {
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
        assertEquals(MaxEndMinutes, event.endMinutes)
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
    fun unsupportedRecurrenceImportsOnlyTheFirstOccurrence() {
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
        assertNull(event.repeat)
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
}
