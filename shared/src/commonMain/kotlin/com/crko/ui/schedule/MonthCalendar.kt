package com.crko.ui.schedule

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import com.crko.domain.monthGrid
import com.crko.domain.plusDays
import com.crko.domain.plusMonths
import com.crko.domain.startOfMonth
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import kotlinx.coroutines.flow.first
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PageCount = 2400
private const val HomePage = PageCount / 2
internal const val MaxMonthRows = 6

/** Standalone month grid used where a folding week view is not wanted (e.g. the due-date picker). */
@Composable
internal fun MonthCalendar(
    hasEvents: (LocalDate) -> Boolean,
    strings: Strings,
    monthAnchor: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchorMonth = monthAnchor.startOfMonth()
    val pager = rememberMonthPager(anchorMonth, onMonthChange)

    // The grid is laid out at its tallest and the container reveals only the rows the month on
    // screen needs, so a five-week month leaves no empty strip under the calendar.
    val monthRows by remember(pager) {
        derivedStateOf { pager.monthAt(pager.state.currentPage).monthGrid().size / 7 }
    }
    val revealedRows by animateFloatAsState(
        targetValue = monthRows.toFloat(),
        animationSpec = tween(200),
    )

    Column(modifier = modifier.fillMaxWidth()) {
        WeekdayHeader(strings)
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MonthRowHeight * revealedRows)
                .clipToBounds(),
        ) {
            HorizontalPager(
                state = pager.state,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MonthRowHeight * MaxMonthRows.toFloat()),
            ) { page ->
                val month = pager.monthAt(page)
                MonthPage(
                    month = month,
                    days = remember(month) { month.monthGrid() },
                    selected = selected,
                    today = today,
                    hasEvents = hasEvents,
                    onSelect = onSelect,
                )
            }
        }
    }
}

/**
 * Month pager with a pinned page-to-month mapping: page [HomePage] always shows [baseMonth] and
 * every other page a fixed offset from it. Paging therefore never remaps content — a month chosen
 * elsewhere moves the pager instead — so a fling is never rewritten while it is in flight.
 */
internal class MonthPager(val state: PagerState, private val baseMonth: LocalDate) {
    fun monthAt(page: Int): LocalDate = baseMonth.plusMonths(page - HomePage)

    fun pageOf(month: LocalDate): Int {
        val months =
            (month.year - baseMonth.year) * 12 + (month.month.number - baseMonth.month.number)
        return (HomePage + months).coerceIn(0, PageCount - 1)
    }
}

