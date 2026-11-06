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
import androidx.compose.runtime.mutableIntStateOf
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
import com.crko.domain.plusDays
import com.crko.i18n.Strings
import com.crko.state.todo.TodoDraft
import com.crko.ui.components.CalendarChips
import com.crko.ui.schedule.DatePickerDialog
import com.crko.ui.schedule.MoreOptionsToggle
import com.crko.ui.schedule.priorityColor
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Which date the shared month picker is editing when the dialog is open. */
private enum class TodoDateField { Start, Due }

/** Completion percentages offered as one-tap chips. */
private val PercentSteps = listOf(0, 25, 50, 75, 100)

/**
 * One dialog for both creating and editing a todo. In edit mode [initial] prefills every field; a
 * subtask reuses the same layout with [parent] naming its parent. [onSave] receives the draft and
 * the calendar picked for a new top-level todo.
 */
@Composable
internal fun TodoEditorDialog(
    show: Boolean,
    strings: Strings,
    today: LocalDate,
    onDismiss: () -> Unit,
    onSave: (TodoDraft, String?) -> Boolean,
    /** Existing todo to edit; null creates a new one. */
    initial: Todo? = null,
    /** Parent of the subtask being created; null creates a top-level todo. */
    parent: Todo? = null,
    calendars: List<Calendar> = emptyList(),
    defaultCalendarId: String? = null,
) {
    // Hold the last editing target so the sheet keeps its content while animating out: the call
    // site nulls [initial]/[parent] on dismiss, which would otherwise blank the form mid-exit.
    var shownInitial by remember { mutableStateOf<Todo?>(null) }
    var shownParent by remember { mutableStateOf<Todo?>(null) }
    if (show) {
        shownInitial = initial
        shownParent = parent
    }
    val editingTarget = shownInitial
    val parentTarget = shownParent
    // Bumped once the sheet finished animating out, so a reopened sheet starts blank instead of
    // keeping the last session's input.
    var session by remember { mutableIntStateOf(0) }
    // Keyed on the target and [session]: editing another todo starts from its content and a
    // reopened dialog starts blank instead of keeping the last session's input.
    var title by remember(editingTarget, parentTarget, session) { mutableStateOf(editingTarget?.title ?: "") }
    var note by remember(editingTarget, parentTarget, session) { mutableStateOf(editingTarget?.note ?: "") }
    var location by remember(editingTarget, parentTarget, session) { mutableStateOf(editingTarget?.location ?: "") }
    var priority by remember(editingTarget, parentTarget, session) {
        mutableStateOf(editingTarget?.priorityLevel ?: TodoPriority.Normal)
    }
    var start by remember(editingTarget, parentTarget, session) { mutableStateOf(editingTarget?.startDay) }
    var due by remember(editingTarget, parentTarget, session) { mutableStateOf(editingTarget?.dueDay) }
    var percent by remember(editingTarget, parentTarget, session) { mutableStateOf(editingTarget?.percentComplete ?: 0) }
    var categoriesText by remember(editingTarget, parentTarget, session) {
        mutableStateOf(editingTarget?.categories?.joinToString(", ").orEmpty())
    }
    var calendarId by remember(editingTarget, parentTarget, session) { mutableStateOf(defaultCalendarId) }
    var picking by remember(editingTarget, parentTarget, session) { mutableStateOf<TodoDateField?>(null) }
    // Collapsed unless an edited todo already carries non-default values in the folded fields, so
    // reopening it never hides the data the user entered before.
    var moreOptions by remember(editingTarget, parentTarget, session) {
        mutableStateOf(
            editingTarget != null && (
                editingTarget.priorityLevel != TodoPriority.Normal ||
                    editingTarget.percentComplete != 0 ||
                    editingTarget.location.isNotBlank() ||
                    editingTarget.categories.isNotEmpty()
                ),
        )
    }

    val dialogTitle = when {
        editingTarget != null -> strings.todoEdit
        parentTarget != null -> strings.subtaskOf(parentTarget.title)
        else -> strings.todoNew
    }

    WindowDialog(
        show = show,
        onDismissRequest = onDismiss,
        onDismissFinished = { session += 1 },
        title = dialogTitle,
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
            if (editingTarget == null && parentTarget == null && calendars.size > 1) {
                Spacer(Modifier.height(12.dp))
                SectionCaption(strings.calendar)
                CalendarChips(
                    calendars = calendars,
                    selectedId = calendarId,
                    onSelect = { calendarId = it },
                )
            }
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.startDate)
            DateRow(
                strings = strings,
                today = today,
                selected = start,
                onSelect = { start = it },
                onCustom = { picking = TodoDateField.Start },
            )
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.dueDate)
            DateRow(
                strings = strings,
                today = today,
                selected = due,
                onSelect = { due = it },
                onCustom = { picking = TodoDateField.Due },
            )
            Spacer(Modifier.height(16.dp))
            TextField(
                value = note,
                onValueChange = { note = it },
                label = strings.noteField,
                useLabelAsPlaceholder = true,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            MoreOptionsToggle(
                text = strings.moreOptions,
                expanded = moreOptions,
                onClick = { moreOptions = !moreOptions },
            )
            if (moreOptions) {
                Spacer(Modifier.height(8.dp))
                SectionCaption(strings.priority)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriorityChip(strings.priorityHigh, TodoPriority.High, priority) { priority = it }
                    PriorityChip(strings.priorityNormal, TodoPriority.Normal, priority) { priority = it }
                    PriorityChip(strings.priorityLow, TodoPriority.Low, priority) { priority = it }
                }
                Spacer(Modifier.height(16.dp))
                SectionCaption(strings.progress)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PercentSteps.forEach { step ->
                        PercentChip(step, percent, { percent = it }, Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextField(
                    value = location,
                    onValueChange = { location = it },
                    label = strings.locationField,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = categoriesText,
                    onValueChange = { categoriesText = it },
                    label = strings.categoriesField,
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val draft = TodoDraft(
                        title = title,
                        note = note,
                        location = location,
                        start = start,
                        due = due,
                        priority = priority,
                        percentComplete = percent,
                        categories = categoriesText
                            .split(',', '，')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() },
                    )
                    val pickedCalendar = if (editingTarget == null && parentTarget == null) calendarId else null
                    if (onSave(draft, pickedCalendar)) onDismiss()
                },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }

    picking?.let { field ->
        val current = if (field == TodoDateField.Start) start else due
        DatePickerDialog(
            initial = current ?: today,
            today = today,
            strings = strings,
            onDismiss = { picking = null },
            onConfirm = { picked ->
                if (field == TodoDateField.Start) start = picked else due = picked
                picking = null
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
private fun DateRow(
    strings: Strings,
    today: LocalDate,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    onCustom: () -> Unit,
) {
    val tomorrow = today.plusDays(1)
    // A date that is neither shortcut is shown on the custom chip itself.
    val custom = selected?.takeIf { it != today && it != tomorrow }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DateChip(
            text = strings.today,
            selected = selected == today,
            onClick = { onSelect(if (selected == today) null else today) },
            modifier = Modifier.weight(1f),
        )
        DateChip(
            text = strings.tomorrow,
            selected = selected == tomorrow,
            onClick = { onSelect(if (selected == tomorrow) null else tomorrow) },
            modifier = Modifier.weight(1f),
        )
        DateChip(
            text = custom?.let(strings::dayMonth) ?: strings.customDate,
            selected = custom != null,
            onClick = onCustom,
            modifier = Modifier.weight(1f),
        )
        DateChip(
            text = strings.clear,
            selected = selected == null,
            onClick = { onSelect(null) },
            modifier = Modifier.weight(1f),
        )
    }
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
private fun PercentChip(value: Int, current: Int, onSelect: (Int) -> Unit, modifier: Modifier) {
    DateChip(
        text = "$value%",
        selected = value == current,
        onClick = { onSelect(value) },
        modifier = modifier,
    )
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
