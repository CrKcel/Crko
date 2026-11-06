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
import com.crko.state.UndoResult
import kotlinx.coroutines.flow.collectLatest
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult

/**
 * Snackbar queue for the messages [AppStateHolder] publishes. The collector lives above the screens
 * and reads the latest strings on every message, so a language switch made after the first message
 * is picked up without restarting it.
 *
 * A delete that left records in the undo snapshot offers [Strings.undo] as its action; performing it
 * restores those records and reports the outcome as the next message.
 */
@Composable
internal fun rememberMessagePresenter(state: AppStateHolder): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    val currentStrings by rememberUpdatedState(state.strings)
    LaunchedEffect(Unit) {
        state.messages.collectLatest { message ->
            val action = actionText(currentStrings, message)
            val result = snackbar.showSnackbar(
                message = messageText(currentStrings, message),
                actionLabel = action,
                // An undo the user cannot reach is worse than no undo, so keep it up longer.
                duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed && message is AppMessage.Deleted) {
                state.notify(
                    if (state.undoLastDelete() == UndoResult.Success) {
                        AppMessage.DeleteUndone
                    } else {
                        AppMessage.DeleteUndoFailed
                    },
                )
            }
        }
    }
    return snackbar
}

/** Snackbar action for one app message, or null when the message has nothing to act on. */
internal fun actionText(strings: Strings, message: AppMessage): String? = when {
    message is AppMessage.Deleted && message.undoable -> strings.undo
    else -> null
}

/** Localized text for one app message. */
internal fun messageText(strings: Strings, message: AppMessage): String = when (message) {
    AppMessage.Saved -> strings.saved
    is AppMessage.Deleted -> strings.deleted
    AppMessage.DeleteUndone -> strings.deleteUndone
    AppMessage.DeleteUndoFailed -> strings.deleteUndoFailed
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
    is AppMessage.CloudSynced -> strings.cloudSynced(
        message.events,
        message.todos,
        message.journals,
        message.removed,
    )
    AppMessage.CloudUnchanged -> strings.cloudUnchanged
    AppMessage.CloudFailed -> strings.cloudErrorGeneric
    AppMessage.CloudAuthExpired -> strings.cloudAuthExpired
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
