package com.crko.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Alarm
import com.crko.domain.Attachment
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.EventTransparency
import com.crko.domain.MaxEndMinutes
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.attachmentMimeType
import com.crko.domain.attachmentBytes
import com.crko.domain.daysTo
import com.crko.domain.localDate
import com.crko.domain.mondayIndex
import com.crko.domain.parseRepeatUntil
import com.crko.domain.plusDays
import com.crko.domain.safeAttachmentFileName
import com.crko.domain.startOfWeek
import com.crko.i18n.Strings
import com.crko.i18n.alarmLabel
import com.crko.state.EditScope
import com.crko.storage.newId
import com.crko.ui.icons.AppIcons
import com.crko.ui.transfer.CalendarFileBridge
import com.crko.ui.transfer.rememberCalendarFileBridge
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
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
    var showMoreOptions by remember(event) {
        mutableStateOf(
            event.id != null && (
                event.location.isNotBlank() ||
                    event.categories.isNotEmpty() ||
                    event.url.isNotBlank() ||
                    event.status != EventStatus.Confirmed ||
                    event.transparency != EventTransparency.Opaque ||
                    event.priority != null
                ),
        )
    }
    var repeatExpanded by remember(event) { mutableStateOf(false) }
    var reminderExpanded by remember(event) { mutableStateOf(false) }
    // The editor edits only the first relative alarm; imported audio/email/absolute alarms survive.
    val originalAlarm = event.alarms.firstOrNull { it.absoluteTrigger == null }
    val originalReminder = originalAlarm?.triggerMinutes
    val originalAtEnd = originalAlarm?.relatedToEnd ?: false
    var reminder by remember(event) { mutableStateOf(originalReminder) }
    var reminderAtEnd by remember(event) { mutableStateOf(originalAtEnd) }
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
    val fileBridge = rememberCalendarFileBridge()
    var attachmentNotice by remember(event) { mutableStateOf<String?>(null) }

    val startMinutes = start.hour * 60 + start.minute
    val endMinutes = end.hour * 60 + end.minute
    val resolvedEndDate = if (endDate < date) date else endDate
    val interval = intervalText.toIntOrNull()?.takeIf { it > 0 } ?: 1
    val repeatCount = countText.toIntOrNull()?.takeIf { it > 0 }
    val repeatValid = (repeatFrequency != RepeatFrequency.Custom || repeatDays.isNotEmpty()) &&
        (repeatFrequency == null || endMode != RepeatEndMode.Count || repeatCount != null)
    val timeValid = allDay || resolvedEndDate > date || endMinutes >= startMinutes
    val valid = title.isNotBlank() && timeValid && repeatValid
    val canScope = event.repeat != null && occurrenceDate != null

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
                    // A zero-length event (RFC 5545: timed event without DTEND) moves as a point in
                    // time instead of being stretched into an hour the user never asked for.
                    val zeroDuration = !allDay && resolvedEndDate == date && startMinutes == endMinutes
                    start = picked
                    endMinutesAfterStartChange(zeroDuration, resolvedEndDate == date, pickedMinutes, endMinutes)?.let {
                        end = minutesToTime(it)
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
            Spacer(Modifier.height(14.dp))
            DateSection(
                strings = strings,
                date = date,
                allDay = allDay,
                onAllDayChange = { checked ->
                    allDay = checked
                    if (!checked && resolvedEndDate == date && endMinutes <= startMinutes) {
                        end = minutesToTime((startMinutes + 60).coerceAtMost(MaxEndMinutes))
                    }
                },
                onChange = {
                    date = it
                    if (endDate < it) endDate = it
                },
                onPickDate = { showDatePicker = true },
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
            Spacer(Modifier.height(10.dp))
            if (resolvedEndDate > date) {
                EndDateRow(
                    strings = strings,
                    date = resolvedEndDate,
                    allDay = allDay,
                    onPickDate = { showEndDatePicker = true },
                    onClear = { endDate = date },
                )
            } else {
                AddEndDateRow(text = strings.endDate, onClick = { showEndDatePicker = true })
            }
            Spacer(Modifier.height(14.dp))
            ExpandableRow(
                label = strings.repeat,
                value = repeatSummary(strings, repeatFrequency),
                expanded = repeatExpanded,
                onClick = { repeatExpanded = !repeatExpanded },
            )
            if (repeatExpanded) {
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
                if (repeatFrequency != null) {
                    Spacer(Modifier.height(12.dp))
                    IntervalRow(
                        label = strings.repeatInterval,
                        interval = interval,
                        onChange = { intervalText = it.toString() },
                    )
                    Spacer(Modifier.height(10.dp))
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
            Spacer(Modifier.height(8.dp))
            ExpandableRow(
                label = strings.reminder,
                value = reminderSummary(strings, reminder, reminderAtEnd),
                expanded = reminderExpanded,
                onClick = { reminderExpanded = !reminderExpanded },
            )
            if (reminderExpanded) {
                Spacer(Modifier.height(10.dp))
                ReminderSection(
                    strings = strings,
                    reminder = reminder,
                    atEnd = reminderAtEnd,
                    onMinutesChange = { reminder = it },
                    onBaseChange = { reminderAtEnd = it },
                )
            }
            Spacer(Modifier.height(14.dp))
            TextField(
                value = note,
                onValueChange = { note = it },
                label = strings.noteField,
                useLabelAsPlaceholder = true,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            MoreOptionsToggle(
                text = strings.moreOptions,
                expanded = showMoreOptions,
                onClick = { showMoreOptions = !showMoreOptions },
            )
            if (showMoreOptions) {
                Spacer(Modifier.height(8.dp))
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
                if (event.attachments.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Caption(strings.attachments)
                    event.attachments.forEach { attachment ->
                        AttachmentRow(attachment, strings, fileBridge) { attachmentNotice = it }
                    }
                    attachmentNotice?.let { notice ->
                        Text(
                            text = notice,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Caption(strings.eventStatus)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RepeatChip(strings.statusConfirmed, status == EventStatus.Confirmed, {
                                status = EventStatus.Confirmed
                            }, Modifier.weight(1f))
                            RepeatChip(strings.statusTentative, status == EventStatus.Tentative, {
                                status = EventStatus.Tentative
                            }, Modifier.weight(1f))
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Caption(strings.transparency)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            RepeatChip(strings.busy, transparency == EventTransparency.Opaque, {
                                transparency = EventTransparency.Opaque
                            }, Modifier.weight(1f))
                            RepeatChip(strings.free, transparency == EventTransparency.Transparent, {
                                transparency = EventTransparency.Transparent
                            }, Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Caption(strings.priority)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RepeatChip(strings.priorityNone, priority == null, { priority = null }, Modifier.weight(1f))
                    RepeatChip(strings.priorityHigh, priority == 1, { priority = 1 }, Modifier.weight(1f))
                    RepeatChip(strings.priorityNormal, priority == 5, { priority = 5 }, Modifier.weight(1f))
                    RepeatChip(strings.priorityLow, priority == 9, { priority = 9 }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
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
                            alarms = updateReminderAlarms(
                                event.alarms,
                                originalReminder,
                                reminder,
                                originalAtEnd,
                                reminderAtEnd,
                            ),
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

/**
 * Changes only the one relative reminder the editor shows. Other imported `VALARM`s (audio/email,
 * absolute or extra relative alarms) survive an edit instead of collapsing into one simple alarm.
 * [atEnd] keeps the `RELATED=END` base: switching the base rewrites it, leaving it untouched
 * preserves an imported end-relative trigger instead of silently rebasing it onto the start.
 */
internal fun updateReminderAlarms(
    alarms: List<Alarm>,
    original: Int?,
    reminder: Int?,
    originalAtEnd: Boolean = false,
    atEnd: Boolean = false,
): List<Alarm> {
    if (reminder == original && atEnd == originalAtEnd) return alarms
    val index = alarms.indexOfFirst { it.absoluteTrigger == null }
    return when {
        reminder == null -> if (index < 0) alarms else alarms.filterIndexed { i, _ -> i != index }
        index < 0 -> alarms + Alarm(triggerMinutes = reminder, relatedToEnd = atEnd)
        else -> alarms.toMutableList().also {
            it[index] = it[index].copy(triggerMinutes = reminder, relatedToEnd = atEnd)
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
        bySecond = keep?.bySecond ?: emptyList(),
        rDates = keep?.rDates ?: emptyList(),
    )
}

internal enum class TimeField { START, END }

internal fun minutesToTime(minutes: Int): LocalTime = LocalTime((minutes.coerceIn(0, MaxEndMinutes)) / 60, minutes.coerceIn(0, MaxEndMinutes) % 60)

/**
 * End minutes after the start time was picked. A zero-duration event keeps its zero length — the
 * end follows the new start; a start pushed past the end on the same day drags the end one hour
 * ahead. Null keeps the current end.
 */
internal fun endMinutesAfterStartChange(
    zeroDuration: Boolean,
    sameDay: Boolean,
    pickedMinutes: Int,
    endMinutes: Int,
): Int? = when {
    zeroDuration -> pickedMinutes
    sameDay && pickedMinutes >= endMinutes -> (pickedMinutes + 60).coerceAtMost(MaxEndMinutes)
    else -> null
}

@Composable
private fun MoreOptionsToggle(
    text: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = AppIcons.Expand,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
        )
    }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    strings: Strings,
    bridge: CalendarFileBridge,
    onNotice: (String) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val openable = attachment.uri.startsWith("http://") || attachment.uri.startsWith("https://")
    // Read once per attachment: a several-megabyte payload decoded on every recomposition while the
    // user types in the editor would stall the frame.
    val payload = remember(attachment) { if (attachment.binary) attachment.attachmentBytes() else null }
    val label = attachment.name.ifBlank {
        if (attachment.binary) strings.attachments else attachment.uri.ifBlank { strings.attachments }
    }
    Text(
        text = label,
        style = MiuixTheme.textStyles.body2,
        fontSize = 13.sp,
        color = if (openable || payload != null) {
            MiuixTheme.colorScheme.primary
        } else {
            MiuixTheme.colorScheme.onSurfaceVariantSummary
        },
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = openable || attachment.binary) {
                if (attachment.binary) {
                    val bytes = payload
                    if (bytes == null) {
                        onNotice(strings.attachmentBroken)
                    } else {
                        bridge.saveAttachment(
                            fileName = safeAttachmentFileName(attachment),
                            bytes = bytes,
                            mimeType = attachmentMimeType(attachment),
                            onSaved = {},
                            onError = { onNotice(strings.attachmentSaveFailed) },
                        )
                    }
                } else if (openable) {
                    uriHandler.openUri(attachment.uri)
                }
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    )
}

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

/** Low-emphasis action that turns a one-day event into a multi-day one. */
@Composable
private fun AddEndDateRow(
    text: String,
    onClick: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = "+ $text",
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
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
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
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

/** Row that reveals a secondary setting and shows its current value while collapsed. */
@Composable
private fun ExpandableRow(
    label: String,
    value: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp),
        )
        Icon(
            imageVector = AppIcons.Expand,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp).rotate(if (expanded) 180f else 0f),
        )
    }
}

private fun repeatSummary(strings: Strings, frequency: RepeatFrequency?): String = when (frequency) {
    null -> strings.repeatNone
    RepeatFrequency.Daily -> strings.repeatDaily
    RepeatFrequency.Weekly -> strings.repeatWeekly
    RepeatFrequency.Monthly -> strings.repeatMonthly
    RepeatFrequency.Yearly -> strings.repeatYearly
    RepeatFrequency.Custom -> strings.repeatCustom
}

private fun reminderSummary(strings: Strings, reminder: Int?, atEnd: Boolean): String = when (reminder) {
    null -> strings.reminderNone
    else -> strings.alarmLabel(reminder, atEnd)
}

/** Compact stepper: the interval is a single line instead of two half-width buttons. */
@Composable
private fun IntervalRow(
    label: String,
    interval: Int,
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        StepButton("−", enabled = interval > 1) { onChange((interval - 1).coerceAtLeast(1)) }
        Text(
            text = interval.toString(),
            style = MiuixTheme.textStyles.title3,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(40.dp),
        )
        StepButton("+") { onChange(interval + 1) }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.title3,
            color = if (enabled) {
                MiuixTheme.colorScheme.onSurface
            } else {
                MiuixTheme.colorScheme.disabledOnSurface
            },
        )
    }
}

/** Horizontal strip of dates: the selected day plus the surrounding week, with a month picker. */
@Composable
private fun DateSection(
    strings: Strings,
    date: LocalDate,
    allDay: Boolean,
    onAllDayChange: (Boolean) -> Unit,
    onChange: (LocalDate) -> Unit,
    onPickDate: () -> Unit,
) {
    val days = remember(date) { date.startOfWeek().let { start -> (0 until 7).map { start.plusDays(it) } } }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.dayMonth(date),
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
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
            Spacer(Modifier.weight(1f))
            Text(
                text = strings.allDay,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(6.dp))
            Switch(checked = allDay, onCheckedChange = onAllDayChange)
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.forEachIndexed { index, day ->
                val selected = day == date
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainerHighest,
                        )
                        .clickable { onChange(day) }
                        .padding(vertical = 5.dp),
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
                        fontSize = 13.sp,
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
private fun ReminderSection(
    strings: Strings,
    reminder: Int?,
    atEnd: Boolean,
    onMinutesChange: (Int?) -> Unit,
    onBaseChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(strings.reminderNone, reminder == null, { onMinutesChange(null) }, Modifier.weight(1f))
            RepeatChip(
                strings.alarmLabel(0, atEnd),
                reminder == 0,
                { onMinutesChange(0) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.alarmLabel(5, atEnd),
                reminder == 5,
                { onMinutesChange(5) },
                Modifier.weight(1f),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RepeatChip(
                strings.alarmLabel(15, atEnd),
                reminder == 15,
                { onMinutesChange(15) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.alarmLabel(30, atEnd),
                reminder == 30,
                { onMinutesChange(30) },
                Modifier.weight(1f),
            )
            RepeatChip(
                strings.alarmLabel(60, atEnd),
                reminder == 60,
                { onMinutesChange(60) },
                Modifier.weight(1f),
            )
        }
        // The presets are minutes relative to a base; imported `RELATED=END` triggers must be able
        // to keep it, and a start-relative reminder can be switched to fire relative to the end.
        if (reminder != null) {
            Spacer(Modifier.height(6.dp))
            Caption(strings.reminderRelativeTo)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                RepeatChip(strings.reminderRelativeToStart, !atEnd, { onBaseChange(false) }, Modifier.weight(1f))
                RepeatChip(strings.reminderRelativeToEnd, atEnd, { onBaseChange(true) }, Modifier.weight(1f))
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
                if (selected) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainerHighest,
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