/** A single Monday-first week, drawn with the same cells as the month grid. */
@Composable
internal fun WeekRow(
    weekStart: LocalDate,
    month: LocalDate,
    selected: LocalDate?,
    today: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    showSelection: Boolean,
    onSelect: (LocalDate) -> Unit,
    selectionPosition: (() -> Float)? = null,
    range: Pair<LocalDate, LocalDate>? = null,
) {
    // The dates and their event dots only depend on the week and the event list, so a selection or
    // a day rollover inside the same week does not rescan every event for all seven cells.
    val dates = remember(weekStart) { weekStart.weekDates() }
    val daysWithEvents = remember(dates, hasEvents) { dates.associateWith(hasEvents) }
    // The floating frame belongs to whichever week row is on screen: riding every page lets a week
    // swipe carry the highlight in with the incoming week instead of popping it in on settle. The
    // two rows of a crossing draw the frame at the same driven position; the row outside the
    // viewport places its copy beyond the strip's bounds, where the strip clips it, so exactly one
    // frame shows and it hands over between the rows as the pages swap mid-gesture.
    val floating = selectionPosition != null && selected != null && showSelection
    Box(modifier = Modifier.fillMaxWidth().height(MonthRowHeight)) {
        Row(modifier = Modifier.fillMaxSize()) {
            dates.forEach { date ->
                MonthCell(
                    date = date,
                    inMonth = date.month == month.month && date.year == month.year,
                    // In the week overview the whole week is on screen, so a single selected day
                    // means nothing there; the row keeps only the today marker. Both ends of a
                    // picked range take the selection outline.
                    selected = showSelection && (date == selected || isRangeEnd(date, range)),
                    drawSelectedBorder = !floating,
                    isToday = date == today,
                    hasEvents = daysWithEvents[date] == true,
                    inRange = isWithinRange(date, range),
                    rangeShape = rangeShapeFor(date, range),
                    onSelect = onSelect,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        }
        if (floating && showSelection) SelectionFrame(selectionPosition)
    }
}

/** Whether [date] is one of the two ends of the picked [range]. */
private fun isRangeEnd(date: LocalDate, range: Pair<LocalDate, LocalDate>?): Boolean =
    range != null && (date == range.first || date == range.second)

/** Whether [date] sits strictly between the ends of [range], so it takes the range tint. */
private fun isWithinRange(date: LocalDate, range: Pair<LocalDate, LocalDate>?): Boolean {
    if (range == null) return false
    val lo = minOf(range.first, range.second)
    val hi = maxOf(range.first, range.second)
    return date > lo && date < hi
}

/**
 * Corner rounding of the range band on [date]'s cell: the band rounds off where it begins or ends —
 * next to an endpoint — and at the row edges, so a range crossing weeks breaks into rounded
 * segments instead of running square off the grid.
 */
private fun rangeShapeFor(date: LocalDate, range: Pair<LocalDate, LocalDate>?): Shape {
    if (range == null) return RectangleShape
    val lo = minOf(range.first, range.second)
    val hi = maxOf(range.first, range.second)
    val roundLeft = date.dayOfWeek.ordinal == 0 || date == lo.plusDays(1)
    val roundRight = date.dayOfWeek.ordinal == 6 || date.plusDays(1) == hi
    return RoundedCornerShape(
        topStart = if (roundLeft) 12.dp else 0.dp,
        bottomStart = if (roundLeft) 12.dp else 0.dp,
        topEnd = if (roundRight) 12.dp else 0.dp,
        bottomEnd = if (roundRight) 12.dp else 0.dp,
    )
}

/**
 * Frame over the selected day of the collapsed strip. Its horizontal position is read while placing
 * it, driven by the day pager, so dragging slides it with the finger and its settle snaps it home.
 */
@Composable
private fun SelectionFrame(position: () -> Float) {
    val scheme = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val frameSize = 38.dp
    // Mirrors a cell's column: the 38.dp day box over its 2.dp gap and 4.dp event dot, centred.
    val frameTop = (MonthRowHeight - 44.dp) * 0.5f
    val density = LocalDensity.current
    val framePx = with(density) { frameSize.toPx().roundToInt() }
    val topPx = with(density) { frameTop.toPx() }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints.fixed(framePx, framePx))
                val height = if (constraints.hasBoundedHeight) {
                    constraints.maxHeight
                } else {
                    topPx.roundToInt() + framePx
                }
                layout(constraints.maxWidth, height) {
                    // The animated position is read while placing, so a slide only re-places the
                    // frame instead of measuring it again on every frame.
                    val cell = constraints.maxWidth / 7f
                    val centre = position().coerceIn(0f, 7f) * cell + cell / 2f
                    placeable.place((centre - framePx / 2f).roundToInt(), topPx.roundToInt())
                }
            }
            .size(frameSize)
            .border(2.dp, scheme.primary, shape),
    )
}

/**
 * Two weeks drawn next to each other and slid between as the driving pager crosses the edge of a
 * week. Sideways for the list, stacked up and down for the week timeline. The translation is read
 * while placing the row, so dragging only re-lays it out, never recomposes it.
 *
 * [selectionPosition] rides the crossing: both rows place the frame at it, and the row outside the
 * viewport places its copy beyond the strip's bounds, where the strip clips it — so one frame shows
 * and glides from the outgoing cell onto where the incoming cell lands.
 */
