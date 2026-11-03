package com.crko.state.todo

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.IcsTimeValue
import com.crko.domain.RelatedTo
import com.crko.domain.Todo
import com.crko.domain.TodoNode
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.toIcsPriority
import com.crko.domain.todoForest
import com.crko.domain.todoPriorityRank
import com.crko.state.StateAccess
import com.crko.state.WriteResult
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

/**
 * Todo queries and mutations. A repeating todo is shown under its next occurrence, so ordering uses
 * the projected due date; the stored record keeps its original `DUE`.
 */
internal class TodoService(
    private val access: StateAccess,
    private val clock: Clock,
    private val idProvider: () -> String,
    private val today: () -> LocalDate,
) {
    private val state: AppState get() = access.state

    /** Index of the calendar new local records go into; a payload always keeps at least one. */
    private val localCalendarIndex: Int
        get() = state.calendars.indexOfFirst { it.isLocal }.takeIf { it >= 0 } ?: 0

    /** Index of the calendar that owns the record selected by [contains]. */
    private fun calendarIndexContaining(contains: (Calendar) -> Boolean): Int? =
        state.calendars.indexOfFirst(contains).takeIf { it >= 0 }

    /** Replaces one calendar, leaving every other calendar's records untouched. */
    private fun AppState.replacingCalendar(index: Int, transform: (Calendar) -> Calendar): AppState {
        if (index !in calendars.indices) return this
        val updated = calendars.toMutableList()
        updated[index] = transform(updated[index])
        return copy(calendars = updated)
    }

    /** Displayed due date of every todo: the next occurrence for a series, the stored due otherwise. */
    private fun shownDueDates(): Map<String, String?> {
        val today = today()
        return state.todos.associate { todo ->
            todo.id to if (todo.repeat != null) {
                todo.nextOccurrenceDate(today)?.toString()
            } else {
                todo.dueDay?.toString()
            }
        }
    }

    fun activeTodos(): List<Todo> {
        val shownDue = shownDueDates()
        return state.todos
            .filterNot { it.done }
            .sortedWith(
                compareBy<Todo> { shownDue[it.id] == null }
                    .thenBy { shownDue[it.id] ?: "" }
                    .thenBy { todoPriorityRank(it.priority) }
                    .thenByDescending { it.createdAt },
            )
    }

    fun completedTodos(): List<Todo> = state.todos
        .filter { it.done }
        .sortedByDescending { it.createdAt }

    /**
     * The `RELATED-TO` sub-task forest whose roots are the active todos. Subtrees stay whole under
     * each root — a finished subtask keeps its place, struck through, next to its pending siblings.
     */
    fun activeTodoForest(): List<TodoNode> = todoForest(active = true)

    /** The same forest rooted at the completed top-level todos. */
    fun completedTodoForest(): List<TodoNode> = todoForest(active = false)

    /** Roots sorted the way the flat lists sort them; children keep their creation order. */
    private fun todoForest(active: Boolean): List<TodoNode> {
        val shownDue = shownDueDates()
        return state.todos
            .todoForest()
            .filter { if (active) !it.todo.done else it.todo.done }
            .sortedWith(
                if (active) {
                    compareBy<TodoNode> { shownDue[it.todo.id] == null }
                        .thenBy { shownDue[it.todo.id] ?: "" }
                        .thenBy { todoPriorityRank(it.todo.priority) }
                        .thenByDescending { it.todo.createdAt }
                } else {
                    compareByDescending { it.todo.createdAt }
                },
            )
    }

    fun addTodo(title: String, priority: TodoPriority, due: LocalDate?, parentUid: String? = null): WriteResult {
        if (title.isBlank()) return WriteResult.EmptyTitle
        val now = clock.now()
        val todo = Todo(
            id = idProvider(),
            title = title.trim(),
            priority = priority.toIcsPriority(),
            due = due?.let(IcsTimeValue::Date),
            createdAt = now.toEpochMilliseconds(),
            lastModified = now.toString(),
            dtStamp = now.toString(),
            relatedTo = parentUid?.let { listOf(RelatedTo.parent(it)) } ?: emptyList(),
        )
        access.persist(state.replacingCalendar(localCalendarIndex) { it.copy(todos = it.todos + todo) })
        return WriteResult.Success
    }

    fun setTodoDone(id: String, done: Boolean) {
        val index = calendarIndexContaining { calendar -> calendar.todos.any { it.id == id } } ?: return
        val now = clock.now()
        access.persist(
            state.replacingCalendar(index) { calendar ->
                calendar.copy(
                    todos = calendar.todos.map { todo ->
                        if (todo.id == id) {
                            todo.copy(
                                completed = if (done) now else null,
                                status = if (done) TodoStatus.Completed else TodoStatus.NeedsAction,
                                percentComplete = if (done) 100 else 0,
                                lastModified = now.toString(),
                                dtStamp = now.toString(),
                                sequence = todo.sequence + 1,
                            )
                        } else {
                            todo
                        }
                    },
                )
            },
        )
    }

    fun deleteTodo(id: String) {
        val index = calendarIndexContaining { calendar -> calendar.todos.any { it.id == id } } ?: return
        access.persist(state.replacingCalendar(index) { calendar ->
            calendar.copy(todos = calendar.todos.filterNot { it.id == id })
        })
    }
}
