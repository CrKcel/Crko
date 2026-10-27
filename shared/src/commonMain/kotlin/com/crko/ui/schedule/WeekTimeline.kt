package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.crko.domain.Event
import com.crko.domain.LocalClock
import com.crko.domain.minutesOfDay
import com.crko.domain.plusDays
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val ColumnGap = 1.dp
private const val TotalMinutes = 24 * 60
private const val TimelineSlots = TotalMinutes / SlotMinutes

/** Seven-day timeline; tapping an empty slot opens the editor at that time. */
@Composable
internal fun WeekTimeline(
    state: AppStateHolder,
    strings: Strings,
    today: LocalDate,
    now: LocalClock,
    weekDates: List<LocalDate>,
    selected: LocalDate,
    defaultScrollHour: Int,
    onSelectInWeek: (LocalDate) -> Unit,
    onAdd: (LocalDate, Int?) -> Unit,
    onOpen: (Event) -> Unit,
) {
    // The grid is one tall canvas, so scrolling is a plain offset rather than per-item lazy
    // scrolling: a 24-hour column fits comfortably in memory and keeps hour alignment exact.
    val events = state.appState.events
    val scrollState = rememberScrollState()
    val targetOffsetPx = with(LocalDensity.current) {
        (HourHeight * (if (today in weekDates) now.time.hour else defaultScrollHour)).roundToPx()
    }

    // Anchor the visible window on the current hour for the current week, otherwise a fixed hour.
    LaunchedEffect(weekDates.first(), targetOffsetPx) {
        val maxOffset = scrollState.maxValue
        if (maxOffset > 0) {
            scrollState.scrollTo(targetOffsetPx.coerceIn(0, maxOffset))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TimelineDayHeader(
            strings = strings,
            today = today,
            weekDates = weekDates,
            selected = selected,
            onSelect = onSelectInWeek,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .swipeWeekPager(weekDates, onSelectInWeek),
        ) {
            TimelineGrid(
                strings = strings,
                today = today,
                now = now,
                weekDates = weekDates,
                selected = selected,
                events = events,
                onAdd = onAdd,
                onOpen = onOpen,
            )
        }
    }
}

/**
 * Turns a decisive horizontal swipe into a week change. Small movements never consume the
 * gesture, so slot taps and vertical scrolling keep working inside the grid.
 */
private fun Modifier.swipeWeekPager(
    weekDates: List<LocalDate>,
    onSelectInWeek: (LocalDate) -> Unit,
): Modifier = pointerInput(weekDates) {
    var total = 0f
    detectHorizontalDragGestures(
        onHorizontalDrag = { _, amount -> total += amount },
        onDragEnd = {
            val threshold = size.width * 0.2f
            when {
                total > threshold -> onSelectInWeek(weekDates.first().plusDays(-1))
                total < -threshold -> onSelectInWeek(weekDates.last().plusDays(1))
            }
            total = 0f
        },
        onDragCancel = { total = 0f },
    )
}

@Composable
private fun TimelineDayHeader(
    strings: Strings,
    today: LocalDate,
    weekDates: List<LocalDate>,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ColumnHorizontalPadding, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TimeGutterWidth))
        weekDates.forEachIndexed { index, date ->
            if (index > 0) Spacer(Modifier.width(ColumnGap))
            val isToday = date == today
            val isSelected = date == selected
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelect(date) }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.weekdaysShort[index],
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = if (strings.weekdaysShort[index].length > 1) 10.sp else 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = date.day.toString(),
                    style = MiuixTheme.textStyles.body1,
                    fontSize = 13.sp,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MiuixTheme.colorScheme.primary
                        isSelected -> MiuixTheme.colorScheme.onSurface
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
    }
}

