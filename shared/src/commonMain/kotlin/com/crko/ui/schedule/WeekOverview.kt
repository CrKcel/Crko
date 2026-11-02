package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalClock
import com.crko.domain.minutesOfDay
import com.crko.domain.mondayIndex
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import com.crko.ui.components.GlassNavigationBarDefaults
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

// Finger span change that switches between the day pager and the week overview.
private const val SpreadTriggerRatio = 1.3f
private const val PinchTriggerRatio = 0.72f

/**
 * Two-finger pinch that opens the week overview (pinch) and closes it (spread). Single-pointer
 * events are left untouched so the timeline scroll and the list pagers keep working; once a
 * second finger lands the events are consumed so nothing underneath scrolls mid-pinch.
 */
@Composable
internal fun Modifier.weekOverviewGesture(
    inWeekView: Boolean,
    onEnter: () -> Unit,
    onExit: () -> Unit,
): Modifier {
    val currentInWeekView by rememberUpdatedState(inWeekView)
    val currentOnEnter by rememberUpdatedState(onEnter)
    val currentOnExit by rememberUpdatedState(onExit)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var startDistance = 0f
            var triggered = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    event.changes.forEach { it.consume() }
                    val distance = (pressed[1].position - pressed[0].position).getDistance()
                    // Re-measure whenever the finger count changes so a re-landed finger cannot
                    // jump the ratio.
                    if (startDistance == 0f) startDistance = distance
                    if (!triggered && startDistance > 0f) {
                        val ratio = distance / startDistance
                        when {
                            ratio >= SpreadTriggerRatio && currentInWeekView -> {
                                triggered = true
                                currentOnExit()
                            }
                            ratio <= PinchTriggerRatio && !currentInWeekView -> {
                                triggered = true
                                currentOnEnter()
                            }
                        }
                    }
                } else {
                    startDistance = 0f
                }
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

/**
 * Week pager with a pinned page-to-week mapping, the same arrangement as [DayPager]: page
 * [BlockAxis.center] always shows the week the screen opened on and every other page a fixed offset
 * from it. Paging never remaps content, so a fling is never rewritten while it is in flight.
 */
internal class WeekPager(val state: PagerState, baseWeek: LocalDate) {
    private val axis = BlockAxis(baseWeek, 7, WeekBlockCount)

    fun weekAt(page: Int): LocalDate = axis.dateAt(page)

    fun pageOf(date: LocalDate): Int = axis.indexOf(date)
}

/**
 * Pager over whole weeks, used by the collapsed calendar row and by the week overview. Settling on
 * another week keeps the same weekday, which is the day the rest of the screen follows.
 *
 * [animatePicks] slides the pager to a week picked elsewhere instead of jumping there, for the
 * collapsed strip above the day timeline: that view has no pager below it, so the strip has to
 * move itself when the scrolling timeline selects a day in another week.
 */
@Composable
internal fun rememberWeekPager(
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    animatePicks: Boolean = false,
): WeekPager {
    val pagerState = rememberPagerState(initialPage = WeekBlockCount / 2) { WeekBlockCount }
    // Saved with the pager so a restored page index still maps to the week it was showing.
    val savedBaseWeek = rememberSaveable { selected.startOfWeek().toString() }
    val pager = remember(pagerState, savedBaseWeek) {
        WeekPager(pagerState, LocalDate.parse(savedBaseWeek))
    }

    // The collectors below outlive recompositions, so read the parameters through the latest value
    // rather than the one captured when they started.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // The pager and the selection feed each other, so one collector tells the two apart instead of
    // letting them chase each other: settling on a week reports its day (keeping the weekday the
    // selection had), while a date picked elsewhere (a cell tap, the day pager, the other week
    // pager) moves this one there. The page last answered by either side marks a report as the
    // pager's own, so a pick still wins over a report it races.
    LaunchedEffect(pagerState, pager, animatePicks) {
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
                    if (pagerState.currentPage != selectedPage) {
                        if (animatePicks) pagerState.animateScrollToPage(selectedPage)
                        else pagerState.scrollToPage(selectedPage)
                    }
                }
                // The pager settled on a page the selection has not caught up with yet: report it.
                settled != selectedPage -> {
                    answeredPage = settled
                    val target = pager.weekAt(settled).plusDays(currentSelected.mondayIndex)
                    if (target != currentSelected) currentOnSelect(target)
                }
                else -> Unit
            }
        }
    }
    return pager
}

/**
 * Whole-week overview drawn as seven day columns on a shared 24-hour grid. One of these is the
 * content of each page of [ContinuousTimeline]'s week overview; the page also uses [layout] to open
 * on the right hour.
 */
@Composable
internal fun WeekTimelineGrid(
    today: LocalDate,
    now: State<LocalClock>,
    weekStart: LocalDate,
    layout: HourLayout,
    eventsByDate: Map<LocalDate, List<Event>>,
    onOpen: (Event, LocalDate) -> Unit,
) {
    val days = remember(weekStart) { weekStart.weekDates() }
    WeekColumns(
        days = days,
        today = today,
        now = now,
        layout = layout,
        eventsByDate = eventsByDate,
        onOpen = onOpen,
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.totalHeight)
            .padding(horizontal = ColumnHorizontalPadding),
    )
}

