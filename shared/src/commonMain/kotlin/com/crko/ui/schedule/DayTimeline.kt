package com.crko.ui.schedule

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.crko.domain.Event
import com.crko.domain.LocalClock
import com.crko.domain.minutesOfDay
import com.crko.i18n.Strings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Hour labels for a 24-hour grid, shared by the day and week timelines. */
@Composable
internal fun TimeGutterLabels(strings: Strings, layout: HourLayout) {
    val labels = remember(strings) { List(24) { strings.time(LocalTime(it, 0)) } }
    val style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp)
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val layoutDirection = LocalLayoutDirection.current
    val gutterPx = with(LocalDensity.current) { TimeGutterWidth.roundToPx() }
    val measurer = rememberTextMeasurer()
    // One canvas instead of 24 text nodes per day and week block. The layouts are measured once per
    // theme, locale and width, then only drawn.
    val layouts = remember(labels, style, layoutDirection, gutterPx, measurer) {
        labels.map { label ->
            measurer.measure(
                text = AnnotatedString(label),
                style = style,
                constraints = Constraints(maxWidth = gutterPx),
                layoutDirection = layoutDirection,
            )
        }
    }
    Canvas(
        modifier = Modifier
            .width(TimeGutterWidth)
            .height(layout.totalHeight)
            .clipToBounds(),
    ) {
        val lift = 7.dp.toPx()
        layouts.forEachIndexed { hour, label ->
            // Centres each label on its hour line. The first one would otherwise be lifted above
            // the grid and clipped there, right against the day heading.
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - label.size.width else 0f
            val y = (layout.offset(hour).toPx() - lift).coerceAtLeast(0f)
            drawText(label, color = color, topLeft = Offset(x, y))
        }
    }
}

/**
 * Single-day grid: a 24-hour canvas with the hour gutter on the left. Tapping an empty slot opens
 * the editor at that time. One of these per day is stacked in [ContinuousTimeline], which owns the
 * scrolling.
 */
@Composable
internal fun DayTimelineGrid(
    strings: Strings,
    today: LocalDate,
    now: State<LocalClock>,
    date: LocalDate,
    events: List<Event>,
    onAdd: (LocalDate, Int?) -> Unit,
    onOpen: (Event) -> Unit,
) {
    val layout = remember(events) { hourLayoutOf(events) }
    val alarmColor = MiuixTheme.colorScheme.error
    val hourLineColor = hourDividerColor()
    val currentOnAdd by rememberUpdatedState(onAdd)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(layout.totalHeight)
            // The whole block — side margins and hour gutter included — is the create surface, so
            // a tap never lands in a dead strip at an edge. The event blocks sit deeper in the tree
            // and consume their own taps first, so tapping one still opens it instead of creating
            // a new event underneath.
            .pointerInput(date, layout) {
                detectTapGestures { offset ->
                    val minutes = layout.minutesAt(offset.y.toDp())
                    currentOnAdd(date, minutes / SlotMinutes * SlotMinutes)
                }
            }
            .padding(horizontal = ColumnHorizontalPadding),
    ) {
        TimeGutterLabels(strings, layout)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                // Hour rules are drawn by a single modifier instead of 25 nested boxes.
                .drawBehind {
                    val hourStroke = 1.dp.toPx()
                    for (hour in 0..24) {
                        val y = layout.offset(hour).toPx().coerceIn(0f, size.height - 1f)
                        drawLine(
                            color = hourLineColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = hourStroke,
                        )
                    }
                }
                // Drawn on top of the content so the marker is never covered by event blocks.
                // The minute is read in the draw phase: a clock tick repaints the marker only.
                .drawWithContent {
                    drawContent()
                    if (date == today) {
                        val y = layout.offsetOf(now.value.time.minutesOfDay()).toPx()
                        drawLine(
                            color = alarmColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                        drawCircle(color = alarmColor, radius = 2.5.dp.toPx(), center = Offset(0f, y))
                    }
                },
        ) {
            EventBlocks(
                events = events,
                hours = layout,
                onOpen = onOpen,
            )
        }
    }
}

/** Lays out event blocks in absolute positions; overlapping events share the column width. */
@Composable
private fun EventBlocks(
    events: List<Event>,
    hours: HourLayout,
    onOpen: (Event) -> Unit,
) {
    if (events.isEmpty()) return
    val tones = eventTones()
    val placed = remember(events) { layoutEvents(events) }
    val totalColumns = remember(placed) { placed.maxOf { it.totalColumns } }
    // Width and position are resolved while measuring: the calendar above animates its height, and
    // a BoxWithConstraints here would recompose every block whenever the viewport changes.
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f),
        content = {
            placed.forEach { item ->
                val (container, content) = tones[eventToneIndex(item.event, tones.size)]
                val compact = eventBlockHeight(item, hours) < 44.dp
                Column(
                    modifier = Modifier
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
        },
    ) { measurables, constraints ->
        val columnWidth = constraints.maxWidth / totalColumns.toFloat()
        val placeables = measurables.mapIndexed { index, measurable ->
            measurable.measure(
                Constraints.fixed(
                    width = columnWidth.roundToInt(),
                    height = eventBlockHeight(placed[index], hours).roundToPx(),
                ),
            )
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val item = placed[index]
                placeable.place(
                    x = (columnWidth * item.column).roundToInt(),
                    y = hours.offsetOf(item.startMinutes).roundToPx(),
                )
            }
        }
    }
}

/** Block height for [item]; short slots can be thinner than a readable one-line block. */
private fun eventBlockHeight(item: PlacedEvent, hours: HourLayout): Dp =
    (hours.offsetOf(item.endMinutes) - hours.offsetOf(item.startMinutes)).coerceAtLeast(20.dp)


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
