package com.crko.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Calendar
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.startOfMonth
import com.crko.i18n.Strings
import com.crko.state.todo.TodoDraft
import com.crko.ui.components.CalendarChips
import com.crko.ui.icons.AppIcons
import com.crko.ui.schedule.WeekMonthCalendar
import com.crko.ui.schedule.priorityColor
import com.crko.ui.schedule.rememberCalendarFold
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Completion percentages offered as one-tap chips. */
private val PercentSteps = listOf(0, 25, 50, 75, 100)

/** The optional fields the icon row toggles; one of them is expanded at a time. */
private enum class TodoField { Due, Range, Priority, Progress, Location, Categories }

/**
 * One dialog for both creating and editing a todo. In edit mode [initial] prefills every field; a
 * subtask reuses the same layout with [parent] naming its parent. The core form carries the merged
 * title/note field and the calendar picker; everything else — the date pickers, priority, progress,
 * location and categories — folds behind a row of small icons, each expanding its editor inline.
 * [onSave] receives the draft and the calendar picked for a new top-level todo.
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
    val state = rememberTodoEditorState(editingTarget, parentTarget, session, defaultCalendarId)
    // Which optional field is expanded under the icon row; keyed like the state so a reopened
    // dialog starts with every section folded.
    var expanded by remember(editingTarget, parentTarget, session) { mutableStateOf<TodoField?>(null) }

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
            TodoFormCore(strings = strings, state = state, calendars = calendars)
            Spacer(Modifier.height(12.dp))
            TodoFieldIconRow(
                strings = strings,
                state = state,
                expanded = expanded,
                onToggle = { field -> expanded = if (expanded == field) null else field },
            )
            TodoFieldSection(strings = strings, today = today, state = state, field = expanded)
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (state.canSave && onSave(state.buildDraft(), if (state.picksCalendar) state.calendarId else null)) onDismiss() },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }
}

/**
 * The form fields every editor session shares: the merged title/note field — the first line is the
 * title, everything after the first line break is the note — and the calendar picker for a new
 * top-level todo. The optional fields live behind the icon row instead of here. Everything binds
 * straight to the shared [state].
 */
@Composable
private fun TodoFormCore(
    strings: Strings,
    state: TodoEditorState,
    calendars: List<Calendar>,
) {
    TextField(
        value = state.bodyText,
        onValueChange = { state.bodyText = it },
        label = strings.todoTitleField,
        useLabelAsPlaceholder = true,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.picksCalendar && calendars.size > 1) {
        Spacer(Modifier.height(12.dp))
        SectionCaption(strings.calendar)
        CalendarChips(
            calendars = calendars,
            selectedId = state.calendarId,
            onSelect = { state.calendarId = it },
        )
    }
}

/**
 * The optional fields as a row of small icons: tapping one expands its editor under the row and
 * swaps the previously open one out — one section at a time keeps the dialog short. A tap light
 * marks the fields whose value is non-default, so set fields stay discoverable while folded away.
 */
@Composable
private fun TodoFieldIconRow(
    strings: Strings,
    state: TodoEditorState,
    expanded: TodoField?,
    onToggle: (TodoField) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldToggle(
            field = TodoField.Due,
            icon = AppIcons.Calendar,
            label = strings.dueDate,
            expanded = expanded == TodoField.Due,
            hasValue = state.due != null,
            onToggle = onToggle,
        )
        FieldToggle(
            field = TodoField.Range,
            icon = AppIcons.DateRange,
            label = strings.dateRange,
            expanded = expanded == TodoField.Range,
            hasValue = state.start != null,
            onToggle = onToggle,
        )
        FieldToggle(
            field = TodoField.Priority,
            icon = AppIcons.Flag,
            label = strings.priority,
            expanded = expanded == TodoField.Priority,
            hasValue = state.priority != TodoPriority.Normal,
            onToggle = onToggle,
        )
        FieldToggle(
            field = TodoField.Progress,
            icon = AppIcons.Progress,
            label = strings.progress,
            expanded = expanded == TodoField.Progress,
            hasValue = state.percent != 0,
            onToggle = onToggle,
        )
        FieldToggle(
            field = TodoField.Location,
            icon = AppIcons.Location,
            label = strings.locationField,
            expanded = expanded == TodoField.Location,
            hasValue = state.location.isNotBlank(),
            onToggle = onToggle,
        )
        FieldToggle(
            field = TodoField.Categories,
            icon = AppIcons.Tag,
            label = strings.categoriesField,
            expanded = expanded == TodoField.Categories,
            hasValue = state.categoriesText.isNotBlank(),
            onToggle = onToggle,
        )
    }
}

