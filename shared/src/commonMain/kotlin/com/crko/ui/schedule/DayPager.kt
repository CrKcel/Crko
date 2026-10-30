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
import kotlinx.datetime.LocalDate

/**
 * Day pager with a pinned page-to-date mapping: page [BlockAxis.center] always shows the day the
 * screen opened on and every other page a fixed offset from it. Paging therefore never remaps
 * content — a day picked elsewhere moves the pager instead — so a fling is never rewritten while in
 * flight.
 */
internal class DayPager(val state: PagerState, baseDate: LocalDate) {
    private val axis = BlockAxis(baseDate, 1, DayBlockCount)

    fun dateAt(page: Int): LocalDate = axis.dateAt(page)

    fun pageOf(date: LocalDate): Int = axis.indexOf(date)
}

@Composable
internal fun rememberDayPager(
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
): DayPager {
    val pagerState = rememberPagerState(initialPage = DayBlockCount / 2) { DayBlockCount }
    // Saved with the pager so a restored page index still maps to the day it was showing.
    val savedBaseDate = rememberSaveable { today.toString() }
    val pager = remember(pagerState, savedBaseDate) {
        DayPager(pagerState, LocalDate.parse(savedBaseDate))
    }

    // The collectors below outlive recompositions, so read the parameters through the latest value
    // rather than the one captured when they started.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // The pager and the selection feed each other, so one collector tells the two apart instead of
    // letting them chase each other: settling on a page reports its day, while a day picked
    // elsewhere (the calendar, the week strip) moves the pager there. The page last answered by
    // either side marks a report as the pager's own, so a pick still wins over a report it races.
    LaunchedEffect(pagerState, pager) {
        var answeredPage = -1
        snapshotFlow {
            Triple(
                pagerState.settledPage,
                pager.pageOf(currentSelected),
                !pagerState.isScrollInProgress,
            )
        }.collect { (settled, selectedPage, idle) ->
            when {
                // A gesture is still running: let it settle instead of fighting it for the page.
                !idle -> Unit
                // The selection left the page this pager last answered — a pick elsewhere. Follow
                // it, so the pick wins over a page that settled while it was being made.
                selectedPage != answeredPage -> {
                    answeredPage = selectedPage
                    if (pagerState.currentPage != selectedPage) pagerState.scrollToPage(selectedPage)
                }
                // The pager settled on a page the selection has not caught up with yet: report it.
                settled != selectedPage -> {
                    answeredPage = settled
                    val date = pager.dateAt(settled)
                    if (date != currentSelected) currentOnSelect(date)
                }
                else -> Unit
            }
        }
    }
    return pager
}
