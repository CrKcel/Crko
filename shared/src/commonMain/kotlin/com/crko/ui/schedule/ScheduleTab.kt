package com.crko.ui.schedule

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.crko.domain.Event
import com.crko.domain.LocalClock
import com.crko.domain.MaxEndMinutes
import com.crko.domain.minutesOfDay
import com.crko.domain.occursOn
import com.crko.domain.startOfMonth
import com.crko.domain.startOfWeek
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import com.crko.i18n.ZhStrings
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.ui.components.PopupListLayout
import com.crko.ui.components.PopupTitle
import kotlinx.datetime.LocalDate
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
    now: LocalClock,
    strings: Strings,
    contentPadding: PaddingValues,
    onOpenEvent: (Event?) -> Unit,
) {
    val today = state.today
    var selectedName by rememberSaveable { mutableStateOf(today.toString()) }
    var anchorName by rememberSaveable { mutableStateOf(today.startOfWeek().toString()) }
    var monthAnchorName by rememberSaveable { mutableStateOf(today.startOfMonth().toString()) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var listMode by rememberSaveable { mutableStateOf(state.settings.agendaView == "list") }
    var weekMode by rememberSaveable { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current

    val selected = remember(selectedName) { LocalDate.parse(selectedName) }
    val weekAnchor = remember(anchorName) { LocalDate.parse(anchorName) }
    val monthAnchor = remember(monthAnchorName) { LocalDate.parse(monthAnchorName) }
    val events = state.appState.events
    val hasEvents = remember(events) { { date: LocalDate -> events.any { it.occursOn(date) } } }
    val fold = rememberCalendarFold()
    // The calendar toggles from the header blocks without a ripple: the movement of the calendar
    // itself is the feedback.
    val titleInteraction = remember { MutableInteractionSource() }
    val summaryInteraction = remember { MutableInteractionSource() }

    fun select(date: LocalDate) {
        selectedName = date.toString()
        val week = date.startOfWeek()
        if (week != weekAnchor) anchorName = week.toString()
        val month = date.startOfMonth()
        if (month != monthAnchor) monthAnchorName = month.toString()
    }

    fun createAt(date: LocalDate, slotMinutes: Int?) {
        val start = when {
            slotMinutes != null -> slotMinutes
            date == today -> ((now.time.minutesOfDay() / SlotMinutes) + 1) * SlotMinutes
            else -> state.settings.agendaScrollHour * 60
        }
        val startMinutes = start.coerceAtMost(23 * 60 + 30)
        onOpenEvent(
            Event(
                id = null,
                title = "",
                date = date.toString(),
                startMinutes = startMinutes,
                endMinutes = (startMinutes + 60).coerceAtMost(MaxEndMinutes),
            ),
        )
    }

    val dayEvents = remember(selected, state.appState.events) { state.eventsOn(selected) }
    val dayPager = rememberDayPager(selected = selected, today = today, onSelect = ::select)

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
        // The fold gesture covers the title row and the day summary as well, so the calendar can be
        // pulled open or pushed away from anywhere in the header block, not only over the grid.
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
                // While the calendar is unfolded the pager may show another month; follow it.
                val heading = if (expanded) monthAnchor else weekAnchor
                Text(
                    text = strings.monthTitle(heading.year, heading.month),
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
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    WindowListPopup(show = showViewMenu, onDismissRequest = { showViewMenu = false }) {
                        PopupListLayout {
                            PopupTitle(strings.tabSchedule)
                            ViewOption(strings.viewTimeline, !listMode) {
                                listMode = false
                                state.setAgendaView("timeline")
                                showViewMenu = false
                            }
                            ViewOption(strings.viewList, listMode) {
                                listMode = true
                                state.setAgendaView("list")
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
                onSelect = ::select,
                onMonthChange = { target -> monthAnchorName = target.toString() },
            )

            DaySummary(
                date = selected,
                today = today,
                events = dayEvents,
                strings = strings,
                onDeleteDay = {
                    state.deleteEventsOn(selected)
                    state.notify(AppMessage.Deleted)
                },
                modifier = Modifier
                    .clickable(
                        interactionSource = summaryInteraction,
                        indication = null,
                        onClickLabel = if (expanded) strings.collapseCalendar else strings.expandCalendar,
                    ) { expanded = !expanded }
                    .padding(horizontal = 16.dp),
            )
        }

        // The overview and the day pager share this area; the upper calendar stays untouched.
        // A two-finger spread opens the week overview and a pinch closes it, while one-finger
        // swipes still page the day view.
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .weekOverviewGesture(
                    inWeekView = weekMode,
                    onEnter = { weekMode = true },
                    onExit = { weekMode = false },
                ),
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
                if (inWeekView) {
                    val weekDays = remember(weekAnchor) { weekAnchor.weekDates() }
                    val weekEvents = remember(weekDays, events) {
                        weekDays.associateWith { date -> state.eventsOn(date) }
                    }
                    if (listMode) {
                        WeekAgendaList(
                            strings = strings,
                            today = today,
                            weekStart = weekAnchor,
                            eventsByDate = weekEvents,
                            onOpen = onOpenEvent,
                        )
                    } else {
                        WeekTimeline(
                            strings = strings,
                            today = today,
                            now = now,
                            weekStart = weekAnchor,
                            eventsByDate = weekEvents,
                            defaultScrollHour = state.settings.agendaScrollHour,
                            onOpen = onOpenEvent,
                        )
                    }
                } else {
                    // No offscreen pages: the calendar above animates its height, so every frame
                    // would otherwise re-measure the neighbouring days as well.
                    HorizontalPager(
                        state = dayPager.state,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        val date = dayPager.dateAt(page)
                        val events = remember(date, state.appState.events) { state.eventsOn(date) }
                        if (listMode) {
                            DayAgendaList(
                                events = events,
                                strings = strings,
                                onOpen = onOpenEvent,
                            )
                        } else {
                            DayTimeline(
                                strings = strings,
                                today = today,
                                now = now,
                                date = date,
                                events = events,
                                defaultScrollHour = state.settings.agendaScrollHour,
                                onAdd = ::createAt,
                                onOpen = onOpenEvent,
                            )
                        }
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
