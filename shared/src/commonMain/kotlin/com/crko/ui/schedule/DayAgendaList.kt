package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.crko.i18n.Strings
import com.crko.ui.icons.AppIcons
import kotlinx.datetime.LocalDate
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
    onDeleteDay: () -> Unit,
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
        if (events.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDeleteDay, backgroundColor = Color.Transparent) {
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

/** Event row shared by the day list and the week list. */
@Composable
internal fun AgendaEventRow(
    event: Event,
    tones: List<Pair<Color, Color>>,
    strings: Strings,
    onOpen: (Event) -> Unit,
) {
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

/** Agenda list for the selected day, ordered by start time. */
@Composable
internal fun DayAgendaList(
    events: List<Event>,
    strings: Strings,
    onOpen: (Event) -> Unit,
) {
    val tones = eventTones()
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(events, key = { "event-${it.id}" }) { event ->
            AgendaEventRow(event = event, tones = tones, strings = strings, onOpen = onOpen)
        }
    }
}
