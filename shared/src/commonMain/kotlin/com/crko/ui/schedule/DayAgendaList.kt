package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.mondayIndex
import com.crko.domain.occurrenceRangesOn
import com.crko.i18n.Strings
import com.crko.ui.components.GlassNavigationBarDefaults
import androidx.compose.runtime.remember
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One agenda row: an event and the occurrence slice shown for it. */
internal data class AgendaEntry(
    val event: Event,
    /** Index of the slice within the event's occurrence ranges, so keys stay unique. */
    val instance: Int,
    val range: Pair<Int, Int>,
)

/**
 * Rows for [date]: one per event, or several per event when a rule expands the day into multiple
 * start times.
 */
internal fun agendaEntries(events: List<Event>, date: LocalDate): List<AgendaEntry> =
    events.flatMap { event ->
        event.occurrenceRangesOn(date).mapIndexed { instance, range ->
            AgendaEntry(event, instance, range)
        }
    }

/** Event row shared by the day list and the week list. */
@Composable
internal fun AgendaEventRow(
    entry: AgendaEntry,
    date: LocalDate,
    tones: List<Pair<Color, Color>>,
    strings: Strings,
    onOpen: (Event, LocalDate) -> Unit,
) {
    val event = entry.event
    val (container, content) = tones[eventToneIndex(event, tones.size)]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container)
            .clickable { onOpen(event, date) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = timeRangeText(event.start is IcsTimeValue.Date, entry.range, strings),
            style = MiuixTheme.textStyles.footnote1,
            fontSize = 12.sp,
            color = content,
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = listOfNotNull(
                event.location.takeIf { it.isNotBlank() },
                eventDateLabel(event, date, strings),
                event.categories.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).joinToString(" · ")
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 10.sp,
                    color = content.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Day title with its event count, used wherever days are stacked: the week agenda list and the
 * blocks of the scrolling timeline.
 */
@Composable
internal fun DayHeadingRow(
    date: LocalDate,
    today: LocalDate,
    count: Int,
    strings: Strings,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val isToday = date == today
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.weekdaysFull[date.mondayIndex],
            style = MiuixTheme.textStyles.title4,
            fontWeight = FontWeight.SemiBold,
            color = if (isToday) scheme.primary else scheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = strings.dayMonth(date),
            style = MiuixTheme.textStyles.footnote1,
            color = scheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        if (isToday) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = strings.today,
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (count > 0) {
            Text(
                text = strings.eventsCount(count),
                style = MiuixTheme.textStyles.footnote1,
                color = scheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** Agenda list for the selected day, ordered by start time. */
@Composable
internal fun DayAgendaList(
    events: List<Event>,
    date: LocalDate,
    strings: Strings,
    onOpen: (Event, LocalDate) -> Unit,
    onSwipeToTimeline: (travel: Float) -> Unit,
) {
    val tones = eventTones()
    val listState = rememberLazyListState()
    val entries = remember(events, date) { agendaEntries(events, date) }
    // Fills the page height: a pager centres a shorter page on the cross axis, which would leave a
    // band of empty screen above the first row.
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .listSwipeToTimeline(listState, onSwipeToTimeline),
        // The page runs to the window bottom behind the glass bar, so the last row needs its own
        // clearance to be reachable.
        contentPadding = PaddingValues(bottom = GlassNavigationBarDefaults.clearance()),
    ) {
        items(entries, key = { "event-${it.event.id}-${it.instance}" }) { entry ->
            AgendaEventRow(entry = entry, date = date, tones = tones, strings = strings, onOpen = onOpen)
        }
    }
}
