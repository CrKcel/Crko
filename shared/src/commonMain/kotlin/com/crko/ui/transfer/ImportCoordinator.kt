package com.crko.ui.transfer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import com.crko.domain.ItipMethod
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.state.CalendarFileParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Orchestrates the calendar file flows behind the More tab: pick a file, let the state holder read
 * and parse it off the UI thread, then hand the parsed selection to the preview, apply what the
 * user kept, or export and share the current calendar.
 *
 * Parsing, merging and ICS encoding stay in the state layer; this class only sequences the steps
 * and reports messages, so the shell holds display state alone.
 */
internal class ImportCoordinator(
    private val state: AppStateHolder,
    private val files: CalendarFileBridge,
    private val scope: CoroutineScope,
    private val onPreview: (ImportPreview) -> Unit,
) {
    fun importCalendar() {
        files.openImport(
            onPicked = { chunks, fileName ->
                scope.launch {
                    when (val parsed = state.parseCalendarFile(chunks)) {
                        is CalendarFileParse.Failed ->
                            state.notify(AppMessage.ImportFailed(parsed.failure))
                        is CalendarFileParse.Parsed -> {
                            val imported = parsed.import
                            // An iTIP REPLY only flips one attendee's status; there is nothing to
                            // preview, so the state holder applies it at once and reports the
                            // answer.
                            if (imported.method == ItipMethod.Reply) {
                                state.applyCalendarImport(imported, fileName)
                                return@launch
                            }
                            val selection = importSelection(imported)
                            if (selection.rows.isEmpty()) {
                                state.notify(
                                    AppMessage.EventsImported(
                                        0,
                                        0,
                                        0,
                                        0,
                                        imported.warnings,
                                        imported.warningDetails,
                                    ),
                                )
                                return@launch
                            }
                            onPreview(ImportPreview(selection = selection, fileName = fileName))
                        }
                    }
                }
            },
            onError = { failure -> state.notify(AppMessage.ImportFailed(failure)) },
        )
    }

    /** Applies the records the user kept in the import preview. */
    fun apply(preview: ImportPreview, checked: List<Boolean>) {
        val selection = preview.selection
        scope.launch {
            val outcome = state.applyCalendarImport(selection.toIcsImport(checked), preview.fileName)
            state.notify(
                AppMessage.EventsImported(
                    outcome.events,
                    outcome.todos,
                    outcome.journals,
                    outcome.removed,
                    selection.warnings,
                    selection.warningDetails,
                ),
            )
        }
    }

    fun exportCalendar() {
        val fileName = "crko-${state.today}.ics"
        scope.launch {
            val content = state.exportCalendarIcs()
            files.saveExport(
                fileName = fileName,
                content = content,
                onExported = { state.notify(AppMessage.Exported) },
                onError = { state.notify(AppMessage.ExportFailed) },
            )
        }
    }
}

@Composable
internal fun rememberImportCoordinator(
    state: AppStateHolder,
    files: CalendarFileBridge,
    onPreview: (ImportPreview) -> Unit,
): ImportCoordinator {
    val scope = rememberCoroutineScope()
    // The preview handler outlives the coordinator; read the latest one so a recomposition that
    // swaps the callback is honoured without rebuilding the coordinator.
    val currentOnPreview by rememberUpdatedState(onPreview)
    return remember(state, files, scope) {
        ImportCoordinator(state, files, scope) { currentOnPreview(it) }
    }
}
