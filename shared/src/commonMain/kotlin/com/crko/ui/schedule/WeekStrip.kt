package com.crko.ui.schedule

import androidx.compose.foundation.pager.PagerState
import com.crko.domain.mondayIndex
import com.crko.domain.plusDays
import com.crko.domain.startOfMonth
import com.crko.domain.startOfWeek
import kotlinx.datetime.LocalDate

/**
 * Pager whose drag the collapsed week strip follows — the list pager below it, or the week timeline
 * pager. A day axis only crosses a week between Sunday and Monday, so a mid-week swipe leaves the
 * strip alone; a week axis crosses on every page. [pairAt] and [progressAt] describe the two weeks
 * to draw next to each other and how far the strip has travelled between them; both are read while
 * laying the row out, so following the finger does not recompose the header on every frame.
 *
 * [vertical] stacks the pair up and down instead of sideways, for the pager that flips weeks in the
 * week overview's timeline.
 */
internal class WeekStrip(
    val state: PagerState,
    private val pageDate: (Int) -> LocalDate,
    private val pageIsWeek: Boolean,
    val vertical: Boolean = false,
) {
    fun weekOf(page: Int): LocalDate =
        if (pageIsWeek) pageDate(page) else pageDate(page).startOfWeek()

    /** Whether each page is a whole week rather than a day. */
    val isWeekAxis: Boolean get() = pageIsWeek

    /**
     * Day axis only: continuous cell position, 0..7, of the page's date inside its week row. Reading
     * it while laying the row out lets the selected frame ride the pager's own offset.
     */
    fun dayPosition(page: Int, fraction: Float): Float = pageDate(page).mondayIndex + fraction

    /**
     * Weeks to draw side by side; a negative offset slides the strip back to the week before. A day
     * axis only crosses a week between Sunday and Monday, so anywhere else in the week it keeps its
     * own page's week: otherwise a day-to-day swipe would slide the strip a whole week.
     */
    fun pairAt(page: Int, fraction: Float): Pair<LocalDate, LocalDate> {
        val week = weekOf(page)
        return when {
            !pageIsWeek && !isCrossing(page, fraction) -> week to week.plusDays(7)
            fraction < 0f -> week.plusDays(-7) to week
            else -> week to week.plusDays(7)
        }
    }

    /** 0 shows the left week of [pairAt], 1 the right one. */
    fun progressAt(page: Int, fraction: Float): Float = when {
        // Mid-week the pair is pinned to the page's own week, so the strip does not move at all.
        !pageIsWeek && !isCrossing(page, fraction) -> 0f
        fraction < 0f -> 1f + fraction
        else -> fraction
    }

    /** Whether the page at this offset is leaving for a neighbouring week. */
    fun isCrossing(page: Int, fraction: Float): Boolean = when {
        fraction > 0f -> pageIsWeek || pageDate(page).mondayIndex == 6
        fraction < 0f -> pageIsWeek || pageDate(page).mondayIndex == 0
        else -> false
    }

    /**
     * The date this axis lands on at [page]: the page's own day, or the day [weekdayIndex] into its
     * week — the weekday the week pager keeps when it flips to a whole week.
     */
    fun landingDate(page: Int, weekdayIndex: Int): LocalDate =
        if (pageIsWeek) pageDate(page).plusDays(weekdayIndex) else pageDate(page)

    /**
     * Months of the two rows [pairAt] draws at this offset, left first. Each row is colored by the
     * month it lands on, so the incoming week already shows its own month while it slides in.
     */
    fun pairMonthsAt(page: Int, fraction: Float, weekdayIndex: Int): Pair<LocalDate, LocalDate> {
        val leftPage = if (fraction < 0f) page - 1 else page
        return landingDate(leftPage, weekdayIndex).startOfMonth() to
            landingDate(leftPage + 1, weekdayIndex).startOfMonth()
    }

    /**
     * Month of the pair row that owns the viewport at this offset: the row that is more than half
     * on screen, so the title flips once the swipe passes halfway instead of when it settles.
     */
    fun pairMonthAt(page: Int, fraction: Float, weekdayIndex: Int): LocalDate {
        val leftPage = if (fraction < 0f) page - 1 else page
        val shown = if (progressAt(page, fraction) >= 0.5f) leftPage + 1 else leftPage
        return landingDate(shown, weekdayIndex).startOfMonth()
    }
}
