package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.crko.domain.Event
import com.crko.domain.LocalClock
import com.crko.domain.occurringOn
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import com.crko.ui.components.GlassNavigationBarDefaults
import kotlinx.datetime.LocalDate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DayContentType = "timeline-day"
private const val DayHeadingContentType = "timeline-day-heading"
private const val WeekContentType = "timeline-week"

/**
 * The timeline as one continuous vertical scroller: a 24-hour block per day, or per week in the
 * overview, running on without end in either direction. Crossing midnight simply keeps scrolling
 * into the neighbouring block — day headings stick to the top of the scroller and hand over to the
 * next day's heading, week blocks carry the pinned strip of dates — so the schedule reads as one
 * long ribbon rather than one page at a time. The block under the top edge is the one the rest of
 * the screen follows.
 */
@Composable
internal fun ContinuousTimeline(
    strings: Strings,
    today: LocalDate,
    now: State<LocalClock>,
    events: List<Event>,
    selected: LocalDate,
    inWeekView: Boolean,
    defaultScrollHour: Int,
    onSelect: (LocalDate) -> Unit,
    onAdd: (LocalDate, Int?) -> Unit,
    onOpen: (Event) -> Unit,
) {
    val axis = rememberBlockAxis(if (inWeekView) selected.startOfWeek() else selected, inWeekView)
    val density = LocalDensity.current
    val initialBlock = axis.indexOf(selected)
    // Seeded on first composition: the opening frame must already sit on the anchor hour, since a
    // block drawn at midnight and scrolled afterwards would flash the wrong window first. Re-seeded
    // when the axis changes shape, because a day index carried into the week axis lands past its end.
    val listState = rememberSaveable(inWeekView, saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = itemIndexOfBlock(initialBlock, inWeekView),
            firstVisibleItemScrollOffset = anchorOffsetPx(
                density, axis, initialBlock, inWeekView, today, now.value, defaultScrollHour, events,
            ),
        )
    }

    // The collectors below outlive recompositions, so read the parameters through the latest value
    // rather than the one captured when they started.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentDefaultHour by rememberUpdatedState(defaultScrollHour)
    val currentEvents by rememberUpdatedState(events)

    // Report the block on the falling edge of a scroll: mid-fling would retarget the jump below, and
    // the index alone also moves when unfolding the calendar shrinks this viewport. The weekday of
    // the current selection is kept, so scrolling weeks move the whole week instead of Monday.
    LaunchedEffect(listState, axis, inWeekView) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .drop(1)
            .collect { scrolling ->
                if (scrolling) return@collect
                val date = axis.restingDateAt(
                    blockOfItem(listState.firstVisibleItemIndex, inWeekView),
                    currentSelected,
                )
                if (date != currentSelected) currentOnSelect(date)
            }
    }

    // A date picked outside the timeline (a calendar cell, the week strip) moves it there. Blocks
    // that come from scrolling already sit under the top edge, so nothing moves.
    LaunchedEffect(selected, axis, inWeekView) {
        val target = axis.indexOf(selected)
        snapshotFlow { listState.isScrollInProgress }.first { scrolling -> !scrolling }
        if (blockOfItem(listState.firstVisibleItemIndex, inWeekView) != target) {
            listState.scrollToItem(
                itemIndexOfBlock(target, inWeekView),
                anchorOffsetPx(
                    density, axis, target, inWeekView, today, now.value, currentDefaultHour, currentEvents,
                ),
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned in the week overview, where the strip of dates is the only thing naming the days
        // the seven columns stand for; the day blocks carry their heading inside the scroller.
        if (inWeekView) PinnedWeekHeader(listState, axis, today, strings)
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            // Runs behind the glass bar, so the last hours of the final block stay reachable.
            contentPadding = PaddingValues(bottom = GlassNavigationBarDefaults.clearance()),
        ) {
            if (inWeekView) {
                items(
                    count = axis.pageCount,
                    key = { "timeline-week-$it" },
                    contentType = { WeekContentType },
                ) { index ->
                    val blockDate = axis.dateAt(index)
                    val days = remember(blockDate) { blockDate.weekDates() }
                    val eventsByDate = remember(days, events) { days.associateWith { events.occurringOn(it) } }
                    WeekTimelineGrid(
                        strings = strings,
                        today = today,
                        now = now,
                        weekStart = blockDate,
                        eventsByDate = eventsByDate,
                        onOpen = onOpen,
                    )
                }
            } else {
                // A day is two items, heading and grid, so the heading can stick to the top of the
                // scroller while its hours pass under it; the next day's heading then takes over.
                for (index in 0 until axis.pageCount) {
                    stickyHeader(
                        key = "timeline-day-heading-$index",
                        contentType = DayHeadingContentType,
                    ) {
                        val blockDate = axis.dateAt(index)
                        val dayEvents = remember(blockDate, events) { events.occurringOn(blockDate) }
                        DayHeadingRow(
                            date = blockDate,
                            today = today,
                            count = dayEvents.size,
                            strings = strings,
                            modifier = Modifier
                                .height(TimelineDayHeadingHeight)
                                // Opaque so the hours sliding beneath the pinned heading stay hidden.
                                .background(MiuixTheme.colorScheme.surface),
                        )
                    }
                    item(
                        key = "timeline-day-$index",
                        contentType = DayContentType,
                    ) {
                        val blockDate = axis.dateAt(index)
                        val dayEvents = remember(blockDate, events) { events.occurringOn(blockDate) }
                        DayTimelineGrid(
                            strings = strings,
                            today = today,
                            now = now,
                            date = blockDate,
                            events = dayEvents,
                            onAdd = onAdd,
                            onOpen = onOpen,
                        )
                    }
                }
            }
        }
    }
}

