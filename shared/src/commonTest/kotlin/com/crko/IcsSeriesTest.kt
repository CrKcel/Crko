package com.crko

import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.RecurrenceRange
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.domain.importIcs
import com.crko.domain.occurrenceRangesOn
import com.crko.domain.occursOn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class IcsSeriesTest : IcsTestBase() {

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
    fun thisAndFutureWithOwnRuleBecomesTheFutureSegment() {
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
        // The exception carries its own rule, so it takes over from its slot: the master is
        // truncated the day before and the exception generates the later instances.
        assertNull(master.repeat?.count)
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 29)), master.repeat?.until)
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
        assertTrue(future.occursOn(LocalDate(2026, 10, 31)))
        assertTrue(future.occursOn(LocalDate(2026, 11, 5)))

        val exported = exportIcs(events, now)
        // The range is written back with the segment's own rule, so the split round trips.
        assertTrue(exported.contains("RECURRENCE-ID;RANGE=THISANDFUTURE:20261030T090000"), exported)
        assertTrue(exported.contains("RRULE:FREQ=DAILY;UNTIL=20261029T090000\r\n"), exported)
        assertFalse(exported.contains("#future-"), exported)
        val reparsed = importIcs(exported, utc, "Untitled")
        assertEquals(2, reparsed.size)
        val later = reparsed.first { it.title == "Later" }
        assertTrue(later.thisAndFuture)
        assertEquals(master.uid, later.icsUid)
        // Splitting an already truncated master is a no-op: the second import sees the same shape.
        val rebased = reparsed.first { it.title == "Daily" }
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 29)), rebased.repeat?.until)
        assertTrue(rebased.occursOn(LocalDate(2026, 10, 29)))
        assertFalse(rebased.occursOn(LocalDate(2026, 10, 30)))
        assertTrue(later.occursOn(LocalDate(2026, 11, 5)))
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
        val imported = importCalendar(text, utc, "Untitled")
        val events = imported.events
        val master = events.first { it.title == "Daily" }
        val future = events.first { it.title == "Later" }
        assertEquals(10, master.repeat?.count)
        assertTrue(future.thisAndFuture)
        // The master still describes every instance; the exception replaces its own slot only,
        // and the import says so instead of leaving the unapplied range invisible.
        assertTrue(master.occursOn(LocalDate(2026, 11, 5)))
        assertTrue(IcsWarning.UnappliedRecurrenceRange in imported.warnings, imported.warnings.toString())
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
        val imported = importCalendar(text, utc, "Untitled")
        val master = imported.events.first { it.title == "Daily" }
        assertFalse(master.occursOn(LocalDate(2026, 11, 1)))
        assertTrue(master.occursOn(LocalDate(2026, 11, 2)))
        assertTrue(IcsWarning.UnappliedRecurrenceRange in imported.warnings, imported.warnings.toString())
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
    fun anExceptionAfterTheSplitReplacesTheSegmentInstance() {
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
            RRULE:FREQ=DAILY
            END:VEVENT
            BEGIN:VEVENT
            UID:future-override
            RECURRENCE-ID:20261102T110000
            SUMMARY:Moved
            DTSTART:20261103T140000
            DTEND:20261103T150000
            END:VEVENT
            """,
        )
        val events = importIcs(text, utc, "Untitled")
        val later = events.first { it.title == "Later" }
        val moved = events.first { it.title == "Moved" }
        // The master no longer generates the slot, so the plain exception must exclude the
        // segment's instance instead — otherwise the day would show the change twice.
        assertTrue("2026-11-02" in later.excludedDates)
        assertFalse(later.occursOn(LocalDate(2026, 11, 2)))
        // The replaced instance moved away, so nothing occurs on the slot's day any more; the
        // segment's own 11-03 instance and the moved copy both occur on the new day.
        assertEquals(0, events.count { it.occursOn(LocalDate(2026, 11, 2)) })
        assertTrue(moved.occursOn(LocalDate(2026, 11, 3)))
        assertTrue(later.occursOn(LocalDate(2026, 11, 3)))
        assertEquals(2, events.count { it.occursOn(LocalDate(2026, 11, 3)) })
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
        val series = testEvent(
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
        // The expansion engine drops each replaced instance by its full slot: the master holds
        // nothing on the 29th and the two exceptions cover that day exactly once each.
        assertFalse(events.first { it.recurrenceId == null }.occursOn(LocalDate(2026, 10, 29)))
        assertEquals(2, events.count { it.occursOn(LocalDate(2026, 10, 29)) })
        assertEquals(1, events.count { it.occursOn(LocalDate(2026, 10, 29)) && it.recurrenceId == slots.first() })
        val reparsed = importCalendar(exportIcs(events, now), utc, "Untitled")
        assertEquals(3, reparsed.events.size)
        assertEquals(2, reparsed.events.mapNotNull { it.recurrenceId }.distinct().size)
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
    fun utcRecurrenceIdOnATzidSeriesReplacesItsInstanceOnce() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:utc-slot
            SUMMARY:Daily
            DTSTART;TZID=America/New_York:20261027T090000
            DTEND;TZID=America/New_York:20261027T100000
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            BEGIN:VEVENT
            UID:utc-slot
            RECURRENCE-ID:20261028T130000Z
            SUMMARY:Moved
            DTSTART;TZID=America/New_York:20261028T110000
            DTEND;TZID=America/New_York:20261028T120000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val master = imported.events.first { it.recurrenceId == null }
        // The exception replaces the master's instance instead of joining it on the same slot.
        assertFalse(master.occursOn(LocalDate(2026, 10, 28)))
        assertEquals(1, imported.events.count { it.occursOn(LocalDate(2026, 10, 28)) })
        // The shared identity and the single visible occurrence survive a re-export and re-import.
        val reparsed = importCalendar(exportIcs(imported.events, now), utc, "Untitled")
        assertEquals(2, reparsed.events.size)
        assertEquals(1, reparsed.events.count { it.occursOn(LocalDate(2026, 10, 28)) })
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
    fun utcExdateOnATzidSeriesStillSkipsItsInstance() {
        // Real feeds mix UTC exclusions into a TZID series. The wall-clock exclusion match has
        // to read the value in the master's own zone, or the deleted instance keeps appearing.
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:utc-exdate
            SUMMARY:Daily
            DTSTART;TZID=America/New_York:20261027T090000
            DTEND;TZID=America/New_York:20261027T100000
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE:20261028T130000Z
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val event = imported.events.single()
        assertFalse(IcsWarning.ExdateMismatch in imported.warnings, imported.warnings.toString())
        assertTrue(event.occursOn(LocalDate(2026, 10, 27)))
        assertFalse(event.occursOn(LocalDate(2026, 10, 28)))
        assertTrue(event.occursOn(LocalDate(2026, 10, 29)))
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
}
