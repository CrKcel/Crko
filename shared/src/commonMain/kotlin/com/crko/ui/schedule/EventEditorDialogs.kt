package com.crko.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crko.i18n.Strings
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

internal enum class TimeField { START, END }

/**
 * Shows the start/end time picker backed by [EventEditorState], including the interaction rules
 * (zero-length follow, next-day rollover) the state applies on confirm.
 */
@Composable
internal fun EventEditorTimeDialog(
    state: EventEditorState,
    strings: Strings,
) {
    val field = state.pickingTimeField ?: return
    TimePickerDialog(
        initial = if (field == TimeField.START) state.start else state.end,
        title = if (field == TimeField.START) strings.startTime else strings.endTime,
        confirm = strings.done,
        cancel = strings.cancel,
        onDismiss = state::dismissTimePicker,
        onConfirm = { picked ->
            if (field == TimeField.START) state.onStartTimePicked(picked) else state.onEndTimePicked(picked)
            state.dismissTimePicker()
        },
    )
}

/**
 * Shows whichever date picker the state requested (start day, end day or repeat-until day) and
 * reports whether a dialog is on screen so the caller can skip rendering the form.
 */
@Composable
internal fun EventEditorDateDialogs(
    state: EventEditorState,
    strings: Strings,
    today: LocalDate,
): Boolean {
    when {
        state.showDatePicker -> DatePickerDialog(
            initial = state.date,
            today = today,
            strings = strings,
            onDismiss = { state.showDatePicker = false },
            onConfirm = { picked ->
                state.onDatePicked(picked)
                state.showDatePicker = false
            },
        )
        state.showEndDatePicker -> DatePickerDialog(
            initial = state.resolvedEndDate,
            today = today,
            strings = strings,
            onDismiss = { state.showEndDatePicker = false },
            onConfirm = { picked ->
                state.onEndDatePicked(picked)
                state.showEndDatePicker = false
            },
        )
        state.showUntilDatePicker -> DatePickerDialog(
            initial = state.untilDate,
            today = today,
            strings = strings,
            onDismiss = { state.showUntilDatePicker = false },
            onConfirm = { picked ->
                state.onUntilDatePicked(picked)
                state.showUntilDatePicker = false
            },
        )
        else -> return false
    }
    return true
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
