package com.crko.state.`import`

import com.crko.domain.Attachment
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.domain.decodedBytes
import com.crko.domain.safeAttachmentFileName
import com.crko.storage.AttachmentRepository

/**
 * Moves inline `ENCODING=BASE64` attachment payloads into the attachment store when a record is
 * imported, leaving only a file reference in the serialized state. Without this, every re-import of
 * the same calendar added the payload to the stored JSON again and it grew without bound.
 */
internal class AttachmentMaterializer(
    private val repository: AttachmentRepository,
) {
    fun materialize(event: Event): Event =
        if (event.attachments.any(::needsMaterialization)) {
            event.copy(attachments = event.attachments.map(::storePayload))
        } else {
            event
        }

    fun materialize(todo: Todo): Todo =
        if (todo.attachments.any(::needsMaterialization)) {
            todo.copy(attachments = todo.attachments.map(::storePayload))
        } else {
            todo
        }

    fun materialize(journal: Journal): Journal =
        if (journal.attachments.any(::needsMaterialization)) {
            journal.copy(attachments = journal.attachments.map(::storePayload))
        } else {
            journal
        }

    /** A binary attachment that still carries its payload inline and has no file yet. */
    private fun needsMaterialization(attachment: Attachment): Boolean =
        attachment.binary && attachment.storedFile == null && attachment.data.isNotBlank()

    private fun storePayload(attachment: Attachment): Attachment {
        if (!needsMaterialization(attachment)) return attachment
        val bytes = attachment.decodedBytes() ?: return attachment
        val stored = repository.save(safeAttachmentFileName(attachment), bytes) ?: return attachment
        // The base64 value is the part that used to grow the stored state; export re-encodes it
        // from the file, while the parameters stay with the record.
        return attachment.copy(
            value = "",
            storedFile = stored,
            parameters = attachment.parameters + ("ENCODING" to "BASE64"),
        )
    }
}
