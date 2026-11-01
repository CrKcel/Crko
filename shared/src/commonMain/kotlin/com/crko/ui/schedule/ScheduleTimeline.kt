package com.crko.ui.schedule

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
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
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import com.crko.ui.components.GlassNavigationBarDefaults
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DayContentType = "timeline-day"
private const val DayHeadingContentType = "timeline-day-heading"

/** The timeline as one continuous vertical scroller in the day view; a page per week in the overview. */
@Composable
internal fun ContinuousTimeline(
    strings: Strings,
    today: LocalDate,
    now: State<LocalClock>,
    events: List<Event>,
    selected: LocalDate,
    weekPager: WeekPager,
    inWeekView: Boolean,
    defaultScrollHour: Int,
    onSelect: (LocalDate) -> Unit,
    onAdd: (LocalDate, Int?) -> Unit,
    onOpen: (Event, LocalDate) -> Unit,
) {
    if (inWeekView) {
        PagedWeekTimeline(
            today = today,
            now = now,
            events = events,
            pager = weekPager,
            defaultScrollHour = defaultScrollHour,
            onOpen = onOpen,
        )
    } else {
        ContinuousDayTimeline(
            strings = strings,
            today = today,
            now = now,
            events = events,
            selected = selected,
            defaultScrollHour = defaultScrollHour,
            onSelect = onSelect,
            onAdd = onAdd,
            onOpen = onOpen,
        )
    }
}

/**
 * Week overview as one page per week: a page keeps the natural hour heights and scrolls inside
 * itself, and the drag left over when it reaches its top or bottom flips the pager to the
 * neighbouring week. A page arriving from above opens at its top and one arriving from below at its
 * bottom, so the hours keep running across a flip instead of snapping back to midnight.
 *
 * [pager] is owned above, so the collapsed week row in the header can follow the same flip up and
 * down while the week changes.
 */
@Composable
private fun PagedWeekTimeline(
    today: LocalDate,
    now: State<LocalClock>,
    events: List<Event>,
    pager: WeekPager,
    defaultScrollHour: Int,
    onOpen: (Event, LocalDate) -> Unit,
) {
    VerticalPager(
        state = pager.state,
        // Laid out ahead of time, so the neighbour already opens at the edge it will be
        // revealed from instead of at midnight.
        beyondViewportPageCount = 1,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        val weekStart = pager.weekAt(page)
        val days = remember(weekStart) { weekStart.weekDates() }
        val eventsByDate = remember(days, events) { days.associateWith { events.occurringOn(it) } }
        WeekTimelinePage(
            today = today,
            now = now,
            weekStart = weekStart,
            page = page,
            pager = pager.state,
            eventsByDate = eventsByDate,
            defaultScrollHour = defaultScrollHour,
            onOpen = onOpen,
        )
    }
}

@Composable
private fun WeekTimelinePage(
    today: LocalDate,
    now: State<LocalClock>,
    weekStart: LocalDate,
    /** Page index and pager, used only to decide the edge this page is first revealed from. */
    page: Int,
    pager: PagerState,
    eventsByDate: Map<LocalDate, List<Event>>,
    defaultScrollHour: Int,
    onOpen: (Event, LocalDate) -> Unit,
) {
    val layout = HourLayout
    val density = LocalDensity.current
    // Saved with the page, so flipping away and back returns to the hours left behind. ScrollState
    // clamps the seeded value once the grid has been measured.
    val scrollState = rememberSaveable(saver = ScrollState.Saver) {
        // Neighbour pages are composed ahead of time, so the edge this page will be revealed from
        // is the side it sits on: above the current page opens at the top, below at the bottom.
        // The pager is read inside the seed only, so the page does not keep observing it and is
        // not recomposed on every flip.
        val offset = when {
            page > pager.currentPage -> 0
            page < pager.currentPage -> Int.MAX_VALUE
            else -> {
                val days = weekStart.weekDates()
                val hour = if (today in days) now.value.time.hour else defaultScrollHour
                with(density) { layout.offset(hour).roundToPx() }
            }
        }
        ScrollState(offset)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
    ) {
        WeekTimelineGrid(
            today = today,
            now = now,
            weekStart = weekStart,
            layout = layout,
            eventsByDate = eventsByDate,
            onOpen = onOpen,
        )
        // Runs behind the glass bar, so the last hours of the week stay reachable.
        Spacer(Modifier.height(GlassNavigationBarDefaults.clearance()))
    }
}

/**
 * The day timeline as one vertical scroller: a 24-hour block per day running on without end in
 * either direction. Crossing midnight simply keeps scrolling into the neighbouring block — day
 * headings stick to the top of the scroller and hand over to the next day's heading — so the
 * schedule reads as one long ribbon rather than one page at a time. The block under the top edge is
 * the one the rest of the screen follows.
 */
