package com.crko.ui.schedule

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.mondayIndex
import com.crko.domain.monthGrid
import com.crko.domain.plusDays
import com.crko.domain.plusMonths
import com.crko.domain.startOfMonth
import com.crko.domain.startOfWeek
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PageCount = 2400
private const val HomePage = PageCount / 2
private const val WeekPageCount = 2400
private const val WeekHomePage = WeekPageCount / 2
private const val MaxMonthRows = 6
private val FoldFlingVelocity = 400.dp

/**
 * Fold progress of the week/month calendar. It is owned above the grid so the drag surface can
 * span the title row and the day summary as well as the calendar itself.
 */
@Stable
internal class CalendarFoldState internal constructor(
    private val progressState: MutableState<Float>,
    private val scope: CoroutineScope,
) {
    /** 0 reveals the selected week, 1 the whole month. */
    val progress: Float get() = progressState.value

    // Height hidden while collapsed; depends on how many weeks the selected month needs.
    private val foldPxState = mutableFloatStateOf(0f)
    internal val foldPx: Float get() = foldPxState.floatValue

    private var settleJob: Job? = null

    internal fun updateFoldPx(value: Float) {
        foldPxState.floatValue = value
    }

    internal fun dragBy(delta: Float) {
        if (foldPx <= 0f) return
        cancelSettle()
        progressState.value = (progress + delta / foldPx).coerceIn(0f, 1f)
    }

    internal fun cancelSettle() {
        settleJob?.cancel()
    }

    internal fun settle(target: Float) {
        cancelSettle()
        settleJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                animationSpec = tween(if (target > progress) 280 else 220),
            ) { value, _ -> progressState.value = value }
            progressState.value = target
        }
    }
}

@Composable
internal fun rememberCalendarFold(): CalendarFoldState {
    val scope = rememberCoroutineScope()
    // Saved with the screen so a configuration change keeps the settled fold position.
    val progress = rememberSaveable { mutableStateOf(0f) }
    return remember(progress, scope) { CalendarFoldState(progress, scope) }
}

/**
 * Vertical drag that folds and unfolds the calendar. Applied to the whole header block, so the
 * title row and the day summary respond to the fold gesture as well as the grid.
 */
@Composable
internal fun Modifier.calendarFoldDrag(
    fold: CalendarFoldState,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
): Modifier {
    val flingVelocityPx = with(LocalDensity.current) { FoldFlingVelocity.toPx() }
    return draggable(
        state = rememberDraggableState { delta -> fold.dragBy(delta) },
        orientation = Orientation.Vertical,
        onDragStarted = { fold.cancelSettle() },
        onDragStopped = { velocity ->
            val target = when {
                velocity >= flingVelocityPx -> 1f
                velocity <= -flingVelocityPx -> 0f
                fold.progress >= 0.5f -> 1f
                else -> 0f
            }
            if ((target == 1f) == expanded) fold.settle(target) else onExpandedChange(target == 1f)
        },
    )
}

/**
 * Weekday header pinned over a paged month grid that unfolds around the selected week. The header
 * is the only place weekday labels appear; every week — the selected one included — is drawn by the
 * same month cell, so collapsing simply reveals one row of the same grid. Once collapsed a week
 * pager takes over that row, so a horizontal swipe switches to the adjacent week.
 *
 * [expanded] is the settled state; while dragging, the revealed height follows the finger directly.
 */
