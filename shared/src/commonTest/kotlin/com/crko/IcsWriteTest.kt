package com.crko

import com.crko.domain.CalAddress
import com.crko.domain.CalendarTimeZone
import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceDate
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.exportCalendar
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.importIcs
import com.crko.domain.localStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class IcsWriteTest : IcsTestBase() {

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
    fun exportAllDayUsesDateValuesAndAnExclusiveEnd() {
        val event = testEvent(
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
    fun tzidParameterValuesAreQuotedWhenTheyNeedIt() {
        // A TZID containing a parameter separator must be quoted on the way out, or the next reader
        // splits the head of the line and loses the value.
        val definition = """
            BEGIN:VTIMEZONE
            TZID:Custom,Zone
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0300
            TZOFFSETTO:+0300
            END:STANDARD
            END:VTIMEZONE
        """.trimIndent()
        val zone = "Custom,Zone"
        val event = baseEvent.copy(
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), zone),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 10, 0), zone),
            recurrence = Recurrence(
                rdates = listOf(RecurrenceDate(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 29, 14, 0), zone))),
            ),
        )
        val exported = exportCalendar(
            listOf(event),
            now = now,
            timeZones = listOf(CalendarTimeZone(zone, definition)),
        )
        assertTrue(exported.contains("DTSTART;TZID=\"$zone\":20261027T090000\r\n"), exported)
        assertTrue(exported.contains("RDATE;TZID=\"$zone\":20261029T140000\r\n"), exported)
        val back = importCalendar(exported, utc, "Untitled")
        assertFalse(IcsWarning.UnresolvedTimeZone in back.warnings, back.warnings.toString())
        assertEquals(6 * 60, back.events.single().localStart(utc).let { it.hour * 60 + it.minute })
        assertEquals(
            Recurrence(rdates = listOf(RecurrenceDate(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 29, 14, 0), zone)))),
            back.events.single().recurrence,
        )
    }

    @Test
    fun uriValuedPropertiesKeepCommasAndSemicolonsLiteral() {
        val event = baseEvent.copy(
            url = "https://example.com/d?team=a,b;ref=1",
            organizer = CalAddress("mailto:host@example.com?subject=A;B"),
            attendees = listOf(CalAddress("mailto:guest@example.com?opt=1,2")),
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
}
