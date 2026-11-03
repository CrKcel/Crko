package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.PersistedSettings
import com.crko.state.persistence.StateRepository
import com.crko.storage.StateStorage
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingStorage(var state: AppState = AppState()) : StateStorage {
    val stateWrites = mutableListOf<AppState>()
    val settingsWrites = mutableListOf<PersistedSettings>()
    var failNextStateWrite = false
    var settings = PersistedSettings()

    override fun loadState(): AppState = state

    override fun saveState(state: AppState) {
        if (failNextStateWrite) {
            failNextStateWrite = false
            error("disk unavailable")
        }
        stateWrites += state
        this.state = state
    }

    override fun loadSettings(): PersistedSettings = settings

    override fun saveSettings(settings: PersistedSettings) {
        settingsWrites += settings
        this.settings = settings
    }
}

class StateRepositoryTest {

    @Test
    fun startupReadsSynchronouslySoTheFirstFrameHasData() = runTest {
        val stored = AppState(calendars = listOf(Calendar.local(name = "Stored")))
        val repository = StateRepository(scope = this, storage = RecordingStorage(stored))
        assertEquals("Stored", repository.state.calendar.name)
    }

    @Test
    fun aBurstOfEditsUpdatesMemoryImmediatelyAndCoalescesTheDiskWrite() = runTest {
        val storage = RecordingStorage()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = StateRepository(scope = this, storage = storage, ioDispatcher = dispatcher)
        val first = AppState(calendars = listOf(Calendar.local(name = "First")))
        val last = AppState(calendars = listOf(Calendar.local(name = "Last")))

        repository.persist(first)
        repository.persist(last)

        // Compose sees every change at once; the disk waits for the injected dispatcher.
        assertEquals("Last", repository.state.calendar.name)
        assertEquals(0, storage.stateWrites.size)
        advanceUntilIdle()
        assertEquals(1, storage.stateWrites.size)
        assertEquals("Last", storage.state.calendar.name)
    }

    @Test
    fun awaitingIdleWaitsForTheQueuedWrite() = runTest {
        val storage = RecordingStorage()
        val repository = StateRepository(
            scope = this,
            storage = storage,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        val state = AppState(calendars = listOf(Calendar.local(name = "Saved")))
        repository.persist(state)
        repository.awaitIdle()
        assertEquals("Saved", storage.state.calendar.name)
    }

    @Test
    fun aFailedWriteKeepsTheInMemoryStateAndLaterWritesStillLand() = runTest {
        val storage = RecordingStorage()
        val failures = mutableListOf<Throwable>()
        val repository = StateRepository(
            scope = this,
            storage = storage,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            onWriteFailure = { failures += it },
        )
        val first = AppState(calendars = listOf(Calendar.local(name = "Unsaved")))
        val second = AppState(calendars = listOf(Calendar.local(name = "Saved")))

        storage.failNextStateWrite = true
        repository.persist(first)
        advanceUntilIdle()
        assertEquals("Unsaved", repository.state.calendar.name)
        assertEquals(1, failures.size)
        assertTrue(storage.stateWrites.isEmpty())

        repository.persist(second)
        advanceUntilIdle()
        assertEquals("Saved", repository.state.calendar.name)
        assertEquals("Saved", storage.state.calendar.name)
        assertEquals(1, storage.stateWrites.size)
    }

    @Test
    fun settingsWritesUseTheSameSerializedQueueAndCoalesce() = runTest {
        val storage = RecordingStorage()
        val repository = StateRepository(
            scope = this,
            storage = storage,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        repository.saveSettings(PersistedSettings(language = "zh"))
        repository.saveSettings(PersistedSettings(language = "en"))
        advanceUntilIdle()
        assertEquals(1, storage.settingsWrites.size)
        assertEquals("en", storage.settingsWrites.single().language)
    }

    @Test
    fun recordsWithoutAnIdAreIdentifiedBeforeMemoryAndDiskSeeThem() = runTest {
        val storage = RecordingStorage()
        val repository = StateRepository(
            scope = this,
            storage = storage,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            idProvider = { "fixed-id" },
        )
        val anonymous = Event(
            id = null,
            title = "Anonymous",
            start = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
        )
        repository.persist(AppState(calendars = listOf(Calendar.local().copy(events = listOf(anonymous)))))

        // The same identified state must be visible to Compose and passed to the writer; generating
        // the id again inside storage would insert a second row for the same event.
        val stored = repository.state.events.single()
        assertEquals("fixed-id", stored.id)
        advanceUntilIdle()
        assertEquals("fixed-id", storage.state.events.single().id)
    }
}
