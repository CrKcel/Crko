package com.crko.ui.todo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.crko.domain.Todo
import com.crko.i18n.Strings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Completion scope of a repeating todo. Checking the card completes the occurrence shown, so this
 * menu carries the two series-wide choices: finish the whole series, or keep the record and drop
 * its rule.
 */
@Composable
internal fun TodoActionsDialog(
    todo: Todo,
    strings: Strings,
    onDismiss: () -> Unit,
    onCompleteSeries: () -> Unit,
    onStopRecurrence: () -> Unit,
) {
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.todoActions,
        summary = todo.title,
    ) {
        Column {
            ActionRow(strings.todoCompleteSeries, onClick = onCompleteSeries)
            ActionRow(strings.todoStopRecurrence, onClick = onStopRecurrence)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.cancel)
            }
        }
    }
}

@Composable
private fun ActionRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body1,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MiuixTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 14.dp),
    )
}
