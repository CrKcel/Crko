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
        // Even with the pager partway between two days, the pair stays on the current week and the
        // strip does not slide: sliding would flip a whole week on every day-to-day swipe.
        assertEquals(monday to monday.plusDays(7), strip.pairAt(wednesdayPage, 0.4f))
        assertEquals(0f, strip.progressAt(wednesdayPage, 0.4f))
        assertEquals(monday to monday.plusDays(7), strip.pairAt(wednesdayPage, -0.4f))
        assertEquals(0f, strip.progressAt(wednesdayPage, -0.4f))
    }

    @Test
    fun sundaySlidesTowardsTheNextWeek() {
        val strip = dayStrip()
        val sundayPage = firstPage + 6
        assertTrue(strip.isCrossing(sundayPage, 0.25f))
        assertEquals(monday to monday.plusDays(7), strip.pairAt(sundayPage, 0.25f))
        assertEquals(0.25f, strip.progressAt(sundayPage, 0.25f))
    }

    @Test
    fun mondayComingBackSlidesFromThePreviousWeek() {
        val strip = dayStrip()
        assertTrue(strip.isCrossing(firstPage, -0.25f))
        assertEquals(monday.plusDays(-7) to monday, strip.pairAt(firstPage, -0.25f))
        assertEquals(0.75f, strip.progressAt(firstPage, -0.25f))
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
        assertEquals(0.3f, strip.progressAt(firstPage, 0.3f))
        assertTrue(strip.isCrossing(firstPage, -0.3f))
        assertEquals(monday.plusDays(-7) to monday, strip.pairAt(firstPage, -0.3f))
        assertEquals(0.7f, strip.progressAt(firstPage, -0.3f))
    }

    @Test
    fun weekAxisPairMonthsFollowTheKeptWeekday() {
        val strip = weekStrip()
        val saturday = 5
        // The current week lands on Saturday Oct 31 (October), the next one on Nov 7 (November).
        assertEquals(
            LocalDate(2026, 10, 1) to LocalDate(2026, 11, 1),
            strip.pairMonthsAt(firstPage, 0.3f, saturday),
        )
        assertEquals(LocalDate(2026, 10, 1), strip.pairMonthAt(firstPage, 0.3f, saturday))
        assertEquals(LocalDate(2026, 11, 1), strip.pairMonthAt(firstPage, 0.7f, saturday))
        // Past halfway the pager reports the incoming page; the pair stays the same two weeks.
        assertEquals(LocalDate(2026, 11, 1), strip.pairMonthAt(firstPage + 1, -0.3f, saturday))
        assertEquals(
            LocalDate(2026, 10, 1) to LocalDate(2026, 11, 1),
            strip.pairMonthsAt(firstPage + 1, -0.3f, saturday),
        )
    }

    @Test
    fun dayAxisPairMonthsFollowTheLandingDays() {
        // A week that straddles the month end: May 25 - 31, then June 1.
        val mayMonday = LocalDate(2026, 5, 25)
        val strip = strip(dayPages = true) { page -> mayMonday.plusDays(page - firstPage) }
        val sundayPage = firstPage + 6
        // The outgoing row lands on Sunday May 31, the incoming one on Monday June 1.
        assertEquals(
            LocalDate(2026, 5, 1) to LocalDate(2026, 6, 1),
            strip.pairMonthsAt(sundayPage, 0.3f, weekdayIndex = 0),
        )
        assertEquals(LocalDate(2026, 5, 1), strip.pairMonthAt(sundayPage, 0.3f, weekdayIndex = 0))
        assertEquals(LocalDate(2026, 6, 1), strip.pairMonthAt(sundayPage, 0.7f, weekdayIndex = 0))
        assertEquals(
            LocalDate(2026, 6, 1),
            strip.pairMonthAt(sundayPage + 1, -0.3f, weekdayIndex = 0),
        )
    }
}
