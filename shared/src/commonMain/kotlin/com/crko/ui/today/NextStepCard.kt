package com.crko.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Todo
import com.crko.i18n.Strings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.squircle.squircleSurface

/**
 * The one card that asks the user to start. Its primary button opens the confirmation step; the
 * text button opens the four "我动不了" branches instead of scolding the user for not starting.
 */
@Composable
internal fun NextStepCard(
    next: Todo?,
    firstStep: String,
    stoppedForToday: Boolean,
    hasPlan: Boolean,
    strings: Strings,
    onStart: () -> Unit,
    onCantMove: () -> Unit,
    onPickPlan: () -> Unit,
    onContinueDay: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .squircleSurface(color = MiuixTheme.colorScheme.surfaceContainerHighest, cornerRadius = 20.dp)
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(
            text = strings.nextStep,
            style = MiuixTheme.textStyles.footnote1,
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.height(6.dp))

        when {
            stoppedForToday -> {
                Text(
                    text = strings.stoppedForToday,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.stoppedForTodayHint,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = strings.continueDay,
                    onClick = onContinueDay,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            next == null -> {
                Text(
                    text = if (hasPlan) strings.completedTodaySection else strings.dayPlanEmpty,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = strings.dayPlanHint,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(12.dp))
                TextButton(
                    text = strings.dayPlanPick,
                    onClick = onPickPlan,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }

            else -> {
                Text(
                    text = next.title,
                    style = MiuixTheme.textStyles.title2,
                    fontWeight = FontWeight.Bold,
                )
                val step = firstStep
                if (step.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${strings.firstStepLabel} · $step",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(strings.startFiveMinutes)
                    }
                    TextButton(
                        text = strings.cantMove,
                        onClick = onCantMove,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(),
                    )
                }
            }
        }
    }
}
