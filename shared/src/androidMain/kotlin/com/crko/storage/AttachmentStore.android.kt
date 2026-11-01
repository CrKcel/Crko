package com.crko.storage

import android.content.Context
import com.crko.AndroidAppContext
import java.io.File
import java.util.UUID

/**
 * Attachment payloads live in `filesDir/attachments`, private to the app. The state JSON only ever
 * carries the file name, so re-importing a calendar does not grow the stored blob.
 */
private class FileAttachmentStore(private val directory: File) : AttachmentStore {

    private fun ensureDirectory(): File? = runCatching {
        directory.apply { if (!exists()) mkdirs() }
    }.getOrNull()?.takeIf { it.isDirectory }

    override fun save(fileName: String, bytes: ByteArray): String? {
        val dir = ensureDirectory() ?: return null
        val safe = cleanAttachmentFileName(fileName)
        // A unique prefix keeps two imports of different files with the same name from overwriting
        // each other's payload while the record still references the old one.
        val target = File(dir, "${UUID.randomUUID()}-$safe")
        return runCatching {
            target.writeBytes(bytes)
            target.name
        }.getOrNull()
    }

    override fun read(name: String): ByteArray? {
        val file = File(directory, cleanAttachmentFileName(name))
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }
}

/** One plain file name: no separators or parent references from an imported `FILENAME` survive. */
private fun cleanAttachmentFileName(name: String): String = buildString(name.length) {
    name.forEach { ch ->
        val reserved = ch == '/' || ch == '\\' || ch == ':' || ch.code < 0x20 || ch.code == 0x7F
        append(if (reserved) '_' else ch)
    }
}.take(120).ifEmpty { "attachment.bin" }

actual fun attachmentStore(): AttachmentStore {
    val context = AndroidAppContext.getOrNull() ?: return InMemoryAttachmentStore
    return FileAttachmentStore(File(context.filesDir, "attachments"))
}
