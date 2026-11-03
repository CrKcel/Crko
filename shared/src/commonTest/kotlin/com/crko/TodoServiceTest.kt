package com.crko

import com.crko.domain.IcsImport
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.domain.effectiveUid
import com.crko.domain.importCalendar
import com.crko.domain.plusDays
import com.crko.state.ImportOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