@Composable
private fun TimelineGrid(
    strings: Strings,
    today: LocalDate,
    now: LocalClock,
    weekDates: List<LocalDate>,
    selected: LocalDate,
    events: List<Event>,
    onAdd: (LocalDate, Int?) -> Unit,
    onOpen: (Event) -> Unit,
) {
    val lineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.14f)
    val hourHeight = HourHeight
    val eventsByDate = remember(events, weekDates) {
        weekDates.associateWith { date -> events.filter { it.date == date.toString() }.sortedBy { it.startMinutes } }
    }
    val nowMinutes = now.time.minutesOfDay()
    val primary = MiuixTheme.colorScheme.primary
    val alarmColor = MiuixTheme.colorScheme.error
    val halfHourColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
    val columnGapColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.06f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(hourHeight * 24)
            .padding(horizontal = ColumnHorizontalPadding),
    ) {
        Column(modifier = Modifier.width(TimeGutterWidth).clipToBounds()) {
            repeat(24) { hour ->
                Text(
                    text = strings.time(LocalTime(hour, 0)),
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    // Centres each label on its hour line, clipped at the top edge of the grid.
                    modifier = Modifier
                        .height(hourHeight)
                        .offset(y = (-7).dp),
                )
            }
        }
        weekDates.forEachIndexed { index, date ->
            if (index > 0) Spacer(Modifier.width(ColumnGap))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    // Grid lines are drawn by a single modifier instead of 48 nested boxes.
                    .drawBehind {
                        val hourPx = hourHeight.toPx()
                        for (hour in 0..24) {
                            val y = (hour * hourPx).coerceAtMost(size.height - 1f)
                            drawLine(
                                color = columnGapColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f,
                            )
                        }
                        for (half in 0..47) {
                            val y = (half * hourPx / 2f).coerceIn(0f, size.height - 1f)
                            drawLine(
                                color = halfHourColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f,
                            )
                        }
                    }
                    // Drawn on top of the content so the marker is never covered by event blocks.
                    .drawWithContent {
                        drawContent()
                        if (date == today) {
                            val y = hourHeight.toPx() * (nowMinutes / 60f)
                            val barColor = alarmColor
                            drawLine(
                                color = barColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.5.dp.toPx(),
                            )
                            drawCircle(color = barColor, radius = 2.5.dp.toPx(), center = Offset(0f, y))
                        }
                    },
            ) {
                if (date == today || date == selected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(primary.copy(alpha = if (date == today) 0.06f else 0.03f)),
                    )
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    repeat(TimelineSlots) { slot ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(SlotHeight)
                                .clickable { onAdd(date, slot * SlotMinutes) },
                        )
                    }
                }
                EventBlocks(
                    events = eventsByDate.getValue(date),
                    onOpen = onOpen,
                )
            }
        }
    }
}

/** Lays out event blocks in absolute positions; overlapping events share the column width. */
@Composable
private fun BoxScope.EventBlocks(
    events: List<Event>,
    onOpen: (Event) -> Unit,
) {
    if (events.isEmpty()) return
    val tones = eventTones()
    val placed = remember(events) { layoutEvents(events) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f),
    ) {
        val columnWidth = maxWidth / (placed.maxOf { it.totalColumns }.toFloat())
        placed.forEach { item ->
            val (container, content) = tones[eventToneIndex(item.event, tones.size)]
            val height = HourHeight * ((item.endMinutes - item.startMinutes) / 60f).coerceAtLeast(20f / 56f)
            val compact = height < 44.dp
            Column(
                modifier = Modifier
                    .offset(
                        x = columnWidth * item.column.toFloat(),
                        y = HourHeight * (item.startMinutes / 60f),
                    )
                    .width(columnWidth)
                    // Fixed slot heights can be shorter than a minute's worth of space; never
                    // drop below a readable one-line block.
                    .height(height.coerceAtLeast(20.dp))
                    .padding(end = 1.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(container)
                    .clickable { onOpen(item.event) }
                    .padding(horizontal = 2.dp, vertical = 1.dp),
            ) {
                Text(
                    text = item.event.title,
                    style = MiuixTheme.textStyles.footnote1,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = content,
                    maxLines = if (compact) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = !compact,
                )
                if (!compact) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = clockRange(item.event),
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 9.sp,
                        color = content.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}


internal class PlacedEvent(val event: Event, val column: Int, val totalColumns: Int) {
    val startMinutes: Int get() = event.startMinutes
    val endMinutes: Int get() = event.endMinutes
}

/** Greedy column packing inside each cluster of overlapping events. */
internal fun layoutEvents(events: List<Event>): List<PlacedEvent> {
    val sorted = events.sortedWith(compareBy({ it.startMinutes }, { it.endMinutes }))
    val result = mutableListOf<PlacedEvent>()
    var cluster = mutableListOf<Event>()
    var clusterEnd = -1

    fun flush() {
        if (cluster.isEmpty()) return
        val columnOf = assignColumns(cluster)
        val total = (columnOf.values.maxOrNull() ?: 0) + 1
        cluster.forEach { result += PlacedEvent(it, columnOf.getValue(it.id ?: it.title), total) }
        cluster = mutableListOf()
    }

    for (event in sorted) {
        if (cluster.isNotEmpty() && event.startMinutes >= clusterEnd) {
            flush()
            clusterEnd = -1
        }
        cluster += event
        clusterEnd = maxOf(clusterEnd, event.endMinutes)
    }
    flush()
    return result
}

private fun assignColumns(events: List<Event>): Map<String, Int> {
    val columns = mutableListOf<MutableList<Event>>()
    for (event in events) {
        val free = columns.indexOfFirst { column -> column.none { it.endMinutes > event.startMinutes } }
        if (free >= 0) columns[free] += event else columns += mutableListOf(event)
    }
    return buildMap {
        columns.forEachIndexed { index, column -> column.forEach { put(it.id ?: it.title, index) } }
    }
}
