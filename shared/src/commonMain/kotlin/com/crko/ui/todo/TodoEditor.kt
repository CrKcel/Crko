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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.TodoPriority
import com.crko.domain.plusDays
import com.crko.i18n.Strings
import com.crko.ui.schedule.MonthCalendar
import com.crko.ui.schedule.priorityColor
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TodoEditorDialog(
    show: Boolean,
    strings: Strings,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, TodoPriority, LocalDate?) -> Boolean,
) {
    if (!show) return
    var title by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(TodoPriority.Normal) }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.todoNew,
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
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.priority)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriorityChip(strings.priorityHigh, TodoPriority.High, priority) { priority = it }
                PriorityChip(strings.priorityNormal, TodoPriority.Normal, priority) { priority = it }
                PriorityChip(strings.priorityLow, TodoPriority.Low, priority) { priority = it }
            }
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.dueDate)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateChip(
                    text = strings.today,
                    selected = due == today,
                    onClick = { due = if (due == today) null else today },
                    modifier = Modifier.weight(1f),
                )
                DateChip(
                    text = strings.tomorrow,
                    selected = due == today.plusDays(1),
                    onClick = { due = if (due == today.plusDays(1)) null else today.plusDays(1) },
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
                    if (onSave(title, priority, due)) onDismiss()
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
            .padding(vertical = 10.dp),
    )
}

/** Month picker used for due dates; horizontal paging between months is handled by the calendar. */
@Composable
internal fun DatePickerDialog(
    initial: LocalDate,
    today: LocalDate,
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(LocalDate(initial.year, initial.month, 1)) }
    var selected by remember { mutableStateOf(initial) }
    val eventDates = remember { emptySet<String>() }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.dueDate,
    ) {
        Column {
            MonthCalendar(
                eventDates = eventDates,
                strings = strings,
                monthAnchor = month,
                selected = selected,
                today = today,
                onSelect = { selected = it },
                onMonthChange = { month = it },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(text = strings.cancel, onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = strings.save,
                    onClick = { onConfirm(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
