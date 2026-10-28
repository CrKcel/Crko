package com.crko

import com.crko.ui.schedule.BlockAxis
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class TimelineAxisTest {

    @Test
    fun dayBlocksRunInBothDirectionsFromTheBase() {
        val axis = BlockAxis(LocalDate(2026, 10, 28), blockDays = 1, pageCount = 20001)
        assertEquals(LocalDate(2026, 10, 28), axis.dateAt(axis.center))
        assertEquals(LocalDate(2026, 10, 27), axis.dateAt(axis.center - 1))
        assertEquals(LocalDate(2026, 11, 2), axis.dateAt(axis.center + 5))
        assertEquals(axis.center, axis.indexOf(LocalDate(2026, 10, 28)))
        assertEquals(axis.center - 1, axis.indexOf(LocalDate(2026, 10, 27)))
    }

    @Test
    fun weekBlocksResolveAnyDateInsideThemToOneIndex() {
        // Monday 26 October 2026 starts the block that also holds Sunday 1 November.
        val axis = BlockAxis(LocalDate(2026, 10, 26), blockDays = 7, pageCount = 2400)
        assertEquals(axis.center, axis.indexOf(LocalDate(2026, 10, 26)))
        assertEquals(axis.center, axis.indexOf(LocalDate(2026, 10, 30)))
        assertEquals(axis.center, axis.indexOf(LocalDate(2026, 11, 1)))
        assertEquals(axis.center + 1, axis.indexOf(LocalDate(2026, 11, 2)))
        assertEquals(axis.center - 1, axis.indexOf(LocalDate(2026, 10, 25)))
        assertEquals(LocalDate(2026, 10, 26), axis.dateAt(axis.indexOf(LocalDate(2026, 10, 30))))
    }

    @Test
    fun restingOnAWeekKeepsTheWeekdayThatWasSelected() {
        val axis = BlockAxis(LocalDate(2026, 10, 26), blockDays = 7, pageCount = 2400)
        val thursday = LocalDate(2026, 10, 29)
        assertEquals(thursday, axis.restingDateAt(axis.center, thursday))
        val nextWeek = axis.restingDateAt(axis.center + 1, thursday)
        assertEquals(LocalDate(2026, 11, 5), nextWeek)
        assertEquals(axis.center + 1, axis.indexOf(nextWeek))
        // A day axis holds one day per block, so there is no weekday left to carry over.
        val days = BlockAxis(LocalDate(2026, 10, 28), blockDays = 1, pageCount = 20001)
        assertEquals(LocalDate(2026, 10, 27), days.restingDateAt(days.center - 1, thursday))
    }

    @Test
    fun aTripPastTheEndsOfTheIndexSpaceStaysInsideIt() {
        val axis = BlockAxis(LocalDate(2026, 10, 28), blockDays = 1, pageCount = 20001)
        assertEquals(0, axis.indexOf(LocalDate(1900, 1, 1)))
        assertEquals(20000, axis.indexOf(LocalDate(2200, 1, 1)))
    }
}
