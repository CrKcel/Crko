package com.crko.state

import com.crko.domain.IcsImport
import com.crko.domain.IcsImportFailure
import com.crko.domain.IcsPartStat
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.domain.SubscriptionFailure

/**
 * Small shared types of the state facade: the user-visible messages, the outcomes the UI maps onto
 * text, and the enum settings. Split out of the holder so each file keeps its line budget.
 */

enum class DarkModeSetting { System, Light, Dark }

enum class EditScope { ThisEvent, ThisAndFuture, AllEvents }

/** User-visible notifications; localized at display time so they follow language switches. */
sealed interface AppMessage {
    data object Saved : AppMessage
    /** A delete happened; [undoable] is true when its records wait in the in-memory undo snapshot. */
    data class Deleted(val undoable: Boolean) : AppMessage

    /** The most recent delete was put back into its calendar. */
    data object DeleteUndone : AppMessage
    /** Undo found nothing to restore, or a record with one of the ids exists again. */
    data object DeleteUndoFailed : AppMessage
    data object TodoAdded : AppMessage
    data class EventsImported(
        val events: Int,
        val todos: Int = 0,
        val journals: Int = 0,
        val removed: Int = 0,
        val warnings: List<IcsWarning> = emptyList(),
        val warningDetails: List<IcsWarningDetail> = emptyList(),
    ) : AppMessage
    data class ImportFailed(val failure: IcsImportFailure) : AppMessage
    data object Exported : AppMessage
    data object ExportFailed : AppMessage

    /** An attendee answered an invitation; [partStat] is the answer. */
    data class ItipReplyProcessed(val attendee: String, val partStat: IcsPartStat) : AppMessage

    /** A `REPLY` named no record this calendar knows (or an older revision than the stored one). */
    data class ItipReplyUnknown(val attendee: String) : AppMessage
    data class SubscriptionSynced(
        val name: String,
        val events: Int,
        val todos: Int = 0,
        val removed: Int = 0,
    ) : AppMessage

    data class SubscriptionUnchanged(val name: String) : AppMessage
    data class SubscriptionFailed(val name: String, val failure: SubscriptionFailure) : AppMessage

    /** A cloud pass moved records; the counts are the applied deltas of the pulled changes. */
    data class CloudSynced(
        val events: Int,
        val todos: Int = 0,
        val journals: Int = 0,
        val removed: Int = 0,
    ) : AppMessage
    data object CloudUnchanged : AppMessage

    /** The cloud token no longer verifies; the user signs in again from the settings. */
    data object CloudAuthExpired : AppMessage

    /**
     * Stored data could not be read completely. [droppedRecords] is zero when nothing at all could
     * be parsed; the damaged payload itself was kept by the storage.
     */
    data class StorageRecovered(val droppedRecords: Int) : AppMessage
}

/** Outcome of reading a picked calendar file; the UI maps [Failed] onto a message. */
sealed interface CalendarFileParse {
    data class Parsed(val import: IcsImport) : CalendarFileParse
    data class Failed(val failure: IcsImportFailure) : CalendarFileParse
}

enum class PaletteSetting { Monet, Monochrome, Custom }

/** Result of a mutation attempt; the UI maps [WriteResult] onto localized text. */
sealed interface WriteResult {
    data object Success : WriteResult
    data object EmptyTitle : WriteResult
    data object InvalidTimeRange : WriteResult
    data object InvalidRepeat : WriteResult
}
