package com.crko.ui.schedule

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.crko.domain.startOfMonth
import com.crko.domain.startOfWeek
import com.crko.i18n.Strings
import com.crko.i18n.ZhStrings
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
    var showViewMenu by remember { mutableStateOf(false) }
    val layoutDirection = LocalLayoutDirection.current

    val selected = remember(selectedName) { LocalDate.parse(selectedName) }
    val weekAnchor = remember(anchorName) { LocalDate.parse(anchorName) }
    val monthAnchor = remember(monthAnchorName) { LocalDate.parse(monthAnchorName) }
    val eventDates = remember(state.appState.events) { state.appState.events.mapTo(HashSet()) { it.date } }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // While the calendar is unfolded the pager may show another month; follow it.
            val heading = if (expanded) monthAnchor else weekAnchor
            Text(
                text = strings.monthTitle(heading.year, heading.month),
                modifier = Modifier.weight(1f, fill = false),
                style = if (strings === ZhStrings) MiuixTheme.textStyles.title3 else MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { showViewMenu = true }) {
                    Icon(
                        imageVector = if (listMode) AppIcons.ListView else AppIcons.Timeline,
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
                    }
                }
            }
            IconButton(onClick = { expanded = !expanded }) {
                Icon(
                    imageVector = if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                    contentDescription = if (expanded) strings.collapseCalendar else strings.expandCalendar,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        WeekMonthCalendar(
            strings = strings,
            selected = selected,
            today = today,
            monthAnchor = monthAnchor,
            eventDates = eventDates,
            expanded = expanded,
            onExpandedChange = { expanded = it },
            onSelect = ::select,
            onMonthChange = { target -> monthAnchorName = target.toString() },
        )

        val dayEvents = remember(selected, state.appState.events) { state.eventsOn(selected) }
        DaySummary(
            date = selected,
            today = today,
            events = dayEvents,
            strings = strings,
            onDeleteDay = { dayEvents.forEach { state.deleteEvent(it.id) } },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Box(modifier = Modifier.weight(1f)) {
            if (listMode) {
                DayAgendaList(
                    events = dayEvents,
                    strings = strings,
                    onOpen = onOpenEvent,
                )
            } else {
                DayTimeline(
                    strings = strings,
                    today = today,
                    now = now,
                    date = selected,
                    events = dayEvents,
                    defaultScrollHour = state.settings.agendaScrollHour,
                    onSelectDay = ::select,
                    onAdd = ::createAt,
                    onOpen = onOpenEvent,
                )
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
