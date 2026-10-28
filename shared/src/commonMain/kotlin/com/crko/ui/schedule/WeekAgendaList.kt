package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Event
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import kotlinx.datetime.LocalDate
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import com.crko.ui.icons.AppIcons
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Header for the selected day: date, relative label, event count. */
@Composable
internal fun DaySummary(
    date: LocalDate,
    today: LocalDate,
    events: List<Event>,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.dayMonth(date),
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (date == today) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = strings.today,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = strings.eventsCount(events.size),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** Agenda list for the current week, grouped by day; the alternative to the timeline. */
@Composable
internal fun WeekAgendaList(
    state: AppStateHolder,
    strings: Strings,
    today: LocalDate,
    weekDates: List<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    onOpen: (Event) -> Unit,
    onDeleteDay: (LocalDate) -> Unit,
) {
    val dayNames = remember(weekDates) { weekDates.map { it.toString() }.toSet() }
    val eventsByDate = remember(state.appState.events, weekDates) {
        state.appState.events.filter { it.date in dayNames }.groupBy { it.date }
    }
    val tones = eventTones()
    LazyColumn(
        contentPadding = PaddingValues(bottom = 100.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        weekDates.forEach { date ->
            val dayEvents = eventsByDate[date.toString()].orEmpty().sortedBy { it.startMinutes }
            item(key = "day-$date") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(date) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = dayLabel(strings, date, today),
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                        color = if (date == today) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = strings.dayMonth(date),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = strings.eventsCount(dayEvents.size),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        if (dayEvents.isNotEmpty()) {
                            Spacer(Modifier.width(12.dp))
                            IconButton(onClick = { onDeleteDay(date) }, backgroundColor = Color.Transparent) {
                                Icon(
                                    imageVector = AppIcons.Delete,
                                    contentDescription = strings.delete,
                                    tint = MiuixTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
            items(dayEvents, key = { "event-${it.id}" }) { event ->
                val (container, content) = tones[eventToneIndex(event, tones.size)]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(container)
                        .clickable { onOpen(event) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = timeRangeText(event, strings),
                        style = MiuixTheme.textStyles.footnote1,
                        fontSize = 12.sp,
                        color = content,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = event.title,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        color = content,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
