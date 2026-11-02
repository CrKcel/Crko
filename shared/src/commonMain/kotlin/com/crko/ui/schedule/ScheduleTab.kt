package com.crko.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalClock
import com.crko.domain.MaxEndMinutes
import com.crko.domain.minutesOfDay
import com.crko.domain.occursOn
import com.crko.domain.startOfMonth
import com.crko.domain.startOfWeek
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import com.crko.i18n.ZhStrings
import com.crko.state.AppStateHolder
import com.crko.ui.components.PopupListLayout
import com.crko.ui.components.PopupTitle
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.crko.ui.icons.AppIcons

@Composable
fun ScheduleTab(
    state: AppStateHolder,
    now: State<LocalClock>,
    strings: Strings,
    contentPadding: PaddingValues,
    onOpenEvent: (Event, LocalDate) -> Unit,
) {
    val today = state.today
    var selectedName by rememberSaveable { mutableStateOf(today.toString()) }
    var monthAnchorName by rememberSaveable { mutableStateOf(today.startOfMonth().toString()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var listMode by rememberSaveable { mutableStateOf(state.settings.agendaView == "list") }
    var weekMode by rememberSaveable { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    // Signed finger travel of the swipe that opened the current view; the switch animation enters
    // from the opposite side so the new view follows the finger.
    var switchTravel by remember { mutableFloatStateOf(0f) }
    val layoutDirection = LocalLayoutDirection.current

    val selected = remember(selectedName) { LocalDate.parse(selectedName) }
    val monthAnchor = remember(monthAnchorName) { LocalDate.parse(monthAnchorName) }
    val events = state.appState.events
    val hasEvents = remember(events) { { date: LocalDate -> events.any { it.occursOn(date) } } }
    val fold = rememberCalendarFold()
    // The calendar toggles from the header block without a ripple: the movement of the calendar
    // itself is the feedback.
    val titleInteraction = remember { MutableInteractionSource() }

    fun select(date: LocalDate) {
        selectedName = date.toString()
        val month = date.startOfMonth()
        if (month != monthAnchor) monthAnchorName = month.toString()
    }

    // The view menu and the view-switch swipes share one path so a gesture keeps the chosen view
    // across restarts just like the menu does. [travel] is the signed swipe that opened the view;
    // a leftward or upward swipe pushes the old view that way and brings the new one in from the
    // right or below. The menu has no finger to follow, so it pushes the new view in the same way.
    fun showListView(show: Boolean, travel: Float? = null) {
        switchTravel = travel ?: -1f
        listMode = show
        state.setAgendaView(if (show) "list" else "timeline")
    }

    // Handlers are remembered so their identity survives recomposition. A fresh function reference
    // per recomposition would count as a changed argument all the way down, recomposing the calendar
    // and rebuilding the timeline's lazy item provider whenever anything above merely re-executes.
    val currentToday by rememberUpdatedState(today)
    val currentOnOpenEvent by rememberUpdatedState(onOpenEvent)
    // The reference wraps a local function that closes over the parsed week/month anchors, so it
    // must be routed through the latest value rather than captured once by the remembered wrapper.
    val currentSelect by rememberUpdatedState<(LocalDate) -> Unit>(::select)
    val onSelectDate: (LocalDate) -> Unit = remember { { currentSelect(it) } }
    val onOpenStable: (Event, LocalDate) -> Unit = remember { { event, date -> currentOnOpenEvent(event, date) } }
    val onAddAt: (LocalDate, Int?) -> Unit = remember {
        { date, slotMinutes ->
            val start = when {
                slotMinutes != null -> slotMinutes
                date == currentToday -> ((now.value.time.minutesOfDay() / SlotMinutes) + 1) * SlotMinutes
                else -> state.settings.agendaScrollHour * 60
            }
            val startMinutes = start.coerceAtMost(24 * 60 - SlotMinutes)
            val endMinutes = (startMinutes + 60).coerceAtMost(MaxEndMinutes)
            currentOnOpenEvent(
                Event(
                    id = null,
                    title = "",
                    start = IcsTimeValue.Floating(
                        LocalDateTime(date, LocalTime(startMinutes / 60, startMinutes % 60)),
                    ),
                    end = IcsTimeValue.Floating(
                        LocalDateTime(date, LocalTime(endMinutes / 60, endMinutes % 60)),
                    ),
                ),
                date,
            )
        }
    }
    val onMonthChange: (LocalDate) -> Unit = remember { { monthAnchorName = it.toString() } }

    val dayPager = rememberDayPager(selected = selected, today = today, onSelect = onSelectDate)
    val overviewPager = rememberWeekPager(selected = selected, onSelect = onSelectDate)
    val timelineWeekPager = rememberWeekPager(selected = selected, onSelect = onSelectDate)
    // The calendar strip above follows the pager below: sideways in the list and up and down in the
    // week timeline, which flips one whole week at a time. The day timeline scrolls continuously and
    // has no such pager.
    val weekStrip = remember(listMode, weekMode, dayPager, overviewPager, timelineWeekPager) {
        when {
            !listMode && weekMode ->
                WeekStrip(timelineWeekPager.state, timelineWeekPager::weekAt, pageIsWeek = true, vertical = true)
            !listMode -> null
            weekMode -> WeekStrip(overviewPager.state, overviewPager::weekAt, pageIsWeek = true)
            else -> WeekStrip(dayPager.state, dayPager::dateAt, pageIsWeek = false)
        }
    }

    // Page content is remembered and reads the latest parameters through states: a fresh content
    // lambda per recomposition would rebuild the pager's item provider and recompose every page it
    // holds — the neighbouring days or weeks included — whenever the selection moves.
    val currentEvents by rememberUpdatedState(events)
    val currentStrings by rememberUpdatedState(strings)
    val currentShowListView by rememberUpdatedState<(Boolean, Float?) -> Unit>(::showListView)
    val onSwipeToTimeline: (Float) -> Unit = remember { { currentShowListView(false, it) } }

    val weekPage: @Composable PagerScope.(Int) -> Unit = remember(overviewPager) {
        { page ->
            val weekStart = overviewPager.weekAt(page)
            val weekDays = remember(weekStart) { weekStart.weekDates() }
            val weekEvents = remember(weekDays, currentEvents) {
                weekDays.associateWith { date -> state.eventsOn(date) }
            }
            WeekAgendaList(
                strings = currentStrings,
                today = currentToday,
                weekStart = weekStart,
                eventsByDate = weekEvents,
                onOpen = onOpenStable,
                onSwipeToTimeline = onSwipeToTimeline,
            )
        }
    }
    val dayPage: @Composable PagerScope.(Int) -> Unit = remember(dayPager) {
        { page ->
            val date = dayPager.dateAt(page)
            val pageEvents = remember(date, currentEvents) { state.eventsOn(date) }
            DayAgendaList(
                events = pageEvents,
                date = date,
                strings = currentStrings,
                onOpen = onOpenStable,
                onSwipeToTimeline = onSwipeToTimeline,
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding(),
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
            ),
    ) {
        // The fold gesture covers the title row as well, so the calendar can be pulled open or
        // pushed away from anywhere in the header block, not only over the grid.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .calendarFoldDrag(
                    fold = fold,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = titleInteraction,
                        indication = null,
                        onClickLabel = if (expanded) strings.collapseCalendar else strings.expandCalendar,
                    ) { expanded = !expanded }
                    .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The month of the date being shown: while unfolded the month pager reports what it
                // is browsing, while collapsed the strip reports the month it slides onto, so the
                // title follows a swipe (or the timeline) instead of jumping when it settles.
                Text(
                    text = strings.monthTitle(monthAnchor.year, monthAnchor.month),
                    modifier = Modifier.weight(1f),
                    style = if (strings === ZhStrings) MiuixTheme.textStyles.title3 else MiuixTheme.textStyles.title1,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Box {
                    IconButton(onClick = { showViewMenu = true }) {
                        Icon(
                            imageVector = when {
                                weekMode -> AppIcons.Week
                                listMode -> AppIcons.ListView
                                else -> AppIcons.Timeline
                            },
                            contentDescription = strings.switchView,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    WindowListPopup(show = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                        PopupListLayout {
                            PopupTitle(strings.tabSchedule)
                            ViewOption(strings.viewTimeline, !listMode) {
                                showListView(false)
                                showViewMenu = false
                            }
                            ViewOption(strings.viewList, listMode) {
                                showListView(true)
                                showViewMenu = false
                            }
                            ViewOption(strings.dayView, !weekMode) {
                                weekMode = false
                                showViewMenu = false
                            }
                            ViewOption(strings.weekOverview, weekMode) {
                                weekMode = true
                                showViewMenu = false
                            }
                        }
                    }
                }
            }

            WeekMonthCalendar(
                strings = strings,
                selected = selected,
                today = today,
                monthAnchor = monthAnchor,
                hasEvents = hasEvents,
                fold = fold,
                expanded = expanded,
                // The week overview shows the whole week already, so the collapsed strip drops the
                // selected-day box; unfolding the calendar brings the selection back.
                showWeekSelection = expanded || !weekMode,
                onSelect = onSelectDate,
                onMonthChange = onMonthChange,
                driveStrip = weekStrip,
            )
        }

        // The overview and the day view share this area; the upper calendar stays untouched.
        // A two-finger pinch opens the week overview and a spread closes it, while one finger runs
        // the date axis: on and on through the days in the timeline, one page at a time in the list.
        // Each view also yields to the other along the axis it does not use: sideways over the
        // timeline opens the list, and a pull past the list's edge returns to the timeline.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .weekOverviewGesture(
                    inWeekView = weekMode,
                    onEnter = { weekMode = true },
                    onExit = { weekMode = false },
                )
                // The timeline scrolls vertically, so a sideways swipe is free to open the list.
                .timelineSwipeToList(enabled = !listMode) { travel -> showListView(true, travel) },
        ) {
            AnimatedContent(
                targetState = weekMode,
                transitionSpec = {
                    val enterScale = if (targetState) 0.94f else 1.04f
                    (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = enterScale))
                        .togetherWith(fadeOut(tween(140)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "schedule-content",
            ) { inWeekView ->
                // The list is a horizontal surface and the timeline a vertical one, so each view
                // arrives along its own axis. The side it comes in from follows the swipe: the
                // outgoing view leaves the way the finger went and the incoming one follows behind.
                AnimatedContent(
                    targetState = listMode,
                    transitionSpec = {
                        val enterSign = if (switchTravel > 0f) -1 else 1
                        val enter = if (targetState) {
                            slideInHorizontally(tween(220)) { width -> width * enterSign / 4 } +
                                fadeIn(tween(180))
                        } else {
                            slideInVertically(tween(220)) { height -> height * enterSign / 4 } +
                                fadeIn(tween(180))
                        }
                        val exit = if (targetState) {
                            slideOutHorizontally(tween(160)) { width -> -width * enterSign / 4 } +
                                fadeOut(tween(140))
                        } else {
                            slideOutVertically(tween(160)) { height -> -height * enterSign / 4 } +
                                fadeOut(tween(140))
                        }
                        enter togetherWith exit
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "schedule-view",
                ) { asList ->
                    if (asList) {
                        // One day — or one week in the overview — per page. A sideways swipe moves
                        // to the adjacent date along the axis while the page itself scrolls
                        // vertically. A week page holds far more rows than a day page, so the
                        // pager keeps one extra week either side composed and a swipe never has to
                        // build a page on the frame it is animating.
                        HorizontalPager(
                            state = if (inWeekView) overviewPager.state else dayPager.state,
                            beyondViewportPageCount = if (inWeekView) 2 else 1,
                            modifier = Modifier.fillMaxSize(),
                            pageContent = if (inWeekView) weekPage else dayPage,
                        )
                    } else {
                        // No page boundary: the days run into each other and the scroll simply
                        // carries on into the next one.
                        ContinuousTimeline(
                            strings = strings,
                            today = today,
                            now = now,
                            events = events,
                            selected = selected,
                            weekPager = timelineWeekPager,
                            inWeekView = inWeekView,
                            defaultScrollHour = state.settings.agendaScrollHour,
                            onSelect = onSelectDate,
                            onAdd = onAddAt,
                            onOpen = onOpenStable,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewOption(text: String, selected: Boolean, onClick: () -> Unit) {
    BasicComponent(
        onClick = onClick,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        )
    }
}

/** Index (0-based) of the week that contains [date] within the week grid of its month. */
internal fun weekOfMonthIndex(date: LocalDate): Int {
    val firstWeekStart = date.startOfMonth().startOfWeek()
    return ((date.startOfWeek().toEpochDays() - firstWeekStart.toEpochDays()) / 7).toInt()
}
