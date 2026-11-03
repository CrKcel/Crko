package com.crko.domain

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal fun readAttachment(line: ContentLine): Attachment {
    val binary = line.params["ENCODING"].equals("BASE64", ignoreCase = true)
    return Attachment(
        value = line.value.trim(),
        parameters = line.params,
        binary = binary,
    )
}

@OptIn(ExperimentalEncodingApi::class)
internal fun StringBuilder.appendAttachments(
    attachments: List<Attachment>,
    storedAttachment: (String) -> ByteArray?,
) {
    attachments.forEach { attachment ->
        // A stored payload is re-encoded at export time; the parameters stay with the record so
        // FMTTYPE and any unknown parameter survive without the base64 blob living in the state.
        val stored = attachment.storedFile?.takeIf { it.isNotBlank() }
        val value = if (attachment.binary) {
            if (attachment.value.isNotBlank()) {
                attachment.value.filterNot { it.isWhitespace() }
            } else {
                val bytes = stored?.let(storedAttachment) ?: return@forEach
                Base64.encode(bytes)
            }
        } else {
            sanitizeUriValue(attachment.value)
        }
        if (value.isBlank()) return@forEach
        val parameters = LinkedHashMap(attachment.parameters)
        if (attachment.binary && "ENCODING" !in parameters) parameters["ENCODING"] = "BASE64"
        line(
            buildString {
                append("ATTACH")
                parameters.forEach { (name, parameter) ->
                    append(';').append(name).append('=').append(icsParameterValue(parameter))
                }
                append(':').append(value)
            },
        )
    }
}
