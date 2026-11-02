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
import androidx.compose.ui.text.TextLayoutResult
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
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalClock
import com.crko.domain.minutesOfDay
import com.crko.domain.occurrenceRangesOn
import com.crko.i18n.Strings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Distance an hour label is lifted so its text centres on the hour line. */
internal val HourLabelLift = 7.dp

/**
 * The 24 hour labels measured once per theme, locale and gutter width, then only drawn. The day
 * timeline composes one block per day, so measuring per block would re-shape the same strings
 * every time a new day scrolls in.
 */
@Composable
internal fun rememberHourLabelLayouts(strings: Strings): List<TextLayoutResult> {
    val labels = remember(strings) { List(24) { strings.time(LocalTime(it, 0)) } }
    val style = MiuixTheme.textStyles.footnote2.copy(fontSize = 11.sp)
    val layoutDirection = LocalLayoutDirection.current
    val gutterPx = with(LocalDensity.current) { TimeGutterWidth.roundToPx() }
    val measurer = rememberTextMeasurer()
    return remember(labels, style, layoutDirection, gutterPx, measurer) {
        labels.map { label ->
            measurer.measure(
                text = AnnotatedString(label),
                style = style,
                constraints = Constraints(maxWidth = gutterPx),
                layoutDirection = layoutDirection,
            )
        }
    }
}

/** Hour gutter of one day block; [labels] come from [rememberHourLabelLayouts]. */
@Composable
internal fun TimeGutterLabels(layout: HourLayout, labels: List<TextLayoutResult>) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val layoutDirection = LocalLayoutDirection.current
    // One canvas instead of 24 text nodes per day block.
    Canvas(
        modifier = Modifier
            .width(TimeGutterWidth)
            .height(layout.totalHeight)
            .clipToBounds(),
    ) {
        val lift = HourLabelLift.toPx()
        labels.forEachIndexed { hour, label ->
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
    hourLabels: List<TextLayoutResult>,
    today: LocalDate,
    now: State<LocalClock>,
    date: LocalDate,
    events: List<Event>,
    onAdd: (LocalDate, Int?) -> Unit,
    onOpen: (Event, LocalDate) -> Unit,
) {
    val layout = HourLayout
    val alarmColor = MiuixTheme.colorScheme.error
    val hourLineColor = hourDividerColor()
    val currentOnAdd by rememberUpdatedState(onAdd)
    val timedEvents = remember(events) { events.filterNot { it.start is IcsTimeValue.Date } }
    val allDayEvents = remember(events) { events.filter { it.start is IcsTimeValue.Date } }

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
        TimeGutterLabels(layout, hourLabels)
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
                events = timedEvents,
                date = date,
                hours = layout,
                onOpen = onOpen,
            )
            if (allDayEvents.isNotEmpty()) {
                AllDayStrip(events = allDayEvents, date = date, onOpen = onOpen)
            }
        }
    }
}

/** Lays out event blocks in absolute positions; overlapping events share the column width. */
@Composable
private fun EventBlocks(
    events: List<Event>,
    date: LocalDate,
    hours: HourLayout,
    onOpen: (Event, LocalDate) -> Unit,
) {
    if (events.isEmpty()) return
    val tones = eventTones()
    val placed = remember(events, date) { layoutEvents(events, date) }
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
                        .clickable { onOpen(item.event, date) }
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
                            text = clockRange(item.startMinutes, item.endMinutes),
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

/** All-day events as compact chips over the top of the day grid. */
@Composable
private fun AllDayStrip(
    events: List<Event>,
    date: LocalDate,
    onOpen: (Event, LocalDate) -> Unit,
) {
    val tones = eventTones()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(2f)
            .padding(horizontal = 2.dp, vertical = 2.dp),
    ) {
        events.take(3).forEach { event ->
            val (container, content) = tones[eventToneIndex(event, tones.size)]
            Text(
                text = event.title,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(container)
                    .clickable { onOpen(event, date) }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

/** Block height for [item]; short slots can be thinner than a readable one-line block. */
private fun eventBlockHeight(item: PlacedEvent, hours: HourLayout): Dp =
    (hours.offsetOf(item.endMinutes) - hours.offsetOf(item.startMinutes)).coerceAtLeast(20.dp)


internal class PlacedEvent(
    val event: Event,
    /** Index of the start time this block renders within the day's occurrence ranges. */
    val instance: Int,
    val column: Int,
    val totalColumns: Int,
    val startMinutes: Int,
    val endMinutes: Int,
)

private data class EventSlice(val event: Event, val instance: Int, val start: Int, val end: Int)

/** Identity of a slice, so two start times of one event claim separate layout columns. */
private fun sliceKey(slice: EventSlice): String = "${slice.event.id ?: slice.event.title}#${slice.instance}"

/** Greedy column packing inside each cluster of overlapping events, clipped to [date]. */
internal fun layoutEvents(events: List<Event>, date: LocalDate): List<PlacedEvent> {
    // A rule with BYHOUR/BYMINUTE puts several starts on one day; every one renders as its own block.
    val slices = events.flatMap { event ->
        event.occurrenceRangesOn(date).mapIndexed { instance, range ->
            EventSlice(event, instance, range.first, range.second)
        }
    }.sortedWith(compareBy({ it.start }, { it.end }))
    val result = mutableListOf<PlacedEvent>()
    var cluster = mutableListOf<EventSlice>()
    var clusterEnd = -1

    fun flush() {
        if (cluster.isEmpty()) return
        val columnOf = assignColumns(cluster)
        val total = (columnOf.values.maxOrNull() ?: 0) + 1
        cluster.forEach { slice ->
            result += PlacedEvent(
                event = slice.event,
                instance = slice.instance,
                column = columnOf.getValue(sliceKey(slice)),
                totalColumns = total,
                startMinutes = slice.start,
                endMinutes = slice.end,
            )
        }
        cluster = mutableListOf()
    }

    for (slice in slices) {
        if (cluster.isNotEmpty() && slice.start >= clusterEnd) {
            flush()
            clusterEnd = -1
        }
        cluster += slice
        clusterEnd = maxOf(clusterEnd, slice.end)
    }
    flush()
    return result
}

private fun assignColumns(events: List<EventSlice>): Map<String, Int> {
    val columns = mutableListOf<MutableList<EventSlice>>()
    for (slice in events) {
        val free = columns.indexOfFirst { column -> column.none { it.end > slice.start } }
        if (free >= 0) columns[free] += slice else columns += mutableListOf(slice)
    }
    return buildMap {
        columns.forEachIndexed { index, column ->
            column.forEach { put(sliceKey(it), index) }
        }
    }
}
