package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
 */
@Composable
internal fun rememberWeekPager(
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
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

    // A week picked outside the pager (a cell tap, the day pager, the other week pager) moves it
    // there; weeks that come from paging already sit under the settled page, so nothing moves.
    LaunchedEffect(selected) {
        val page = pager.pageOf(selected)
        if (page != pagerState.currentPage) pagerState.scrollToPage(page)
    }

    // Report the week once the pager has come to rest. A page reported mid-gesture would retarget
    // the selection above and interrupt the fling that is still in flight.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val target = pager.weekAt(page).plusDays(currentSelected.mondayIndex)
            if (target != currentSelected) currentOnSelect(target)
        }
    }
    return pager
}

/**
 * Whole-week overview drawn as seven day columns on a shared 24-hour grid. One of these per week is
 * stacked in [ContinuousTimeline], which owns the scrolling and pins [WeekDayHeaderRow] above it.
 */
@Composable
internal fun WeekTimelineGrid(
    strings: Strings,
    today: LocalDate,
    now: State<LocalClock>,
    weekStart: LocalDate,
    eventsByDate: Map<LocalDate, List<Event>>,
    onOpen: (Event) -> Unit,
) {
    val days = remember(weekStart) { weekStart.weekDates() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HourHeight * 24)
            .padding(horizontal = ColumnHorizontalPadding),
    ) {
        TimeGutterLabels(strings)
        WeekColumns(
            days = days,
            today = today,
            now = now,
            eventsByDate = eventsByDate,
            onOpen = onOpen,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Weekday and date strip of one week block; pinned above the week timeline while it scrolls. */
@Composable
internal fun WeekDayHeaderRow(
    days: List<LocalDate>,
    today: LocalDate,
    strings: Strings,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(WeekDayHeaderHeight)
            .padding(horizontal = ColumnHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TimeGutterWidth))
        days.forEachIndexed { index, date ->
            val isToday = date == today
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = strings.weekdaysShort[index],
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 10.sp,
                    color = if (isToday) {
                        scheme.primary
                    } else {
                        scheme.onSurfaceVariantSummary.copy(alpha = if (index >= 5) 0.6f else 1f)
                    },
                    maxLines = 1,
                )
                Text(
                    text = date.day.toString(),
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 13.sp,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    color = if (isToday) scheme.primary else scheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun WeekColumns(
    days: List<LocalDate>,
    today: LocalDate,
    now: State<LocalClock>,
    eventsByDate: Map<LocalDate, List<Event>>,
    onOpen: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hourLineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.06f)
    val halfHourColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
    val daySeparatorColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.12f)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clipToBounds()
            .drawBehind {
                val hourPx = HourHeight.toPx()
                for (hour in 0..24) {
                    val y = (hour * hourPx).coerceAtMost(size.height - 1f)
                    drawLine(hourLineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                for (half in 0..47) {
                    val y = (half * hourPx / 2f).coerceIn(0f, size.height - 1f)
                    drawLine(halfHourColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
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
    events: List<Event>,
    onOpen: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val tones = eventTones()
    val placed = remember(events) { layoutEvents(events) }
    Box(
        modifier = modifier.drawWithContent {
            drawContent()
            // Read in the draw phase so a clock tick only repaints the marker, never recomposes
            // the seven columns or the week block around them.
            if (isToday) {
                val y = HourHeight.toPx() * (now.value.time.minutesOfDay() / 60f)
                drawLine(
                    color = scheme.error,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        },
    ) {
        if (isToday) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(scheme.primary.copy(alpha = 0.06f)),
            )
        }
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
                            .clickable { onOpen(item.event) }
                            .padding(horizontal = 2.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = item.event.title,
                            style = MiuixTheme.textStyles.footnote2,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = content,
                            maxLines = if (weekBlockHeight(item) >= 34.dp) 2 else 1,
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
                        height = weekBlockHeight(item).roundToPx(),
                    ),
                )
            }
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeables.forEachIndexed { index, placeable ->
                    val item = placed[index]
                    val columnWidth = dayWidth / item.totalColumns.toFloat()
                    placeable.place(
                        x = (columnWidth * item.column).roundToInt(),
                        y = (HourHeight * (item.startMinutes / 60f)).roundToPx(),
                    )
                }
            }
        }
    }
}

private fun weekBlockHeight(item: PlacedEvent): Dp =
    (HourHeight * ((item.endMinutes - item.startMinutes) / 60f)).coerceAtLeast(18.dp)

/** Whole-week overview grouped by day, ordered by start time within each day. */
@Composable
internal fun WeekAgendaList(
    strings: Strings,
    today: LocalDate,
    weekStart: LocalDate,
    eventsByDate: Map<LocalDate, List<Event>>,
    onOpen: (Event) -> Unit,
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
                items(dayEvents, key = { "week-event-$date-${it.id ?: it.title}" }) { event ->
                    AgendaEventRow(event = event, tones = tones, strings = strings, onOpen = onOpen)
                }
            }
        }
    }
}
