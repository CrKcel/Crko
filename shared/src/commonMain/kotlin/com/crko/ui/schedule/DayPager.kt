package com.crko.ui.schedule

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.max

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

/** Finger travel that counts as one sideways page. */
private val HorizontalPageTrip = 64.dp

/**
 * Sideways drag that pages the same axis a [androidx.compose.foundation.pager.VerticalPager]
 * scrolls: the list shows one day or week per page, and either direction of travel walks that
 * axis, so a horizontal swipe changes the page vertically. Multi-finger travel is left alone for
 * the pinch that opens the week overview, and a drag that turns out to be vertical reaches the
 * pager untouched.
 */
@Composable
internal fun Modifier.horizontalPageSwipe(onSwipe: (Int) -> Unit): Modifier {
    val currentOnSwipe by rememberUpdatedState(onSwipe)
    val slopPx = LocalViewConfiguration.current.touchSlop
    val tripPx = with(LocalDensity.current) { HorizontalPageTrip.toPx() }
    return pointerInput(slopPx, tripPx) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var locked = false
            var sideways = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size != 1) break
                val delta = pressed.first().positionChange()
                dx += delta.x
                dy += delta.y
                if (!locked && max(abs(dx), abs(dy)) > slopPx) {
                    locked = true
                    sideways = abs(dx) > abs(dy)
                }
                if (sideways) event.changes.forEach { it.consume() }
            }
            if (sideways && abs(dx) > tripPx) currentOnSwipe(if (dx < 0) 1 else -1)
        }
    }
}
