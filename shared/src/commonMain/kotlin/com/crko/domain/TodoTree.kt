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
    forEach { todo -> if (todo.effectiveUid !in byUid) byUid[todo.effectiveUid] = todo }
    // The first resolvable related value that is not the todo itself wins; RELTYPE is not modelled,
    // so every value is treated as the default `PARENT` relation the export writes.
    val parentOf = HashMap<String, Todo>()
    forEach { todo ->
        val self = todo.effectiveUid
        val parent = todo.relatedTo
            .asSequence()
            .mapNotNull(byUid::get)
            .firstOrNull { it.effectiveUid != self }
        if (parent != null) parentOf[self] = parent
    }

    // A todo whose parent chain is acyclic and ends at a root hangs under its parent; a todo inside
    // or below a cycle becomes a root instead of disappearing with the loop.
    fun reachesRoot(uid: String): Boolean {
        val seen = mutableSetOf(uid)
        var current = parentOf[uid]
        while (current != null) {
            val parentUid = current.effectiveUid
            if (!seen.add(parentUid)) return false
            current = parentOf[parentUid]
        }
        return true
    }

    val childrenOf = HashMap<String, MutableList<Todo>>()
    val roots = mutableListOf<Todo>()
    forEach { todo ->
        val uid = todo.effectiveUid
        val parent = parentOf[uid]
        if (parent != null && reachesRoot(uid)) {
            childrenOf.getOrPut(parent.effectiveUid) { mutableListOf() }.add(todo)
        } else {
            roots += todo
        }
    }
    // Each todo has at most one resolved parent, so building downward visits every child once.
    fun build(todo: Todo): TodoNode =
        TodoNode(todo, childrenOf[todo.effectiveUid].orEmpty().map(::build))
    return roots.map(::build)
}
