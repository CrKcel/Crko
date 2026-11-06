package com.crko.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Todo
import com.crko.domain.plusDays
import com.crko.i18n.Strings
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The three things, kept in plan order. A completed row stays visible and struck through; the
 * next-step card skips it instead of removing the day's record of what got done.
 */
@Composable
internal fun TodayPlanSection(
    tasks: List<Todo>,
    today: LocalDate,
    strings: Strings,
    shownDue: (Todo) -> LocalDate?,
    firstStep: (Todo) -> String,
    onToggle: (Todo) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = strings.dayPlan,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = strings.edit,
            style = MiuixTheme.textStyles.footnote2,
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onEdit),
        )
    }
    SurfaceCard {
        if (tasks.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.dayPlanEmpty,
                    style = MiuixTheme.textStyles.body1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = strings.dayPlanPick,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        } else {
            tasks.forEachIndexed { index, todo ->
                TodayPlanRow(
                    todo = todo,
                    shownDue = shownDue(todo),
                    firstStep = firstStep(todo),
                    today = today,
                    strings = strings,
                    onToggle = { onToggle(todo) },
                )
                if (index != tasks.lastIndex) CardRowDivider()
            }
        }
    }
}

@Composable
private fun TodayPlanRow(
    todo: Todo,
    shownDue: LocalDate?,
    firstStep: String,
    today: LocalDate,
    strings: Strings,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (todo.done) MiuixTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent)
                .then(
                    if (todo.done) Modifier else Modifier.border(
                        1.5.dp,
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.45f),
                        CircleShape,
                    ),
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (todo.done) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = todo.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                textDecoration = if (todo.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (todo.done) {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
            )
            val step = firstStep
            if (!todo.done && step.isNotBlank()) {
                Text(
                    text = step,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!todo.done && shownDue != null) {
                val label = when {
                    shownDue == today -> strings.today
                    shownDue == today.plusDays(1) -> strings.tomorrow
                    else -> strings.dayMonth(shownDue)
                }
                Text(
                    text = label,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}
