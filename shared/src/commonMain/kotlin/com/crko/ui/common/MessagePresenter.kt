package com.crko.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.i18n.Strings
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.SnackbarHostState

/**
 * Snackbar queue for the messages [AppStateHolder] publishes. The collector lives above the screens
 * and reads the latest strings on every message, so a language switch made after the first message
 * is picked up without restarting it.
 */
@Composable
internal fun rememberMessagePresenter(state: AppStateHolder): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    val currentStrings by rememberUpdatedState(state.strings)
    LaunchedEffect(Unit) {
        state.messages.collectLatest { message ->
            snackbar.showSnackbar(messageText(currentStrings, message))
        }
    }
    return snackbar
}

/** Localized text for one app message. */
internal fun messageText(strings: Strings, message: AppMessage): String = when (message) {
    AppMessage.Saved -> strings.saved
    AppMessage.Deleted -> strings.deleted
    AppMessage.TodoAdded -> strings.todoAdded
    is AppMessage.EventsImported -> importSummaryText(
        strings = strings,
        events = message.events,
        todos = message.todos,
        journals = message.journals,
        removed = message.removed,
        warnings = message.warnings,
        details = message.warningDetails,
    )
    is AppMessage.ImportFailed -> strings.importFailure(message.failure)
    AppMessage.Exported -> strings.exported
    AppMessage.ExportFailed -> strings.exportFailed
    is AppMessage.StorageRecovered -> strings.storageRecovered(message.droppedRecords)
    is AppMessage.ItipReplyProcessed -> strings.inviteReplyProcessed(
        message.attendee,
        message.partStat,
    )
    is AppMessage.ItipReplyUnknown -> strings.inviteReplyUnknown(message.attendee)
    is AppMessage.SubscriptionSynced -> strings.subscriptionSynced(
        message.name,
        message.events,
        message.todos,
        message.removed,
    )
    is AppMessage.SubscriptionUnchanged -> strings.subscriptionUnchanged(message.name)
    is AppMessage.SubscriptionFailed ->
        strings.subscriptionFailed(message.name, message.failure)
}

/** How many records one warning kind may name in the import message. */
private const val MaxWarningLocations = 3

/**
 * Text for one import result: a summary plus the records each warning pointed at. The list is
 * bounded per warning kind, because a file with hundreds of broken records would otherwise produce
 * a message no one can read in a toast.
 */
internal fun importSummaryText(
    strings: Strings,
    events: Int,
    todos: Int,
    journals: Int,
    removed: Int,
    warnings: List<IcsWarning>,
    details: List<IcsWarningDetail>,
): String {
    if (events == 0 && todos == 0 && journals == 0 && removed == 0) return strings.nothingToImport
    val parts = mutableListOf(strings.importedItems(events, todos, journals, removed))
    val kinds = warnings + details.map { it.warning }.filterNot { it in warnings }
    kinds.distinct().forEach { warning ->
        val matching = details.filter { it.warning == warning }
        if (matching.isEmpty()) {
            parts += strings.importWarning(warning)
            return@forEach
        }
        val shown = matching.take(MaxWarningLocations)
        shown.forEach { detail ->
            parts += strings.importWarning(warning) +
                strings.importWarningLocation(detail.line, detail.record)
        }
        val hidden = matching.size - shown.size
        if (hidden > 0) parts += strings.importWarning(warning) + strings.importWarningMore(hidden)
    }
    return parts.joinToString("; ")
}
