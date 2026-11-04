package com.crko.state.todo

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.IcsTimeValue
import com.crko.domain.RelatedTo
import com.crko.domain.Todo
import com.crko.domain.TodoNode
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.displayDate
import com.crko.domain.effectiveUid
import com.crko.domain.nextOccurrenceDate
import com.crko.domain.occursOn
import com.crko.domain.recurrenceSlotOn
import com.crko.domain.targetCalendarIndex
import com.crko.domain.toIcsPriority
import com.crko.domain.todoForest
import com.crko.domain.todoPriorityRank
import com.crko.state.DeleteOutcome
import com.crko.state.DeleteUndo
import com.crko.state.DeleteUndoStore
import com.crko.state.PlacedRecord
import com.crko.state.StateAccess
import com.crko.state.WriteResult
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
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
    private val undo: DeleteUndoStore,
) {
    private val state: AppState get() = access.state

    /** Index of the calendar new local records go into; a payload always keeps at least one. */
    private val targetCalendarIndex: Int
        get() = state.targetCalendarIndex(access.defaultCalendarId)

    /** Index of the calendar that owns the record selected by [contains]. */
    private fun calendarIndexContaining(contains: (Calendar) -> Boolean): Int? =
        state.calendars.indexOfFirst(contains).takeIf { it >= 0 }

    /** A calendar explicitly chosen in the editor; null when it no longer exists. */
    private fun requestedCalendarIndex(id: String?): Int? =
        id?.let { calendarId -> state.calendars.indexOfFirst { it.id == calendarId }.takeIf { it >= 0 } }

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
        return state.visibleCalendars.flatMap { calendar ->
            // Completion instances share their master's UID; the projected next occurrence skips
            // their slots, so the lookup is scoped to the calendar that holds the master.
            val instances = calendar.todos.filter { it.isOccurrence }
            calendar.todos.map { todo ->
                todo.id to if (todo.repeat != null) {
                    todo.nextOccurrenceDate(today, instances)?.toString()
                } else {
                    todo.dueDay?.toString()
                }
            }
        }.toMap()
    }

    /** Display date the todo lists show for [todo]; null for an undated one-off todo. */
    fun nextOccurrence(todo: Todo): LocalDate? =
        if (todo.repeat != null) todo.nextOccurrenceDate(today(), occurrencesOf(todo)) else todo.dueDay

    /** Occurrence instances stored next to [master] in the same calendar. */
    private fun occurrencesOf(master: Todo): List<Todo> =
        state.calendars
            .firstOrNull { calendar -> calendar.todos.any { it.id == master.id } }
            ?.todos
            .orEmpty()
            .filter { it.isOccurrence }

    fun activeTodos(): List<Todo> {
        val shownDue = shownDueDates()
        return state.visibleTodos
            .filterNot { it.done }
            .sortedWith(
                compareBy<Todo> { shownDue[it.id] == null }
                    .thenBy { shownDue[it.id] ?: "" }
                    .thenBy { todoPriorityRank(it.priority) }
                    .thenByDescending { it.createdAt },
            )
    }

    fun completedTodos(): List<Todo> = state.visibleTodos
        .filter { it.done }
        .sortedByDescending { it.createdAt }

    /**
     * The `RELATED-TO` sub-task forest whose roots are the active todos. Subtrees stay whole under
     * each root — a finished subtask keeps its place, struck through, next to its pending siblings.
     */
    fun activeTodoForest(): List<TodoNode> = todoForests().active

    /** The same forest rooted at the completed top-level todos. */
    fun completedTodoForest(): List<TodoNode> = todoForests().completed

    /**
     * Both forests from one scan. They share the projected due dates and the tree, and the todo tab
     * asks for both on every frame, so caching the pair per state (and day) avoids building it twice.
     */
    private fun todoForests(): TodoForests {
        val current = state
        val currentToday = today()
        forestCache?.let { (key, forests) ->
            if (key.first === current && key.second == currentToday) return forests
        }
        val shownDue = shownDueDates()
        val forest = current.visibleTodos.todoForest()
        val active = forest.filterNot { it.todo.done }
            .sortedWith(
                compareBy<TodoNode> { shownDue[it.todo.id] == null }
                    .thenBy { shownDue[it.todo.id] ?: "" }
                    .thenBy { todoPriorityRank(it.todo.priority) }
                    .thenByDescending { it.todo.createdAt },
            )
        val completed = forest.filter { it.todo.done }.sortedByDescending { it.todo.createdAt }
        return TodoForests(active, completed).also { forestCache = (current to currentToday) to it }
    }

    private var forestCache: Pair<Pair<AppState, LocalDate>, TodoForests>? = null

    private data class TodoForests(val active: List<TodoNode>, val completed: List<TodoNode>)

    fun addTodo(
        title: String,
        priority: TodoPriority,
        due: LocalDate?,
        parentUid: String? = null,
        calendarId: String? = null,
    ): WriteResult = addTodo(
        TodoDraft(title = title, due = due, priority = priority),
        parentUid = parentUid,
        calendarId = calendarId,
    )

    fun addTodo(
        draft: TodoDraft,
        parentUid: String? = null,
        calendarId: String? = null,
    ): WriteResult {
        if (draft.title.isBlank()) return WriteResult.EmptyTitle
        val now = clock.now()
        val percent = draft.percentComplete.coerceIn(0, 100)
        val todo = Todo(
            id = idProvider(),
            title = draft.title.trim(),
            start = draft.start?.let(IcsTimeValue::Date),
            due = draft.due?.let(IcsTimeValue::Date),
            priority = draft.priority.toIcsPriority(),
            createdAt = now.toEpochMilliseconds(),
            note = draft.note,
            location = draft.location.trim(),
            categories = draft.categories,
            lastModified = now.toString(),
            dtStamp = now.toString(),
            status = statusForPercent(percent, current = TodoStatus.NeedsAction, done = false),
            percentComplete = percent,
            relatedTo = parentUid?.let { listOf(RelatedTo.parent(it)) } ?: emptyList(),
        )
        // A subtask stays next to its parent; a top-level todo goes where the editor points.
        val parentCalendarIndex = parentUid?.let { uid ->
            state.calendars.indexOfFirst { calendar -> calendar.todos.any { it.effectiveUid == uid } }
                .takeIf { it >= 0 }
        }
        access.persist(
            state.replacingCalendar(
                parentCalendarIndex ?: requestedCalendarIndex(calendarId) ?: targetCalendarIndex,
            ) {
                it.copy(todos = it.todos + todo)
            },
        )
        return WriteResult.Success
    }

    /**
     * Applies the editor draft to [id]. Only the draft's own fields change: the `UID`, creation time,
     * `RELATED-TO` links, attachments and recurrence set stay as stored. The revision counter and
     * modification timestamps advance so a later export/sync carries the edit.
     */
    fun updateTodo(id: String, draft: TodoDraft): WriteResult {
        if (draft.title.isBlank()) return WriteResult.EmptyTitle
        val index = calendarIndexContaining { calendar -> calendar.todos.any { it.id == id } }
            ?: return WriteResult.Success
        val now = clock.now()
        val percent = draft.percentComplete.coerceIn(0, 100)
        access.persist(
            state.replacingCalendar(index) { calendar ->
                calendar.copy(
                    todos = calendar.todos.map { todo ->
                        if (todo.id != id) {
                            todo
                        } else {
                            todo.copy(
                                title = draft.title.trim(),
                                note = draft.note,
                                location = draft.location.trim(),
                                start = editedDateValue(todo.start, draft.start),
                                due = editedDateValue(todo.due, draft.due),
                                priority = draft.priority.toIcsPriority(),
                                percentComplete = percent,
                                status = statusForPercent(percent, current = todo.status, done = todo.done),
                                categories = draft.categories,
                                lastModified = now.toString(),
                                dtStamp = now.toString(),
                                sequence = todo.sequence + 1,
                            )
                        }
                    },
                )
            },
        )
        return WriteResult.Success
    }

    /**
     * Editor date choice as a stored value. Picking the day the record already shows keeps the
     * imported value (its time of day and `TZID`); any other day becomes a date-only value, and
     * clearing the field removes it.
     */
    private fun editedDateValue(original: IcsTimeValue?, picked: LocalDate?): IcsTimeValue? = when {
        picked == null -> null
        original != null && original.displayDate(TimeZone.currentSystemDefault()) == picked -> original
        else -> IcsTimeValue.Date(picked)
    }

    /**
     * A percent between 1 and 99 means the work has started; zero on a pending todo returns it to
     * `NEEDS-ACTION`. Completion and cancellation keep their status and go through `setTodoDone`.
     */
    private fun statusForPercent(percent: Int, current: TodoStatus, done: Boolean): TodoStatus = when {
        done -> current
        percent in 1..99 -> TodoStatus.InProcess
        percent == 0 -> TodoStatus.NeedsAction
        else -> current
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

    /**
     * Removes [id] and keeps what it removed as the undo target. A repeating master also takes its
     * completed occurrence instances with it: they share its `UID` and would otherwise linger
     * without a series projecting them. Every other record, including a sub-task, is removed alone.
     */
    fun deleteTodo(id: String): DeleteOutcome {
        val index = calendarIndexContaining { calendar -> calendar.todos.any { it.id == id } }
            ?: return DeleteOutcome.Nothing
        val calendar = state.calendars[index]
        val master = calendar.todos.first { it.id == id }
        val seriesUid = master.effectiveUid
        val hasInstances = !master.isOccurrence &&
            calendar.todos.any { it.isOccurrence && it.id != id && it.effectiveUid == seriesUid }
        val removed = calendar.todos.withIndex()
            .filter { (_, todo) ->
                todo.id == id || (hasInstances && todo.isOccurrence && todo.effectiveUid == seriesUid)
            }
            .map { PlacedRecord(it.value, it.index) }
        val ids = removed.mapTo(mutableSetOf()) { it.record.id }
        undo.record(DeleteUndo.Todos(calendar.id, removed))
        access.persist(
            state.replacingCalendar(index) { current ->
                current.copy(todos = current.todos.filterNot { it.id in ids })
            },
        )
        return DeleteOutcome.Removed
    }

    // ----- recurring completion -----

    /**
     * Completes the occurrence of the series [id] that is shown on [date]. The stored record is a
     * `VTODO` sharing the master's `UID` and carrying `RECURRENCE-ID` for the slot; the master keeps
     * its rule and its own fields, so the series projects its next occurrence instead of vanishing.
     * Completing the same slot twice updates that instance rather than adding a second one.
     */
    fun completeOccurrence(id: String, date: LocalDate): WriteResult {
        val index = calendarIndexContaining { calendar -> calendar.todos.any { it.id == id } }
            ?: return WriteResult.Success
        val calendar = state.calendars[index]
        val master = calendar.todos.first { it.id == id }
        // Without a rule the record completes itself; only a series needs an occurrence instance.
        if (master.repeat == null) {
            if (!master.done) setTodoDone(id, true)
            return WriteResult.Success
        }
        if (master.done) return WriteResult.Success
        // A date the rule never generates (an EXDATE, a skipped weekday) has no slot to complete.
        if (!master.occursOn(date)) return WriteResult.Success
        val slot = master.recurrenceSlotOn(date) ?: return WriteResult.Success
        val existing = calendar.todos.firstOrNull { todo ->
            todo.isOccurrence && todo.id != master.id &&
                todo.effectiveUid == master.effectiveUid && todo.recurrenceId == slot
        }
        val now = clock.now()
        val todos = if (existing != null) {
            calendar.todos.map { if (it.id == existing.id) existing.occurrenceCompleted(now) else it }
        } else {
            calendar.todos + master.completedOccurrence(slot, idProvider(), now)
        }
        access.persist(state.replacingCalendar(index) { it.copy(todos = todos) })
        return WriteResult.Success
    }

    /** Completes the whole series: the master turns done, so no further occurrence is projected. */
    fun completeSeries(id: String): WriteResult {
        val master = state.calendars
            .firstOrNull { calendar -> calendar.todos.any { it.id == id } }
            ?.todos
            ?.first { it.id == id }
            ?: return WriteResult.Success
        if (!master.done) setTodoDone(id, true)
        return WriteResult.Success
    }

    /**
     * Clears the recurrence set of [id] but keeps the record itself, so a series that has run its
     * course becomes an ordinary todo. Completed occurrence instances stay as history.
     */
    fun stopRecurrence(id: String): WriteResult {
        val index = calendarIndexContaining { calendar -> calendar.todos.any { it.id == id } }
            ?: return WriteResult.Success
        val master = state.calendars[index].todos.first { it.id == id }
        if (master.repeat == null && master.rDates.isEmpty() && master.excludedDates.isEmpty()) {
            return WriteResult.Success
        }
        val now = clock.now()
        access.persist(state.replacingCalendar(index) { calendar ->
            calendar.copy(
                todos = calendar.todos.map { todo ->
                    if (todo.id != id) {
                        todo
                    } else {
                        todo.copy(
                            repeat = null,
                            rDates = emptyList(),
                            excludedDates = emptyList(),
                            lastModified = now.toString(),
                            dtStamp = now.toString(),
                            sequence = todo.sequence + 1,
                        )
                    }
                },
            )
        })
        return WriteResult.Success
    }
}
