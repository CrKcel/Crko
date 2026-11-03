package com.crko.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.crko.domain.startOfMonth
import com.crko.i18n.Strings
import kotlinx.datetime.LocalDate
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog

/** Month picker shown in its own dialog; horizontal paging between months is handled by the calendar. */
@Composable
internal fun DatePickerDialog(
    initial: LocalDate,
    today: LocalDate,
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var month by remember { mutableStateOf(initial.startOfMonth()) }
    var selected by remember { mutableStateOf(initial) }

    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.monthTitle(month.year, month.month),
    ) {
        Column {
            MonthCalendar(
                hasEvents = { false },
                strings = strings,
                monthAnchor = month,
                selected = selected,
                today = today,
                onSelect = { selected = it },
                onMonthChange = { month = it },
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(text = strings.cancel, onClick = onDismiss, modifier = Modifier.weight(1f))
                TextButton(
                    text = strings.save,
                    onClick = { onConfirm(selected) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}
