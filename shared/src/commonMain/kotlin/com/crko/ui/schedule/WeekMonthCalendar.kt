package com.crko.ui.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.crko.domain.mondayIndex
import com.crko.domain.monthGrid
import com.crko.domain.plusDays
import com.crko.domain.startOfMonth
import com.crko.domain.startOfWeek
import com.crko.i18n.Strings
import kotlinx.coroutines.flow.collectLatest
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt

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
    val drive = rememberWeekStripDrive(driveStrip, weekPager, selected, fold, anchorMonth, onMonthChange)

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
    val currentSelected by rememberUpdatedState(selected)
    val currentToday by rememberUpdatedState(today)
    val currentHasEvents by rememberUpdatedState(hasEvents)
    val currentOnSelect by rememberUpdatedState(onSelect)
    // One stable handler for every cell: a fresh lambda per recomposition would recompose the
    // whole row of cells whenever anything above merely re-executes.
    val onSelectDate: (LocalDate) -> Unit = remember { { currentOnSelect(it) } }
    val currentShowWeekSelection by rememberUpdatedState(showWeekSelection)
    val currentSelectionPosition by rememberUpdatedState(drive.selectionPosition)

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
        Spacer(Modifier.height(6.dp))
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
                MonthGridPanel(
                    pager = pager,
                    selectedRow = selectedRow,
                    rowHeightPx = rowHeightPx,
                    fold = fold,
                    scrollable = monthGridScrollable,
                    pageContent = monthPage,
                )
            } else {
                // The week pager draws the same row the month grid would, so the switch as the
                // calendar folds open is invisible.
                WeekStripPanel(
                    weekPager = weekPager,
                    drive = drive,
                    driveStrip = driveStrip,
                    selected = currentSelected,
                    today = currentToday,
                    hasEvents = currentHasEvents,
                    showSelection = currentShowWeekSelection,
                    onSelect = onSelectDate,
                    pageContent = weekPage,
                )
            }
        }
    }
}

/**
 * Measured unbounded and top-aligned: the grid keeps its full size while the folding box clips it,
 * otherwise the small container would centre and swallow the pager.
 */
@Composable
private fun MonthGridPanel(
    pager: MonthPager,
    selectedRow: Int,
    rowHeightPx: Float,
    fold: CalendarFoldState,
    scrollable: Boolean,
    pageContent: @Composable PagerScope.(Int) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.Top, unbounded = true),
    ) {
        HorizontalPager(
            state = pager.state,
            userScrollEnabled = scrollable,
            modifier = Modifier
                .fillMaxWidth()
                .height(MonthRowHeight * MaxMonthRows.toFloat())
                .offset {
                    IntOffset(
                        0,
                        (-(1f - fold.progress) * selectedRow * rowHeightPx).roundToInt(),
                    )
                },
            pageContent = pageContent,
        )
    }
}

/**
 * The collapsed week row, plus the pair of weeks that stands in while the pager below crosses a
 * week edge.
 */
