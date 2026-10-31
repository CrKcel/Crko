package com.crko.ui.schedule

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
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
import kotlinx.coroutines.flow.collectLatest
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
private const val MaxMonthRows = 6
private val FoldFlingVelocity = 400.dp

/**
 * Fold progress of the week/month calendar. It is owned above the grid so the drag surface can
 * span the title row as well as the calendar itself.
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
 * title row responds to the fold gesture as well as the grid.
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
 * [driveStrip] is the pager below the calendar, when one is showing (the list pager, or the week
 * overview's timeline pager): the collapsed week row then slides with it, sideways or up and down,
 * so a swipe that flips the week carries the calendar above along. Without one — the day timeline,
 * which scrolls continuously — the strip animates its own selection instead.
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
    showWeekSelection: Boolean,
    onSelect: (LocalDate) -> Unit,
    onMonthChange: (LocalDate) -> Unit,
    driveStrip: WeekStrip?,
) {
    val density = LocalDensity.current
    val rowHeightPx = with(density) { MonthRowHeight.toPx() }

    // The unfold offset is measured inside the selected week's month. It only matters while
    // folding, and collapsing first sends the pager back to that month.
    val selectedRow = remember(selected) { weekOfMonthIndex(selected) }

    LaunchedEffect(expanded) { fold.settle(if (expanded) 1f else 0f) }

    val anchorMonth = monthAnchor.startOfMonth()
    val pager = rememberMonthPager(anchorMonth, onMonthChange)
    // Only the strip of the day timeline has no pager below to slide it; it animates across weeks
    // itself rather than jumping when the timeline scrolls into the next one.
    val weekPager = rememberWeekPager(selected, onSelect, animatePicks = driveStrip == null)
    val currentSelected by rememberUpdatedState(selected)

    // The collapsed row can be moved from two sides: its own pager when swiped directly, and the
    // pager below while its swipe crosses a week edge. The latter draws both weeks and slides between
    // them, staying on screen until the week pager has caught up with the selection; dropping it at
    // the page edge would flash the old week for a frame.
    val stripPair by remember(driveStrip) {
        derivedStateOf {
            val strip = driveStrip ?: return@derivedStateOf null
            strip.pairAt(strip.state.currentPage, strip.state.currentPageOffsetFraction)
        }
    }
    val stripCrossing by remember(driveStrip) {
        derivedStateOf {
            val strip = driveStrip ?: return@derivedStateOf false
            strip.isCrossing(strip.state.currentPage, strip.state.currentPageOffsetFraction)
        }
    }
    var pendingWeek by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(driveStrip, weekPager) {
        pendingWeek = null
        val strip = driveStrip ?: return@LaunchedEffect
        snapshotFlow {
            Triple(
                strip.state.currentPage,
                strip.state.currentPageOffsetFraction,
                weekPager.state.currentPage,
            )
        }.collect { (page, fraction, weekPage) ->
            val driveWeek = strip.weekOf(page)
            pendingWeek = when {
                strip.isCrossing(page, fraction) -> driveWeek
                pendingWeek == null -> null
                weekPager.weekAt(weekPage) == driveWeek -> null
                else -> driveWeek
            }
        }
    }
    // The pair stands in for the week pager while the pager below crosses a week edge, and stays
    // until the week pager has caught up with the selection it reported.
    val showPair: () -> Boolean = remember(driveStrip) {
        { driveStrip != null && (stripCrossing || pendingWeek != null) }
    }
    // Months of the two rows the pair draws. Read while composing only when both change — a page
    // boundary — so the drag itself does not recompose the row.
    val stripPairMonths by remember(driveStrip) {
        derivedStateOf {
            val strip = driveStrip ?: return@derivedStateOf null
            strip.pairMonthsAt(
                strip.state.currentPage,
                strip.state.currentPageOffsetFraction,
                currentSelected.mondayIndex,
            )
        }
    }
    // Month the collapsed strip stands on: the pair row that owns the viewport while one is shown,
    // otherwise the week under the strip's own pager. Reported upward so the title above follows a
    // swipe instead of jumping to the new month once the pager settles.
    val stripMonth by remember(driveStrip, weekPager) {
        derivedStateOf {
            val strip = driveStrip
            if (strip == null || !showPair()) {
                weekPager.weekAt(weekPager.state.currentPage)
                    .plusDays(currentSelected.mondayIndex)
                    .startOfMonth()
            } else {
                strip.pairMonthAt(
                    strip.state.currentPage,
                    strip.state.currentPageOffsetFraction,
                    currentSelected.mondayIndex,
                )
            }
        }
    }
    val currentMonthAnchor by rememberUpdatedState(monthAnchor)
    val currentOnMonthChange by rememberUpdatedState(onMonthChange)
    LaunchedEffect(driveStrip, weekPager) {
        snapshotFlow { stripMonth.takeIf { fold.progress <= 0.001f } }
            .collect { month ->
                if (month != null && month != currentMonthAnchor.startOfMonth()) {
                    currentOnMonthChange(month)
                }
            }
    }
    val stripProgress: () -> Float = remember(driveStrip) {
        {
            driveStrip?.let {
                it.progressAt(it.state.currentPage, it.state.currentPageOffsetFraction)
            } ?: 0f
        }
    }

    // The collapsed day strip floats one frame over its week instead of outlining the selected cell,
    // so the frame can glide: it rides the day pager (tracks the finger across the row, follows the
    // settle onto the date being swiped to, glides after a tapped date) and, above the day timeline,
    // walks to the day the scrolling blocks selected. Only a day axis does this; the week overview
    // hides the selection and slides whole weeks.
    val dayStrip = driveStrip?.takeIf { !it.isWeekAxis }
    // The day timeline scrolls continuously and has no pager to drive the strip, so its selection
    // arrives day by day; the strip animates those changes itself.
    val selfDrivenStrip = driveStrip == null
    val selectionSlide = remember(dayStrip, selfDrivenStrip) {
        when {
            dayStrip != null -> Animatable(
                dayStrip.dayPosition(
                    dayStrip.state.currentPage,
                    dayStrip.state.currentPageOffsetFraction,
                ),
            )
            selfDrivenStrip -> Animatable(selected.mondayIndex.toFloat())
            else -> null
        }
    }
    LaunchedEffect(dayStrip) {
        val strip = dayStrip ?: return@LaunchedEffect
        val slide = selectionSlide ?: return@LaunchedEffect
        snapshotFlow {
            Triple(
                strip.state.currentPage,
                strip.state.currentPageOffsetFraction,
                strip.state.isScrollInProgress,
            )
        }.collectLatest { (page, fraction, scrolling) ->
            val target = strip.dayPosition(page, fraction)
            if (scrolling) slide.snapTo(target) else slide.animateTo(target, tween(220))
        }
    }
    // Read while laying the row out, so a drag only re-lays the frame instead of recomposing it.
    val selectionPosition: (() -> Float)? = remember(selectionSlide) {
        selectionSlide?.let { slide -> { slide.value } }
    }

    // Progress changes on every animation frame, so it is only read in the layout/draw passes or
    // through a derived state that flips at most twice per fold. Reading it during composition
    // would recompose the grid, the pagers and every date cell on each frame.
    val showMonthGrid by remember(fold) { derivedStateOf { fold.progress > 0.001f } }
    val monthGridScrollable by remember(fold) { derivedStateOf { fold.progress > 0.99f } }

    // Height follows the month on screen, not the selected week's month: a six-week month opened
    // from a five-week one must grow instead of clipping its last row. Animated so paging between
    // months of different lengths does not snap.
    //
    // Resolved through a derived state: the header only cares about the row count, which is the
    // same across most page settles, so paging must not recompose the header and its pagers.
    val monthRows by remember(pager) {
        derivedStateOf {
            val shownMonth = pager.monthAt(pager.state.currentPage)
            shownMonth.monthGrid().size / 7
        }
    }
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
    // The pager is only composed while the calendar is open. Its state survives; make sure it opens
    // on the month the header is naming even if a jump happened while it was hidden.
    LaunchedEffect(showMonthGrid) {
        if (!showMonthGrid) return@LaunchedEffect
        val page = pager.pageOf(anchorMonth)
        if (pager.state.currentPage != page) pager.state.scrollToPage(page)
    }

    // Pager content is remembered and reads the latest parameters through states: a fresh content
    // lambda per recomposition would rebuild the pager item provider and recompose its pages along
    // with it.
    val currentToday by rememberUpdatedState(today)
    val currentHasEvents by rememberUpdatedState(hasEvents)
    val currentOnSelect by rememberUpdatedState(onSelect)
    // One stable handler for every cell: a fresh lambda per recomposition would recompose the
    // whole row of cells whenever anything above merely re-executes.
    val onSelectDate: (LocalDate) -> Unit = remember { { currentOnSelect(it) } }
    val currentShowWeekSelection by rememberUpdatedState(showWeekSelection)
    val currentSelectionPosition by rememberUpdatedState(selectionPosition)

    // The day timeline reports a day the moment a block crosses its top edge, so the frame animates
    // wherever the selection lands: across the row for another day of the same week, or straight to
    // its cell for a day in another week, where the week pager slides the weeks themselves.
    LaunchedEffect(selfDrivenStrip, selectionSlide) {
        if (!selfDrivenStrip) return@LaunchedEffect
        val slide = selectionSlide ?: return@LaunchedEffect
        var previousWeek = currentSelected.startOfWeek()
        snapshotFlow { currentSelected }.collectLatest { date ->
            val week = date.startOfWeek()
            val weeks = (week.toEpochDays() - previousWeek.toEpochDays()) / 7
            previousWeek = week
            if (weeks == 0L) {
                slide.animateTo(date.mondayIndex.toFloat(), tween(220))
            } else {
                slide.snapTo(date.mondayIndex.toFloat())
            }
        }
    }

    val monthPage: @Composable PagerScope.(Int) -> Unit = remember(pager) {
        { page ->
            val month = pager.monthAt(page)
            MonthPage(
                month = month,
                days = remember(month) { month.monthGrid() },
                selected = currentSelected,
                today = currentToday,
                hasEvents = currentHasEvents,
                onSelect = onSelectDate,
            )
        }
    }
    val weekPage: @Composable PagerScope.(Int) -> Unit = remember(weekPager) {
        { page ->
            val weekStart = weekPager.weekAt(page)
            WeekRow(
                weekStart = weekStart,
                // Colored by the month this page lands on — the selected weekday's month — so a
                // week swipe already shows the incoming month's dates as in-month while sliding.
                month = weekStart.plusDays(currentSelected.mondayIndex).startOfMonth(),
                // A hidden selection draws nothing, so it is passed as null: a week swipe then
                // settles without recomposing every page of this pager over a selection that is
                // not shown anyway.
                selected = if (currentShowWeekSelection) currentSelected else null,
                today = currentToday,
                hasEvents = currentHasEvents,
                showSelection = currentShowWeekSelection,
                onSelect = onSelectDate,
                selectionPosition = currentSelectionPosition,
            )
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        WeekdayHeader(strings)
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clipToBounds()
                .foldRevealHeight(fold, rowHeightPx) { foldPx },
        ) {
            // Collapsed, the week row is the only thing shown and the month grid is not composed at
            // all: keeping it composed and transparent meant its 42 cells recomposed on every
            // selection change while invisible. The pager state stays above, so page jumps made
            // while folded still land.
            if (showMonthGrid) {
                // Measured unbounded and top-aligned: the grid keeps its full size while the
                // folding box clips it, otherwise the small container would centre and swallow
                // the pager.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(align = Alignment.Top, unbounded = true),
                ) {
                    HorizontalPager(
                        state = pager.state,
                        userScrollEnabled = monthGridScrollable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MonthRowHeight * MaxMonthRows.toFloat())
                            .offset {
                                IntOffset(
                                    0,
                                    (-(1f - fold.progress) * selectedRow * rowHeightPx).roundToInt(),
                                )
                            },
                        pageContent = monthPage,
                    )
                }
            }

            // The week pager draws the same row the month grid would, so the switch as the
            // calendar folds open is invisible.
            if (!showMonthGrid) {
                val pair = stripPair
                val pairMonths = stripPairMonths
                // The pair of weeks takes the place of the week pager while the pager below crosses
                // a week edge. A week axis crosses on every flip, so there both stay composed and
                // only their visibility is switched, in measure and in draw: recomposing one of
                // them in place of the other would compose a whole row of cells on the animation
                // frame, every swipe. A day axis only crosses between Sunday and Monday, so it
                // keeps swapping them.
                if (pair != null && driveStrip?.isWeekAxis == true) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MonthRowHeight),
                    ) {
                        HorizontalPager(
                            state = weekPager.state,
                            beyondViewportPageCount = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MonthRowHeight)
                                // Read in the draw phase; a crossing only flips this skip, and the
                                // pager behind the pair does not draw while it is covered.
                                .drawWithContent { if (!showPair()) drawContent() },
                            pageContent = weekPage,
                        )
                        WeekStripRow(
                            left = pair.first,
                            right = pair.second,
                            leftMonth = pairMonths?.first ?: currentSelected.startOfMonth(),
                            rightMonth = pairMonths?.second ?: currentSelected.startOfMonth(),
                            progress = stripProgress,
                            vertical = driveStrip.vertical,
                            selected = if (currentShowWeekSelection) currentSelected else null,
                            today = currentToday,
                            hasEvents = currentHasEvents,
                            showSelection = currentShowWeekSelection,
                            onSelect = onSelectDate,
                            visible = showPair,
                        )
                    }
                } else {
                    val crossingPair = if (pair != null && showPair()) pair else null
                    if (crossingPair != null) {
                        WeekStripRow(
                            left = crossingPair.first,
                            right = crossingPair.second,
                            leftMonth = pairMonths?.first ?: currentSelected.startOfMonth(),
                            rightMonth = pairMonths?.second ?: currentSelected.startOfMonth(),
                            progress = stripProgress,
                            vertical = driveStrip?.vertical == true,
                            selected = if (currentShowWeekSelection) currentSelected else null,
                            today = currentToday,
                            hasEvents = currentHasEvents,
                            showSelection = currentShowWeekSelection,
                            onSelect = onSelectDate,
                        )
                    } else {
                        HorizontalPager(
                            state = weekPager.state,
                            beyondViewportPageCount = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(MonthRowHeight),
                            pageContent = weekPage,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Height of the visible calendar window. The fold progress and the animated hidden height are read
 * while measuring, so dragging and settling only re-layout the header instead of recomposing it and
 * everything below.
 */
