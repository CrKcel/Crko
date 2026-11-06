package com.crko.ui.today

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import com.crko.domain.Todo
import com.crko.state.AppStateHolder
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

/**
 * A one-second clock for the focus screen and its compact banner. Kept in Compose state so only the
 * countdown text recomposes.
 */
@Composable
internal fun rememberNowMillis(): State<Long> = produceState(Clock.System.now().toEpochMilliseconds()) {
    while (true) {
        delay(1_000.milliseconds)
        value = Clock.System.now().toEpochMilliseconds()
    }
}

/** `mm:ss` remaining; the focus timer never shows more than its own session length. */
internal fun remainingLabel(remainingMillis: Long): String {
    val totalSeconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1000L
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
}

/** True when [todo] lives in a local calendar; coaching fields are hidden everywhere else. */
internal fun isLocalTodo(state: AppStateHolder, todo: Todo): Boolean =
    state.appState.calendars
        .firstOrNull { calendar -> calendar.todos.any { it.id == todo.id } }
        ?.isLocal
        ?: false

/**
 * Completes a todo the way the todo list does: a series completes its occurrence, a one-off task
 * toggles itself. Never completes a whole series by accident.
 */
internal fun completeTodo(state: AppStateHolder, todo: Todo, shownDue: LocalDate?, today: LocalDate) {
    if (todo.repeat != null) {
        state.completeTodoOccurrence(todo.id, shownDue ?: today)
    } else {
        state.setTodoDone(todo.id, true)
    }
}

/**
 * The check circle toggles in both directions: a finished row reopens instead of completing
 * itself again, so the today page keeps the old todo page's ability to restore a task.
 */
internal fun toggleTodo(state: AppStateHolder, todo: Todo, shownDue: LocalDate?, today: LocalDate) {
    if (todo.done) {
        state.setTodoDone(todo.id, false)
    } else {
        completeTodo(state, todo, shownDue, today)
    }
}