/** One icon in the row: lit while its section is open, dotted once the field carries a value. */
@Composable
private fun FieldToggle(
    field: TodoField,
    icon: ImageVector,
    label: String,
    expanded: Boolean,
    hasValue: Boolean,
    onToggle: (TodoField) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (expanded) MiuixTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable { onToggle(field) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = when {
                expanded -> MiuixTheme.colorScheme.primary
                hasValue -> MiuixTheme.colorScheme.onSurface
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
            modifier = Modifier.size(20.dp),
        )
        if (hasValue) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * The expanded field's editor, animated in and out under the icon row. Every field keeps its own
 * AnimatedVisibility so a folding section keeps rendering its content through the exit animation —
 * reading [field] directly would swap to nothing (or straight to the next field) the moment the
 * row toggles, and the collapse would jump instead of shrinking. Switching fields folds the old
 * section while the new one unfolds below it. The date pickers carry their own caption rows; the
 * chip rows and text fields get a caption or a placeholder instead. Everything binds straight to
 * the shared [state].
 */
@Composable
private fun TodoFieldSection(
    strings: Strings,
    today: LocalDate,
    state: TodoEditorState,
    field: TodoField?,
) {
    Column {
        TodoField.entries.forEach { entry ->
            AnimatedVisibility(
                visible = field == entry,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    when (entry) {
                        TodoField.Due -> WeekCalendar(
                            label = strings.dueDate,
                            strings = strings,
                            today = today,
                            selected = state.due,
                            onSelect = { state.due = it },
                        )
                        TodoField.Range -> DateRangeCalendar(
                            strings = strings,
                            today = today,
                            start = state.start,
                            due = state.due,
                            onStart = { state.start = it },
                            onDue = { state.due = it },
                        )
                        TodoField.Priority -> {
                            SectionCaption(strings.priority)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PriorityChip(strings.priorityHigh, TodoPriority.High, state.priority, { state.priority = it }, Modifier.weight(1f))
                                PriorityChip(strings.priorityNormal, TodoPriority.Normal, state.priority, { state.priority = it }, Modifier.weight(1f))
                                PriorityChip(strings.priorityLow, TodoPriority.Low, state.priority, { state.priority = it }, Modifier.weight(1f))
                            }
                        }
                        TodoField.Progress -> {
                            SectionCaption(strings.progress)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PercentSteps.forEach { step ->
                                    PercentChip(step, state.percent, { state.percent = it }, Modifier.weight(1f))
                                }
                            }
                        }
                        TodoField.Location -> TextField(
                            value = state.location,
                            onValueChange = { state.location = it },
                            label = strings.locationField,
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TodoField.Categories -> TextField(
                            value = state.categoriesText,
                            onValueChange = { state.categoriesText = it },
                            label = strings.categoriesField,
                            useLabelAsPlaceholder = true,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * The range editor: one calendar holding both ends of the todo's date range. The first pick sets
 * the due date, a second pick completes the range — the earlier day becomes the start, swap
 * included — and picking with both ends set restarts from that day as the new due. The days
 * between the ends take the range tint and the start end carries the small dot; picking both ends
 * folds the month back into the week strip. The caption's clear button empties both.
 */
@Composable
private fun DateRangeCalendar(
    strings: Strings,
    today: LocalDate,
    start: LocalDate?,
    due: LocalDate?,
    onStart: (LocalDate?) -> Unit,
    onDue: (LocalDate?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var monthAnchor by remember { mutableStateOf((due ?: start ?: today).startOfMonth()) }
    val fold = rememberCalendarFold()
    val hasDates = start != null || due != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            text = strings.dateRange,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        if (hasDates) {
            Text(
                text = strings.clear,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        onStart(null)
                        onDue(null)
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = strings.monthName(monthAnchor.month),
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = AppIcons.Expand,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .size(14.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
    WeekMonthCalendar(
        strings = strings,
        selected = due ?: start ?: today,
        today = today,
        monthAnchor = monthAnchor,
        // The start end carries the small dot, so the two ends read differently.
        hasEvents = { it == start },
        fold = fold,
        expanded = expanded,
        showWeekSelection = hasDates,
        showSelection = hasDates,
        onSelect = { day ->
            val currentStart = start
            val currentDue = due
            val restarting = currentStart != null && currentDue != null
            val newStart: LocalDate?
            val newDue: LocalDate?
            when {
                currentStart == null && currentDue == null -> {
                    newStart = null
                    newDue = day
                }
                currentStart == null && currentDue != null -> if (day < currentDue) {
                    newStart = day
                    newDue = currentDue
                } else {
                    newStart = currentDue
                    newDue = day
                }
                currentStart != null && currentDue == null -> if (day < currentStart) {
                    newStart = day
                    newDue = currentStart
                } else {
                    newStart = currentStart
                    newDue = day
                }
                else -> {
                    newStart = null
                    newDue = day
                }
            }
            onStart(newStart)
            onDue(newDue)
            // A completed range folds the month back into the week strip; a restart stays open.
            if (!restarting && newStart != null && newDue != null) expanded = false
        },
        onMonthChange = { monthAnchor = it },
        driveStrip = null,
        range = if (start != null && due != null) start to due else null,
    )
}

/**
 * The due-date editor: a pinned weekday header over a horizontally paged week strip that picks
 * nearby days with one tap, and a caption row that folds the full month calendar open for far
 * ones — the same unfold the schedule's calendar uses. Tapping the selected day again clears it,
 * and picking from the month folds the week row back.
 */
@Composable
private fun WeekCalendar(
    label: String,
    strings: Strings,
    today: LocalDate,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var monthAnchor by remember { mutableStateOf((selected ?: today).startOfMonth()) }
    val fold = rememberCalendarFold()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = strings.monthName(monthAnchor.month),
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = AppIcons.Expand,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .size(14.dp)
                .rotate(if (expanded) 180f else 0f),
        )
    }
    WeekMonthCalendar(
        strings = strings,
        // The calendar wants a day to stand on; with none picked the week strip simply hides its
        // selection and the month grid outlines nothing extra — today keeps its own fill.
        selected = selected ?: today,
        today = today,
        monthAnchor = monthAnchor,
        hasEvents = { false },
        fold = fold,
        expanded = expanded,
        showWeekSelection = selected != null,
        onSelect = { picked ->
            if (picked == selected) onSelect(null) else onSelect(picked)
            expanded = false
        },
        onMonthChange = { monthAnchor = it },
        driveStrip = null,
    )
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
    modifier: Modifier = Modifier,
) {
    val selected = value == current
    val accent = priorityColor(value)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable { onSelect(value) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
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
    val selected = value == current
    Text(
        text = "$value%",
        style = MiuixTheme.textStyles.body2,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) {
                    MiuixTheme.colorScheme.primaryContainer
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
            )
            .clickable { onSelect(value) }
            .padding(vertical = 8.dp),
    )
}