@Composable
internal fun WeekMonthCalendar(
    strings: Strings,
    selected: LocalDate,
    today: LocalDate,
    monthAnchor: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    fold: CalendarFoldState,
    expanded: Boolean,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { MonthRowHeight.toPx() }
    val progress = fold.progress

    // The unfold offset is measured inside the selected week's month. It only matters while
    // folding, and collapsing first sends the pager back to that month.
    val selectedRow = remember(selected) { weekOfMonthIndex(selected) }

    LaunchedEffect(expanded) { fold.settle(if (expanded) 1f else 0f) }

    val anchorMonth = monthAnchor.startOfMonth()
    val pager = rememberMonthPager(anchorMonth, onMonthChange)
    val weekPager = rememberWeekPager(selected, onSelect)

    // Height follows the month on screen, not the selected week's month: a six-week month opened
    // from a five-week one must grow instead of clipping its last row. Animated so paging between
    // months of different lengths does not snap.
    val shownMonth = pager.monthAt(pager.state.currentPage)
    val monthRows = remember(shownMonth) { shownMonth.monthGrid().size / 7 }
    val foldPx by animateFloatAsState(
        targetValue = (monthRows - 1) * rowHeightPx,
        animationSpec = tween(200),
    )
    // The drag handler maps finger travel with the settled value, not a mid-animation one.
    SideEffect { fold.updateFoldPx((monthRows - 1) * rowHeightPx) }

    // Folding back always returns to the selected week; paging may have left another month shown.
    LaunchedEffect(expanded, selected) {
        if (!expanded) {
            val selectedMonth = selected.startOfMonth()
            if (pager.monthAt(pager.state.currentPage) != selectedMonth) {
                pager.state.scrollToPage(pager.pageOf(selectedMonth))
                onMonthChange(selectedMonth)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        WeekdayHeader(strings)
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .height(with(density) { (rowHeightPx + progress * foldPx).toDp() }),
        ) {
            // Collapsed, the week row is the only thing shown; the month grid is kept composed but
            // transparent so it stays laid out (programmatic page jumps while hidden still work).
            // Leaving it visible would show a second copy of the dates whenever a week swipe moves
            // the row aside.
            val showMonthGrid = progress > 0.001f

            // Measured unbounded and top-aligned: the grid keeps its full size while the folding
            // box clips it, otherwise the small container would centre and swallow the pager.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true)
                    .graphicsLayer { alpha = if (showMonthGrid) 1f else 0f },
            ) {
                HorizontalPager(
                    state = pager.state,
                    userScrollEnabled = progress > 0.99f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MonthRowHeight * MaxMonthRows.toFloat())
                        .offset { IntOffset(0, (-(1f - progress) * selectedRow * rowHeightPx).roundToInt()) },
                ) { page ->
                    val month = pager.monthAt(page)
                    MonthPage(
                        month = month,
                        days = month.monthGrid(),
                        selected = selected,
                        today = today,
                        hasEvents = hasEvents,
                        onSelect = onSelect,
                    )
                }
            }

            // The week pager draws the same row the month grid would, so the switch as the
            // calendar folds open is invisible.
            if (!showMonthGrid) {
                HorizontalPager(
                    state = weekPager.state,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MonthRowHeight),
                ) { page ->
                    WeekRow(
                        weekStart = weekPager.weekStartAt(page),
                        month = selected.startOfMonth(),
                        selected = selected,
                        today = today,
                        hasEvents = hasEvents,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }
}

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

    Column(modifier = modifier.fillMaxWidth()) {
        WeekdayHeader(strings)
        Spacer(Modifier.height(2.dp))
        MonthCaption(
            strings = strings,
            month = pager.monthAt(pager.state.currentPage),
            fraction = pager.state.currentPageOffsetFraction,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        HorizontalPager(
            state = pager.state,
            modifier = Modifier
                .fillMaxWidth()
                .height(MonthRowHeight * MaxMonthRows.toFloat()),
        ) { page ->
            val month = pager.monthAt(page)
            MonthPage(
                month = month,
                days = month.monthGrid(),
                selected = selected,
                today = today,
                hasEvents = hasEvents,
                onSelect = onSelect,
            )
        }
    }
}

/**
 * Month pager with a pinned page-to-month mapping: page [HomePage] always shows [baseMonth] and
 * every other page a fixed offset from it. Paging therefore never remaps content — a month chosen
 * elsewhere moves the pager instead — so a fling is never rewritten while it is in flight.
 */
private class MonthPager(val state: PagerState, private val baseMonth: LocalDate) {
    fun monthAt(page: Int): LocalDate = baseMonth.plusMonths(page - HomePage)

    fun pageOf(month: LocalDate): Int {
        val months =
            (month.year - baseMonth.year) * 12 + (month.month.number - baseMonth.month.number)
        return (HomePage + months).coerceIn(0, PageCount - 1)
    }
}

/** Same pinned mapping as [MonthPager], one page per week instead of per month. */
private class WeekPager(val state: PagerState, private val baseWeek: LocalDate) {
    fun weekStartAt(page: Int): LocalDate = baseWeek.plusDays((page - WeekHomePage) * 7)

    fun pageOf(date: LocalDate): Int {
        val weeks = ((date.startOfWeek().toEpochDays() - baseWeek.toEpochDays()) / 7).toInt()
        return (WeekHomePage + weeks).coerceIn(0, WeekPageCount - 1)
    }
}

/**
 * Week pager for the collapsed calendar. Picking another week keeps the same weekday, which is the
 * day the rest of the screen follows.
 */
@Composable
private fun rememberWeekPager(
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
): WeekPager {
    val pagerState = rememberPagerState(initialPage = WeekHomePage) { WeekPageCount }
    // Saved with the pager so a restored page index still maps to the week it was showing.
    val savedBaseWeek = rememberSaveable { selected.startOfWeek().toString() }
    val pager = remember(pagerState, savedBaseWeek) {
        WeekPager(pagerState, LocalDate.parse(savedBaseWeek))
    }

    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // A week picked outside the pager (a cell tap, the day pager) moves it there; weeks that come
    // from paging already sit under the settled page, so nothing moves.
    LaunchedEffect(selected) {
        val page = pager.pageOf(selected)
        if (page != pagerState.currentPage) pagerState.scrollToPage(page)
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val target = pager.weekStartAt(page).plusDays(currentSelected.mondayIndex)
            if (target != currentSelected) currentOnSelect(target)
        }
    }
    return pager
}

/** A single Monday-first week, drawn with the same cells as the month grid. */
@Composable
private fun WeekRow(
    weekStart: LocalDate,
    month: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().height(MonthRowHeight)) {
        weekStart.weekDates().forEach { date ->
            MonthCell(
                date = date,
                inMonth = date.month == month.month && date.year == month.year,
                selected = date == selected,
                isToday = date == today,
                hasEvents = hasEvents(date),
                onClick = { onSelect(date) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            )
        }
    }
}

/** Snapped to a page with nothing animating: not dragging, not settling, not between pages. */
private val PagerState.isAtRest: Boolean
    get() = !isScrollInProgress && currentPageOffsetFraction == 0f

@Composable
private fun rememberMonthPager(
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
private fun WeekdayHeader(strings: Strings) {
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

/**
 * Month label that fades between pages. The outgoing label leaves as the pager offset grows so the
 * title follows the finger instead of jumping when the page settles.
 */
@Composable
private fun MonthCaption(
    strings: Strings,
    month: LocalDate,
    fraction: Float,
    modifier: Modifier = Modifier,
) {
    val alpha = (1f - abs(fraction) * 2f).coerceIn(0.35f, 1f)
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = strings.monthTitle(month.year, month.month),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = alpha),
        )
    }
}

@Composable
private fun MonthPage(
    month: LocalDate,
    days: List<LocalDate>,
    selected: LocalDate,
    today: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    // Fill the pager so short months stay top-aligned; otherwise the pager centres the page and
    // the first row would not line up with the folding window.
    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
        days.chunked(7).forEach { week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MonthRowHeight),
            ) {
                week.forEach { date ->
                    MonthCell(
                        date = date,
                        inMonth = date.month == month.month && date.year == month.year,
                        selected = date == selected,
                        isToday = date == today,
                        hasEvents = hasEvents(date),
                        onClick = { onSelect(date) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    date: LocalDate,
    inMonth: Boolean,
    selected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier,
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
                        selected -> Modifier.border(2.dp, scheme.primary, shape)
                        else -> Modifier
                    },
                )
                .clickable(onClick = onClick),
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
                        isToday || !hasEvents || !inMonth -> Color.Transparent
                        selected -> scheme.primary
                        else -> scheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                    },
                ),
        )
    }
}
