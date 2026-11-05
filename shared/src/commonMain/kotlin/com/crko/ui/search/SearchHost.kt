package com.crko.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.WriteResult
import com.crko.state.search.SearchRecord
import com.crko.ui.more.JournalEditorDialog
import com.crko.ui.more.journalWithEdits
import com.crko.ui.todo.TodoDetailDialog
import com.crko.ui.todo.TodoEditorDialog

/**
 * Owns the search sheet and the record cards it opens. The sheet closes when a result is tapped: a
 * todo shows its read-only detail card (editing is one tap further) and a journal opens its editor.
 * The tab that launched the search stays where it was. [onOpenEvent] hands a tapped event to the app
 * shell, which shows the detail card with the series-occurrence information it holds.
 */
@Composable
internal fun SearchHost(
    open: Boolean,
    state: AppStateHolder,
    strings: Strings,
    onDismiss: () -> Unit,
    onOpenEvent: (Event) -> Unit,
) {
    var viewingTodo by remember { mutableStateOf<Todo?>(null) }
    var editingTodo by remember { mutableStateOf<Todo?>(null) }
    var editingJournal by remember { mutableStateOf<Journal?>(null) }

    SearchSheet(
        show = open,
        state = state,
        strings = strings,
        onDismiss = onDismiss,
        onOpenHit = { hit ->
            when (val record = hit.record) {
                is SearchRecord.EventRecord -> {
                    onDismiss()
                    onOpenEvent(record.event)
                }
                is SearchRecord.TodoRecord -> {
                    onDismiss()
                    viewingTodo = record.todo
                }
                is SearchRecord.JournalRecord -> {
                    onDismiss()
                    editingJournal = record.journal
                }
            }
        },
    )

    viewingTodo?.let { todo ->
        TodoDetailDialog(
            todo = todo,
            shownDue = state.nextTodoOccurrence(todo),
            today = state.today,
            calendar = state.appState.calendars.firstOrNull { calendar ->
                calendar.todos.any { it.id == todo.id }
            },
            strings = strings,
            onDismiss = { viewingTodo = null },
            onEdit = {
                viewingTodo = null
                editingTodo = todo
            },
        )
    }

    editingTodo?.let { todo ->
        TodoEditorDialog(
            show = true,
            strings = strings,
            today = state.today,
            onDismiss = { editingTodo = null },
            onSave = { draft, _ -> state.updateTodo(todo.id, draft) == WriteResult.Success },
            initial = todo,
            calendars = state.localCalendars,
            defaultCalendarId = state.newRecordCalendarId,
        )
    }

    editingJournal?.let { journal ->
        JournalEditorDialog(
            show = true,
            strings = strings,
            today = state.today,
            journal = journal,
            onDismiss = { editingJournal = null },
            onSave = { title, note, date, _ ->
                state.updateJournal(journalWithEdits(journal, title, note, date)) == WriteResult.Success
            },
            onDelete = {
                state.deleteJournal(journal.id)
                editingJournal = null
            },
        )
    }
}
