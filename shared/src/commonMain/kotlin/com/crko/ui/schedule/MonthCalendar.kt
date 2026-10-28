package com.crko.ui.schedule

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.monthGrid
import com.crko.domain.plusMonths
import com.crko.domain.startOfMonth
import com.crko.i18n.Strings
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val PageCount = 2400
private const val HomePage = PageCount / 2
private const val MaxMonthRows = 6
private val FoldFlingVelocity = 400.dp

/**
 * Weekday header pinned over a paged month grid that unfolds around the selected week. The header
 * is the only place weekday labels appear; every week — the selected one included — is drawn by the
 * same month cell, so collapsing simply reveals one row of the same grid.
 *
 * [expanded] is the settled state; while dragging, the revealed height follows the finger directly.
 */
@Composable
internal fun WeekMonthCalendar(
    strings: Strings,
    selected: LocalDate,
    today: LocalDate,
    monthAnchor: LocalDate,
    eventDates: Set<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { MonthRowHeight.toPx() }
    val flingVelocityPx = with(density) { FoldFlingVelocity.toPx() }

    // Geometry follows the selected week's month: the grid must not move while paging months.
    val selectedRow = remember(selected) { weekOfMonthIndex(selected) }
    val monthRows = remember(selected) { selected.startOfMonth().monthGrid().size / 7 }
    val foldPx = (monthRows - 1) * rowHeightPx

    var progress by rememberSaveable { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }

    fun settle(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = progress,
                targetValue = target,
                animationSpec = tween(if (target > progress) 280 else 220),
            ) { value, _ -> progress = value }
            progress = target
        }
    }

    LaunchedEffect(expanded) { settle(if (expanded) 1f else 0f) }

    val anchorMonth = monthAnchor.startOfMonth()
    val pagerState = rememberMonthPagerState(anchorMonth, onMonthChange)

    // Folding back always returns to the selected week; paging may have left another month shown.
    LaunchedEffect(expanded, selected) {
        if (!expanded && anchorMonth.plusMonths(pagerState.currentPage - HomePage) != selected.startOfMonth()) {
            pagerState.scrollToPage(HomePage)
            onMonthChange(selected.startOfMonth())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .draggable(
                state = rememberDraggableState { delta ->
                    if (foldPx > 0f) {
                        progress = (progress + delta / foldPx).coerceIn(0f, 1f)
                    }
                },
                orientation = Orientation.Vertical,
                onDragStarted = { settleJob?.cancel() },
                onDragStopped = { velocity ->
                    val target = when {
                        velocity >= flingVelocityPx -> 1f
                        velocity <= -flingVelocityPx -> 0f
                        progress >= 0.5f -> 1f
                        else -> 0f
                    }
                    if ((target == 1f) == expanded) settle(target) else onExpandedChange(target == 1f)
                },
            ),
    ) {
        WeekdayHeader(strings)
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .height(with(density) { (rowHeightPx + progress * foldPx).toDp() }),
        ) {
            // Measured unbounded and top-aligned: the grid keeps its full size while the folding
            // box clips it, otherwise the small container would centre and swallow the pager.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = progress > 0.99f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MonthRowHeight * MaxMonthRows.toFloat())
                        .offset { IntOffset(0, (-(1f - progress) * selectedRow * rowHeightPx).roundToInt()) },
                ) { page ->
                    MonthPage(
                        month = anchorMonth.plusMonths(page - HomePage),
                        days = anchorMonth.plusMonths(page - HomePage).monthGrid(),
                        selected = selected,
                        today = today,
                        eventDates = eventDates,
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
    eventDates: Set<String>,
    strings: Strings,
    monthAnchor: LocalDate,
    selected: LocalDate,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val anchorMonth = monthAnchor.startOfMonth()
    val pagerState = rememberMonthPagerState(anchorMonth, onMonthChange)

    Column(modifier = modifier.fillMaxWidth()) {
        WeekdayHeader(strings)
        Spacer(Modifier.height(2.dp))
        MonthCaption(
            strings = strings,
            month = anchorMonth.plusMonths(pagerState.currentPage - HomePage),
            fraction = pagerState.currentPageOffsetFraction,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(MonthRowHeight * MaxMonthRows.toFloat()),
        ) { page ->
            MonthPage(
                month = anchorMonth.plusMonths(page - HomePage),
                days = anchorMonth.plusMonths(page - HomePage).monthGrid(),
                selected = selected,
                today = today,
                eventDates = eventDates,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun rememberMonthPagerState(
    anchorMonth: LocalDate,
    onMonthChange: (LocalDate) -> Unit,
): PagerState {
    val pagerState = rememberPagerState(initialPage = HomePage) { PageCount }

    // Selection outside the pager (week row, today) recenters the pager on the anchor month.
    LaunchedEffect(anchorMonth) {
        val pageMonth = anchorMonth.plusMonths(pagerState.currentPage - HomePage)
        if (pageMonth != anchorMonth) pagerState.scrollToPage(HomePage)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            if (page != HomePage) onMonthChange(anchorMonth.plusMonths(page - HomePage))
        }
    }
    return pagerState
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
    eventDates: Set<String>,
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
                        hasEvents = date.toString() in eventDates,
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
    Column(
        modifier = modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    when {
                        selected -> scheme.primary
                        isToday -> scheme.primaryContainer
                        else -> Color.Transparent
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.day.toString(),
                style = MiuixTheme.textStyles.body1,
                fontSize = 14.sp,
                fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
                color = when {
                    selected -> scheme.onPrimary
                    isToday -> scheme.onPrimaryContainer
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
