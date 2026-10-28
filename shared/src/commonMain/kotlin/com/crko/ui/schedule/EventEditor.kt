package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Event
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.mondayIndex
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
import com.crko.i18n.Strings
import com.crko.storage.newId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Editor for a new or existing event. A null [Event.id] marks a new event. */
@Composable
internal fun EventEditorDialog(
    event: Event?,
    strings: Strings,
    onDismiss: () -> Unit,
    onSave: (Event) -> Boolean,
    onDelete: (Event) -> Unit,
) {
    if (event != null) {
        EventEditorContent(event, strings, onDismiss, onSave, onDelete)
    }
}

@Composable
private fun EventEditorContent(
    event: Event,
    strings: Strings,
    onDismiss: () -> Unit,
    onSave: (Event) -> Boolean,
    onDelete: (Event) -> Unit,
) {
    // Re-key by the full event so switching between events resets every field.
    var title by remember(event) { mutableStateOf(event.title) }
    var note by remember(event) { mutableStateOf(event.note) }
    var date by remember(event) { mutableStateOf(LocalDate.parse(event.date)) }
    var start by remember(event) { mutableStateOf(minutesToTime(event.startMinutes)) }
    var end by remember(event) { mutableStateOf(minutesToTime(event.endMinutes)) }
    var repeatFrequency by remember(event) { mutableStateOf(event.repeat?.frequency) }
    var repeatDays by remember(event) { mutableStateOf(event.repeat?.weekdays?.toSet().orEmpty()) }
    var error by remember(event) { mutableStateOf<String?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val startMinutes = start.hour * 60 + start.minute
    val endMinutes = end.hour * 60 + end.minute
    val repeatValid = repeatFrequency != RepeatFrequency.Custom || repeatDays.isNotEmpty()
    val valid = title.isNotBlank() && endMinutes > startMinutes && repeatValid

    // A single dialog hosts both the form and the time dial; a second stacked dialog would dim
    // the first one and lose the edited values when dismissed.
    val picking: TimeField? = when {
        showStartPicker -> TimeField.START
        showEndPicker -> TimeField.END
        else -> null
    }
    if (picking != null) {
        TimePickerDialog(
            initial = if (picking == TimeField.START) start else end,
            title = if (picking == TimeField.START) strings.startTime else strings.endTime,
            confirm = strings.done,
            cancel = strings.cancel,
            onDismiss = {
                showStartPicker = false
                showEndPicker = false
            },
            onConfirm = { picked ->
                if (picking == TimeField.START) {
                    start = picked
                    // Keep a positive duration when the start moves past the end.
                    if (picked.hour * 60 + picked.minute >= endMinutes) {
                        end = minutesToTime((picked.hour * 60 + picked.minute + 60).coerceAtMost(MaxEndMinutes))
                    }
                } else {
                    end = picked
                }
                showStartPicker = false
                showEndPicker = false
            },
        )
        return
    }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = if (event.id == null) strings.eventNew else strings.eventEdit,
        insideMargin = DpSize(20.dp, 16.dp),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TextField(
                value = title,
                onValueChange = {
                    title = it
                    error = null
                },
                label = strings.eventTitleField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            DateRow(strings = strings, date = date, onChange = { date = it })
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TimeField(
                    label = strings.startTime,
                    value = strings.time(start),
                    onClick = { showStartPicker = true },
                    modifier = Modifier.weight(1f),
                )
                TimeField(
                    label = strings.endTime,
                    value = strings.time(end),
                    onClick = { showEndPicker = true },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(10.dp))
            RepeatSection(
                strings = strings,
                frequency = repeatFrequency,
                weekdays = repeatDays,
                onFrequencyChange = { next ->
                    repeatFrequency = next
                    if (next == RepeatFrequency.Custom && repeatDays.isEmpty()) {
                        repeatDays = setOf(date.mondayIndex)
                    }
                },
                onToggleWeekday = { index ->
                    repeatDays = if (index in repeatDays) repeatDays - index else repeatDays + index
                },
            )
            Spacer(Modifier.height(10.dp))
            TextField(
                value = note,
                onValueChange = { note = it },
                label = strings.noteField,
                useLabelAsPlaceholder = true,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            val message = when {
                error != null -> error
                title.isBlank() -> strings.emptyTitle
                endMinutes <= startMinutes -> strings.invalidTimeRange
                !repeatValid -> strings.repeatWeekdaysRequired
                else -> null
            }
            if (message != null && (error != null || title.isNotBlank())) {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val result = onSave(
                        event.copy(
                            id = event.id ?: newId(),
                            title = title,
                            note = note,
                            date = date.toString(),
                            startMinutes = startMinutes,
                            endMinutes = endMinutes,
                            repeat = when (repeatFrequency) {
                                null -> null
                                RepeatFrequency.Daily -> RepeatRule(RepeatFrequency.Daily)
                                RepeatFrequency.Weekly -> RepeatRule(RepeatFrequency.Weekly)
                                RepeatFrequency.Custom -> RepeatRule(RepeatFrequency.Custom, repeatDays.sorted())
                            },
                        ),
                    )
                    if (result) onDismiss()
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save, color = MiuixTheme.colorScheme.onPrimary)
            }
            if (event.id != null) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = strings.delete,
                    onClick = {
                        onDelete(event)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(),
                )
            }
        }
    }


}