/** Weekday and date strip of whichever week is under the top edge of the scroller. */
@Composable
private fun PinnedWeekHeader(
    listState: LazyListState,
    axis: BlockAxis,
    today: LocalDate,
    strings: Strings,
) {
    // Read through a derived state: the block changes once per week, while reading the index
    // directly would recompose this header, and its parent, on every frame of the scroll.
    val weekStart by remember(listState, axis) {
        derivedStateOf { axis.dateAt(listState.firstVisibleItemIndex) }
    }
    WeekDayHeaderRow(
        days = remember(weekStart) { weekStart.weekDates() },
        today = today,
        strings = strings,
    )
}

/** Vertical offset that opens [block] on its anchor hour: today's hour, or the configured one. */
private fun anchorOffsetPx(
    density: Density,
    axis: BlockAxis,
    block: Int,
    weeks: Boolean,
    today: LocalDate,
    now: LocalClock,
    defaultScrollHour: Int,
    events: List<Event>,
): Int {
    val date = axis.dateAt(block)
    val hour = if (covers(date, today, if (weeks) 7 else 1)) now.time.hour else defaultScrollHour
    val blockEvents = if (weeks) {
        date.weekDates().flatMap { events.occurringOn(it) }
    } else {
        events.occurringOn(date)
    }
    // A pinned day heading covers the top of the grid, so the anchor hour is placed just below it
    // instead of underneath it.
    val heading = if (weeks) 0.dp else TimelineDayHeadingHeight + HourLabelLift
    return with(density) {
        (hourLayoutOf(blockEvents).offset(hour) - heading).coerceAtLeast(0.dp).roundToPx()
    }
}

/** Scroller item that opens [block]: its grid in the day view, where its heading is a sticky item. */
private fun itemIndexOfBlock(block: Int, weeks: Boolean): Int = if (weeks) block else block * 2 + 1

/** Block whose content is at [firstVisibleItemIndex]; a day block is a heading item and a grid item. */
private fun blockOfItem(firstVisibleItemIndex: Int, weeks: Boolean): Int =
    if (weeks) firstVisibleItemIndex else firstVisibleItemIndex / 2

/** Whether the block starting at [first] and running [days] long contains [day]. */
private fun covers(first: LocalDate, day: LocalDate, days: Int): Boolean = day in first..first.plusDays(days - 1)
