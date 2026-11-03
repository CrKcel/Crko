package com.crko.domain

/**
 * `RELATED-TO` sub-task hierarchy of a `VTODO` set (RFC 5545 §3.8.8.3). A todo links to its parent
 * by naming the parent's UID; the forest groups children under the parent they name.
 */

/** The `UID` a todo is exported with and referenced by in `RELATED-TO`. */
val Todo.effectiveUid: String
    get() = icsUid?.takeIf { it.isNotBlank() } ?: "$id@crko"

/** A todo with the subtasks whose `RELATED-TO` names it. */
data class TodoNode(
    val todo: Todo,
    val children: List<TodoNode>,
) {
    /** Subtasks in the whole subtree, not counting [todo] itself. */
    val subtaskCount: Int get() = children.size + children.sumOf { it.subtaskCount }

    /** Completed subtasks in the whole subtree, for a parent's progress badge. */
    val doneSubtaskCount: Int get() =
        children.count { it.todo.done } + children.sumOf { it.doneSubtaskCount }
}

/**
 * Builds the sub-task forest. Todos whose parent UID resolves within this list become children;
 * the rest (no parent, a parent that is not in the list, or a parent inside a `RELATED-TO` cycle)
 * are roots, so no record can ever vanish from the list because of a broken or looping link.
 */
fun List<Todo>.todoForest(): List<TodoNode> {
    val byUid = LinkedHashMap<String, Todo>()
    forEach { todo ->
        val current = byUid[todo.effectiveUid]
        // An occurrence instance shares its master's UID. The master owns the hierarchy, so a
        // finished instance never becomes the record a live subtask hangs under — that would move
        // the subtask into the completed projection along with the instance.
        if (current == null || (current.isOccurrence && !todo.isOccurrence)) {
            byUid[todo.effectiveUid] = todo
        }
    }
    // The first resolvable `PARENT` relation that is not the todo itself wins; `CHILD` and
    // `SIBLING` links are kept on the record for export but do not build the hierarchy. Links are
    // kept per record (by id), not per UID: two records may share a UID, and neither inherits the
    // other's parent or children.
    val parentOf = HashMap<String, Todo>()
    forEach { todo ->
        val self = todo.effectiveUid
        val parent = todo.relatedTo
            .asSequence()
            .filter { it.isParent }
            .mapNotNull { byUid[it.uid] }
            .firstOrNull { it.effectiveUid != self }
        if (parent != null) parentOf[todo.id] = parent
    }

    // A todo whose parent chain is acyclic and ends at a root hangs under its parent; a todo inside
    // or below a cycle becomes a root instead of disappearing with the loop.
    fun reachesRoot(todo: Todo): Boolean {
        val seen = mutableSetOf(todo.id)
        var current = parentOf[todo.id]
        while (current != null) {
            if (!seen.add(current.id)) return false
            current = parentOf[current.id]
        }
        return true
    }

    val childrenOf = HashMap<String, MutableList<Todo>>()
    val roots = mutableListOf<Todo>()
    forEach { todo ->
        val parent = parentOf[todo.id]
        if (parent != null && reachesRoot(todo)) {
            childrenOf.getOrPut(parent.id) { mutableListOf() }.add(todo)
        } else {
            roots += todo
        }
    }
    // Each todo has at most one resolved parent, so building downward visits every child once.
    fun build(todo: Todo): TodoNode =
        TodoNode(todo, childrenOf[todo.id].orEmpty().map(::build))
    return roots.map(::build)
}