internal enum class TimeField { START, END }

internal fun minutesToTime(minutes: Int): LocalTime = LocalTime(minutes / 60, minutes % 60)

@Composable
private fun TimeField(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = value,
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Horizontal strip of dates: the selected day plus the surrounding week. */
@Composable
private fun DateRow(strings: Strings, date: LocalDate, onChange: (LocalDate) -> Unit) {
    val days = remember(date) { date.startOfWeek().let { start -> (0 until 7).map { start.plusDays(it) } } }
    Column {
        Text(
            text = strings.dayMonth(date),
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEachIndexed { index, day ->
                val selected = day == date
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHigh,
                        )
                        .clickable { onChange(day) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = strings.weekdaysShort[index],
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 10.sp,
                        color = if (selected) {
                            MiuixTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = day.day.toString(),
                        style = MiuixTheme.textStyles.body1,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun RepeatSection(
    strings: Strings,
    frequency: RepeatFrequency?,
    weekdays: Set<Int>,
    onFrequencyChange: (RepeatFrequency?) -> Unit,
    onToggleWeekday: (Int) -> Unit,
) {
    Column {
        Text(
            text = strings.repeat,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(strings.repeatNone, frequency == null, { onFrequencyChange(null) }, Modifier.weight(1f))
            RepeatChip(
                strings.repeatDaily,
                frequency == RepeatFrequency.Daily,
                { onFrequencyChange(RepeatFrequency.Daily) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.repeatWeekly,
                frequency == RepeatFrequency.Weekly,
                { onFrequencyChange(RepeatFrequency.Weekly) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.repeatCustom,
                frequency == RepeatFrequency.Custom,
                { onFrequencyChange(RepeatFrequency.Custom) },
                Modifier.weight(1f),
            )
        }
        if (frequency == RepeatFrequency.Custom) {
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                strings.weekdaysFull.forEachIndexed { index, label ->
                    RepeatChip(label, index in weekdays, { onToggleWeekday(index) }, Modifier.weight(1f))
                }
            }
        }
        if (frequency != null) {
            Text(
                text = strings.repeatSeriesHint,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun RepeatChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainerHigh,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    )
}

@Composable
internal fun TimePickerDialog(
    initial: LocalTime,
    title: String,
    confirm: String,
    cancel: String,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime) -> Unit,
) {
    val pickerState = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            TimePicker(state = pickerState)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(text = cancel, onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = confirm,
                    onClick = { onConfirm(LocalTime(pickerState.hour, pickerState.minute)) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
