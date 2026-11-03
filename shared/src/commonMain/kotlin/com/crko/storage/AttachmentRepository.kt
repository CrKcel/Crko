package com.crko.storage

import com.crko.domain.Attachment
import com.crko.domain.decodedBytes

/**
 * File-system side of `ATTACH` payloads. The domain keeps decoding the inline base64; reading and
 * materializing payloads lives here, so `com.crko.domain` never has to import storage.
 */
class AttachmentRepository(private val store: AttachmentStore = AppAttachmentStore) {

    /** Writes [bytes] under a collision-free name derived from [fileName]; null when it failed. */
    fun save(fileName: String, bytes: ByteArray): String? = store.save(fileName, bytes)

    /** Bytes of the attachment file [storedFile], or null when it is gone. */
    fun read(storedFile: String): ByteArray? = store.read(storedFile)

    /**
     * Bytes of [attachment] wherever they live: the materialized file first, the inline base64
     * payload for one that has not been stored yet. Null when neither is readable.
     */
    fun readBytes(attachment: Attachment): ByteArray? {
        val file = attachment.storedFile?.takeIf { it.isNotBlank() }
        if (file != null && attachment.data.isBlank()) return read(file)
        if (attachment.data.isNotBlank()) return attachment.decodedBytes()
        return null
    }
}