@Composable
private fun WeekColumns(
    days: List<LocalDate>,
    today: LocalDate,
    now: State<LocalClock>,
    layout: HourLayout,
    eventsByDate: Map<LocalDate, List<Event>>,
    onOpen: (Event, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hourLineColor = hourDividerColor()
    val daySeparatorColor = hourLineColor
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clipToBounds()
            .drawBehind {
                val hourStroke = 1.dp.toPx()
                for (hour in 0..24) {
                    val y = layout.offset(hour).toPx().coerceIn(0f, size.height - 1f)
                    drawLine(hourLineColor, Offset(0f, y), Offset(size.width, y), hourStroke)
                }
                for (day in 1 until 7) {
                    val x = size.width * day / 7f
                    drawLine(daySeparatorColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
            },
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            days.forEach { date ->
                WeekDayColumn(
                    isToday = date == today,
                    now = now,
                    hours = layout,
                    date = date,
                    events = eventsByDate[date].orEmpty(),
                    onOpen = onOpen,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
    }
}

/** One day of the week grid; overlapping events split the column width. */
@Composable
private fun WeekDayColumn(
    isToday: Boolean,
    now: State<LocalClock>,
    hours: HourLayout,
    date: LocalDate,
    events: List<Event>,
    onOpen: (Event, LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val tones = eventTones()
    val timed = remember(events) { events.filterNot { it.start is IcsTimeValue.Date } }
    val allDay = remember(events) { events.filter { it.start is IcsTimeValue.Date } }
    val placed = remember(timed, date) { layoutEvents(timed, date) }
    Box(
        modifier = modifier.drawWithContent {
            drawContent()
            // Read in the draw phase so a clock tick only repaints the marker, never recomposes
            // the seven columns or the week block around them.
            if (isToday) {
                val y = hours.offsetOf(now.value.time.minutesOfDay()).toPx()
                drawLine(
                    color = scheme.error,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        },
    ) {
        // Width and position are resolved while measuring: the calendar above animates its height,
        // and a BoxWithConstraints here would recompose the column whenever the viewport changes.
        Layout(
            modifier = Modifier.fillMaxSize(),
            content = {
                placed.forEach { item ->
                    val (container, content) = tones[eventToneIndex(item.event, tones.size)]
                    Box(
                        modifier = Modifier
                            .padding(end = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(container)
                            .clickable { onOpen(item.event, date) }
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = item.event.title,
                            style = MiuixTheme.textStyles.footnote2,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = content,
                            maxLines = if (weekBlockHeight(item, hours) >= 34.dp) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
        ) { measurables, constraints ->
            val dayWidth = constraints.maxWidth
            val placeables = measurables.mapIndexed { index, measurable ->
                val item = placed[index]
                measurable.measure(
                    Constraints.fixed(
                        width = (dayWidth / item.totalColumns.toFloat()).roundToInt(),
                        height = weekBlockHeight(item, hours).roundToPx(),
                    ),
                )
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val item = placed[index]
                    val columnWidth = dayWidth / item.totalColumns.toFloat()
                    placeable.place(
                        x = (columnWidth * item.column).roundToInt(),
                        y = hours.offsetOf(item.startMinutes).roundToPx(),
                    )
                }
            }
        }
        if (allDay.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 1.dp, vertical = 1.dp),
            ) {
                allDay.take(2).forEach { event ->
                    val (container, content) = tones[eventToneIndex(event, tones.size)]
                    Text(
                        text = event.title,
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 8.sp,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 1.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(container)
                            .clickable { onOpen(event, date) }
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                    )
                }
                if (allDay.size > 2) {
                    Text(
                        text = "+${allDay.size - 2}",
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun weekBlockHeight(item: PlacedEvent, hours: HourLayout): Dp =
    (hours.offsetOf(item.endMinutes) - hours.offsetOf(item.startMinutes)).coerceAtLeast(18.dp)

/** Whole-week overview grouped by day, ordered by start time within each day. */
@Composable
internal fun WeekAgendaList(
    strings: Strings,
    today: LocalDate,
    weekStart: LocalDate,
    eventsByDate: Map<LocalDate, List<Event>>,
    onOpen: (Event, LocalDate) -> Unit,
    onSwipeToTimeline: (travel: Float) -> Unit,
) {
    val days = remember(weekStart) { weekStart.weekDates() }
    val tones = eventTones()
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .listSwipeToTimeline(listState, onSwipeToTimeline),
        contentPadding = PaddingValues(bottom = GlassNavigationBarDefaults.clearance()),
    ) {
        days.forEach { date ->
            val dayEvents = eventsByDate[date].orEmpty()
            item(key = "week-day-$date") {
                DayHeadingRow(
                    date = date,
                    today = today,
                    count = dayEvents.size,
                    strings = strings,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            if (dayEvents.isEmpty()) {
                item(key = "week-empty-$date") {
                    Text(
                        text = strings.noEventsSelected,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                val entries = agendaEntries(dayEvents, date)
                items(entries, key = { "week-event-$date-${it.event.id ?: it.event.title}-${it.instance}" }) { entry ->
                    AgendaEventRow(entry = entry, date = date, tones = tones, strings = strings, onOpen = onOpen)
                }
            }
        }
    }
}
