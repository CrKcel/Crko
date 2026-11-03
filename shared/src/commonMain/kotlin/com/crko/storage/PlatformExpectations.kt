package com.crko.storage

import app.cash.sqldelight.db.SqlDriver

/**
 * Minimal key-value persistence. Reads are synchronous so the first frame shows restored data.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Settings storage, kept separate so a corrupt state payload never loses preferences. */
expect fun settingsStore(): KeyValueStore

/**
 * Opens the single process-wide SQLite driver, or returns null when the platform has no writable
 * location yet (host tests, receivers before `AndroidAppContext.install`). Never open a second
 * driver from background code; the state facade owns the only write connection.
 */
expect fun persistenceDriver(): SqlDriver?

/**
 * Holds the payloads of embedded ICS attachments as files, outside the serialized state. The
 * reference stored with a record is the plain file name inside the store's own directory.
 */
interface AttachmentStore {
    /** Writes [bytes] under a collision-free name derived from [fileName]; null when the write failed. */
    fun save(fileName: String, bytes: ByteArray): String?

    /** Bytes of the file [name] written earlier, or null when it is gone. */
    fun read(name: String): ByteArray?
}

/** Platform-provided attachment file store. */
expect fun attachmentStore(): AttachmentStore

/** Attachment store used by the app, resolved lazily so tests can run without a platform. */
object AppAttachmentStore : AttachmentStore {
    override fun save(fileName: String, bytes: ByteArray): String? = attachmentStore().save(fileName, bytes)

    override fun read(name: String): ByteArray? = attachmentStore().read(name)
}

/** Fallback for host tests and for the moment a platform cannot provide its directory yet. */
internal object InMemoryAttachmentStore : AttachmentStore {
    private var counter = 0

    private val files = mutableMapOf<String, ByteArray>()

    override fun save(fileName: String, bytes: ByteArray): String {
        // Single-threaded in practice; imports run on one dispatcher at a time.
        counter += 1
        val name = "$counter-$fileName"
        files[name] = bytes
        return name
    }

    override fun read(name: String): ByteArray? = files[name]
}

/** Platform-provided identifier for newly created records. */
expect fun newId(): String

/** BCP-47 tag of the current system locale, e.g. "zh-Hans-CN" or "en-US". */
expect fun systemLocaleTag(): String

/** Whether the current platform can supply wallpaper-based Monet colors. */
expect fun platformSupportsMonet(): Boolean