@Composable
internal fun WeekStripRow(
    left: LocalDate,
    right: LocalDate,
    leftMonth: LocalDate,
    rightMonth: LocalDate,
    progress: () -> Float,
    vertical: Boolean,
    selected: LocalDate?,
    today: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    showSelection: Boolean,
    onSelect: (LocalDate) -> Unit,
    selectionPosition: (() -> Float)? = null,
    visible: () -> Boolean = { true },
) {
    // Both weeks are measured at exactly the viewport size and placed one page apart, so the offset
    // can slide them by whole pages without depending on how a Row would distribute a wider child.
    Layout(
        content = {
            listOf(left to leftMonth, right to rightMonth).forEach { (weekStart, month) ->
                WeekRow(
                    weekStart = weekStart,
                    month = month,
                    selected = selected,
                    today = today,
                    hasEvents = hasEvents,
                    showSelection = showSelection,
                    onSelect = onSelect,
                    selectionPosition = selectionPosition,
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(MonthRowHeight)
            .clipToBounds(),
    ) { measurables, constraints ->
        // Hidden while the week pager itself shows these weeks: nothing is measured or placed, so
        // the row neither draws over the pager nor swallows the strip's own swipes. Read in
        // measure, so a crossing starting or ending never recomposes it.
        if (!visible()) return@Layout layout(constraints.maxWidth, constraints.maxHeight) {}
        val pageWidth = constraints.maxWidth
        val pageHeight = constraints.maxHeight
        val placeables = measurables.map { it.measure(Constraints.fixed(pageWidth, pageHeight)) }
        layout(pageWidth, pageHeight) {
            // Read at placement time so a drag re-lays out the row's position without recomposing it.
            val pageSize = if (vertical) pageHeight else pageWidth
            val shift = (-progress() * pageSize).roundToInt()
            placeables.forEachIndexed { index, placeable ->
                val offset = index * pageSize + shift
                if (vertical) placeable.place(0, offset) else placeable.place(offset, 0)
            }
        }
    }
}

/** Snapped to a page with nothing animating: not dragging, not settling, not between pages. */
private val PagerState.isAtRest: Boolean
    get() = !isScrollInProgress && currentPageOffsetFraction == 0f

@Composable
internal fun rememberMonthPager(
    anchorMonth: LocalDate,
    onMonthChange: (LocalDate) -> Unit,
): MonthPager {
    val pagerState = rememberPagerState(initialPage = HomePage) { PageCount }
    // Saved with the pager so a restored page index still maps to the month it was showing.
    val savedBaseMonth = rememberSaveable { anchorMonth.startOfMonth().toString() }
    val pager = remember(pagerState) { MonthPager(pagerState, LocalDate.parse(savedBaseMonth)) }

    // The collectors below outlive recompositions, so read the parameters through the latest value
    // rather than the one captured when they started.
    val currentAnchor by rememberUpdatedState(anchorMonth)
    val currentOnMonthChange by rememberUpdatedState(onMonthChange)

    // A month picked outside the pager (a week row, today) moves it there. Months that come from
    // paging already sit under the settled page, so nothing moves.
    LaunchedEffect(anchorMonth) {
        if (pager.monthAt(pagerState.currentPage) == anchorMonth) return@LaunchedEffect
        // Never cut a running settle short: only jump once the pager has come to rest.
        snapshotFlow { pagerState.isAtRest }.first { it }
        val page = pager.pageOf(anchorMonth)
        if (page != pagerState.currentPage) pagerState.scrollToPage(page)
    }

    // Report the month once the pager has come to rest. A page reported mid-gesture would retarget
    // the anchor above and interrupt the fling that is still in flight.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage.takeIf { pagerState.isAtRest } }
            .collect { page ->
                if (page == null) return@collect
                val month = pager.monthAt(page)
                if (month != currentAnchor) currentOnMonthChange(month)
            }
    }
    return pager
}

@Composable
internal fun WeekdayHeader(strings: Strings) {
    Row(modifier = Modifier.fillMaxWidth()) {
        strings.weekdaysShort.forEachIndexed { index, label ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (index >= 5) 0.6f else 1f),
                )
            }
        }
    }
}

@Composable
internal fun MonthPage(
    month: LocalDate,
    days: List<LocalDate>,
    selected: LocalDate?,
    today: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
    range: Pair<LocalDate, LocalDate>? = null,
) {
    // Event dots are resolved once per month instead of once per cell per recomposition.
    val weeks = remember(days) { days.chunked(7) }
    val daysWithEvents = remember(days, hasEvents) { days.associateWith(hasEvents) }
    // Fill the pager so short months stay top-aligned; otherwise the pager centres the page and
    // the first row would not line up with the folding window.
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        weeks.forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MonthRowHeight),
            ) {
                week.forEach { date ->
                    MonthCell(
                        date = date,
                        inMonth = date.month == month.month && date.year == month.year,
                        selected = date == selected || isRangeEnd(date, range),
                        isToday = date == today,
                        hasEvents = daysWithEvents[date] == true,
                        inRange = isWithinRange(date, range),
                        rangeShape = rangeShapeFor(date, range),
                        onSelect = onSelect,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * A date cell. [onSelect] is handed down instead of a per-cell click lambda: a fresh lambda
 * instance would defeat skipping and recompose all 42 cells of the month on every selection change.
 */
@Composable
private fun MonthCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    drawSelectedBorder: Boolean = true,
    inRange: Boolean = false,
    rangeShape: Shape = RectangleShape,
) {
    val scheme = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        // The range tint spans the whole cell, so adjacent in-range days read as one band whose
        // ends round off per [rangeShape].
        modifier = if (inRange) {
            modifier.background(scheme.primary.copy(alpha = 0.12f), rangeShape)
        } else {
            modifier
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(shape)
                .then(
                    when {
                        isToday -> Modifier.background(scheme.primary)
                        selected && drawSelectedBorder -> Modifier.border(2.dp, scheme.primary, shape)
                        else -> Modifier
                    },
                )
                .clickable { onSelect(date) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MiuixTheme.textStyles.body1,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    isToday -> scheme.onPrimary
                    selected -> scheme.primary
                    inMonth -> scheme.onSurface
                    else -> scheme.onSurfaceVariantSummary.copy(alpha = 0.45f)
                },
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(
                    when {
                        !hasEvents || !inMonth -> Color.Transparent
                        selected -> scheme.primary
                        else -> scheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                    },
                ),
        )
    }
}
