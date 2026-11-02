package com.crko

import com.crko.domain.RelatedTo
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.effectiveUid
import com.crko.domain.todoForest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TodoForestTest {

    private fun todo(
        id: String,
        relatedTo: List<String> = emptyList(),
        icsUid: String? = null,
        done: Boolean = false,
    ): Todo = Todo(
        id = id,
        title = id,
        relatedTo = relatedTo.map { RelatedTo.parent(it) },
        icsUid = icsUid,
        status = if (done) TodoStatus.Completed else TodoStatus.NeedsAction,
    )

    @Test
    fun effectiveUidPrefersTheImportedUidOverTheLocalOne() {
        assertEquals("ext-1", todo("local", icsUid = "ext-1").effectiveUid)
        assertEquals("local@crko", todo("local").effectiveUid)
        // A blank stored UID falls back to the local id instead of exporting an empty value.
        assertEquals("local@crko", todo("local", icsUid = "  ").effectiveUid)
    }

    @Test
    fun localSubtasksNestUnderTheirParent() {
        val forest = listOf(
            todo("parent"),
            todo("child", relatedTo = listOf("parent@crko")),
        ).todoForest()
        assertEquals(1, forest.size)
        assertEquals("parent", forest.single().todo.id)
        assertEquals("child", forest.single().children.single().todo.id)
    }

    @Test
    fun importedUidsLinkAcrossSources() {
        val forest = listOf(
            todo("child", relatedTo = listOf("ext-parent"), icsUid = "ext-child"),
            todo("parent", icsUid = "ext-parent"),
        ).todoForest()
        assertEquals(1, forest.size)
        assertEquals("parent", forest.single().todo.id)
        assertEquals("child", forest.single().children.single().todo.id)
    }

    @Test
    fun aParentMissingFromTheListLeavesTheTodoAtTheTopLevel() {
        val forest = listOf(todo("orphan", relatedTo = listOf("gone@crko"))).todoForest()
        assertEquals("orphan", forest.single().todo.id)
        assertTrue(forest.single().children.isEmpty())
    }

    @Test
    fun selfReferencesDoNotCreateACycle() {
        val forest = listOf(todo("self", relatedTo = listOf("self@crko"))).todoForest()
        assertEquals("self", forest.single().todo.id)
        assertTrue(forest.single().children.isEmpty())
    }

    @Test
    fun cycleMembersStayVisibleAsRoots() {
        val forest = listOf(
            todo("a", relatedTo = listOf("b@crko")),
            todo("b", relatedTo = listOf("a@crko")),
        ).todoForest()
        // Neither link is followed; both records surface at the top level instead of vanishing.
        assertEquals(setOf("a", "b"), forest.map { it.todo.id }.toSet())
        forest.forEach { assertTrue(it.children.isEmpty()) }
    }

    @Test
    fun todosHangingOffACycleAlsoSurfaceAsRoots() {
        val forest = listOf(
            todo("a", relatedTo = listOf("b@crko")),
            todo("b", relatedTo = listOf("a@crko")),
            todo("c", relatedTo = listOf("b@crko")),
        ).todoForest()
        assertEquals(setOf("a", "b", "c"), forest.map { it.todo.id }.toSet())
    }

    @Test
    fun nestedSubtasksCountAcrossTheWholeSubtree() {
        val forest = listOf(
            todo("root"),
            todo("child", relatedTo = listOf("root@crko")),
            todo("grandchild", relatedTo = listOf("child@crko"), done = true),
        ).todoForest()
        val root = forest.single()
        assertEquals(2, root.subtaskCount)
        assertEquals(1, root.doneSubtaskCount)
    }

    @Test
    fun aTodoIsAttachedOnceEvenWhenSeveralRelatedValuesResolve() {
        val forest = listOf(
            todo("parent"),
            todo("other"),
            todo("child", relatedTo = listOf("missing@crko", "other@crko", "parent@crko")),
        ).todoForest()
        // The first resolvable value wins, so the child hangs under `other` alone.
        assertEquals(setOf("parent", "other"), forest.map { it.todo.id }.toSet())
        assertTrue(forest.first { it.todo.id == "parent" }.children.isEmpty())
        val other = forest.first { it.todo.id == "other" }
        assertEquals("child", other.children.single().todo.id)
    }

    @Test
    fun deepChainsNestWithoutCycles() {
        val forest = listOf(
            todo("top"),
            todo("mid", relatedTo = listOf("top@crko")),
            todo("leaf", relatedTo = listOf("mid@crko")),
        ).todoForest()
        val mid = forest.single().children.single()
        assertEquals("leaf", mid.children.single().todo.id)
    }

    @Test
    fun duplicateUidsKeepTheirOwnParentsAndChildren() {
        val forest = listOf(
            todo("x1", icsUid = "shared"),
            todo("x2", relatedTo = listOf("p@crko"), icsUid = "shared"),
            todo("p"),
            todo("c1", relatedTo = listOf("shared"), icsUid = "ext-c1"),
        ).todoForest()
        // The duplicate without RELATED-TO stays a root, and the child that names "shared" nests
        // under the one record it resolves to instead of appearing under both duplicates.
        assertEquals(listOf("x1", "p"), forest.map { it.todo.id })
        assertEquals("c1", forest[0].children.single().todo.id)
        assertEquals("x2", forest[1].children.single().todo.id)
    }
}
