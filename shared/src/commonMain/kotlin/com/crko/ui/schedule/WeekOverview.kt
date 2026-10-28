package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
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
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.roundToInt

private val WeekDayHeaderHeight = 38.dp

// Finger span change that switches between the day pager and the week overview.
private const val ExpandTriggerRatio = 1.3f
private const val CollapseTriggerRatio = 0.72f

/**
 * Two-finger pinch that opens the week overview (spread) and closes it (pinch). Single-pointer
 * events are left untouched so the day pager and the scrollable content keep working; once a
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
                            ratio >= ExpandTriggerRatio && !currentInWeekView -> {
                                triggered = true
                                currentOnEnter()
                            }
                            ratio <= CollapseTriggerRatio && currentInWeekView -> {
                                triggered = true
                                currentOnExit()
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

/** Whole-week overview drawn as seven day columns on a shared 24-hour grid. */
@Composable
internal fun WeekTimeline(
    strings: Strings,
    today: LocalDate,
    now: LocalClock,
    weekStart: LocalDate,
    eventsByDate: Map<LocalDate, List<Event>>,
    defaultScrollHour: Int,
    onOpen: (Event) -> Unit,
) {
    val days = remember(weekStart) { weekStart.weekDates() }
    val scrollState = rememberScrollState()
    val startHour = if (today in days) now.time.hour else defaultScrollHour
    val targetOffsetPx = with(LocalDensity.current) { (HourHeight * startHour).roundToPx() }

    LaunchedEffect(weekStart, targetOffsetPx) {
        val maxOffset = scrollState.maxValue
        if (maxOffset > 0) {
            scrollState.scrollTo(targetOffsetPx.coerceIn(0, maxOffset))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WeekDayHeaderRow(days = days, today = today, strings = strings)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(scrollState),
        ) {
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
                    nowMinutes = now.time.minutesOfDay(),
                    eventsByDate = eventsByDate,
                    onOpen = onOpen,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun WeekDayHeaderRow(
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
    nowMinutes: Int,
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
                    nowMinutes = nowMinutes,
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
    nowMinutes: Int,
    events: List<Event>,
    onOpen: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val tones = eventTones()
    val placed = remember(events) { layoutEvents(events) }
    Box(modifier = modifier) {
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
        if (isToday) {
            Box(
                modifier = Modifier
                    .offset(y = HourHeight * (nowMinutes / 60f))
                    .fillMaxWidth()
                    .height(1.5.dp)
                    .background(scheme.error),
            )
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
) {
    val days = remember(weekStart) { weekStart.weekDates() }
    val tones = eventTones()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
    ) {
        days.forEach { date ->
            val dayEvents = eventsByDate[date].orEmpty()
            item(key = "week-day-$date") {
                WeekDayHeading(date = date, today = today, count = dayEvents.size, strings = strings)
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

@Composable
private fun WeekDayHeading(
    date: LocalDate,
    today: LocalDate,
    count: Int,
    strings: Strings,
) {
    val scheme = MiuixTheme.colorScheme
    val isToday = date == today
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.weekdaysFull[date.mondayIndex],
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            color = if (isToday) scheme.primary else scheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = strings.dayMonth(date),
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        if (isToday) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = strings.today,
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (count > 0) {
            Text(
                text = strings.eventsCount(count),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary,
            )
        }
    }
}