@Composable
private fun WeekStripPanel(
    weekPager: WeekPager,
    drive: WeekStripDrive,
    driveStrip: WeekStrip?,
    selected: LocalDate,
    today: LocalDate,
    hasEvents: (LocalDate) -> Boolean,
    showSelection: Boolean,
    onSelect: (LocalDate) -> Unit,
    pageContent: @Composable PagerScope.(Int) -> Unit,
) {
    val pair = drive.stripPair
    val pairMonths = drive.stripPairMonths
    val progress = drive.stripProgress
    val visible = drive.showPair
    val strip = driveStrip
    // The pair of weeks takes the place of the week pager while the pager below crosses a week
    // edge. A week axis crosses on every flip, so there both stay composed and only their
    // visibility is switched, in measure and in draw: recomposing one of them in place of the
    // other would compose a whole row of cells on the animation frame, every swipe. A day axis
    // only crosses between Sunday and Monday, so it keeps swapping them.
    if (pair != null && strip != null && strip.isWeekAxis) {
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
                    // Read in the draw phase; a crossing only flips this skip, and the pager
                    // behind the pair does not draw while it is covered.
                    .drawWithContent { if (!visible()) drawContent() },
                pageContent = pageContent,
            )
            WeekStripRow(
                left = pair.first,
                right = pair.second,
                leftMonth = pairMonths?.first ?: selected.startOfMonth(),
                rightMonth = pairMonths?.second ?: selected.startOfMonth(),
                progress = progress,
                vertical = strip.vertical,
                selected = if (showSelection) selected else null,
                today = today,
                hasEvents = hasEvents,
                showSelection = showSelection,
                onSelect = onSelect,
                selectionPosition = drive.selectionPosition,
                visible = visible,
            )
        }
    } else {
        val crossingPair = if (pair != null && visible()) pair else null
        if (crossingPair != null) {
            // The selection frame rides the crossing: drawn on both rows at the driven position,
            // the row outside the viewport clips its copy, so one frame glides from the old cell
            // onto where the incoming week's cell lands and hands over between the rows.
            WeekStripRow(
                left = crossingPair.first,
                right = crossingPair.second,
                leftMonth = pairMonths?.first ?: selected.startOfMonth(),
                rightMonth = pairMonths?.second ?: selected.startOfMonth(),
                progress = progress,
                vertical = strip?.vertical == true,
                selected = if (showSelection) selected else null,
                today = today,
                hasEvents = hasEvents,
                showSelection = showSelection,
                onSelect = onSelect,
                selectionPosition = drive.selectionPosition,
            )
        } else {
            HorizontalPager(
                state = weekPager.state,
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(MonthRowHeight),
                pageContent = pageContent,
            )
        }
    }
}

/**
 * State the collapsed week strip follows: the pair of weeks to draw while [WeekStrip] below the
 * calendar crosses an edge, the month those rows report upward, and the floating selection frame.
 * Read while laying out the row where possible, so following the finger does not recompose it.
 */
@Stable
internal class WeekStripDrive internal constructor(
    private val strip: WeekStrip?,
    private val weekPager: WeekPager,
    private val selected: State<LocalDate>,
    private val slide: Animatable<Float, AnimationVector1D>?,
) {
    private var pendingWeek by mutableStateOf<LocalDate?>(null)

    // The collapsed row can be moved from two sides: its own pager when swiped directly, and the
    // pager below while its swipe crosses a week edge. The latter draws both weeks and slides
    // between them, staying on screen until the week pager has caught up with the selection;
    // dropping it at the page edge would flash the old week for a frame.
    val stripPair: Pair<LocalDate, LocalDate>? by derivedStateOf {
        strip?.pairAt(strip.state.currentPage, strip.state.currentPageOffsetFraction)
    }
    private val stripCrossing: Boolean by derivedStateOf {
        strip?.isCrossing(strip.state.currentPage, strip.state.currentPageOffsetFraction) == true
    }

    // The pair stands in for the week pager while the pager below crosses a week edge, and stays
    // until the week pager has caught up with the selection it reported.
    val showPair: () -> Boolean = { strip != null && (stripCrossing || pendingWeek != null) }

    // Months of the two rows the pair draws. Read while composing only when both change — a page
    // boundary — so the drag itself does not recompose the row.
    val stripPairMonths: Pair<LocalDate, LocalDate>? by derivedStateOf {
        strip?.pairMonthsAt(
            strip.state.currentPage,
            strip.state.currentPageOffsetFraction,
            selected.value.mondayIndex,
        )
    }

    // Month the collapsed strip stands on: the pair row that owns the viewport while one is shown,
    // otherwise the week under the strip's own pager. Reported upward so the title above follows a
    // swipe instead of jumping to the new month once the pager settles.
    val stripMonth: LocalDate by derivedStateOf {
        val current = strip
        if (current == null || !showPair()) {
            weekPager.weekAt(weekPager.state.currentPage)
                .plusDays(selected.value.mondayIndex)
                .startOfMonth()
        } else {
            current.pairMonthAt(
                current.state.currentPage,
                current.state.currentPageOffsetFraction,
                selected.value.mondayIndex,
            )
        }
    }

    val stripProgress: () -> Float = {
        strip?.progressAt(strip.state.currentPage, strip.state.currentPageOffsetFraction) ?: 0f
    }

    val selectionPosition: (() -> Float)? = slide?.let { frame -> { frame.value } }

    internal fun resetPending() {
        pendingWeek = null
    }

    internal fun updatePending(page: Int, fraction: Float, weekPage: Int) {
        val strip = strip ?: return
        val driveWeek = strip.weekOf(page)
        pendingWeek = when {
            strip.isCrossing(page, fraction) -> driveWeek
            pendingWeek == null -> null
            weekPager.weekAt(weekPage) == driveWeek -> null
            else -> driveWeek
        }
    }
}

