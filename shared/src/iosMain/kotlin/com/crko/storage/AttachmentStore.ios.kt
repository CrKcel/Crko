@file:OptIn(ExperimentalForeignApi::class)

package com.crko.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.getBytes
import platform.Foundation.writeToFile

/**
 * Attachment payloads live in `Documents/attachments`, where the app can always write. The state
 * JSON only ever carries the file name, so re-importing a calendar does not grow the stored blob.
 */
private class IosAttachmentStore(private val directory: String) : AttachmentStore {

    private val manager = NSFileManager.defaultManager

    private fun ensureDirectory(): Boolean = runCatching {
        if (!manager.fileExistsAtPath(directory)) {
            manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        }
        manager.fileExistsAtPath(directory)
    }.getOrDefault(false)

    override fun save(fileName: String, bytes: ByteArray): String? {
        if (!ensureDirectory()) return null
        val safe = cleanAttachmentFileName(fileName)
        // A unique prefix keeps two imports of different files with the same name from overwriting
        // each other's payload while a record still references the old one.
        val path = "$directory/${NSUUID().UUIDString}-$safe"
        val saved = bytes.toNSData().writeToFile(path, atomically = true)
        return if (saved) path.substringAfterLast('/') else null
    }

    override fun read(name: String): ByteArray? {
        val path = "$directory/${cleanAttachmentFileName(name)}"
        if (!manager.fileExistsAtPath(path)) return null
        return runCatching { NSData.dataWithContentsOfFile(path)?.toByteArray() }.getOrNull()
    }
}

private fun cleanAttachmentFileName(name: String): String = buildString(name.length) {
    name.forEach { ch ->
        val reserved = ch == '/' || ch == '\\' || ch == ':' || ch.code < 0x20 || ch.code == 0x7F
        append(if (reserved) '_' else ch)
    }
}.take(120).ifEmpty { "attachment.bin" }

/** The directory holding attachment payloads, created on demand. */
private fun attachmentDirectory(): String? {
    val base = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
        ?: return null
    return "$base/attachments"
}

actual fun attachmentStore(): AttachmentStore {
    val directory = attachmentDirectory() ?: return InMemoryAttachmentStore
    return IosAttachmentStore(directory)
}

private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData.create(bytes = null, length = 0u)
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    }

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned -> getBytes(pinned.addressOf(0), length = length) }
    return bytes
}
