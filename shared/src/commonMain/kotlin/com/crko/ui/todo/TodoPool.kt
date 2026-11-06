package com.crko.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Todo
import com.crko.domain.TodoNode
import com.crko.domain.TodoPriority
import com.crko.domain.TodoStatus
import com.crko.i18n.Strings
import com.crko.i18n.todoPriorityLabel
import com.crko.ui.icons.AppIcons
import com.crko.ui.schedule.isOverdue
import com.crko.ui.schedule.priorityColor
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Start offset of one nesting level; subtask cards are inset by this much per depth. */
private val IndentStep = 20.dp

/** Title row of the todo pool, including the search entry the old todo page carried. */
@Composable
internal fun TodoPoolHeader(
    activeCount: Int,
    strings: Strings,
    onOpenSearch: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.todoPool,
            style = MiuixTheme.textStyles.title2,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = strings.items(activeCount),
            style = MiuixTheme.textStyles.footnote1,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onOpenSearch) {
            Icon(
                imageVector = AppIcons.Search,
                contentDescription = strings.search,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * The former todo page's list, embedded in the today page: open root tasks with their subtask
 * forest, then all completed todos behind one folded header.
 */
internal fun LazyListScope.todoPoolItems(
    active: List<TodoNode>,
    completed: List<TodoNode>,
    activeCount: Int,
    completedCount: Int,
    strings: Strings,
    today: LocalDate,
    collapsed: Map<String, Boolean>,
    completedExpanded: Boolean,
    shownDue: (Todo) -> LocalDate?,
    onToggleCompleted: () -> Unit,
    onToggleCollapse: (Todo) -> Unit,
    onToggleDone: (Todo) -> Unit,
    onSubtask: (Todo) -> Unit,
    onDetail: (Todo) -> Unit,
    onActions: (Todo) -> Unit,
    onDelete: (Todo) -> Unit,
) {
    if (activeCount == 0 && completedCount == 0) {
        item(key = "pool-empty") {
            EmptyState(strings.emptyTodos, Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
        }
        return
    }
    items(flatten(active, collapsed), key = { "todo-${it.first.todo.id}" }) { (node, depth) ->
        TodoCard(
            node = node,
            depth = depth,
            collapsed = collapsed[node.todo.id] == true,
            onToggleCollapse = { onToggleCollapse(node.todo) },
            today = today,
            strings = strings,
            shownDue = shownDue(node.todo),
            onToggle = { onToggleDone(node.todo) },
            onAddSubtask = { onSubtask(node.todo) },
            onShowDetail = { onDetail(node.todo) },
            onActions = if (node.todo.repeat != null) {
                { onActions(node.todo) }
            } else {
                null
            },
            onDelete = { onDelete(node.todo) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (completedCount > 0) {
        item(key = "pool-completed-header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onToggleCompleted)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.completed,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = completedCount.toString(),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (completedExpanded) {
            items(flatten(completed, collapsed), key = { "done-${it.first.todo.id}" }) { (node, depth) ->
                TodoCard(
                    node = node,
                    depth = depth,
                    collapsed = collapsed[node.todo.id] == true,
                    onToggleCollapse = { onToggleCollapse(node.todo) },
                    today = today,
                    strings = strings,
                    shownDue = shownDue(node.todo),
                    onToggle = { onToggleDone(node.todo) },
                    onAddSubtask = { onSubtask(node.todo) },
                    onShowDetail = { onDetail(node.todo) },
                    onActions = if (node.todo.repeat != null) {
                        { onActions(node.todo) }
                    } else {
                        null
                    },
                    onDelete = { onDelete(node.todo) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

/** Depth-first rows of the forest, children dropped while their parent is collapsed. */
private fun flatten(nodes: List<TodoNode>, collapsed: Map<String, Boolean>): List<Pair<TodoNode, Int>> {
    val rows = mutableListOf<Pair<TodoNode, Int>>()
    fun walk(node: TodoNode, depth: Int) {
        rows += node to depth
        if (collapsed[node.todo.id] != true) node.children.forEach { walk(it, depth + 1) }
    }
    nodes.forEach { walk(it, 0) }
    return rows
}

@Composable
private fun TodoCard(
    node: TodoNode,
    depth: Int,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    today: LocalDate,
    strings: Strings,
    /** Date the card shows: the next occurrence of a series, or the stored due of a one-off todo. */
    shownDue: LocalDate?,
    onToggle: () -> Unit,
    onAddSubtask: () -> Unit,
    onShowDetail: () -> Unit,
    /** Opens the series actions; null for a one-off todo. */
    onActions: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val todo = node.todo
    Row(
        modifier = modifier
            .padding(start = IndentStep * depth)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            // Tapping anywhere on the row opens the read-only detail card; the check circle still
            // toggles completion.
            .combinedClickable(onClick = onShowDetail, onLongClick = onShowDetail)
            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = todo.done, priority = todo.priorityLevel, onClick = onToggle)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = todo.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (todo.done) FontWeight.Normal else FontWeight.Medium,
                color = if (todo.done) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                textDecoration = if (todo.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (todo.note.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = todo.note,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (shownDue != null || todo.repeat != null) {
                Spacer(Modifier.height(3.dp))
                if (shownDue != null) {
                    val overdue = isOverdue(todo, today)
                    Text(
                        text = dueText(strings, shownDue, today),
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 11.sp,
                        color = if (overdue) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (todo.status == TodoStatus.InProcess || todo.status == TodoStatus.Cancelled) {
                Spacer(Modifier.height(3.dp))
                val label = if (todo.status == TodoStatus.InProcess) {
                    buildString {
                        append(strings.todoStatusInProgress)
                        if (todo.percentComplete in 1..99) append(' ').append(todo.percentComplete).append('%')
                    }
                } else {
                    strings.todoStatusCancelled
                }
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = if (todo.status == TodoStatus.Cancelled) {
                        MiuixTheme.colorScheme.error
                    } else {
                        MiuixTheme.colorScheme.primary
                    },
                )
            }
            if (node.children.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onToggleCollapse)
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = AppIcons.Expand,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(if (collapsed) -90f else 0f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${node.doneSubtaskCount}/${node.subtaskCount}",
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        PriorityTag(todo.priorityLevel, strings)
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onAddSubtask, backgroundColor = Color.Transparent) {
            Icon(
                imageVector = AppIcons.Add,
                contentDescription = strings.subtaskAdd,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(18.dp),
            )
        }
        onActions?.let { openActions ->
            IconButton(onClick = openActions, backgroundColor = Color.Transparent) {
                Icon(
                    imageVector = AppIcons.More,
                    contentDescription = strings.todoActions,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onDelete, backgroundColor = Color.Transparent) {
            Icon(
                imageVector = AppIcons.Delete,
                contentDescription = strings.delete,
                tint = MiuixTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun dueText(strings: Strings, due: LocalDate, today: LocalDate): String {
    val delta = (due.toEpochDays() - today.toEpochDays()).toInt()
    return when (delta) {
        0 -> strings.today
        1 -> strings.tomorrow
        -1 -> strings.yesterday
        else -> strings.dayMonth(due)
    }
}

@Composable
private fun CheckCircle(checked: Boolean, priority: TodoPriority, onClick: () -> Unit) {
    val accent = priorityColor(priority)
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (checked) accent else Color.Transparent)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, accent, CircleShape))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = AppIcons.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun PriorityTag(priority: TodoPriority, strings: Strings) {
    Text(
        text = todoPriorityLabel(strings, priority),
        style = MiuixTheme.textStyles.footnote2,
        fontSize = 11.sp,
        color = priorityColor(priority),
    )
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
