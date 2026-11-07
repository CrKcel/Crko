package com.crko

import com.crko.domain.BusyInterval
import com.crko.domain.BusyType
import com.crko.domain.EndOfDayMinutes
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.busyAt
import com.crko.domain.busyIntervals
import com.crko.domain.busySchedule
import com.crko.domain.exportFreeBusy
import com.crko.domain.freeGaps
import com.crko.domain.importCalendar
import com.crko.domain.mergeAdjacent
import com.crko.domain.mergeBusyPeriods
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val Zone = TimeZone.UTC

private fun instant(text: String) = LocalDateTime.parse(text).toInstant(Zone)

private fun event(
    title: String,
    date: String,
    start: Int,
    end: Int,
    allDay: Boolean = false,
    status: EventStatus = EventStatus.Confirmed,
    transparency: EventTransparency = EventTransparency.Opaque,
    repeat: RepeatRule? = null,
) = testEvent(
    id = title,
    title = title,
    date = date,
    startMinutes = start,
    endMinutes = end,
    allDay = allDay,
    status = status,
    transparency = transparency,
    repeat = repeat,
)

class FreeBusyTest {

    private val from = instant("2026-03-01T00:00:00")
    private val to = instant("2026-03-03T00:00:00")

    @Test
    fun timedEventsOccupyTheirWallClockRange() {
        val busy = listOf(event("A", "2026-03-01", 600, 660)).busyIntervals(from, to, Zone)
        assertEquals(listOf(BusyInterval.of(BusyType.Busy, instant("2026-03-01T10:00:00"), instant("2026-03-01T11:00:00"))), busy)
    }

    @Test
    fun allDayEventsOccupyTheWholeDay() {
        val busy = listOf(event("A", "2026-03-01", 0, 0, allDay = true)).busyIntervals(from, to, Zone)
        assertEquals(
            listOf(BusyInterval.of(BusyType.Busy, instant("2026-03-01T00:00:00"), instant("2026-03-02T00:00:00"))),
            busy,
        )
    }

    @Test
    fun cancelledAndTransparentEventsAreNeverBusy() {
        val events = listOf(
            event("Cancelled", "2026-03-01", 600, 660, status = EventStatus.Cancelled),
            event("Free-ish", "2026-03-01", 700, 760, transparency = EventTransparency.Transparent),
        )
        assertTrue(events.busyIntervals(from, to, Zone).isEmpty())
    }

    @Test
    fun tentativeEventsReportAsTentative() {
        val busy = listOf(event("Maybe", "2026-03-01", 600, 660, status = EventStatus.Tentative)).busyIntervals(from, to, Zone)
        assertEquals(BusyType.Tentative, busy.single().type)
    }

    @Test
    fun repeatingEventsExpandEveryOccurrenceInTheWindow() {
        val events = listOf(
            event("Standup", "2026-02-27", 540, 570, repeat = RepeatRule(RepeatFrequency.Daily)),
        )
        val busy = events.busyIntervals(from, to, Zone)
        assertEquals(2, busy.size)
        assertEquals(instant("2026-03-01T09:00:00"), busy[0].start)
        assertEquals(instant("2026-03-02T09:30:00"), busy[1].end)
    }

    @Test
    fun overlappingPeriodsOfOneTypeMerge() {
        val merged = listOf(
            BusyInterval.of(BusyType.Busy, instant("2026-03-01T10:00:00"), instant("2026-03-01T11:00:00")),
            BusyInterval.of(BusyType.Busy, instant("2026-03-01T10:30:00"), instant("2026-03-01T12:00:00")),
            BusyInterval.of(BusyType.Tentative, instant("2026-03-01T10:00:00"), instant("2026-03-01T11:00:00")),
        ).mergeAdjacent()
        assertEquals(2, merged.size)
        assertEquals(instant("2026-03-01T12:00:00"), merged.first { it.type == BusyType.Busy }.end)
    }

    @Test
    fun storedPeriodsJoinEventTimeInOneSchedule() {
        val stored = listOf(
            BusyInterval.of(BusyType.BusyUnavailable, instant("2026-03-01T00:00:00"), instant("2026-03-01T08:00:00")),
        )
        val events = listOf(event("A", "2026-03-01", 600, 660))
        val schedule = busySchedule(events, stored, from, to, Zone)
        assertEquals(2, schedule.size)
        assertEquals(BusyType.BusyUnavailable, schedule.first().type)
        assertEquals(BusyType.Busy, schedule.last().type)
    }

    @Test
    fun busyAtAnswersSingleMoments() {
        val busy = listOf(event("A", "2026-03-01", 600, 660)).busyIntervals(from, to, Zone)
        assertTrue(busyAt(busy, instant("2026-03-01T10:30:00")))
        assertFalse(busyAt(busy, instant("2026-03-01T11:00:00")))
    }

    @Test
    fun busyTimesOnADstChangeDaySitAtTheirWallClockTimes() {
        val newYork = TimeZone.of("America/New_York")
        // 2026-03-08 springs forward: midnight is EST (-05:00) but 14:00 wall clock is EDT (-04:00),
        // so the fixed midnight offset put the period an hour late.
        val events = listOf(event("A", "2026-03-08", 14 * 60, 15 * 60))
        val intervals = events.busyIntervals(instant("2026-03-08T00:00:00"), instant("2026-03-09T00:00:00"), newYork)
        assertEquals(1, intervals.size)
        assertEquals(Instant.parse("2026-03-08T18:00:00Z"), intervals.single().start)
        assertEquals(Instant.parse("2026-03-08T19:00:00Z"), intervals.single().end)
    }

