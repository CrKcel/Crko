package com.crko.ui.todo

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Calendar
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.effectiveUid
import com.crko.domain.plusDays
import com.crko.i18n.Strings
import com.crko.ui.components.CalendarChips
import com.crko.ui.schedule.DatePickerDialog
import com.crko.ui.schedule.priorityColor
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TodoEditorDialog(
    show: Boolean,
    strings: Strings,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, TodoPriority, LocalDate?, String?, String?) -> Boolean,
    /** Parent of the subtask being created; null creates a top-level todo. */
    parent: Todo? = null,
    calendars: List<Calendar> = emptyList(),
    defaultCalendarId: String? = null,
) {
    if (!show) return
    // Keyed on [show]: the fields start blank on every open instead of keeping the last session's
    // input while the closed dialog's slots stay in the composition.
    var title by remember(show) { mutableStateOf("") }
    var priority by remember(show) { mutableStateOf(TodoPriority.Normal) }
    var due by remember(show) { mutableStateOf<LocalDate?>(null) }
    var calendarId by remember(show) { mutableStateOf(defaultCalendarId) }
    var showDatePicker by remember(show) { mutableStateOf(false) }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = parent?.let { strings.subtaskOf(it.title) } ?: strings.todoNew,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TextField(
                value = title,
                onValueChange = { title = it },
                label = strings.todoTitleField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (parent == null && calendars.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.calendar,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(6.dp))
                CalendarChips(
                    calendars = calendars,
                    selectedId = calendarId,
                    onSelect = { calendarId = it },
                )
            }
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.priority)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriorityChip(strings.priorityHigh, TodoPriority.High, priority) { priority = it }
                PriorityChip(strings.priorityNormal, TodoPriority.Normal, priority) { priority = it }
                PriorityChip(strings.priorityLow, TodoPriority.Low, priority) { priority = it }
            }
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.dueDate)
            val tomorrow = today.plusDays(1)
            // A due date that is neither of the shortcuts is shown on the custom chip itself.
            val customDue = due?.takeIf { it != today && it != tomorrow }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateChip(
                    text = strings.today,
                    selected = due == today,
                    onClick = { due = if (due == today) null else today },
                    modifier = Modifier.weight(1f),
                )
                DateChip(
                    text = strings.tomorrow,
                    selected = due == tomorrow,
                    onClick = { due = if (due == tomorrow) null else tomorrow },
                    modifier = Modifier.weight(1f),
                )
                DateChip(
                    text = customDue?.let(strings::dayMonth) ?: strings.customDate,
                    selected = customDue != null,
                    onClick = { showDatePicker = true },
                    modifier = Modifier.weight(1f),
                )
                DateChip(
                    text = strings.clear,
                    selected = due == null,
                    onClick = { due = null },
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    if (onSave(title, priority, due, parent?.effectiveUid, if (parent == null) calendarId else null)) {
                        onDismiss()
                    }
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }

    if (showDatePicker) {
        DatePickerDialog(
            initial = due ?: today,
            today = today,
            strings = strings,
            onDismiss = { showDatePicker = false },
            onConfirm = {
                due = it
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote2,
        fontSize = 11.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun PriorityChip(
    text: String,
    value: TodoPriority,
    current: TodoPriority,
    onSelect: (TodoPriority) -> Unit,
) {
    val selected = value == current
    val accent = priorityColor(value)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable { onSelect(value) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) accent else MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun DateChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    )
}
