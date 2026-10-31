package com.crko

import androidx.compose.foundation.pager.PagerState
import com.crko.domain.plusDays
import com.crko.ui.schedule.WeekStrip
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeekStripTest {

    private val monday = LocalDate(2026, 10, 26)
    private val firstPage = 10

    private fun strip(dayPages: Boolean, pageDate: (Int) -> LocalDate) =
        WeekStrip(PagerState(currentPage = firstPage) { 100 }, pageDate, pageIsWeek = !dayPages)

    private fun dayStrip() = strip(dayPages = true) { page ->
        monday.plusDays(page - firstPage)
    }

    private fun weekStrip() = strip(dayPages = false) { page ->
        monday.plusDays((page - firstPage) * 7)
    }

    @Test
    fun midWeekSwipeLeavesTheStripAlone() {
        val strip = dayStrip()
        val wednesdayPage = firstPage + 2
        assertFalse(strip.isCrossing(wednesdayPage, 0.4f))
        assertFalse(strip.isCrossing(wednesdayPage, -0.4f))
    }

    @Test
    fun sundaySlidesTowardsTheNextWeek() {
        val strip = dayStrip()
        val sundayPage = firstPage + 6
        assertTrue(strip.isCrossing(sundayPage, 0.25f))
        assertEquals(monday to monday.plusDays(7), strip.pairAt(sundayPage, 0.25f))
        assertEquals(0.25f, strip.progressAt(0.25f))
    }

    @Test
    fun mondayComingBackSlidesFromThePreviousWeek() {
        val strip = dayStrip()
        assertTrue(strip.isCrossing(firstPage, -0.25f))
        assertEquals(monday.plusDays(-7) to monday, strip.pairAt(firstPage, -0.25f))
        assertEquals(0.75f, strip.progressAt(-0.25f))
    }

    @Test
    fun restingPageDoesNotCross() {
        assertFalse(dayStrip().isCrossing(firstPage, 0f))
        assertFalse(weekStrip().isCrossing(firstPage, 0f))
    }

    @Test
    fun dayPositionRidesThePagerWithinTheWeek() {
        val strip = dayStrip()
        val wednesdayPage = firstPage + 2
        assertEquals(2f, strip.dayPosition(wednesdayPage, 0f))
        assertEquals(2.5f, strip.dayPosition(wednesdayPage, 0.5f))
        assertEquals(1.5f, strip.dayPosition(wednesdayPage, -0.5f))
    }

    @Test
    fun weekPagesSlideOnEveryPage() {
        val strip = weekStrip()
        assertTrue(strip.isCrossing(firstPage, 0.3f))
        assertEquals(monday to monday.plusDays(7), strip.pairAt(firstPage, 0.3f))
        assertTrue(strip.isCrossing(firstPage, -0.3f))
        assertEquals(monday.plusDays(-7) to monday, strip.pairAt(firstPage, -0.3f))
    }
}
