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

private const val TimelineSlots = 24 * 60 / SlotMinutes

/** Hour labels for a 24-hour grid, shared by the day and week timelines. */
@Composable
internal fun TimeGutterLabels(strings: Strings) {
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
            .height(HourHeight * 24)
            .clipToBounds(),
    ) {
        val hourPx = HourHeight.toPx()
        val lift = 7.dp.toPx()
        layouts.forEachIndexed { hour, layout ->
            // Centres each label on its hour line, clipped at the top edge of the grid.
            val x = if (layoutDirection == LayoutDirection.Rtl) size.width - layout.size.width else 0f
            drawText(layout, color = color, topLeft = Offset(x, hour * hourPx - lift))
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
    val hourHeight = HourHeight
    val primary = MiuixTheme.colorScheme.primary
    val alarmColor = MiuixTheme.colorScheme.error
    val halfHourColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.08f)
    val hourLineColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.06f)
    val currentOnAdd by rememberUpdatedState(onAdd)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(hourHeight * 24)
            // The whole block — side margins and hour gutter included — is the create surface, so
            // a tap never lands in a dead strip at an edge. The event blocks sit deeper in the tree
            // and consume their own taps first, so tapping one still opens it instead of creating
            // a new event underneath.
            .pointerInput(date) {
                detectTapGestures { offset ->
                    val slot = (offset.y / SlotHeight.toPx()).toInt().coerceIn(0, TimelineSlots - 1)
                    currentOnAdd(date, slot * SlotMinutes)
                }
            }
            .padding(horizontal = ColumnHorizontalPadding),
    ) {
        TimeGutterLabels(strings)
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
                            color = hourLineColor,
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
                // The minute is read in the draw phase: a clock tick repaints the marker only.
                .drawWithContent {
                    drawContent()
                    if (date == today) {
                        val y = hourHeight.toPx() * (now.value.time.minutesOfDay() / 60f)
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
            if (date == today) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(primary.copy(alpha = 0.06f)),
                )
            }
            EventBlocks(
                events = events,
                onOpen = onOpen,
            )
        }
    }
}

/** Lays out event blocks in absolute positions; overlapping events share the column width. */
@Composable
private fun EventBlocks(
    events: List<Event>,
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
                val compact = eventBlockHeight(item) < 44.dp
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
                    height = eventBlockHeight(placed[index]).roundToPx(),
                ),
            )
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val item = placed[index]
                placeable.place(
                    x = (columnWidth * item.column).roundToInt(),
                    y = (HourHeight * (item.startMinutes / 60f)).roundToPx(),
                )
            }
        }
    }
}

/** Block height for [item]; fixed slot heights can be shorter than a readable one-line block. */
private fun eventBlockHeight(item: PlacedEvent): Dp =
    (HourHeight * ((item.endMinutes - item.startMinutes) / 60f).coerceAtLeast(20f / 56f))
        .coerceAtLeast(20.dp)


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
