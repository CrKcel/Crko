package com.crko.ui.schedule

import androidx.compose.foundation.pager.PagerState
import com.crko.domain.mondayIndex
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
import kotlinx.datetime.LocalDate

/**
 * Horizontal list pager whose drag the collapsed week strip follows. A day axis only crosses a week
 * between Sunday and Monday, so a mid-week swipe leaves the strip alone; a week axis crosses on
 * every page. [pairAt] and [progressAt] describe the two weeks to draw side by side and how far the
 * strip has travelled between them; both are read while laying the row out, so following the finger
 * does not recompose the header on every frame.
 */
internal class WeekStrip(
    val state: PagerState,
    private val pageDate: (Int) -> LocalDate,
    private val pageIsWeek: Boolean,
) {
    fun weekOf(page: Int): LocalDate =
        if (pageIsWeek) pageDate(page) else pageDate(page).startOfWeek()

    /** Weeks to draw side by side; a negative offset slides the strip back to the week before. */
    fun pairAt(page: Int, fraction: Float): Pair<LocalDate, LocalDate> {
        val week = weekOf(page)
        return if (fraction < 0f) week.plusDays(-7) to week else week to week.plusDays(7)
    }

    /** 0 shows the left week of [pairAt], 1 the right one. */
    fun progressAt(fraction: Float): Float =
        if (fraction < 0f) 1f + fraction else fraction

    /** Whether the page at this offset is leaving for a neighbouring week. */
    fun isCrossing(page: Int, fraction: Float): Boolean = when {
        fraction > 0f -> pageIsWeek || pageDate(page).mondayIndex == 6
        fraction < 0f -> pageIsWeek || pageDate(page).mondayIndex == 0
        else -> false
    }
}
