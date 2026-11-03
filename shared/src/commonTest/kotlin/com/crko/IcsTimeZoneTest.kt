package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.exportCalendar
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.importIcs
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.occursOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

class IcsTimeZoneTest : IcsTestBase() {

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
        assertEquals(LocalDateTime(2026, 10, 27, 9, 0), events[0].localStart(shanghai))
        assertEquals(LocalDateTime(2026, 10, 27, 10, 30), events[0].localEnd(shanghai))
        assertEquals(LocalDate(2026, 10, 27), events[0].localStart(shanghai).date)
        assertEquals(LocalDateTime(2026, 10, 27, 9, 0), events[1].localStart(shanghai))
        assertEquals(LocalDateTime(2026, 10, 27, 10, 0), events[1].localEnd(shanghai))
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
        val imported = importCalendar(text, utc, "Untitled")
        val event = imported.events.single()
        assertEquals(6 * 60, event.localStart(utc).hour * 60 + event.localStart(utc).minute)
        assertEquals(7 * 60, event.localEnd(utc)?.let { it.hour * 60 + it.minute })
        assertEquals("Custom/Plus3", event.timeZoneId)
        assertTrue(imported.timeZones.single().definition.contains("TZOFFSETTO:+0300"))
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
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 11, 1, 18, 0), "TESTZONE"),
            import.todos.single().due,
        )
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 10, 30, 18, 0), "TESTZONE"),
            import.todos.single().start,
        )
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 11, 1, 18, 0), "TESTZONE"),
            import.journals.single().start,
        )
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
        assertEquals(IcsTimeValue.Floating(LocalDateTime(2026, 11, 1, 4, 0)), todo.due)
        // Reading the same record back must land on the same day: stamping the wall clock as UTC
        // would turn 04:00 into the previous evening and move the deadline.
        val exported = exportCalendar(emptyList(), listOf(todo), now)
        val back = importCalendar(exported, newYork, "Untitled").todos.single()
        assertEquals(todo.due, back.due)
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
}
