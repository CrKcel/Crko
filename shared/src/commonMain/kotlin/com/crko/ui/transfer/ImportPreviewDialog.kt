package com.crko.ui.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.state.ToggleableState
import com.crko.domain.BusyInterval
import com.crko.domain.CancelledIcsRecord
import com.crko.domain.Event
import com.crko.domain.IcsImport
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.domain.ItipMethod
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.i18n.Strings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

internal enum class ImportRowKind { Event, Todo, Journal, Remove }

/** One selectable line of the import preview. */
internal data class ImportRow(
    val kind: ImportRowKind,
    val title: String,
    val date: String? = null,
    val line: Int? = null,
)

/**
 * The records one parsed file carried, in the order the preview lists them: events, todos,
 * journals, then the removals a `STATUS:CANCELLED` master implies. [toIcsImport] keeps exactly the
 * rows the user checked; calendar-level facts (method, name, refresh interval, busy periods) are
 * not selectable and always travel with the import.
 */
internal data class ImportSelection(
    val rows: List<ImportRow>,
    val events: List<Event>,
    val todos: List<Todo>,
    val journals: List<Journal>,
    val cancelled: List<CancelledIcsRecord>,
    val warnings: List<IcsWarning>,
    val warningDetails: List<IcsWarningDetail>,
    val method: ItipMethod? = null,
    val calendarName: String? = null,
    val refreshMinutes: Int? = null,
    val freeBusy: List<BusyInterval> = emptyList(),
)

/** A parsed file waiting for the user's selection before anything touches the calendar. */
internal data class ImportPreview(
    val selection: ImportSelection,
    val fileName: String?,
)

/** Rows and records of [imported], laid out for the preview dialog. */
internal fun importSelection(imported: IcsImport): ImportSelection = ImportSelection(
    rows = buildList {
        imported.events.forEach { event ->
            add(
                ImportRow(
                    kind = ImportRowKind.Event,
                    title = event.title.ifBlank { event.icsUid ?: "…" },
                    date = event.date,
                    line = event.sourceLine.takeIf { it > 0 },
                ),
            )
        }
        imported.todos.forEach { todo ->
            add(
                ImportRow(
                    kind = ImportRowKind.Todo,
                    title = todo.title.ifBlank { todo.icsUid ?: "…" },
                    date = todo.due ?: todo.start,
                    line = todo.sourceLine.takeIf { it > 0 },
                ),
            )
        }
        imported.journals.forEach { journal ->
            add(
                ImportRow(
                    kind = ImportRowKind.Journal,
                    title = journal.title.ifBlank { journal.date ?: journal.icsUid ?: "…" },
                    date = journal.date,
                    line = journal.sourceLine.takeIf { it > 0 },
                ),
            )
        }
        imported.cancelled.forEach { cancelled ->
            add(
                ImportRow(
                    kind = ImportRowKind.Remove,
                    title = cancelled.summary.ifBlank { cancelled.uid },
                    line = cancelled.line,
                ),
            )
        }
    },
    events = imported.events,
    todos = imported.todos,
    journals = imported.journals,
    cancelled = imported.cancelled,
    warnings = imported.warnings,
    warningDetails = imported.warningDetails,
    method = imported.method,
    calendarName = imported.calendarName,
    refreshMinutes = imported.refreshMinutes,
    freeBusy = imported.freeBusy,
)

/** The subset of [this] the user kept, as an [IcsImport] the state holder can apply. */
internal fun ImportSelection.toIcsImport(checked: List<Boolean>): IcsImport {
    fun kept(index: Int): Boolean = checked.getOrNull(index) == true
    val eventsEnd = events.size
    val todosEnd = eventsEnd + todos.size
    val journalsEnd = todosEnd + journals.size
    return IcsImport(
        events = events.filterIndexed { index, _ -> kept(index) },
        todos = todos.filterIndexed { index, _ -> kept(eventsEnd + index) },
        journals = journals.filterIndexed { index, _ -> kept(todosEnd + index) },
        cancelledUids = cancelled
            .mapIndexed { index, record -> index to record }
            .filter { (index, _) -> kept(journalsEnd + index) }
            .mapTo(mutableSetOf()) { (_, record) -> record.uid },
        cancelled = cancelled.filterIndexed { index, _ -> kept(journalsEnd + index) },
        warnings = warnings,
        warningDetails = warningDetails,
        method = method,
        calendarName = calendarName,
        refreshMinutes = refreshMinutes,
        freeBusy = freeBusy,
    )
}

/**
 * Per-record preview: every record the file carried, with its kind, date and the line its warnings
 * pointed at, each one individually kept or dropped before the import is applied.
 */
@Composable
internal fun ImportPreviewDialog(
    preview: ImportPreview,
    strings: Strings,
    onConfirm: (List<Boolean>) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = preview.selection.rows
    val checked = remember(preview) {
        mutableStateListOf<Boolean>().apply { repeat(rows.size) { add(true) } }
    }
    WindowDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = strings.importPreviewTitle,
        summary = preview.fileName?.takeIf { it.isNotBlank() },
    ) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    text = strings.importSelectAll,
                    onClick = { repeat(rows.size) { checked[it] = true } },
                )
                TextButton(
                    text = strings.importSelectNone,
                    onClick = { repeat(rows.size) { checked[it] = false } },
                )
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                itemsIndexed(rows) { index, row ->
                    ImportRowLine(
                        row = row,
                        strings = strings,
                        checked = checked[index],
                        onChecked = { checked[index] = it },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onConfirm(checked.toList()) },
                enabled = checked.any { it },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
            ) {
                Text(strings.importConfirm)
            }
        }
    }
}

@Composable
private fun ImportRowLine(
    row: ImportRow,
    strings: Strings,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val kindLabel = when (row.kind) {
        ImportRowKind.Event -> strings.importPreviewEvent
        ImportRowKind.Todo -> strings.importPreviewTodo
        ImportRowKind.Journal -> strings.importPreviewJournal
        ImportRowKind.Remove -> strings.importPreviewRemove
    }
    val subtitle = listOfNotNull(
        kindLabel,
        row.date,
        row.line?.let { strings.importWarningLocation(it, null) },
    ).joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Checkbox(state = ToggleableState(checked), onClick = { onChecked(!checked) })
        Column(Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MiuixTheme.textStyles.footnote2,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
