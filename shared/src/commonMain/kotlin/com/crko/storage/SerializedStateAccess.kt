package com.crko.storage

import com.crko.domain.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The only entry background code (widget refreshes, notification actions, boot rebuilds) may use to
 * read or modify stored state. One mutex serializes every call in the process, and writes land in
 * one storage transaction through the same facade the foreground repository uses, so no second
 * writer is created.
 *
 * It deliberately does not touch [com.crko.state] memory: action receivers run without a Compose
 * scope. A foreground process that already holds state must route interactive edits through the
 * repository and only use this entry for short background follow-ups.
 */
class SerializedStateAccess(
    private val storage: () -> StateStorage = { AppStorage },
    private val idProvider: () -> String = ::newId,
) {
    private val mutex = Mutex()

    suspend fun read(): AppState = withContext(Dispatchers.Default) {
        mutex.withLock { storage().loadState() }
    }

    /** Applies [transform] to the stored state, writes it, and returns the normalized result. */
    suspend fun update(transform: (AppState) -> AppState): AppState = withContext(Dispatchers.Default) {
        mutex.withLock {
            val target = storage()
            val next = transform(target.loadState()).ensureRecordIds(idProvider)
            target.saveState(next)
            next
        }
    }
}

/** Process-wide background entry; shares the single [AppStorage] driver with the foreground. */
val BackgroundStateAccess: SerializedStateAccess = SerializedStateAccess()
