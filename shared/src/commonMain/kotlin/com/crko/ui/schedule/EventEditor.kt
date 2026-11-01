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
import com.crko.domain.Alarm
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.daysTo
import com.crko.domain.localDate
import com.crko.domain.mondayIndex
import com.crko.domain.parseRepeatUntil
import com.crko.domain.plusDays
import com.crko.domain.startOfWeek
import com.crko.i18n.Strings
import com.crko.state.EditScope
import com.crko.storage.newId
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class RepeatEndMode { Never, Count, Until }

/** Editor for a new or existing event. A null [Event.id] marks a new event. */
@Composable
internal fun EventEditorDialog(
    event: Event?,
    occurrenceDate: LocalDate?,
    strings: Strings,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Event, EditScope) -> Boolean,
    onDelete: (Event, EditScope) -> Unit,
) {
    if (event != null) {
        EventEditorContent(event, occurrenceDate, strings, today, onDismiss, onSave, onDelete)
    }
}

@Composable
private fun EventEditorContent(
    event: Event,
    occurrenceDate: LocalDate?,
    strings: Strings,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (Event, EditScope) -> Boolean,
    onDelete: (Event, EditScope) -> Unit,
) {
    val startDate = occurrenceDate ?: event.day
    val initialEndDate = startDate.plusDays(event.day.daysTo(event.endDay))
    // Re-key by the full event so switching between events resets every field.
    var title by remember(event, occurrenceDate) { mutableStateOf(event.title) }
    var note by remember(event, occurrenceDate) { mutableStateOf(event.note) }
    var date by remember(event, occurrenceDate) { mutableStateOf(startDate) }
    var endDate by remember(event, occurrenceDate) { mutableStateOf(initialEndDate) }
    var allDay by remember(event) { mutableStateOf(event.allDay) }
    var start by remember(event) { mutableStateOf(minutesToTime(event.startMinutes)) }
    var end by remember(event) { mutableStateOf(minutesToTime(event.endMinutes)) }
    var repeatFrequency by remember(event) { mutableStateOf(event.repeat?.frequency) }
    var repeatDays by remember(event) { mutableStateOf(event.repeat?.weekdays?.toSet().orEmpty()) }
    var intervalText by remember(event) { mutableStateOf((event.repeat?.interval ?: 1).toString()) }
    var endMode by remember(event) {
        mutableStateOf(
            when {
                event.repeat?.count != null -> RepeatEndMode.Count
                event.repeat?.until != null -> RepeatEndMode.Until
                else -> RepeatEndMode.Never
            },
        )
    }
    var countText by remember(event) { mutableStateOf((event.repeat?.count ?: 10).toString()) }
    var untilDate by remember(event) {
        mutableStateOf(
            event.repeat?.until?.let(::parseRepeatUntil)?.localDate()
                ?: startDate.plusDays(30),
        )
    }
    var location by remember(event) { mutableStateOf(event.location) }
    var categories by remember(event) { mutableStateOf(event.categories.joinToString(", ")) }
    var url by remember(event) { mutableStateOf(event.url) }
    var status by remember(event) { mutableStateOf(event.status) }
    var transparency by remember(event) { mutableStateOf(event.transparency) }
    var priority by remember(event) { mutableStateOf(event.priority) }
    var reminder by remember(event) { mutableStateOf(event.alarms.firstOrNull()?.triggerMinutes) }
    var scope by remember(event, occurrenceDate) {
        mutableStateOf(
            if (event.repeat != null && occurrenceDate != null) EditScope.ThisEvent else EditScope.AllEvents,
        )
    }
    var error by remember(event) { mutableStateOf<String?>(null) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showUntilDatePicker by remember { mutableStateOf(false) }

    val startMinutes = start.hour * 60 + start.minute
    val endMinutes = end.hour * 60 + end.minute
    val resolvedEndDate = if (endDate < date) date else endDate
    val interval = intervalText.toIntOrNull()?.takeIf { it > 0 } ?: 1
    val repeatCount = countText.toIntOrNull()?.takeIf { it > 0 }
    val repeatValid = (repeatFrequency != RepeatFrequency.Custom || repeatDays.isNotEmpty()) &&
        (repeatFrequency == null || endMode != RepeatEndMode.Count || repeatCount != null)
    val timeValid = allDay || resolvedEndDate > date || endMinutes > startMinutes
    val valid = title.isNotBlank() && timeValid && repeatValid
    val canScope = event.repeat != null && occurrenceDate != null
    val originalReminder = event.alarms.firstOrNull()?.triggerMinutes

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
                val pickedMinutes = picked.hour * 60 + picked.minute
                if (picking == TimeField.START) {
                    start = picked
                    if (resolvedEndDate == date && pickedMinutes >= endMinutes) {
                        end = minutesToTime((pickedMinutes + 60).coerceAtMost(MaxEndMinutes))
                    }
                } else {
                    end = picked
                    // Midnight or an earlier clock time on the start day means the event ends the next day.
                    if (pickedMinutes <= startMinutes && resolvedEndDate == date) {
                        endDate = date.plusDays(1)
                    }
                }
                showStartPicker = false
                showEndPicker = false
            },
        )
        return
    }

    if (showDatePicker) {
        DatePickerDialog(
            initial = date,
            today = today,
            strings = strings,
            onDismiss = { showDatePicker = false },
            onConfirm = { picked ->
                date = picked
                if (endDate < picked) endDate = picked
                showDatePicker = false
            },
        )
        return
    }
    if (showEndDatePicker) {
        DatePickerDialog(
            initial = resolvedEndDate,
            today = today,
            strings = strings,
            onDismiss = { showEndDatePicker = false },
            onConfirm = { picked ->
                endDate = if (picked < date) date else picked
                showEndDatePicker = false
            },
        )
        return
    }
    if (showUntilDatePicker) {
        DatePickerDialog(
            initial = untilDate,
            today = today,
            strings = strings,
            onDismiss = { showUntilDatePicker = false },
            onConfirm = { picked ->
                untilDate = picked
                showUntilDatePicker = false
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(strings.allDay, style = MiuixTheme.textStyles.body1, modifier = Modifier.weight(1f))
                Switch(
                    checked = allDay,
                    onCheckedChange = { checked ->
                        allDay = checked
                        if (!checked && resolvedEndDate == date && endMinutes <= startMinutes) {
                            end = minutesToTime((startMinutes + 60).coerceAtMost(MaxEndMinutes))
                        }
                    },
                )
            }
            Spacer(Modifier.height(10.dp))
            DateRow(
                strings = strings,
                date = date,
                onChange = {
                    date = it
                    if (endDate < it) endDate = it
                },
                onPickDate = { showDatePicker = true },
            )
            Spacer(Modifier.height(8.dp))
            EndDateRow(
                strings = strings,
                date = resolvedEndDate,
                allDay = allDay,
                onPickDate = { showEndDatePicker = true },
                onClear = { endDate = date },
            )
            if (!allDay) {
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
            }
            Spacer(Modifier.height(12.dp))
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
            if (repeatFrequency != null) {
                Spacer(Modifier.height(8.dp))
                Column {
                    Caption(strings.repeatInterval)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            text = "−",
                            onClick = {
                                intervalText = (interval - 1).coerceAtLeast(1).toString()
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(),
                        )
                        Text(
                            text = interval.toString(),
                            style = MiuixTheme.textStyles.title3,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        TextButton(
                            text = "+",
                            onClick = { intervalText = (interval + 1).toString() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Caption(strings.repeatEnd)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        RepeatChip(strings.repeatNeverEnds, endMode == RepeatEndMode.Never, {
                            endMode = RepeatEndMode.Never
                        }, Modifier.weight(1f))
                        RepeatChip(strings.repeatAfterTimes, endMode == RepeatEndMode.Count, {
                            endMode = RepeatEndMode.Count
                        }, Modifier.weight(1f))
                        RepeatChip(strings.repeatUntilDate, endMode == RepeatEndMode.Until, {
                            endMode = RepeatEndMode.Until
                        }, Modifier.weight(1f))
                    }
                    when (endMode) {
                        RepeatEndMode.Count -> {
                            Spacer(Modifier.height(6.dp))
                            TextField(
                                value = countText,
                                onValueChange = { value -> countText = value.filter(Char::isDigit) },
                                label = strings.repeatAfterTimes,
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        RepeatEndMode.Until -> {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = strings.dayMonth(untilDate),
                                style = MiuixTheme.textStyles.body1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MiuixTheme.colorScheme.primaryContainer)
                                    .clickable { showUntilDatePicker = true }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                        RepeatEndMode.Never -> Unit
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            TextField(
                value = location,
                onValueChange = { location = it },
                label = strings.locationField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = categories,
                onValueChange = { categories = it },
                label = strings.categoriesField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = url,
                onValueChange = { url = it },
                label = strings.urlField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Caption(strings.eventStatus)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.statusConfirmed, status == EventStatus.Confirmed, {
                    status = EventStatus.Confirmed
                }, Modifier.weight(1f))
                RepeatChip(strings.statusTentative, status == EventStatus.Tentative, {
                    status = EventStatus.Tentative
                }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Caption(strings.transparency)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.busy, transparency == EventTransparency.Opaque, {
                    transparency = EventTransparency.Opaque
                }, Modifier.weight(1f))
                RepeatChip(strings.free, transparency == EventTransparency.Transparent, {
                    transparency = EventTransparency.Transparent
                }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Caption(strings.priority)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.priorityNone, priority == null, { priority = null }, Modifier.weight(1f))
                RepeatChip(strings.priorityHigh, priority == 1, { priority = 1 }, Modifier.weight(1f))
                RepeatChip(strings.priorityNormal, priority == 5, { priority = 5 }, Modifier.weight(1f))
                RepeatChip(strings.priorityLow, priority == 9, { priority = 9 }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Caption(strings.reminder)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.reminderNone, reminder == null, { reminder = null }, Modifier.weight(1f))
                RepeatChip(strings.reminderAtTime, reminder == 0, { reminder = 0 }, Modifier.weight(1f))
                RepeatChip(strings.reminderBefore(5), reminder == 5, { reminder = 5 }, Modifier.weight(1f))
                RepeatChip(strings.reminderBefore(15), reminder == 15, { reminder = 15 }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.reminderBefore(30), reminder == 30, { reminder = 30 }, Modifier.weight(1f))
                RepeatChip(strings.reminderBefore(60), reminder == 60, { reminder = 60 }, Modifier.weight(1f))
                Spacer(Modifier.weight(2f))
            }
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
            if (canScope) {
                Spacer(Modifier.height(10.dp))
                Caption(strings.editScope)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RepeatChip(strings.scopeThisEvent, scope == EditScope.ThisEvent, {
                        scope = EditScope.ThisEvent
                    }, Modifier.weight(1f))
                    RepeatChip(strings.scopeThisAndFuture, scope == EditScope.ThisAndFuture, {
                        scope = EditScope.ThisAndFuture
                    }, Modifier.weight(1f))
                    RepeatChip(strings.scopeAllEvents, scope == EditScope.AllEvents, {
                        scope = EditScope.AllEvents
                    }, Modifier.weight(1f))
                }
            }
            val message = when {
                error != null -> error
                title.isBlank() -> strings.emptyTitle
                !timeValid -> strings.invalidTimeRange
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
            val selectedScope = if (canScope) scope else EditScope.AllEvents
            Button(
                onClick = {
                    val saved = onSave(
                        event.copy(
                            id = event.id ?: newId(),
                            title = title,
                            note = note,
                            date = date.toString(),
                            allDay = allDay,
                            startMinutes = startMinutes,
                            endMinutes = endMinutes,
                            startSeconds = if (startMinutes == event.startMinutes) event.startSeconds else 0,
                            endSeconds = if (endMinutes == event.endMinutes) event.endSeconds else 0,
                            endDate = resolvedEndDate.takeIf { it != date }?.toString(),
                            location = location,
                            categories = categories.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() },
                            url = url,
                            status = status,
                            transparency = transparency,
                            priority = priority,
                            alarms = if (reminder == originalReminder) {
                                event.alarms
                            } else {
                                reminder?.let { listOf(Alarm(triggerMinutes = it)) } ?: emptyList()
                            },
                            repeat = buildRule(
                                frequency = repeatFrequency,
                                old = event.repeat,
                                weekdays = repeatDays,
                                interval = interval,
                                endMode = endMode,
                                count = repeatCount,
                                until = untilDate,
                            ),
                        ),
                        selectedScope,
                    )
                    if (saved) onDismiss()
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
                        onDelete(event, selectedScope)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(),
                )
            }
        }
    }
}

private fun buildRule(
    frequency: RepeatFrequency?,
    old: RepeatRule?,
    weekdays: Set<Int>,
    interval: Int,
    endMode: RepeatEndMode,
    count: Int?,
    until: LocalDate,
): RepeatRule? {
    if (frequency == null) return null
    val keep = old?.takeIf { it.frequency == frequency }
    return RepeatRule(
        frequency = frequency,
        weekdays = when (frequency) {
            RepeatFrequency.Custom -> weekdays.filter { it in 0..6 }.sorted()
            else -> keep?.weekdays ?: emptyList()
        },
        interval = interval.coerceAtLeast(1),
        count = if (endMode == RepeatEndMode.Count) count else null,
        until = if (endMode == RepeatEndMode.Until) {
            // Keep the imported value (instant/floating) when the picked date did not change.
            val original = keep?.until
            if (original != null && parseRepeatUntil(original)?.localDate() == until) original else until.toString()
        } else {
            null
        },
        byDay = keep?.byDay ?: emptyList(),
        byMonthDay = keep?.byMonthDay ?: emptyList(),
        byMonth = keep?.byMonth ?: emptyList(),
        bySetPos = keep?.bySetPos ?: emptyList(),
        byYearDay = keep?.byYearDay ?: emptyList(),
        byWeekNo = keep?.byWeekNo ?: emptyList(),
        weekStart = keep?.weekStart,
        byHour = keep?.byHour ?: emptyList(),
        byMinute = keep?.byMinute ?: emptyList(),
        rDates = keep?.rDates ?: emptyList(),
    )
}

internal enum class TimeField { START, END }

internal fun minutesToTime(minutes: Int): LocalTime = LocalTime((minutes.coerceIn(0, MaxEndMinutes)) / 60, minutes.coerceIn(0, MaxEndMinutes) % 60)

@Composable
private fun Caption(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        fontSize = 11.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun EndDateRow(
    strings: Strings,
    date: LocalDate,
    allDay: Boolean,
    onPickDate: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.endDate,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = strings.dayMonth(date),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onPickDate)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
        if (!allDay) {
            Text(
                text = strings.clear,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

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

/** Horizontal strip of dates: the selected day plus the surrounding week, with a month picker. */
@Composable
private fun DateRow(
    strings: Strings,
    date: LocalDate,
    onChange: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    val days = remember(date) { date.startOfWeek().let { start -> (0 until 7).map { start.plusDays(it) } } }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.dayMonth(date),
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                text = strings.selectDate,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPickDate)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
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
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(
                strings.repeatMonthly,
                frequency == RepeatFrequency.Monthly,
                { onFrequencyChange(RepeatFrequency.Monthly) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.repeatYearly,
                frequency == RepeatFrequency.Yearly,
                { onFrequencyChange(RepeatFrequency.Yearly) },
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