private fun Modifier.foldRevealHeight(
    fold: CalendarFoldState,
    rowHeightPx: Float,
    animatedFoldPx: () -> Float,
): Modifier =
    layout { measurable, constraints ->
        val height = (rowHeightPx + fold.progress * animatedFoldPx()).roundToInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, height) { placeable.place(0, 0) }
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
                days = remember(month) { month.monthGrid() },
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

/** A single Monday-first week, drawn with the same cells as the month grid. */
@Composable
private fun WeekRow(
    weekStart: LocalDate,
    month: LocalDate,
    selected: LocalDate?,
    today: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    showSelection: Boolean,
    onSelect: (LocalDate) -> Unit,
    selectionPosition: (() -> Float)? = null,
) {
    // The dates and their event dots only depend on the week and the event list, so a selection or
    // a day rollover inside the same week does not rescan every event for all seven cells.
    val dates = remember(weekStart) { weekStart.weekDates() }
    val daysWithEvents = remember(dates, hasEvents) { dates.associateWith(hasEvents) }
    // The floating frame belongs to whichever week row is on screen: riding every page lets a week
    // swipe carry the highlight in with the incoming week instead of popping it in on settle. Two
    // weeks of a crossing draw no frame at all (the pair passes no position), so only the pager's
    // own pages ever show it.
    val floating = selectionPosition != null && selected != null && showSelection
    Box(modifier = Modifier.fillMaxWidth().height(MonthRowHeight)) {
        Row(modifier = Modifier.fillMaxSize()) {
            dates.forEach { date ->
                MonthCell(
                    date = date,
                    inMonth = date.month == month.month && date.year == month.year,
                    // In the week overview the whole week is on screen, so a single selected day
                    // means nothing there; the row keeps only the today marker.
                    selected = showSelection && date == selected,
                    drawSelectedBorder = !floating,
                    isToday = date == today,
                    hasEvents = daysWithEvents[date] == true,
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
 */
@Composable
private fun WeekStripRow(
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
                        selected = date == selected,
                        isToday = date == today,
                        hasEvents = daysWithEvents[date] == true,
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
