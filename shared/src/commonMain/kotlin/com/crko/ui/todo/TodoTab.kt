package com.crko.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.LocalClock
import com.crko.domain.Todo
import com.crko.domain.TodoPriority
import com.crko.i18n.Strings
import com.crko.ui.schedule.isOverdue
import com.crko.ui.schedule.priorityColor
import com.crko.state.AppStateHolder
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.crko.ui.icons.AppIcons

@Composable
fun TodoTab(
    state: AppStateHolder,
    now: LocalClock,
    strings: Strings,
    contentPadding: PaddingValues,
    onAddTodo: () -> Unit,
) {
    val today = state.today
    val active = state.activeTodos()
    val completed = state.completedTodos()
    var completedExpanded by rememberSaveable { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = contentPadding.calculateTopPadding()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.todos,
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = strings.items(active.size),
                style = MiuixTheme.textStyles.footnote1,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (active.isEmpty() && completed.isEmpty()) {
                item(key = "empty") {
                    EmptyState(strings.emptyTodos, Modifier.padding(top = 80.dp))
                }
            }
            items(active, key = { "todo-${it.id}" }) { todo ->
                TodoCard(
                    todo = todo,
                    today = today,
                    strings = strings,
                    onToggle = { state.setTodoDone(todo.id, true) },
                    onDelete = { state.deleteTodo(todo.id) },
                    onOpen = onAddTodo,
                )
            }
            if (completed.isNotEmpty()) {
                item(key = "completed-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { completedExpanded = !completedExpanded }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = strings.completed,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = completed.size.toString(),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (completedExpanded) {
                    items(completed, key = { "done-${it.id}" }) { todo ->
                        TodoCard(
                            todo = todo,
                            today = today,
                            strings = strings,
                            onToggle = { state.setTodoDone(todo.id, false) },
                            onDelete = { state.deleteTodo(todo.id) },
                            onOpen = null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoCard(
    todo: Todo,
    today: LocalDate,
    strings: Strings,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onOpen: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .clickable { onToggle() }
            .padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckCircle(checked = todo.done, priority = todo.priority, onClick = onToggle)
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
            if (todo.due != null) {
                Spacer(Modifier.height(3.dp))
                val due = LocalDate.parse(todo.due)
                val overdue = isOverdue(todo, today)
                Text(
                    text = dueText(strings, due, today),
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = if (overdue) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        PriorityTag(todo.priority, strings)
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
        text = when (priority) {
            TodoPriority.High -> strings.priorityHigh
            TodoPriority.Normal -> strings.priorityNormal
            TodoPriority.Low -> strings.priorityLow
        },
        style = MiuixTheme.textStyles.footnote2,
        fontSize = 11.sp,
        color = priorityColor(priority),
    )
}

@Composable
internal fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
