package com.crko

import com.crko.domain.mondayIndex
import com.crko.domain.monthGrid
import com.crko.domain.plusMonths
import com.crko.domain.startOfWeek
import com.crko.domain.weekDates
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarTest {

    @Test
    fun weekStartsOnMondayAndAlwaysHasSevenDays() {
        // 2026-10-27 is a Tuesday.
        val tuesday = LocalDate(2026, 10, 27)
        assertEquals(1, tuesday.mondayIndex)
        val week = tuesday.startOfWeek().weekDates()
        assertEquals(LocalDate(2026, 10, 26), week.first())
        assertEquals(LocalDate(2026, 11, 1), week.last())
        assertEquals(7, week.size)
        // A Sunday belongs to the week that started six days earlier.
        assertEquals(LocalDate(2026, 10, 26), LocalDate(2026, 11, 1).startOfWeek())
    }

    @Test
    fun weekSpansMonthAndYearBoundaries() {
        val week = LocalDate(2027, 1, 1).startOfWeek().weekDates()
        assertEquals(LocalDate(2026, 12, 28), week.first())
        assertEquals(LocalDate(2027, 1, 3), week.last())
    }

    @Test
    fun monthGridIsMondayAlignedAndCoversTheWholeMonth() {
        val grid = LocalDate(2026, 10, 15).monthGrid()
        assertEquals(0, grid.size % 7)
        assertEquals(0, grid.first().mondayIndex)
        assertTrue(grid.contains(LocalDate(2026, 10, 1)))
        assertTrue(grid.contains(LocalDate(2026, 10, 31)))
        // Leading days come from the previous month, not from a blank placeholder.
        assertTrue(grid.first() < LocalDate(2026, 10, 1))
    }

    @Test
    fun monthGridHandlesLeapFebruary() {
        val grid = LocalDate(2028, 2, 1).monthGrid()
        assertTrue(grid.contains(LocalDate(2028, 2, 29)))
        val nonLeapGrid = LocalDate(2029, 2, 1).monthGrid()
        assertEquals(28, nonLeapGrid.count { it.month == LocalDate(2029, 2, 1).month })
    }

    @Test
    fun monthArithmeticClampsDayToTargetMonth() {
        assertEquals(LocalDate(2026, 2, 28), LocalDate(2026, 1, 31).plusMonths(1))
        assertEquals(LocalDate(2025, 12, 15), LocalDate(2026, 1, 15).plusMonths(-1))
        assertEquals(LocalDate(2027, 1, 15), LocalDate(2026, 12, 15).plusMonths(1))
        assertEquals(LocalDate(2028, 2, 29), LocalDate(2028, 1, 31).plusMonths(1))
    }
}
