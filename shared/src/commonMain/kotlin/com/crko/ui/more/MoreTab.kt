package com.crko.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Event
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.occursOn
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
import com.crko.domain.weekDates
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.components.SurfaceCard
import com.crko.ui.schedule.priorityColor
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.crko.ui.icons.AppIcons

/**
 * Statistics and the journal, plus two entry cards: calendars/subscriptions/ICS data live in the
 * bottom sheet opened by [onOpenCalendarSheet], settings in the page opened by [onOpenSettings].
 */
@Composable
fun MoreTab(
    state: AppStateHolder,
    strings: Strings,
    contentPadding: PaddingValues,
    onOpenCalendarSheet: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val today = state.today
    val layoutDirection = LocalLayoutDirection.current
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
        Text(
            text = strings.tabMore,
            style = MiuixTheme.textStyles.title1,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            LinkedCardTitle(
                icon = AppIcons.Task,
                iconTint = MiuixTheme.colorScheme.primary,
                title = strings.statistics,
            )
            Spacer(Modifier.height(12.dp))
            StatisticsSection(state = state, strings = strings, today = today)
            Spacer(Modifier.height(20.dp))
            JournalSection(state = state, strings = strings)
            Spacer(Modifier.height(20.dp))
            EntryCard(
                icon = AppIcons.Week,
                iconTint = MiuixTheme.colorScheme.primary,
                title = strings.calendarsAndData,
                onClick = onOpenCalendarSheet,
            )
            Spacer(Modifier.height(12.dp))
            EntryCard(
                icon = AppIcons.Settings,
                iconTint = MiuixTheme.colorScheme.secondary,
                title = strings.settings,
                onClick = onOpenSettings,
            )
            Spacer(Modifier.height(GlassNavigationBarDefaults.clearance() + 12.dp))
        }
    }
}

@Composable
private fun EntryCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    onClick: () -> Unit,
) {
    SurfaceCard {
        BasicComponent(onClick = onClick, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(iconTint.copy(alpha = 0.15f))
                        .padding(6.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.width(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(text = title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun StatisticsSection(state: AppStateHolder, strings: Strings, today: LocalDate) {
    val events = state.appState.visibleEvents
    val todos = state.appState.visibleTodos
    // The whole panel is derived from the records and the day: recompositions must not rescan the
    // calendar for every tile.
    val stats = remember(events, todos, today) { statistics(events, todos, today) }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(strings.completedToday, stats.completedToday.toString(), Modifier.weight(1f))
        StatTile(strings.activeTodos, stats.active.toString(), Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(strings.upcomingEvents, stats.upcoming.toString(), Modifier.weight(1f))
        StatTile(strings.todayEvents, stats.todayEvents.toString(), Modifier.weight(1f))
    }
    Spacer(Modifier.height(10.dp))
    SurfaceCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = strings.last7Days,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            WeekBars(todos = todos, today = today)
        }
    }
    Spacer(Modifier.height(10.dp))
    SurfaceCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = strings.byPriority,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            PriorityRow(TodoPriority.High, strings.priorityHigh, stats.priority[TodoPriority.High] ?: TodoCounts.Empty)
            Spacer(Modifier.height(8.dp))
            PriorityRow(TodoPriority.Normal, strings.priorityNormal, stats.priority[TodoPriority.Normal] ?: TodoCounts.Empty)
            Spacer(Modifier.height(8.dp))
            PriorityRow(TodoPriority.Low, strings.priorityLow, stats.priority[TodoPriority.Low] ?: TodoCounts.Empty)
            Spacer(Modifier.height(12.dp))
            CardRowDivider(Modifier.padding(horizontal = 0.dp))
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(strings.thisWeek, style = MiuixTheme.textStyles.body1)
                Spacer(Modifier.weight(1f))
                Text(
                    strings.eventsCount(stats.weekEvents),
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

private data class Statistics(
    val completedToday: Int,
    val active: Int,
    val upcoming: Int,
    val todayEvents: Int,
    val weekEvents: Int,
    val priority: Map<TodoPriority, TodoCounts>,
)

private data class TodoCounts(val done: Int, val total: Int) {
    companion object {
        val Empty = TodoCounts(done = 0, total = 0)
    }
}

/** One pass over both record lists; the same day is only counted once. */
private fun statistics(events: List<Event>, todos: List<Todo>, today: LocalDate): Statistics {
    val eventsOn = HashMap<LocalDate, Int>()
    fun countOn(day: LocalDate): Int = eventsOn.getOrPut(day) { events.count { it.occursOn(day) } }
    val week = today.startOfWeek().weekDates()
    val priorities = TodoPriority.entries.associateWith { TodoCounts.Empty }.toMutableMap()
    var active = 0
    var completedToday = 0
    val completedAt = today.toString()
    todos.forEach { todo ->
        if (todo.done) {
            if (todo.completedAt == completedAt) completedToday++
        } else {
            active++
        }
        val current = priorities.getValue(todo.priorityLevel)
        priorities[todo.priorityLevel] = TodoCounts(
            done = current.done + if (todo.done) 1 else 0,
            total = current.total + 1,
        )
    }
    return Statistics(
        completedToday = completedToday,
        active = active,
        upcoming = (0..2).sumOf { countOn(today.plusDays(it)) },
        todayEvents = countOn(today),
        weekEvents = week.sumOf { countOn(it) },
        priority = priorities,
    )
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    SurfaceCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = value,
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.primary,
            )
            Text(
                text = label,
                style = MiuixTheme.textStyles.footnote1,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** Seven columns for the last seven days, bar height relative to the busiest day in the window. */
@Composable
private fun WeekBars(todos: List<Todo>, today: LocalDate) {
    val days = remember(today) { (6 downTo 0).map { today.plusDays(-it) } }
    val counts = remember(todos, today) {
        days.map { day -> todos.count { it.done && it.dueDay == day } }
    }
    val max = (counts.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth().height(90.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { index, day ->
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Text(
                    text = counts[index].toString(),
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Empty days keep a baseline tick so the axis stays readable.
                        .height((if (counts[index] == 0) 4f else 8f + (counts[index].toFloat() / max) * 46f).dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (day == today) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.primary.copy(alpha = 0.45f)
                            },
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = day.day.toString(),
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                    color = if (day == today) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
            }
        }
    }
}

@Composable
private fun PriorityRow(priority: TodoPriority, label: String, counts: TodoCounts) {
    val total = counts.total
    val done = counts.done
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(priorityColor(priority))
                .width(8.dp)
                .height(8.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = MiuixTheme.textStyles.body1, modifier = Modifier.widthIn(min = 56.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(50))
                .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.15f)),
        ) {
            if (total > 0) {
                Box(
                    Modifier
                        .fillMaxWidth(done.toFloat() / total)
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(priorityColor(priority)),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "$done/$total",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
