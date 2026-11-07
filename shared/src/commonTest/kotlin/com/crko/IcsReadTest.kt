package com.crko

import com.crko.domain.CalAddress
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RecurrenceDate
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.importIcs
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.occurrenceDurationOn
import com.crko.domain.occurrenceRangesOn
import com.crko.domain.occursOn
import com.crko.domain.triggerMinutes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant

class IcsReadTest : IcsTestBase() {

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
            testEvent(
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
        assertEquals(LocalDateTime(2026, 10, 27, 9, 0), event.localStart(shanghai))
        assertEquals(LocalDateTime(2026, 10, 27, 17, 30), event.localEnd(shanghai))
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
        assertEquals(CalAddress("mailto:host@example.com"), event.organizer)
        assertEquals(listOf(CalAddress("mailto:guest@example.com")), event.attendees)
        assertEquals(4, event.sequence)
        assertEquals(15, event.alarms.single().triggerMinutes)
        assertTrue(event.extraProperties.any { it.startsWith("X-CUSTOM") })
        // Unmodeled properties must ride along on the export even though the
        // modeled ones above are re-derived from the record.
        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("X-CUSTOM:kept\r\n"), exported)
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
        assertEquals(CalAddress("mailto:host@example.com", cn = "Host"), event.organizer)
        assertEquals(
            listOf(CalAddress("mailto:guest@example.com", cn = "Guest", role = "REQ-PARTICIPANT", partStat = com.crko.domain.IcsPartStat.Accepted)),
            event.attendees,
        )

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
    fun anUnknownStatusYieldsToAUserChosenOne() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:x-enum
            SUMMARY:Extended
            DTSTART:20261027T090000
            DTEND:20261027T100000
            STATUS:X-VACATION
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        val exported = exportIcs(listOf(event.copy(status = EventStatus.Tentative)), now)
        assertTrue(exported.contains("STATUS:TENTATIVE\r\n"), exported)
        // The preserved X- value must not appear next to the line the user's choice produced.
        assertFalse(exported.contains("X-VACATION"), exported)
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
        assertNotNull(series.recurrence?.rdates?.single())
        // The PERIOD's own duration overrides the series duration on that day.
        assertEquals(30, series.occurrenceDurationOn(LocalDate(2026, 11, 5)).inWholeMinutes)
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
    fun fractionalDurationsAreWeighedNotMisread() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:fractional
            SUMMARY:Fractional
            DTSTART:20261027T090000
            DURATION:PT90.5M
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertFalse(IcsWarning.InvalidValue in imported.warnings, imported.warnings.toString())
        val event = imported.events.single()
        // 90.5 minutes is 90 minutes 30 seconds; the digits after the point used to be read as
        // another whole unit, stretching the event to 95 minutes.
        assertEquals(LocalDateTime(2026, 10, 27, 10, 30, 30), event.localEnd(utc))
    }

    @Test
    fun aLeapSecondRollsIntoTheNextMinute() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:leap
            SUMMARY:Leap
            DTSTART:20261231T235960Z
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertFalse(IcsWarning.InvalidValue in imported.warnings, imported.warnings.toString())
        assertEquals(
            IcsTimeValue.Utc(LocalDateTime(2027, 1, 1, 0, 0, 0).toInstant(utc)),
            imported.events.single().start,
        )
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
    fun quotedPrintableCalendarValuesAreDecoded() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:qp\r\n" +
            "SUMMARY;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:=E6=97=A5=E7=A8=8B\r\n" +
            "DTSTART:20261027T090000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        assertEquals("日程", importIcs(text, utc, "Untitled").single().title)
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
}
