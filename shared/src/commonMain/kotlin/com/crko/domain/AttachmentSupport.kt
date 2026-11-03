package com.crko.domain

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Decoded bytes of an embedded `ATTACH`. Exporters disagree about padding and about the alphabet,
 * so both are repaired before decoding instead of leaving the payload unreadable.
 */
@OptIn(ExperimentalEncodingApi::class)
fun Attachment.decodedBytes(): ByteArray? {
    val text = data.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
    val padded = when (text.length % 4) {
        0 -> text
        2, 3 -> text + "=".repeat(4 - text.length % 4)
        else -> return null
    }
    return runCatching { Base64.decode(padded) }.getOrNull()
}

/** Characters a file name must never contain when it is handed to the platform file picker. */
private const val ReservedFileNameCharacters = "\\/:*?\"<>|"

/** Longest attachment name offered to the picker, so a pathological `FILENAME` stays usable. */
private const val MaxAttachmentNameChars = 80

/**
 * Safe destination name for [this] attachment. `FILENAME` and URI segments come straight out of an
 * imported file, so they have to lose path separators, parent references and control characters
 * before they may be joined to a directory the app does not control.
 */
fun safeAttachmentFileName(attachment: Attachment): String {
    val candidate = listOf(attachment.name, attachment.uri)
        .asSequence()
        .map { it.replace('\\', '/').substringAfterLast('/').substringBefore('?').substringBefore('#') }
        .map(::cleanFileNamePart)
        .firstOrNull { it.isNotEmpty() }
        ?: ""
    if (candidate.isEmpty()) return "attachment${attachmentExtension(attachment)}"
    return if ('.' in candidate) candidate else candidate + attachmentExtension(attachment)
}

/**
 * One path segment without separators, control characters or leading dots, so neither `../` nor an
 * absolute `FILENAME` can escape the folder the user picked.
 */
private fun cleanFileNamePart(value: String): String {
    val replaced = buildString(value.length) {
        value.forEach { ch ->
            val reserved = ch in ReservedFileNameCharacters || ch.code < 0x20 || ch.code == 0x7F
            append(if (reserved) ' ' else ch)
        }
    }
    return replaced
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .trimStart('.')
        .take(MaxAttachmentNameChars)
        .trimStart('.', ' ')
}

private fun attachmentExtension(attachment: Attachment): String = mimeTypeExtension(
    attachment.mimeType.substringBefore(';').trim().lowercase(),
    fallback = if (attachment.binary) ".bin" else "",
)

/** Extension for a MIME type, or [fallback] when one is not known. */
private fun mimeTypeExtension(type: String, fallback: String): String = when (type) {
    "image/png" -> ".png"
    "image/jpeg", "image/jpg" -> ".jpg"
    "image/gif" -> ".gif"
    "image/webp" -> ".webp"
    "image/heic" -> ".heic"
    "application/pdf" -> ".pdf"
    "text/plain", "text/csv" -> ".txt"
    "application/json" -> ".json"
    "audio/mpeg" -> ".mp3"
    "video/mp4" -> ".mp4"
    else -> fallback
}

private val extensionMimeTypes = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "heic" to "image/heic",
    "pdf" to "application/pdf",
    "txt" to "text/plain",
    "csv" to "text/csv",
    "json" to "application/json",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
    "ics" to "text/calendar",
)

/**
 * MIME type for saving [this] attachment: `FMTTYPE` when the file declares one, else the type its
 * file name extension implies, else a generic binary type. The platform picker builds its document
 * from this value, so a payload should not be forced to `application/octet-stream` when its type is
 * known.
 */
fun attachmentMimeType(attachment: Attachment): String {
    val declared = attachment.mimeType.substringBefore(';').trim().lowercase()
    if (declared.isNotEmpty()) return declared
    val name = safeAttachmentFileName(attachment)
    val extension = name.substringAfterLast('.', "").lowercase()
    return extensionMimeTypes[extension] ?: "application/octet-stream"
}
