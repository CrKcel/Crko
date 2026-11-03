package com.crko

import com.crko.domain.AppState
import com.crko.domain.IcsImport
import com.crko.domain.IcsTimeValue
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.effectiveUid
import com.crko.domain.importCalendar
import com.crko.domain.plusDays
import com.crko.domain.wallTime
import com.crko.i18n.Strings
import com.crko.state.AppStateHolder
import com.crko.state.ImportOutcome
import com.crko.state.ReminderScheduler
import com.crko.state.WriteResult
import com.crko.state.todo.TodoDraft
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TodoServiceTest {

    @Test
    fun activeTodosSortDueDatesFirstThenPriority() {
        val f = Fixture()
        f.holder.addTodo("No due low", TodoPriority.Low, null)
        f.holder.addTodo("Later", TodoPriority.High, f.holder.today.plusDays(2))
        f.holder.addTodo("Due high", TodoPriority.High, f.holder.today)
        f.holder.addTodo("Due normal", TodoPriority.Normal, f.holder.today)
        assertEquals(
            listOf("Due high", "Due normal", "Later", "No due low"),
            f.holder.activeTodos().map { it.title },
        )
    }

    @Test
    fun completingATodoMovesItOutOfTheActiveList() {
        val f = Fixture()
        f.holder.addTodo("Ship", TodoPriority.Normal, null)
        val id = f.holder.activeTodos().single().id
        f.holder.setTodoDone(id, true)
        assertTrue(f.holder.activeTodos().isEmpty())
        val completed = f.holder.completedTodos().single()
        assertEquals("Ship", completed.title)
        assertEquals(TodoStatus.Completed, completed.status)
        assertEquals(100, completed.percentComplete)
        assertTrue(completed.completed != null)
        f.holder.setTodoDone(id, false)
        val reopened = f.holder.activeTodos().single()
        assertEquals(TodoStatus.NeedsAction, reopened.status)
        assertEquals(0, reopened.percentComplete)
        assertNull(reopened.completed)
    }

    @Test
    fun activeTodosOrderNormalBeforeLowWithTheSameDueDate() {
        val f = Fixture()
        f.holder.addTodo("Low", TodoPriority.Low, f.holder.today)
        f.holder.addTodo("Normal", TodoPriority.Normal, f.holder.today)
        assertEquals(listOf("Normal", "Low"), f.holder.activeTodos().map { it.title })
    }

    @Test
    fun subtasksNestUnderTheirParentInTheForest() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parent.effectiveUid)
        f.holder.addTodo("Top-level", TodoPriority.Normal, null)

        val roots = f.holder.activeTodoForest()
        // The subtask is nested, not a second root, and a done parent moves its subtree along.
        assertEquals(listOf("Parent", "Top-level"), roots.map { it.todo.title })
        val parent1 = roots.first()
        assertEquals("Child", parent1.children.single().todo.title)
        assertEquals(1, parent1.subtaskCount)
    }

    @Test
    fun aCompletedSubtaskStaysNestedWithItsProgressCounted() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parent.effectiveUid)
        val child = f.holder.activeTodos().first { it.title == "Child" }
        f.holder.setTodoDone(child.id, true)

        val root = f.holder.activeTodoForest().single()
        assertEquals(1, root.subtaskCount)
        assertEquals(1, root.doneSubtaskCount)
        assertTrue(root.children.single().todo.done)
    }

    @Test
    fun deletingATodoLeavesItsSubtasksAtTheTopLevel() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parent.effectiveUid)
        f.holder.deleteTodo(parent.id)

        val forest = f.holder.activeTodoForest()
        assertEquals(listOf("Child"), forest.map { it.todo.title })
    }

    @Test
    fun importedTodosGetIdsAndAreDeduplicatedByUid() {
        val f = Fixture()
        val todo = Todo("", "Imported", icsUid = "todo-uid")
        assertEquals(ImportOutcome(todos = 1), f.holder.importCalendar(IcsImport(todos = listOf(todo))))
        assertEquals(ImportOutcome(), f.holder.importCalendar(IcsImport(todos = listOf(todo))))
        val stored = f.holder.appState.todos.single()
        assertTrue(stored.id.isNotBlank())
        assertEquals("todo-uid", stored.icsUid)
    }

    @Test
    fun completedTodayUsesTheCompletionDate() {
        val f = Fixture()
        f.holder.addTodo("Due tomorrow", TodoPriority.Normal, f.holder.today.plusDays(1))
        f.holder.setTodoDone(f.holder.appState.todos.single().id, true)
        assertEquals(1, f.holder.completedToday())
    }

    @Test
    fun creatingATodoFromTheDraftStoresEveryEditorField() {
        val f = Fixture()
        val result = f.holder.addTodo(
            TodoDraft(
                title = "Prepare",
                note = "Agenda",
                location = "Room 3",
                start = f.holder.today,
                due = f.holder.today.plusDays(1),
                priority = TodoPriority.High,
                percentComplete = 30,
                categories = listOf("work"),
            ),
        )
        assertEquals(WriteResult.Success, result)
        val todo = f.holder.appState.todos.single()
        assertEquals("Prepare", todo.title)
        assertEquals("Agenda", todo.note)
        assertEquals("Room 3", todo.location)
        assertEquals(f.holder.today, todo.startDay)
        assertEquals(f.holder.today.plusDays(1), todo.dueDay)
        assertEquals(TodoPriority.High, todo.priorityLevel)
        assertEquals(30, todo.percentComplete)
        assertEquals(TodoStatus.InProcess, todo.status)
        assertEquals(listOf("work"), todo.categories)
    }

    @Test
    fun editingATodoUpdatesEveryEditableFieldAndKeepsItsIdentity() {
        val f = Fixture()
        f.holder.addTodo("Old", TodoPriority.Low, null)
        val original = f.holder.activeTodos().single()

        val result = f.holder.updateTodo(
            original.id,
            TodoDraft(
                title = "  New  ",
                note = "Detail",
                location = "Office",
                start = f.holder.today,
                due = f.holder.today.plusDays(3),
                priority = TodoPriority.High,
                percentComplete = 40,
                categories = listOf("work", "home"),
            ),
        )

        assertEquals(WriteResult.Success, result)
        val edited = f.holder.appState.todos.single()
        assertEquals("New", edited.title)
        assertEquals("Detail", edited.note)
        assertEquals("Office", edited.location)
        assertEquals(f.holder.today, edited.startDay)
        assertEquals(f.holder.today.plusDays(3), edited.dueDay)
        assertEquals(TodoPriority.High, edited.priorityLevel)
        assertEquals(40, edited.percentComplete)
        assertEquals(TodoStatus.InProcess, edited.status)
        assertEquals(listOf("work", "home"), edited.categories)
        assertEquals(original.id, edited.id)
        assertEquals(original.icsUid, edited.icsUid)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(original.sequence + 1, edited.sequence)
    }

    @Test
    fun anEmptyDraftClearsTheOptionalFields() {
        val f = Fixture()
        f.holder.addTodo("Keep", TodoPriority.Normal, f.holder.today)
        val original = f.holder.appState.todos.single()
        f.holder.updateTodo(original.id, TodoDraft(title = "Keep"))

        val edited = f.holder.appState.todos.single()
        assertEquals("", edited.note)
        assertEquals("", edited.location)
        assertNull(edited.start)
        assertNull(edited.due)
        assertEquals(emptyList(), edited.categories)
    }

    @Test
    fun percentCompleteMapsStatusAndCompletionStaysOnTheDonePath() {
        val f = Fixture()
        f.holder.addTodo("Task", TodoPriority.Normal, null)
        val id = f.holder.appState.todos.single().id

        f.holder.updateTodo(id, TodoDraft(title = "Task", percentComplete = 60))
        assertEquals(TodoStatus.InProcess, f.holder.appState.todos.single().status)

        f.holder.updateTodo(id, TodoDraft(title = "Task", percentComplete = 0))
        assertEquals(TodoStatus.NeedsAction, f.holder.appState.todos.single().status)

        f.holder.setTodoDone(id, true)
        f.holder.updateTodo(id, TodoDraft(title = "Task", percentComplete = 30))
        val completed = f.holder.appState.todos.single()
        assertEquals(TodoStatus.Completed, completed.status)
        assertTrue(completed.done)
    }

    @Test
    fun editingARepeatingTodoKeepsTheSeriesRule() {
        val f = Fixture()
        val rule = RepeatRule(RepeatFrequency.Daily, count = 3)
        f.holder.importCalendar(
            IcsImport(
                todos = listOf(Todo("", "Series", due = IcsTimeValue.Date(f.holder.today), repeat = rule)),
            ),
        )
        val original = f.holder.appState.todos.single()

        f.holder.updateTodo(
            original.id,
            TodoDraft(title = "Series edited", due = f.holder.today.plusDays(1)),
        )

        val edited = f.holder.appState.todos.single()
        assertEquals("Series edited", edited.title)
        assertEquals(rule, edited.repeat)
        assertEquals(original.icsUid, edited.icsUid)
    }

    @Test
    fun editingASubtaskKeepsItsParentLink() {
        val f = Fixture()
        f.holder.addTodo("Parent", TodoPriority.Normal, null)
        val parent = f.holder.activeTodos().single()
        f.holder.addTodo("Child", TodoPriority.Normal, null, parent.effectiveUid)
        val child = f.holder.activeTodos().first { it.title == "Child" }

        f.holder.updateTodo(child.id, TodoDraft(title = "Child edited"))

        val edited = f.holder.appState.todos.first { it.id == child.id }
        assertEquals(child.relatedTo, edited.relatedTo)
        val root = f.holder.activeTodoForest().single()
        assertEquals("Parent", root.todo.title)
        assertEquals("Child edited", root.children.single().todo.title)
    }

    @Test
    fun editingADateThatDidNotMoveKeepsTheImportedTimeOfDay() {
        val f = Fixture()
        val due = IcsTimeValue.Floating(LocalDateTime(f.holder.today, LocalTime(9, 30)))
        f.holder.importCalendar(IcsImport(todos = listOf(Todo("", "Timed", due = due))))
        val original = f.holder.appState.todos.single()

        f.holder.updateTodo(original.id, TodoDraft(title = "Timed", due = f.holder.today))

        val edited = f.holder.appState.todos.single()
        assertIs<IcsTimeValue.Floating>(edited.due)
        assertEquals(LocalTime(9, 30), edited.due.wallTime)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun editingATodoReschedulesReminders() = runTest {
        val scheduler = CountingReminderScheduler()
        val holder = AppStateHolder(
            scope = this,
            initialSystemLocale = "en-US",
            clock = FakeClock(1_793_101_200_000),
            idProvider = { "id-1" },
            storage = MemoryStorage(),
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
            reminders = scheduler,
        )
        holder.addTodo("Task", TodoPriority.Normal, null)
        advanceUntilIdle()
        val afterAdd = scheduler.calls

        holder.updateTodo(holder.appState.todos.single().id, TodoDraft(title = "Edited", percentComplete = 50))
        advanceUntilIdle()

        assertTrue(scheduler.calls > afterAdd)
    }
}

private class CountingReminderScheduler : ReminderScheduler {
    var calls = 0

    override suspend fun reschedule(state: AppState, strings: Strings) {
        calls++
    }

    override suspend fun ensurePermission(): Boolean = true
}
