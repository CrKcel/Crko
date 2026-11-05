package com.crko.ui.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crko.domain.Calendar
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.relatedToEnd
import com.crko.domain.triggerMinutes
import com.crko.i18n.Strings
import com.crko.i18n.alarmLabel
import com.crko.i18n.todoPriorityLabel
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.DetailCardDialog
import com.crko.ui.components.DetailPill
import com.crko.ui.components.DetailRow
import com.crko.ui.components.SurfaceCard
import com.crko.ui.components.accentColor
import com.crko.ui.schedule.isOverdue
import com.crko.ui.schedule.priorityColor
import com.crko.ui.schedule.repeatSummary
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Read-only card summarising a todo; editing still happens in [TodoEditorDialog]. */
@Composable
internal fun TodoDetailDialog(
    todo: Todo,
    shownDue: LocalDate?,
    today: LocalDate,
    calendar: Calendar?,
    strings: Strings,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
) {
    val accent = priorityColor(todo.priorityLevel)
    val statusText = when (todo.status) {
        TodoStatus.Completed -> strings.completed
        TodoStatus.Cancelled -> strings.todoStatusCancelled
        TodoStatus.InProcess -> strings.todoStatusInProgress
        TodoStatus.NeedsAction -> strings.incomplete
    }
    val statusColor = when (todo.status) {
        TodoStatus.Completed -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        TodoStatus.Cancelled -> MiuixTheme.colorScheme.error
        TodoStatus.InProcess -> MiuixTheme.colorScheme.primary
        TodoStatus.NeedsAction -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val overdue = isOverdue(todo, today)
    val dueText = shownDue?.let {
        "${strings.dueDate} · ${relativeDue(strings, it, today)}"
    }
    val reminderText = todo.alarms.firstOrNull()
        ?.let { alarm -> alarm.triggerMinutes?.let { strings.alarmLabel(it, alarm.relatedToEnd) } }
    val rows = buildList {
        add(strings.priority to todoPriorityLabel(strings, todo.priorityLevel))
        todo.startDay?.let { add(strings.startDate to strings.dayMonth(it)) }
        (shownDue ?: todo.dueDay)?.let { add(strings.dueDate to strings.dayMonth(it)) }
        if (todo.percentComplete in 1..100) add(strings.progress to "${todo.percentComplete}%")
        todo.location.takeIf { it.isNotBlank() }?.let { add(strings.location to it) }
        todo.categories.takeIf { it.isNotEmpty() }?.let { add(strings.categories to it.joinToString(", ")) }
        if (todo.repeat != null) {
            add(strings.repeat to repeatSummary(strings, todo.repeat?.frequency))
        }
        reminderText?.let { add(strings.reminder to it) }
    }

    DetailCardDialog(
        title = strings.todoDetail,
        onDismiss = onDismiss,
    ) { requestClose ->
        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (calendar != null) {
                            Spacer(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(calendar.accentColor()),
                            )
                            Spacer(Modifier.size(7.dp))
                            Text(
                                text = calendar.name,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        DetailPill(statusText, statusColor)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = todo.title.ifBlank { strings.untitledTodo },
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                        color = if (todo.done) {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        } else {
                            MiuixTheme.colorScheme.onSurface
                        },
                        textDecoration = if (todo.done) TextDecoration.LineThrough else TextDecoration.None,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dueText?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (overdue) MiuixTheme.colorScheme.error else accent,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SurfaceCard {
                rows.forEachIndexed { index, (label, value) ->
                    if (index > 0) CardRowDivider()
                    DetailRow(label, value)
                }
            }
            if (todo.note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                SurfaceCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = strings.noteField,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = todo.note,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { requestClose(onEdit) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.edit, color = MiuixTheme.colorScheme.onPrimary)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = strings.close,
                onClick = { requestClose(null) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }
}

private fun relativeDue(strings: Strings, due: LocalDate, today: LocalDate): String {
    val delta = (due.toEpochDays() - today.toEpochDays()).toInt()
    return when (delta) {
        0 -> strings.today
        1 -> strings.tomorrow
        -1 -> strings.yesterday
        else -> strings.dayMonth(due)
    }
}