@Composable
internal fun rememberWeekStripDrive(
    driveStrip: WeekStrip?,
    weekPager: WeekPager,
    selected: LocalDate,
    fold: CalendarFoldState,
    monthAnchor: LocalDate,
    onMonthChange: (LocalDate) -> Unit,
): WeekStripDrive {
    val selectedState = rememberUpdatedState(selected)
    val currentMonthAnchor by rememberUpdatedState(monthAnchor)
    val currentOnMonthChange by rememberUpdatedState(onMonthChange)

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
            selfDrivenStrip -> Animatable(selectedState.value.mondayIndex.toFloat())
            else -> null
        }
    }

    val drive = remember(driveStrip, weekPager, selectionSlide) {
        WeekStripDrive(driveStrip, weekPager, selectedState, selectionSlide)
    }

    // The pager below and the week pager feed each other: one collector reports the week the strip
    // should stand on, and the pair stays until the week pager has caught up with it.
    LaunchedEffect(driveStrip, weekPager) {
        val strip = driveStrip ?: return@LaunchedEffect
        drive.resetPending()
        snapshotFlow {
            Triple(
                strip.state.currentPage,
                strip.state.currentPageOffsetFraction,
                weekPager.state.currentPage,
            )
        }.collect { (page, fraction, weekPage) ->
            drive.updatePending(page, fraction, weekPage)
        }
    }

    // Report the month the collapsed strip slid onto, but only while the calendar is folded.
    LaunchedEffect(driveStrip, weekPager) {
        snapshotFlow { drive.stripMonth.takeIf { fold.progress <= 0.001f } }
            .collect { month ->
                if (month != null && month != currentMonthAnchor.startOfMonth()) {
                    currentOnMonthChange(month)
                }
            }
    }

    LaunchedEffect(dayStrip) {
        val strip = dayStrip ?: return@LaunchedEffect
        val slide = selectionSlide ?: return@LaunchedEffect
        var previousPage = strip.state.currentPage
        snapshotFlow {
            Triple(
                strip.state.currentPage,
                strip.state.currentPageOffsetFraction,
                strip.state.isScrollInProgress,
            )
        }.collectLatest { (page, fraction, scrolling) ->
            val target = strip.dayPosition(page, fraction)
            // A flip across the Sunday/Monday edge wraps the position from one end of the row to
            // the other. While scrolling the pair row carries the frame over; on a jump (a pick
            // elsewhere, or a settle whose final emissions conflate) the pair has already slid the
            // new week in, so the frame lands where the row left it instead of gliding back
            // across the row it just arrived on.
            val wrapped = strip.weekOf(page) != strip.weekOf(previousPage)
            previousPage = page
            when {
                scrolling || wrapped -> slide.snapTo(target)
                else -> slide.animateTo(target, tween(220))
            }
        }
    }

    // The day timeline reports a day the moment a block crosses its top edge, so the frame animates
    // wherever the selection lands: across the row for another day of the same week, or straight to
    // its cell for a day in another week, where the week pager slides the weeks themselves.
    LaunchedEffect(selfDrivenStrip, selectionSlide) {
        if (!selfDrivenStrip) return@LaunchedEffect
        val slide = selectionSlide ?: return@LaunchedEffect
        var previousWeek = selectedState.value.startOfWeek()
        snapshotFlow { selectedState.value }.collectLatest { date ->
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
    return drive
}
