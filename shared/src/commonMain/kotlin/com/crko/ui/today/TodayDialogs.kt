package com.crko.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Todo
import com.crko.i18n.Strings
import com.crko.ui.icons.AppIcons
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.time.Duration.Companion.seconds

/** Second tap of the launch gesture: confirming here starts the 3-2-1 countdown. */
@Composable
internal fun StartFocusDialog(
    task: Todo,
    firstStep: String,
    strings: Strings,
    onDismiss: () -> Unit,
    onStart: (minutes: Int) -> Unit,
) {
    var counting by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(3) }

    LaunchedEffect(counting) {
        if (!counting) return@LaunchedEffect
        while (countdown > 0) {
            delay(1.seconds)
            countdown -= 1
        }
        onStart(5)
    }

    WindowDialog(show = true, onDismissRequest = { if (!counting) onDismiss() }, title = strings.nextStep) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            if (counting) {
                Text(
                    text = countdown.toString(),
                    style = MiuixTheme.textStyles.title1,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                Text(
                    text = task.title,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                val step = firstStep
                if (step.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = step,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { counting = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(strings.startFiveMinutes)
                }
                TextButton(
                    text = strings.cancel,
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(),
                )
            }
        }
    }
}

/** The four fixed branches of "我动不了"; every one of them lands on a concrete action. */
@Composable
internal fun CantMoveDialog(
    strings: Strings,
    hasOther: Boolean,
    onDismiss: () -> Unit,
    onSmaller: () -> Unit,
    onTwoMinutes: () -> Unit,
    onOther: () -> Unit,
    onStop: () -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.cantMoveTitle) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            BranchRow(
                title = strings.cantMoveSmaller,
                hint = strings.cantMoveSmallerHint,
                icon = null,
                onClick = onSmaller,
            )
            BranchRow(
                title = strings.cantMoveTwoMinutes,
                hint = strings.cantMoveTwoMinutesHint,
                icon = null,
                onClick = onTwoMinutes,
            )
            BranchRow(
                title = strings.cantMoveOther,
                hint = strings.cantMoveOtherHint,
                icon = null,
                enabled = hasOther,
                onClick = onOther,
            )
            BranchRow(
                title = strings.cantMoveStop,
                hint = strings.stoppedForTodayHint,
                icon = null,
                onClick = onStop,
            )
            TextButton(
                text = strings.cancel,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }
}

@Composable
private fun BranchRow(
    title: String,
    hint: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        onClick = { if (enabled) onClick() },
        insideMargin = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.padding(end = 10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) {
                        MiuixTheme.colorScheme.onSurface
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Text(
                    text = hint,
                    style = MiuixTheme.textStyles.footnote2,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Three generic suggestions plus a field for the user's own smaller step. */
@Composable
internal fun SmallerStepDialog(
    task: Todo,
    strings: Strings,
    onDismiss: () -> Unit,
    onChoose: (String) -> Unit,
) {
    var custom by remember { mutableStateOf("") }

    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.smallerStepTitle) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = strings.smallerStepHint,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(10.dp))
            strings.smallerStepIdeas.forEach { idea ->
                SurfaceChoiceRow(text = idea) { onChoose(idea) }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            TextField(
                value = custom,
                onValueChange = { custom = it },
                label = strings.smallerStepCustom,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { if (custom.isNotBlank()) onChoose(custom.trim()) },
                enabled = custom.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.smallerStepUse)
            }
            TextButton(
                text = strings.cancel,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }
}

@Composable
private fun SurfaceChoiceRow(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    BasicComponent(
        onClick = { if (enabled) onClick() },
        insideMargin = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = when {
                    !enabled -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    selected -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Icon(
                    imageVector = AppIcons.Check,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** "先做别的": picking another task starts a five-minute focus on it. */
@Composable
internal fun OtherTaskDialog(
    candidates: List<Todo>,
    strings: Strings,
    onDismiss: () -> Unit,
    onPick: (Todo) -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.otherTaskTitle) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (candidates.isEmpty()) {
                Text(
                    text = strings.otherTaskEmpty,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                candidates.forEach { todo ->
                    SurfaceChoiceRow(text = todo.title) { onPick(todo) }
                    Spacer(Modifier.height(4.dp))
                }
            }
            TextButton(
                text = strings.cancel,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColors(),
            )
        }
    }
}

/** Asked after a focus session ends; completing stays a choice, never an automatic write. */
@Composable
internal fun FocusDoneDialog(
    task: Todo,
    strings: Strings,
    onComplete: () -> Unit,
    onNotYet: () -> Unit,
) {
    WindowDialog(show = true, onDismissRequest = onNotYet, title = strings.focus) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = task.title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = strings.focusAskComplete,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = strings.focusNotYet,
                    onClick = onNotYet,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                )
                Button(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(strings.focusComplete)
                }
            }
        }
    }
}

/** Up to three root tasks; selection is capped instead of silently dropping one. */
@Composable
internal fun TodayPlanEditorDialog(
    candidates: List<Todo>,
    selectedIds: List<String>,
    strings: Strings,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var selection by remember { mutableStateOf(selectedIds.take(3)) }

    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.dayPlan) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = strings.dayPlanHint,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(8.dp))
            candidates.forEach { todo ->
                val selected = todo.id in selection
                SurfaceChoiceRow(text = todo.title, selected = selected) {
                    selection = when {
                        selected -> selection - todo.id
                        selection.size >= 3 -> selection
                        else -> selection + todo.id
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = strings.dayPlanClear,
                    onClick = { onSave(emptyList()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColors(),
                )
                Button(
                    onClick = { onSave(selection) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(strings.save)
                }
            }
        }
    }
}
