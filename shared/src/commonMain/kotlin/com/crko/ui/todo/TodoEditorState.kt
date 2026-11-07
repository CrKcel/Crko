package com.crko.ui.todo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.state.todo.TodoDraft
import kotlinx.datetime.LocalDate

/** Which date the shared month picker is editing when the dialog is open. */
internal enum class TodoDateField { Start, Due }

/**
 * Form state for [TodoEditorDialog], shared by the dialog and the advanced-settings page so both
 * edit the same draft and either one can save it. Holds every field the editor edits.
 */
@Stable
internal class TodoEditorState(
    private val editingTarget: Todo?,
    private val parentTarget: Todo?,
    defaultCalendarId: String?,
) {
    var title by mutableStateOf(editingTarget?.title ?: "")
    var note by mutableStateOf(editingTarget?.note ?: "")
    var location by mutableStateOf(editingTarget?.location ?: "")
    var priority by mutableStateOf(editingTarget?.priorityLevel ?: TodoPriority.Normal)
    var start by mutableStateOf(editingTarget?.startDay)
    var due by mutableStateOf(editingTarget?.dueDay)
    var percent by mutableStateOf(editingTarget?.percentComplete ?: 0)
    var categoriesText by mutableStateOf(editingTarget?.categories?.joinToString(", ").orEmpty())
    var calendarId by mutableStateOf(defaultCalendarId)
    var picking by mutableStateOf<TodoDateField?>(null)

    /** Whether the save button is enabled: a todo needs a title. */
    val canSave: Boolean get() = title.isNotBlank()

    /** Whether the calendar picker shows: only a new top-level todo picks where to live. */
    val picksCalendar: Boolean get() = editingTarget == null && parentTarget == null

    /** The draft the save action submits; the categories field splits on both comma forms. */
    fun buildDraft(): TodoDraft = TodoDraft(
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
}

@Composable
internal fun rememberTodoEditorState(
    editingTarget: Todo?,
    parentTarget: Todo?,
    session: Int = 0,
    defaultCalendarId: String? = null,
): TodoEditorState {
    // Re-key by the editing targets and the close/open session so switching between todos or
    // reopening the editor resets every field.
    return remember(editingTarget, parentTarget, session) {
        TodoEditorState(editingTarget, parentTarget, defaultCalendarId)
    }
}