@Composable
private fun ContinuousDayTimeline(
    strings: Strings,
    today: LocalDate,
    now: State<LocalClock>,
    events: List<Event>,
    selected: LocalDate,
    defaultScrollHour: Int,
    onSelect: (LocalDate) -> Unit,
    onAdd: (LocalDate, Int?) -> Unit,
    onOpen: (Event, LocalDate) -> Unit,
) {
    val axis = rememberBlockAxis(selected, weeks = false)
    val density = LocalDensity.current
    val initialBlock = axis.indexOf(selected)
    val hourLabels = rememberHourLabelLayouts(strings)
    // Seeded on first composition: the opening frame must already sit on the anchor hour, since a
    // block drawn at midnight and scrolled afterwards would flash the wrong window first. Keyed on
    // the axis base so a pick far outside the window re-seeds the scroller for the new axis.
    val listState = rememberSaveable(axis.baseDate, saver = LazyListState.Saver) {
        LazyListState(
            firstVisibleItemIndex = itemIndexOfBlock(initialBlock),
            firstVisibleItemScrollOffset = anchorOffsetPx(
                density, axis, initialBlock, today, now.value, defaultScrollHour,
            ),
        )
    }

    // The collectors below outlive recompositions, so read the parameters through the latest value
    // rather than the one captured when they started.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentToday by rememberUpdatedState(today)
    val currentDefaultHour by rememberUpdatedState(defaultScrollHour)

    // The block under the top edge feeds the selection and the selection drives the scroller, so one
    // collector tells the two apart instead of letting them chase each other: a block the scroll
    // crosses is reported at once, so the pinned day heading and the week calendar above name the
    // same day while the finger is still moving; a date picked elsewhere (a calendar cell, the week
    // strip, the list view) moves the scroller there once it comes to rest.
    LaunchedEffect(listState, axis) {
        // Block the scroller last answered (or was seeded at). A selection that still maps to it is
        // the scroll's own, so the next report is for the block under the top edge; a selection
        // that moved elsewhere is a pick, which the scroll must not overwrite mid-fling.
        var reportedBlock = blockOfItem(listState.firstVisibleItemIndex)
        snapshotFlow {
            Triple(
                blockOfItem(listState.firstVisibleItemIndex),
                axis.indexOf(currentSelected),
                !listState.isScrollInProgress,
            )
        }.collect { (block, selectedBlock, idle) ->
            when (timelineSyncAction(block, selectedBlock, reportedBlock, idle)) {
                TimelineSync.None -> Unit
                TimelineSync.Follow -> {
                    reportedBlock = selectedBlock
                    listState.scrollToItem(
                        itemIndexOfBlock(selectedBlock),
                        anchorOffsetPx(
                            density, axis, selectedBlock, currentToday, now.value,
                            currentDefaultHour,
                        ),
                    )
                }
                TimelineSync.Report -> {
                    reportedBlock = block
                    val date = axis.dateAt(block)
                    if (date != currentSelected) currentOnSelect(date)
                }
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize(),
        // Runs behind the glass bar, so the last hours of the final block stay reachable.
        contentPadding = PaddingValues(bottom = GlassNavigationBarDefaults.clearance()),
    ) {
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
                    hourLabels = hourLabels,
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

/** Vertical offset that opens [block] on its anchor hour: today's hour, or the configured one. */
private fun anchorOffsetPx(
    density: Density,
    axis: BlockAxis,
    block: Int,
    today: LocalDate,
    now: LocalClock,
    defaultScrollHour: Int,
): Int {
    val date = axis.dateAt(block)
    val hour = if (date == today) now.time.hour else defaultScrollHour
    // A pinned day heading covers the top of the grid, so the anchor hour is placed just below it
    // instead of underneath it.
    val heading = TimelineDayHeadingHeight + HourLabelLift
    return with(density) {
        (HourLayout.offset(hour) - heading).coerceAtLeast(0.dp).roundToPx()
    }
}

/** Scroller item that opens [block]: its grid, preceded by the heading item of the same day. */
private fun itemIndexOfBlock(block: Int): Int = block * 2 + 1

/** Block whose content is at [firstVisibleItemIndex]; a day block is a heading item and a grid item. */
private fun blockOfItem(firstVisibleItemIndex: Int): Int = firstVisibleItemIndex / 2

/** What the day timeline's scroll/selection collector does for one observed state. */
internal enum class TimelineSync { None, Report, Follow }

/**
 * Tells a scroll-driven selection apart from a pick made elsewhere (a calendar cell, the week
 * strip, the list view).
 *
 * [reportedBlock] is the block the scroller last answered. While the selection still maps to it,
 * a different visible block is the scroll crossing into a new day and is reported. A selection
 * that moved to another block came from outside: the scroller follows it once it comes to rest,
 * and until then does nothing, so a fling cannot overwrite the pick.
 */
internal fun timelineSyncAction(
    block: Int,
    selectedBlock: Int,
    reportedBlock: Int,
    idle: Boolean,
): TimelineSync = when {
    block == selectedBlock -> TimelineSync.None
    selectedBlock != reportedBlock -> if (idle) TimelineSync.Follow else TimelineSync.None
    else -> TimelineSync.Report
}
