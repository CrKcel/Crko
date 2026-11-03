package com.crko.ui.more

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crko.domain.Calendar
import com.crko.domain.DefaultCalendarColor
import com.crko.domain.recordCount
import com.crko.i18n.Strings
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.calendar.CalendarDeleteResult
import com.crko.ui.components.CalendarDot
import com.crko.ui.components.CardRowDivider
import com.crko.ui.components.SurfaceCard
import com.crko.ui.icons.AppIcons
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Preset accents a calendar can pick; the first one is [DefaultCalendarColor]. */
private val CalendarColorChoices = listOf(
    0xFF3B6CF5L, 0xFF7C4DFFL, 0xFFD81B60L, 0xFFFF7043L, 0xFFFFB300L,
    0xFF43A047L, 0xFF00897BL, 0xFF00ACC1L, 0xFF546E7AL,
)

/**
 * Management of local calendars: colour, name, visibility, default and deletion. Subscriptions keep
 * their own section; their records never receive newly created local entries.
 */
@Composable
internal fun CalendarsSection(
    state: AppStateHolder,
    strings: Strings,
    onExportCalendar: (String) -> Unit,
) {
    val calendars = state.localCalendars
    val defaultId = state.newRecordCalendarId
    val canDelete = calendars.size > 1
    var showAdd by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Calendar?>(null) }
    var coloring by remember { mutableStateOf<Calendar?>(null) }
    var deleting by remember { mutableStateOf<Calendar?>(null) }

    SurfaceCard {
        calendars.forEachIndexed { index, calendar ->
            CalendarRow(
                calendar = calendar,
                isDefault = calendar.id == defaultId,
                strings = strings,
                canDelete = canDelete,
                onRename = { renaming = calendar },
                onColor = { coloring = calendar },
                onSetDefault = { state.setDefaultCalendar(calendar.id) },
                onToggleVisible = { state.setCalendarVisible(calendar.id, it) },
                onExport = { onExportCalendar(calendar.id) },
                onDelete = { deleting = calendar },
            )
            if (index != calendars.lastIndex) CardRowDivider()
        }
        CardRowDivider()
        AddCalendarRow(strings = strings, onClick = { showAdd = true })
    }

    if (showAdd) {
        AddCalendarDialog(
            strings = strings,
            onDismiss = { showAdd = false },
            onConfirm = { name, color ->
                val created = state.createLocalCalendar(name, color)
                if (created != null) showAdd = false
                created != null
            },
        )
    }
    renaming?.let { calendar ->
        RenameCalendarDialog(
            calendar = calendar,
            strings = strings,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                if (state.renameCalendar(calendar.id, name)) renaming = null
            },
        )
    }
    coloring?.let { calendar ->
        ColorPickerDialog(
            strings = strings,
            initial = calendar.colorArgb ?: DefaultCalendarColor,
            onDismiss = { coloring = null },
            onConfirm = {
                state.setCalendarColor(calendar.id, it)
                coloring = null
            },
        )
    }
    deleting?.let { calendar ->
        DeleteCalendarDialog(
            calendar = calendar,
            strings = strings,
            onDismiss = { deleting = null },
            onConfirm = {
                if (state.deleteCalendar(calendar.id) == CalendarDeleteResult.Success) {
                    // Deleting a calendar drops its records with it, so there is no record undo.
                    state.notify(AppMessage.Deleted(undoable = false))
                }
                deleting = null
            },
        )
    }
}

@Composable
private fun CalendarRow(
    calendar: Calendar,
    isDefault: Boolean,
    strings: Strings,
    canDelete: Boolean,
    onRename: () -> Unit,
    onColor: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleVisible: (Boolean) -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    BasicComponent(
        onClick = onRename,
        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onColor)
                    .padding(6.dp),
            ) {
                CalendarDot(calendar, size = 16)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = calendar.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isDefault) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = strings.calendarDefault,
                            style = MiuixTheme.textStyles.footnote2,
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (calendar.visible) {
                            strings.items(calendar.recordCount)
                        } else {
                            "${strings.items(calendar.recordCount)} · ${strings.calendarHidden}"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (!isDefault) {
                        Text(
                            text = strings.calendarSetDefault,
                            style = MiuixTheme.textStyles.footnote2,
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onSetDefault)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            IconButton(onClick = onExport, backgroundColor = Color.Transparent) {
                Icon(
                    imageVector = AppIcons.Export,
                    contentDescription = strings.calendarExport,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Switch(checked = calendar.visible, onCheckedChange = onToggleVisible)
            if (canDelete) {
                IconButton(onClick = onDelete, backgroundColor = Color.Transparent) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = strings.calendarDelete,
                        tint = MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun AddCalendarRow(strings: Strings, onClick: () -> Unit) {
    BasicComponent(onClick = onClick, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                    .padding(6.dp),
            ) {
                Icon(
                    AppIcons.Add,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(text = strings.calendarAdd, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun AddCalendarDialog(
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Boolean,
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(DefaultCalendarColor) }
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.calendarAdd,
        summary = strings.calendarAddHint,
    ) {
        Column {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = strings.calendarName,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            ColorChoices(selected = color, onSelect = { color = it })
            Spacer(Modifier.height(16.dp))
            DialogButtons(
                cancel = strings.cancel,
                confirm = strings.save,
                onCancel = onDismiss,
                onConfirm = { onConfirm(name, color) },
                confirmEnabled = name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun RenameCalendarDialog(
    calendar: Calendar,
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(calendar.id) { mutableStateOf(calendar.name) }
    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.calendarRename) {
        Column {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = strings.calendarName,
                useLabelAsPlaceholder = true,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            DialogButtons(
                cancel = strings.cancel,
                confirm = strings.save,
                onCancel = onDismiss,
                onConfirm = { onConfirm(name) },
                confirmEnabled = name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ColorPickerDialog(
    strings: Strings,
    initial: Long,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
) {
    var color by remember { mutableStateOf(initial) }
    WindowDialog(show = true, onDismissRequest = onDismiss, title = strings.calendarColor) {
        Column {
            ColorChoices(selected = color, onSelect = { color = it })
            Spacer(Modifier.height(16.dp))
            DialogButtons(
                cancel = strings.cancel,
                confirm = strings.save,
                onCancel = onDismiss,
                onConfirm = { onConfirm(color) },
                confirmEnabled = true,
            )
        }
    }
}

@Composable
private fun DeleteCalendarDialog(
    calendar: Calendar,
    strings: Strings,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.calendarDelete,
        summary = strings.calendarDeleteSummary(calendar.name, calendar.recordCount),
    ) {
        DialogButtons(
            cancel = strings.cancel,
            confirm = strings.delete,
            onCancel = onDismiss,
            onConfirm = onConfirm,
            confirmEnabled = true,
        )
    }
}

@Composable
private fun ColorChoices(selected: Long, onSelect: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CalendarColorChoices.forEach { color ->
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(color.toInt()))
                    .then(
                        if (color == selected) {
                            Modifier.border(2.dp, MiuixTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun DialogButtons(
    cancel: String,
    confirm: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(text = cancel, onClick = onCancel, modifier = Modifier.weight(1f))
        TextButton(
            text = confirm,
            onClick = onConfirm,
            enabled = confirmEnabled,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}
