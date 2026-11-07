package com.crko.ui.todo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.icons.AppIcons
import com.crko.ui.schedule.DatePickerDialog
import com.crko.ui.schedule.priorityColor
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Completion percentages offered as one-tap chips. */
private val PercentSteps = listOf(0, 25, 50, 75, 100)

/**
 * One dialog for both creating and editing a todo. In edit mode [initial] prefills every field; a
 * subtask reuses the same layout with [parent] naming its parent. The advanced-settings page
 * opened from the entry row carries the complete form — the same fields plus the advanced ones and
 * its own save — so a todo can be added without going back. Both surfaces share [TodoEditorState].
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
    // Whether the advanced-settings page is open above the dialog; reset per session so a reopened
    // dialog always lands on the main form.
    var advanced by remember(editingTarget, parentTarget, session) { mutableStateOf(false) }
    // Set when the page itself saved: that path never runs the dialog's dismiss animation, so the
    // session reset waits for the page to finish animating out instead.
    var resetPending by remember { mutableStateOf(false) }

    val dialogTitle = when {
        editingTarget != null -> strings.todoEdit
        parentTarget != null -> strings.subtaskOf(parentTarget.title)
        else -> strings.todoNew
    }

    // Both surfaces submit the same draft; only a new top-level todo picks where it lives.
    val saveDraft: () -> Boolean = {
        onSave(state.buildDraft(), if (state.picksCalendar) state.calendarId else null)
    }

    // The advanced-settings page takes over while open: the dialog hides so the page renders above
    // the shell (a dialog window would otherwise float over it). The session reset is skipped for
    // that round-trip, keeping everything the user entered.
    WindowDialog(
        show = show && !advanced,
        onDismissRequest = onDismiss,
        onDismissFinished = { if (!advanced) session += 1 },
        title = dialogTitle,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TextField(
                value = state.title,
                onValueChange = { state.title = it },
                label = strings.todoTitleField,
                useLabelAsPlaceholder = true,
                singleLine = true,
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
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.dueDate)
            DateRow(
                strings = strings,
                today = today,
                selected = state.due,
                onSelect = { state.due = it },
                onCustom = { state.picking = TodoDateField.Due },
            )
            Spacer(Modifier.height(16.dp))
            TextField(
                value = state.note,
                onValueChange = { state.note = it },
                label = strings.noteField,
                useLabelAsPlaceholder = true,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            AdvancedEntry(
                text = strings.advancedSettings,
                onClick = { advanced = true },
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { if (saveDraft()) onDismiss() },
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }

    // Page visibility is a transition state so the reset deferred by the page's save can wait for
    // the exit animation instead of blanking the form mid-flight.
    val pageVisible = remember { MutableTransitionState(false) }
    pageVisible.targetState = advanced
    AnimatedVisibility(
        visibleState = pageVisible,
        enter = slideInHorizontally { it } + fadeIn(),
        exit = slideOutHorizontally { it } + fadeOut(),
    ) {
        TodoAdvancedPage(
            strings = strings,
            today = today,
            state = state,
            calendars = calendars,
            onSaveFromPage = {
                if (saveDraft()) {
                    resetPending = true
                    onDismiss()
                    advanced = false
                }
            },
            onBack = { advanced = false },
        )
    }

    LaunchedEffect(pageVisible.isIdle) {
        if (resetPending && pageVisible.isIdle && !pageVisible.currentState) {
            resetPending = false
            session += 1
        }
    }

    state.picking?.let { field ->
        val current = if (field == TodoDateField.Start) state.start else state.due
        DatePickerDialog(
            initial = current ?: today,
            today = today,
            strings = strings,
            onDismiss = { state.picking = null },
            onConfirm = { picked ->
                if (field == TodoDateField.Start) state.start = picked else state.due = picked
                state.picking = null
            },
        )
    }
}

/**
 * The advanced-settings page, a full-screen page above the tab shell like the settings page. It
 * carries the complete form — the dialog's fields plus the advanced ones and its own save — so a
 * todo can be added without going back. Everything binds straight to the shared [state]; [onBack]
 * only navigates.
 */
@Composable
private fun TodoAdvancedPage(
    strings: Strings,
    today: LocalDate,
    state: TodoEditorState,
    calendars: List<Calendar>,
    onSaveFromPage: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 16.dp, bottom = 4.dp),
        ) {
            IconButton(onClick = onBack, backgroundColor = Color.Transparent) {
                Icon(
                    imageVector = AppIcons.Back,
                    contentDescription = strings.back,
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = strings.advancedSettings,
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = GlassNavigationBarDefaults.bottomMargin() + 12.dp),
        ) {
            TextField(
                value = state.title,
                onValueChange = { state.title = it },
                label = strings.todoTitleField,
                useLabelAsPlaceholder = true,
                singleLine = true,
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
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.dueDate)
            DateRow(
                strings = strings,
                today = today,
                selected = state.due,
                onSelect = { state.due = it },
                onCustom = { state.picking = TodoDateField.Due },
            )
            Spacer(Modifier.height(16.dp))
            TextField(
                value = state.note,
                onValueChange = { state.note = it },
                label = strings.noteField,
                useLabelAsPlaceholder = true,
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.startDate)
            DateRow(
                strings = strings,
                today = today,
                selected = state.start,
                onSelect = { state.start = it },
                onCustom = { state.picking = TodoDateField.Start },
            )
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.priority)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriorityChip(strings.priorityHigh, TodoPriority.High, state.priority) { state.priority = it }
                PriorityChip(strings.priorityNormal, TodoPriority.Normal, state.priority) { state.priority = it }
                PriorityChip(strings.priorityLow, TodoPriority.Low, state.priority) { state.priority = it }
            }
            Spacer(Modifier.height(16.dp))
            SectionCaption(strings.progress)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PercentSteps.forEach { step ->
                    PercentChip(step, state.percent, { state.percent = it }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(16.dp))
            TextField(
                value = state.location,
                onValueChange = { state.location = it },
                label = strings.locationField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = state.categoriesText,
                onValueChange = { state.categoriesText = it },
                label = strings.categoriesField,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onSaveFromPage,
                enabled = state.canSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.save)
            }
        }
    }
}

/** Row in the main dialog opening the advanced-settings page, styled like the other editor rows. */
@Composable
private fun AdvancedEntry(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Tune,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        Icon(
            imageVector = AppIcons.Forward,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
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
