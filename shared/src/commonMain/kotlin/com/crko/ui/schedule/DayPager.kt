package com.crko.ui.schedule

import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import com.crko.domain.daysTo
import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate

private const val DayPageCount = 20001
private const val DayHomePage = DayPageCount / 2

/**
 * Day pager with a pinned page-to-date mapping: page [DayHomePage] always shows the day the screen
 * opened on and every other page a fixed offset from it. Paging therefore never remaps content — a
 * day picked elsewhere moves the pager instead — so a fling is never rewritten while in flight.
 */
internal class DayPager(val state: PagerState, private val baseDate: LocalDate) {
    fun dateAt(page: Int): LocalDate = baseDate.plusDays(page - DayHomePage)

    fun pageOf(date: LocalDate): Int = (DayHomePage + baseDate.daysTo(date))
        .coerceIn(0, DayPageCount - 1)
}

@Composable
internal fun rememberDayPager(
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
): DayPager {
    val pagerState = rememberPagerState(initialPage = DayHomePage) { DayPageCount }
    // Saved with the pager so a restored page index still maps to the day it was showing.
    val savedBaseDate = rememberSaveable { today.toString() }
    val pager = remember(pagerState, savedBaseDate) {
        DayPager(pagerState, LocalDate.parse(savedBaseDate))
    }

    // The collectors below outlive recompositions, so read the parameters through the latest value
    // rather than the one captured when they started.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // A day picked outside the pager (the calendar, the day summary) moves it there. Days that come
    // from paging already sit under the settled page, so nothing moves.
    LaunchedEffect(selected) {
        val page = pager.pageOf(selected)
        if (page != pagerState.currentPage) pagerState.scrollToPage(page)
    }

    // Report the day once the pager has come to rest. A page reported mid-gesture would retarget
    // the selection above and interrupt the fling that is still in flight.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val date = pager.dateAt(page)
            if (date != currentSelected) currentOnSelect(date)
        }
    }
    return pager
}
