package com.crko

import kotlinx.datetime.LocalDate
import com.crko.ui.schedule.weekOfMonthIndex
import kotlin.test.Test
import kotlin.test.assertEquals

class WeekIndexTest {

    @Test
    fun weekIndexCountsGridRowsFromTheFirstMonday() {
        // October 2026 starts on a Thursday, so the grid's first row covers 28 Sep - 4 Oct.
        assertEquals(0, weekOfMonthIndex(LocalDate(2026, 10, 1)))
        assertEquals(3, weekOfMonthIndex(LocalDate(2026, 10, 19)))
        assertEquals(4, weekOfMonthIndex(LocalDate(2026, 10, 27)))
        // The grid's last row starts on Monday 26 October and runs into November.
        assertEquals(4, weekOfMonthIndex(LocalDate(2026, 10, 31)))
        // A date first seen through another month's grid resolves against its own month.
        assertEquals(0, weekOfMonthIndex(LocalDate(2026, 11, 1)))
        // September's own grid starts on Tuesday 1 Sep, putting 28 Sep in its fifth row.
        assertEquals(4, weekOfMonthIndex(LocalDate(2026, 9, 28)))
    }

    @Test
    fun monthStartingOnMondayHasNoLeadingRow() {
        assertEquals(0, weekOfMonthIndex(LocalDate(2028, 5, 1)))
        assertEquals(1, weekOfMonthIndex(LocalDate(2028, 5, 8)))
    }
}
