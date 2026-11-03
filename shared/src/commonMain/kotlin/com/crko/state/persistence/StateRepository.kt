package com.crko.state.persistence

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppState
import com.crko.domain.PersistedSettings
import com.crko.domain.replaceIcsTimeZones
import com.crko.domain.withLocalCalendar
import com.crko.storage.LoadDamage
import com.crko.storage.StateStorage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the in-memory [AppState] and the writes that keep it on disk.
 *
 * Startup still reads [storage] synchronously so the first frame shows restored data. Every later
 * change updates [state] immediately for Compose, and is handed to a writer coroutine on
 * [ioDispatcher]; requests that pile up behind the writer's mutex are drained together, so a burst
 * of edits serializes the latest snapshot once instead of once per edit.
 */
internal class StateRepository(
    private val scope: CoroutineScope,
    private val storage: StateStorage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Called after [state] changed; the facade hooks reminder rescheduling in here. */
    private val onStateChanged: (AppState) -> Unit = {},
    /** Receives write errors without stopping later writes, so memory state stays intact. */
    private val onWriteFailure: (Throwable) -> Unit = {},
) {
    private sealed interface Write {
        class State(val state: AppState) : Write
        class Settings(val settings: PersistedSettings) : Write
        class Flush(val done: CompletableDeferred<Unit>) : Write
    }

    private val loaded: AppState = storage.loadState().withLocalCalendar()

    var state: AppState by mutableStateOf(loaded)
        private set

    /** What the initial [StateStorage.loadState] had to drop, if anything. */
    val loadDamage: LoadDamage = storage.loadDamage()

    private val writes = Channel<Write>(Channel.UNLIMITED)
    private val writeMutex = Mutex()

    init {
        // Unknown zones live at the calendar level; registering them before the first frame lets
        // restored records resolve their wall clock without waiting for a write.
        replaceIcsTimeZones(loaded.calendars)
    }

    fun loadSettings(): PersistedSettings = storage.loadSettings()

    /** Applies [next] to memory immediately and queues its disk write behind earlier requests. */
    fun persist(next: AppState) {
        // The lookup cache is a derived index of the stored calendars: rebuilding it per write drops
        // the definitions of a deleted calendar and of an import preview that was never applied.
        replaceIcsTimeZones(next.calendars)
        state = next
        enqueue(Write.State(next))
        onStateChanged(next)
    }

    /** Queues the preference write; the latest snapshot wins when requests are coalesced. */
    fun saveSettings(settings: PersistedSettings) = enqueue(Write.Settings(settings))

    /** Completes once every write queued before this call has reached the storage. */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        enqueue(Write.Flush(done))
        done.await()
    }

    private fun enqueue(request: Write) {
        writes.trySend(request)
        scope.launch(ioDispatcher) { drainWrites() }
    }

    /**
     * One writer at a time: whoever holds [writeMutex] absorbs every request already queued, writes
     * the latest state and settings once, and lets later jobs find an empty queue.
     */
    private suspend fun drainWrites() = writeMutex.withLock {
        var state: AppState? = null
        var settings: PersistedSettings? = null
        val flushed = mutableListOf<CompletableDeferred<Unit>>()
        var request = writes.tryReceive().getOrNull()
        while (request != null) {
            when (val write = request) {
                is Write.State -> state = write.state
                is Write.Settings -> settings = write.settings
                is Write.Flush -> flushed += write.done
            }
            request = writes.tryReceive().getOrNull()
        }
        state?.let { write { storage.saveState(it) } }
        settings?.let { write { storage.saveSettings(it) } }
        flushed.forEach { it.complete(Unit) }
    }

    private inline fun write(block: () -> Unit) {
        runCatching(block).onFailure(onWriteFailure)
    }
}