    @Test
    fun anAllDayEventOnADstChangeDaySpansExactlyThatDay() {
        val newYork = TimeZone.of("America/New_York")
        // A 25-hour day: the end must be the next midnight in the zone, not midnight + 24h. The
        // window reaches into November 2 because the period itself ends at 05:00Z that day.
        val events = listOf(event("A", "2026-11-01", 0, EndOfDayMinutes, allDay = true))
        val intervals = events.busyIntervals(instant("2026-11-01T00:00:00"), instant("2026-11-03T00:00:00"), newYork)
        assertEquals(1, intervals.size)
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), intervals.single().start)
        assertEquals(Instant.parse("2026-11-02T05:00:00Z"), intervals.single().end)
    }

    @Test
    fun freeGapsLeaveTheSlotsBetweenMeetings() {
        val busy = listOf(
            event("Morning", "2026-03-01", 540, 600),
            event("Noon", "2026-03-01", 660, 720),
        ).busyIntervals(from, to, Zone)
        val gaps = busy.freeGaps(from, to, minMinutes = 30)
        // Before the first meeting, between the two, and after the last one until the window end.
        assertEquals(3, gaps.size)
        assertEquals(instant("2026-03-01T10:00:00"), gaps[1].first)
        assertEquals(instant("2026-03-01T11:00:00"), gaps[1].second)
    }

    @Test
    fun declaredFreeTimeReopensBusySlots() {
        val stored = listOf(
            BusyInterval.of(BusyType.Busy, instant("2026-03-01T10:00:00"), instant("2026-03-01T12:00:00")),
            BusyInterval.of(BusyType.Free, instant("2026-03-01T10:00:00"), instant("2026-03-01T11:00:00")),
        )
        val gaps = stored.freeGaps(from, to)
        // The busy part that overlaps declared free time is reopened, so only 11:00–12:00 stays busy.
        assertEquals(2, gaps.size)
        assertEquals(instant("2026-03-01T11:00:00"), gaps[0].second)
        assertEquals(instant("2026-03-01T12:00:00"), gaps[1].first)
    }

    @Test
    fun vfreebusyPeriodsParseWithDurationsAndTypes() {
        val imported = importCalendar(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n" +
                "BEGIN:VFREEBUSY\r\nUID:fb@x\r\nDTSTAMP:20260101T000000Z\r\n" +
                "FREEBUSY;FBTYPE=BUSY-UNAVAILABLE:20260301T000000Z/PT8H30M,20260301T090000Z/20260301T093000Z\r\n" +
                "FREEBUSY:20260301T100000Z/20260301T110000Z\r\n" +
                "FREEBUSY;FBTYPE=BUSY-TENTATIVE:20260301T130000Z/20260301T140000Z\r\n" +
                "END:VFREEBUSY\r\nEND:VCALENDAR\r\n",
            Zone,
            untitledTitle = "Untitled",
        )
        assertTrue(imported.events.isEmpty())
        val busy = imported.freeBusy
        assertEquals(4, busy.size)
        assertEquals(BusyType.BusyUnavailable, busy[0].type)
        assertEquals(instant("2026-03-01T08:30:00"), busy[0].end)
        assertEquals(BusyType.BusyUnavailable, busy[1].type)
        assertEquals(BusyType.Busy, busy[2].type)
        assertEquals(BusyType.Tentative, busy[3].type)
    }

    @Test
    fun freebusyExportRoundTripsThroughTheReader() {
        val intervals = listOf(
            BusyInterval.of(BusyType.Busy, instant("2026-03-01T10:00:00"), instant("2026-03-01T11:00:00")),
            BusyInterval.of(BusyType.Tentative, instant("2026-03-01T13:00:00"), instant("2026-03-01T14:00:00")),
        )
        val text = exportFreeBusy(intervals, organizer = "mailto:me@example.com")
        assertTrue("METHOD:PUBLISH" in text)
        assertTrue("ORGANIZER:mailto:me@example.com" in text)
        val imported = importCalendar(text, Zone, untitledTitle = "Untitled")
        assertEquals(intervals, imported.freeBusy)
    }

    @Test
    fun importedPeriodsAreDeduplicatedAndExpiredOnesArePruned() {
        val now = instant("2026-03-10T00:00:00")
        val fresh = BusyInterval.of(BusyType.Busy, instant("2026-03-12T10:00:00"), instant("2026-03-12T11:00:00"))
        val stale = BusyInterval.of(BusyType.Busy, instant("2026-01-01T10:00:00"), instant("2026-01-01T11:00:00"))
        val stored = mergeBusyPeriods(emptyList(), listOf(fresh, stale), now)
        // The period that ended more than a week ago is not kept.
        assertEquals(listOf(fresh), stored)
        // Re-importing the same feed does not grow the set.
        val again = mergeBusyPeriods(stored, listOf(fresh, stale), now)
        assertEquals(listOf(fresh), again)
    }

    @Test
    fun queriesOutsideTheWindowReturnNothing() {
        val events = listOf(event("A", "2026-04-01", 600, 660))
        assertTrue(events.busyIntervals(from, to, Zone).isEmpty())
        assertTrue(events.busyIntervals(from, from, Zone).isEmpty())
    }
}
