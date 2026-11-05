package com.crko.storage

/**
 * Platform secure storage for small secret strings, currently the CalDAV password. Values live
 * outside SQLite on purpose: the database file can be exported and backed up, the platform
 * keystore/keychain cannot be read by another app.
 */
interface SecretStore {
    /** Stores [value] under [key], replacing any previous value. */
    suspend fun save(key: String, value: String)

    /** The stored value, or null when nothing is stored (or it cannot be decoded). */
    suspend fun load(key: String): String?

    /** Removes the value under [key]; removing an absent key is a no-op. */
    suspend fun delete(key: String)
}

/** Key under which the password of the calendar with [calendarId] is stored. */
fun caldavPasswordKey(calendarId: String): String = "caldav-password:$calendarId"

/** Platform-provided store, wired into the state holder by the app composition root. */
expect fun defaultSecretStore(): SecretStore?
