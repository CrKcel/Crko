package com.crko

import com.crko.domain.Event
import com.crko.migration.legacyEvent
import com.crko.domain.EndOfDayMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.occursOn
import com.crko.domain.timeRangeOn
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeRangeTest {

    private fun timed(
        date: String,
        start: Int,
        end: Int,
        endDate: String? = null,
        repeat: RepeatRule? = null,
    ) = legacyEvent(id = "e", title = "e", date = date, startMinutes = start, endMinutes = end, endDate = endDate, repeat = repeat)

    @Test
    fun aCrossMidnightEventIsClippedToEachDay() {
        // 22:00 on the 27th until 02:00 on the 28th: the first day runs to midnight.
        val event = timed("2026-10-27", 22 * 60, 2 * 60, endDate = "2026-10-28")
        assertEquals(22 * 60 to EndOfDayMinutes, event.timeRangeOn(LocalDate(2026, 10, 27)))
        assertEquals(0 to 2 * 60, event.timeRangeOn(LocalDate(2026, 10, 28)))
    }

    @Test
    fun aMidnightEndingFillsItsDayUpTo24() {
        // Ends at midnight of the 28th, so only the 27th is occupied, up to 24:00.
        val event = timed("2026-10-27", 22 * 60, 0, endDate = "2026-10-28")
        assertEquals(22 * 60 to EndOfDayMinutes, event.timeRangeOn(LocalDate(2026, 10, 27)))
        assertEquals(false, event.occursOn(LocalDate(2026, 10, 28)))
    }

    @Test
    fun aMultiDayEventRunsToMidnightOnItsMiddleDays() {
        val event = timed("2026-10-27", 22 * 60, 15 * 60, endDate = "2026-10-29")
        assertEquals(22 * 60 to EndOfDayMinutes, event.timeRangeOn(LocalDate(2026, 10, 27)))
        assertEquals(0 to EndOfDayMinutes, event.timeRangeOn(LocalDate(2026, 10, 28)))
        assertEquals(0 to 15 * 60, event.timeRangeOn(LocalDate(2026, 10, 29)))
    }

    @Test
    fun aSingleDayEventKeepsItsRawRange() {
        val event = timed("2026-10-27", 9 * 60, 10 * 60)
        assertEquals(9 * 60 to 10 * 60, event.timeRangeOn(LocalDate(2026, 10, 27)))
    }

    @Test
    fun aRepeatingMultiDayEventSlicesLaterOccurrencesByTheirOwnStart() {
        // 2026-10-27 is a Tuesday, so occurrences start on Tuesdays only.
        val weekly = RepeatRule(RepeatFrequency.Weekly)
        val event = timed("2026-10-27", 22 * 60, 2 * 60, endDate = "2026-10-28", repeat = weekly)
        // The occurrence starting on Nov 3 slices like the anchor one did, and its second day
        // keeps the tail instead of the raw start time.
        assertEquals(22 * 60 to EndOfDayMinutes, event.timeRangeOn(LocalDate(2026, 11, 3)))
        assertEquals(0 to 2 * 60, event.timeRangeOn(LocalDate(2026, 11, 4)))
    }

    @Test
    fun aDailyMultiDayEventSlicesTheOccurrenceStartingOnTheViewedDay() {
        // A daily rule starts an occurrence every day, so the previous occurrence's tail and the
        // fresh one overlap; the day slices the occurrence that starts on it.
        val daily = RepeatRule(RepeatFrequency.Daily)
        val event = timed("2026-10-27", 22 * 60, 2 * 60, endDate = "2026-10-28", repeat = daily)
        assertEquals(22 * 60 to EndOfDayMinutes, event.timeRangeOn(LocalDate(2026, 11, 4)))
    }

    @Test
    fun aRepeatingSingleDayEventKeepsItsTimeOnLaterOccurrences() {
        val daily = RepeatRule(RepeatFrequency.Daily)
        val event = timed("2026-10-27", 9 * 60, 10 * 60, repeat = daily)
        assertEquals(9 * 60 to 10 * 60, event.timeRangeOn(LocalDate(2026, 11, 3)))
    }
}
