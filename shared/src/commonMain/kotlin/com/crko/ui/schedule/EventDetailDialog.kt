package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.IcsTimeValue
import com.crko.domain.mondayIndex
import com.crko.domain.occurrenceRangesOn
import com.crko.domain.occurrenceStartOn
import com.crko.domain.relatedToEnd
import com.crko.domain.triggerMinutes
import com.crko.i18n.Strings
import com.crko.i18n.alarmLabel
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.DetailCardDialog
import com.crko.ui.components.DetailPill
import com.crko.ui.components.DetailRow
import com.crko.ui.components.SurfaceCard
import com.crko.ui.components.accentColor
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Read-only card summarising an event; editing still happens in [EventEditorDialog]. */
@Composable
internal fun EventDetailDialog(
    event: Event,
    occurrenceDate: LocalDate?,
    calendar: Calendar?,
    strings: Strings,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = calendar?.accentColor() ?: MiuixTheme.colorScheme.primary
    val date = occurrenceDate ?: event.day
    val startDate = event.occurrenceStartOn(date) ?: event.day
    val dateText = eventDateLabel(event, date, strings)
        ?: "${strings.weekdaysFull[startDate.mondayIndex]} ${strings.dayMonth(startDate)}"
    val timeText = if (event.start is IcsTimeValue.Date) {
        strings.allDay
    } else {
        event.occurrenceRangesOn(date).firstOrNull()
            ?.let { timeRangeText(false, it, strings) }
            ?: strings.allDay
    }
    val statusText = when (event.status) {
        EventStatus.Confirmed -> null
        EventStatus.Tentative -> strings.statusTentative
        EventStatus.Cancelled -> strings.statusCancelled
    }
    val statusColor = if (event.status == EventStatus.Cancelled) {
        MiuixTheme.colorScheme.error
    } else {
        accent
    }
    val reminderText = event.alarms.firstOrNull()
        ?.let { alarm -> alarm.triggerMinutes?.let { strings.alarmLabel(it, alarm.relatedToEnd) } }
    val rows = buildList {
        event.location.takeIf { it.isNotBlank() }?.let { add(strings.location to it) }
        event.categories.takeIf { it.isNotEmpty() }?.let { add(strings.categories to it.joinToString(", ")) }
        event.url.takeIf { it.isNotBlank() }?.let { add(strings.urlField to it) }
        if (event.repeat != null) {
            add(strings.repeat to repeatSummary(strings, event.repeat?.frequency))
        }
        reminderText?.let { add(strings.reminder to it) }
        if (event.transparency == EventTransparency.Transparent) {
            add(strings.transparency to strings.free)
        }
    }

    DetailCardDialog(
        title = strings.eventDetail,
        onDismiss = onDismiss,
    ) { requestClose ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (calendar != null) {
                            Spacer(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(calendar.accentColor()),
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                text = calendar.name,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        statusText?.let { DetailPill(it, statusColor) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = event.title.ifBlank { strings.untitledEvent },
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "$dateText · $timeText",
                        style = MiuixTheme.textStyles.footnote1,
                        color = accent,
                    )
                }
            }
            if (rows.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SurfaceCard {
                    rows.forEachIndexed { index, (label, value) ->
                        if (index > 0) CardRowDivider()
                        DetailRow(label, value)
                    }
                }
            }
            if (event.note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SurfaceCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.noteField,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = event.note,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { requestClose(onEdit) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.edit, color = MiuixTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = strings.close,
                onClick = { requestClose(null) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }
}
