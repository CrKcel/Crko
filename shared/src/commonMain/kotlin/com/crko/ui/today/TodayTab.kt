package com.crko.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.effectiveUid
import com.crko.i18n.Strings
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.state.today.FocusController
import com.crko.state.today.TodayController
import com.crko.ui.components.GlassNavigationBarDefaults
import com.crko.ui.todo.TodoActionsDialog
import com.crko.ui.todo.TodoDetailDialog
import com.crko.ui.todo.TodoEditorDialog
import com.crko.ui.todo.TodoPoolHeader
import com.crko.ui.todo.todoPoolItems

/**
 * The default page: date and energy, the next step, the three things, quick capture, and then the
 * whole todo pool. The former todo page lives here, so the app keeps three navigation slots.
 */
@Composable
internal fun TodayTab(
    state: AppStateHolder,
    controller: TodayController,
    focus: FocusController,
    strings: Strings,
    contentPadding: PaddingValues,
    captureRequest: Int,
    focusVisible: Boolean,
    onStartFocus: (taskId: String?, minutes: Int) -> Unit,
    onOpenFocus: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val today = state.today
    val plan = controller.plan
    val tasks = controller.tasks
    val next = controller.nextTask
    val active = state.activeTodoForest()
    val completed = state.completedTodoForest()
    val counts = remember(state.appState.visibleTodos) {
        var activeCount = 0
        var completedCount = 0
        state.appState.visibleTodos.forEach { todo ->
            if (todo.done) completedCount++ else activeCount++
        }
        activeCount to completedCount
    }

    var startTask by remember { mutableStateOf<Todo?>(null) }
    var showCantMove by remember { mutableStateOf(false) }
    var showSmallerStep by remember { mutableStateOf(false) }
    var showOtherTask by remember { mutableStateOf(false) }
    var showPlanEditor by remember { mutableStateOf(false) }
    var poolCompletedExpanded by rememberSaveable { mutableStateOf(false) }
    // Expansion is keyed by parent id; a missing entry means expanded, so new subtasks show up.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    var subtaskParent by remember { mutableStateOf<Todo?>(null) }
    var editing by remember { mutableStateOf<Todo?>(null) }
    var viewing by remember { mutableStateOf<Todo?>(null) }
    var actionsFor by remember { mutableStateOf<Todo?>(null) }
    val captureRequester = remember { FocusRequester() }
    val layoutDirection = LocalLayoutDirection.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                top = contentPadding.calculateTopPadding(),
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
            ),
    ) {
        TodayHeader(
            today = today,
            energy = plan.energy,
            strings = strings,
            onEnergy = controller::setEnergy,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = GlassNavigationBarDefaults.clearance() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val session = focus.session
            if (session != null && !focusVisible) {
                item(key = "focus-banner") {
                    val now = rememberNowMillis()
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        FocusBanner(
                            task = tasks.firstOrNull { it.id == session.taskId }
                                ?: state.appState.visibleTodos.firstOrNull { it.id == session.taskId },
                            strings = strings,
                            remainingMillis = focus.remainingMillis(now.value),
                            paused = focus.isPaused,
                            onOpen = onOpenFocus,
                        )
                    }
                }
            }

            item(key = "next-step") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    NextStepCard(
                        next = next,
                        firstStep = next?.let { todo ->
                            if (isLocalTodo(state, todo)) todo.coaching?.firstStep.orEmpty() else ""
                        }.orEmpty(),
                        stoppedForToday = controller.stoppedForToday,
                        hasPlan = plan.taskIds.isNotEmpty(),
                        strings = strings,
                        onStart = { next?.let { startTask = it } },
                        onCantMove = { showCantMove = true },
                        onPickPlan = { showPlanEditor = true },
                        onContinueDay = controller::resumeDay,
                    )
                }
            }

            item(key = "day-plan") {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    TodayPlanSection(
                        tasks = tasks,
                        today = today,
                        strings = strings,
                        shownDue = { todo -> state.nextTodoOccurrence(todo) },
                        firstStep = { todo ->
                            if (isLocalTodo(state, todo)) todo.coaching?.firstStep.orEmpty() else ""
                        },
                        onToggle = { todo ->
                            completeTodo(state, todo, state.nextTodoOccurrence(todo), today)
                        },
                        onEdit = { showPlanEditor = true },
                    )
                }
            }

            item(key = "quick-capture") {
                Box(Modifier.padding(horizontal = 16.dp)) {
                    QuickCapture(
                        strings = strings,
                        focusRequester = captureRequester,
                        captureRequest = captureRequest,
                        onAdd = { title ->
                            state.addTodo(title, TodoPriority.Normal, null)
                            state.notify(AppMessage.TodoAdded)
                        },
                    )
                }
            }

            item(key = "pool-header") {
                TodoPoolHeader(
                    activeCount = counts.first,
                    strings = strings,
                    onOpenSearch = onOpenSearch,
                )
            }

            todoPoolItems(
                active = active,
                completed = completed,
                activeCount = counts.first,
                completedCount = counts.second,
                strings = strings,
                today = today,
                collapsed = collapsed,
                completedExpanded = poolCompletedExpanded,
                shownDue = { todo -> state.nextTodoOccurrence(todo) },
                onToggleCompleted = { poolCompletedExpanded = !poolCompletedExpanded },
                onToggleCollapse = { todo ->
                    collapsed[todo.id] = !(collapsed[todo.id] ?: false)
                },
                onToggleDone = { todo ->
                    completeTodo(state, todo, state.nextTodoOccurrence(todo), today)
                },
                onSubtask = { subtaskParent = it },
                onDetail = { viewing = it },
                onActions = { actionsFor = it },
                onDelete = { state.deleteTodo(it.id) },
            )
        }
    }

    startTask?.let { task ->
        StartFocusDialog(
            task = task,
            firstStep = if (isLocalTodo(state, task)) task.coaching?.firstStep.orEmpty() else "",
            strings = strings,
            onDismiss = { startTask = null },
            onStart = { minutes ->
                startTask = null
                onStartFocus(task.id, minutes)
            },
        )
    }

    if (showCantMove) {
        val others = remember(plan.taskIds, state.appState.visibleTodos, next?.id) {
            val picked = plan.taskIds.toSet()
            state.appState.visibleTodos
                .filterNot { it.done }
                .filter { it.id != next?.id && (it.relatedTo.none { rel -> rel.isParent } || it.id in picked) }
        }
        CantMoveDialog(
            strings = strings,
            hasOther = others.isNotEmpty(),
            onDismiss = { showCantMove = false },
            onSmaller = {
                showCantMove = false
                showSmallerStep = true
            },
            onTwoMinutes = {
                showCantMove = false
                onStartFocus(next?.id, 2)
            },
            onOther = {
                showCantMove = false
                showOtherTask = true
            },
            onStop = {
                showCantMove = false
                controller.stopForToday()
            },
        )
    }

    if (showSmallerStep) {
        next?.let { task ->
            SmallerStepDialog(
                task = task,
                strings = strings,
                onDismiss = { showSmallerStep = false },
                onChoose = { step ->
                    state.setTodoFirstStep(task.id, step)
                    showSmallerStep = false
                },
            )
        }
    }

    if (showOtherTask) {
        val others = remember(plan.taskIds, state.appState.visibleTodos, next?.id) {
            val picked = plan.taskIds.toSet()
            state.appState.visibleTodos
                .filterNot { it.done }
                .filter { todo ->
                    todo.id != next?.id &&
                        (todo.relatedTo.none { rel -> rel.isParent } || todo.id in picked)
                }
        }
        OtherTaskDialog(
            candidates = others,
            strings = strings,
            onDismiss = { showOtherTask = false },
            onPick = { todo ->
                showOtherTask = false
                onStartFocus(todo.id, 5)
            },
        )
    }

    if (showPlanEditor) {
        TodayPlanEditorDialog(
            candidates = controller.candidates(),
            selectedIds = plan.taskIds,
            strings = strings,
            onDismiss = { showPlanEditor = false },
            onSave = { ids ->
                controller.setTasks(ids)
                showPlanEditor = false
            },
        )
    }

    TodoEditorDialog(
        show = subtaskParent != null,
        strings = strings,
        today = today,
        onDismiss = { subtaskParent = null },
        onSave = { draft, _ ->
            state.addTodo(draft, parentUid = subtaskParent?.effectiveUid) == WriteResult.Success
        },
        parent = subtaskParent,
        calendars = state.localCalendars,
        defaultCalendarId = state.newRecordCalendarId,
    )

    editing?.let { todo ->
        TodoEditorDialog(
            show = true,
            strings = strings,
            today = today,
            onDismiss = { editing = null },
            onSave = { draft, _ -> state.updateTodo(todo.id, draft) == WriteResult.Success },
            initial = todo,
            calendars = state.localCalendars,
            defaultCalendarId = state.newRecordCalendarId,
        )
    }

    viewing?.let { todo ->
        TodoDetailDialog(
            todo = todo,
            shownDue = state.nextTodoOccurrence(todo),
            today = today,
            calendar = state.appState.calendars.firstOrNull { calendar ->
                calendar.todos.any { it.id == todo.id }
            },
            strings = strings,
            onDismiss = { viewing = null },
            onEdit = {
                viewing = null
                editing = todo
            },
        )
    }

    actionsFor?.let { todo ->
        TodoActionsDialog(
            todo = todo,
            strings = strings,
            onDismiss = { actionsFor = null },
            onCompleteSeries = {
                state.completeTodoSeries(todo.id)
                actionsFor = null
            },
            onStopRecurrence = {
                state.stopTodoRecurrence(todo.id)
                actionsFor = null
            },
        )
    }
}
