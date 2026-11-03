package com.crko.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.crko.domain.Attachment
import com.crko.domain.Event
import com.crko.i18n.Strings
import com.crko.state.EditScope
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Editor for a new or existing event. A null [Event.id] marks a new event. */
@Composable
internal fun EventEditorDialog(
    event: Event?,
    occurrenceDate: LocalDate?,
    strings: Strings,
    today: LocalDate,
    readAttachment: (Attachment) -> ByteArray?,
    onDismiss: () -> Unit,
    onSave: (Event, EditScope) -> Boolean,
    onDelete: (Event, EditScope) -> Unit,
) {
    if (event != null) {
        EventEditorContent(
            event,
            occurrenceDate,
            strings,
            today,
            readAttachment,
            onDismiss,
            onSave,
            onDelete,
        )
    }
}

@Composable
private fun EventEditorContent(
    event: Event,
    occurrenceDate: LocalDate?,
    strings: Strings,
    today: LocalDate,
    readAttachment: (Attachment) -> ByteArray?,
    onDismiss: () -> Unit,
    onSave: (Event, EditScope) -> Boolean,
    onDelete: (Event, EditScope) -> Unit,
) {
    val state = rememberEventEditorState(event, occurrenceDate)
    if (state.pickingTimeField != null) {
        EventEditorTimeDialog(state = state, strings = strings)
        return
    }
    if (EventEditorDateDialogs(state = state, strings = strings, today = today)) return

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = if (event.id == null) strings.eventNew else strings.eventEdit,
        insideMargin = DpSize(20.dp, 16.dp),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TextField(
                value = state.title,
                onValueChange = state::onTitleChange,
                label = strings.eventTitleField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            DateSection(
                strings = strings,
                date = state.date,
                allDay = state.allDay,
                onAllDayChange = state::onAllDayChange,
                onChange = state::onDatePicked,
                onPickDate = { state.showDatePicker = true },
            )
            if (!state.allDay) {
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeField(
                        label = strings.startTime,
                        value = strings.time(state.start),
                        onClick = { state.showStartPicker = true },
                        modifier = Modifier.weight(1f),
                    )
                    TimeField(
                        label = strings.endTime,
                        value = strings.time(state.end),
                        onClick = { state.showEndPicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (state.resolvedEndDate > state.date) {
                EndDateRow(
                    strings = strings,
                    date = state.resolvedEndDate,
                    allDay = state.allDay,
                    onPickDate = { state.showEndDatePicker = true },
                    onClear = { state.endDate = state.date },
                )
            } else {
                AddEndDateRow(text = strings.endDate, onClick = { state.showEndDatePicker = true })
            }
            Spacer(Modifier.height(14.dp))
            ExpandableRow(
                label = strings.repeat,
                value = repeatSummary(strings, state.repeatFrequency),
                expanded = state.repeatExpanded,
                onClick = { state.repeatExpanded = !state.repeatExpanded },
            )
            if (state.repeatExpanded) {
                Spacer(Modifier.height(10.dp))
                RepeatSettings(state = state, strings = strings)
            }
            Spacer(Modifier.height(8.dp))
            ExpandableRow(
                label = strings.reminder,
                value = reminderSummary(strings, state.reminder, state.reminderAtEnd),
                expanded = state.reminderExpanded,
                onClick = { state.reminderExpanded = !state.reminderExpanded },
            )
            if (state.reminderExpanded) {
                Spacer(Modifier.height(10.dp))
                ReminderSection(
                    strings = strings,
                    reminder = state.reminder,
                    atEnd = state.reminderAtEnd,
                    onMinutesChange = { state.reminder = it },
                    onBaseChange = { state.reminderAtEnd = it },
                )
            }
            Spacer(Modifier.height(14.dp))
            TextField(
                value = state.note,
                onValueChange = { state.note = it },
                label = strings.noteField,
                useLabelAsPlaceholder = true,
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            MoreOptionsToggle(
                text = strings.moreOptions,
                expanded = state.showMoreOptions,
                onClick = { state.showMoreOptions = !state.showMoreOptions },
            )
            if (state.showMoreOptions) {
                Spacer(Modifier.height(8.dp))
                MoreOptionsSection(
                    state = state,
                    strings = strings,
                    attachments = event.attachments,
                    readAttachment = readAttachment,
                )
            }
            if (state.canScope) {
                Spacer(Modifier.height(10.dp))
                EditScopeSection(
                    strings = strings,
                    scope = state.scope,
                    onScopeChange = { state.scope = it },
                )
            }
            val message = when {
                state.error != null -> state.error
                state.title.isBlank() -> strings.emptyTitle
                !state.timeValid -> strings.invalidTimeRange
                !state.repeatValid -> strings.repeatWeekdaysRequired
                else -> null
            }
            if (message != null && (state.error != null || state.title.isNotBlank())) {
                Text(
                    text = message,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            val selectedScope = state.selectedScope
            Button(
                onClick = {
                    if (onSave(state.buildEvent(), selectedScope)) onDismiss()
                },
                enabled = state.valid,
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
