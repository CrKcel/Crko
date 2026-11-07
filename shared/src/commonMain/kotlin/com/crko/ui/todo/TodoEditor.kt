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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.icons.AppIcons
import com.crko.ui.schedule.WeekMonthCalendar
import com.crko.ui.schedule.priorityColor
import com.crko.ui.schedule.rememberCalendarFold
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
 * opened from the dialog's action row carries the complete form — the same fields plus the advanced
 * ones and
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
    // session reset waits for the page to finish animating out instead. While it is set, the
    // dialog's own hide animation may still be finishing (a save tapped right after opening the
    // page); its dismiss-finished callback must not reset the form the exiting page still shows.
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
        onDismissFinished = { if (!advanced && !resetPending) session += 1 },
        title = dialogTitle,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            TodoFormCore(strings = strings, today = today, state = state, calendars = calendars)
            Spacer(Modifier.height(12.dp))
            // The advanced entry shares the save button's row so the dialog stays short.
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AdvancedEntry(text = strings.advancedSettings, onClick = { advanced = true })
                Button(
                    onClick = { if (saveDraft()) onDismiss() },
                    enabled = state.canSave,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(strings.save)
                }
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
            TodoFormCore(
                strings = strings,
                today = today,
                state = state,
                calendars = calendars,
                showStartDate = true,
            )
            Spacer(Modifier.height(12.dp))
            SectionCaption(strings.priority)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PriorityChip(strings.priorityHigh, TodoPriority.High, state.priority, { state.priority = it }, Modifier.weight(1f))
                PriorityChip(strings.priorityNormal, TodoPriority.Normal, state.priority, { state.priority = it }, Modifier.weight(1f))
                PriorityChip(strings.priorityLow, TodoPriority.Low, state.priority, { state.priority = it }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            SectionCaption(strings.progress)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PercentSteps.forEach { step ->
                    PercentChip(step, state.percent, { state.percent = it }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
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

/**
 * The form fields both editor surfaces share: the merged title/note field, calendar picker and the
 * date fields, in the same order and spacing. The field's first line is the title, the rest is the
 * note. With [showStartDate] the start date sits under the due date. Everything binds straight to
 * the shared [state]; each surface adds its own fields and actions around this.
 */
@Composable
private fun TodoFormCore(
    strings: Strings,
    today: LocalDate,
    state: TodoEditorState,
    calendars: List<Calendar>,
    showStartDate: Boolean = false,
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
    Spacer(Modifier.height(12.dp))
    if (showStartDate) {
        DateRangeCalendar(
            strings = strings,
            today = today,
            start = state.start,
            due = state.due,
            onStart = { state.start = it },
            onDue = { state.due = it },
        )
    } else {
        WeekCalendar(
            label = strings.dueDate,
            strings = strings,
            today = today,
            selected = state.due,
            onSelect = { state.due = it },
        )
    }
}

/**
 * The advanced page's date section: one calendar holding both ends of the todo's date range. The
 * first pick sets the due date, a second pick completes the range — the earlier day becomes the
 * start, swap included — and picking with both ends set restarts from that day as the new due. The
 * days between the ends take the range tint and the start end carries the small dot; picking both
 * ends folds the month back into the week strip. The caption's clear button empties both.
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
 * The editor's date section: a pinned weekday header over a horizontally paged week strip that
 * picks nearby days with one tap, and a caption row that folds the full month calendar open for
 * far ones — the same unfold the schedule's calendar uses. Tapping the selected day again clears
 * it, and picking from the month folds the week row back.
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

/** Chip in the dialog's action row opening the advanced-settings page, sized to the save button. */
@Composable
private fun AdvancedEntry(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = AppIcons.Tune,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
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
