@file:OptIn(ExperimentalCoroutinesApi::class)

package com.crko

import com.crko.state.entry.EntryInbox
import com.crko.state.entry.EntryTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Deep links and notification actions share one inbox, so their shapes must match exactly. */
class EntryInboxTest {
    @Test
    fun knownLinksBecomeEntryTargets() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        val today = async(dispatcher) { EntryInbox.entries.first() }
        yield()
        EntryInbox.offerDeepLink("crko://today")
        assertEquals(EntryTarget.Today, today.await())

        val capture = async(dispatcher) { EntryInbox.entries.first() }
        yield()
        EntryInbox.offerDeepLink("crko://capture/")
        assertEquals(EntryTarget.Capture, capture.await())

        val start = async(dispatcher) { EntryInbox.entries.first() }
        yield()
        EntryInbox.offerDeepLink("crko://start?taskId=task-1")
        assertEquals(EntryTarget.Focus("task-1"), start.await())
    }

    @Test
    fun startWithoutATaskCarriesNone() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val start = async(dispatcher) { EntryInbox.entries.first() }
        yield()
        EntryInbox.offerDeepLink("crko://start")
        assertEquals(EntryTarget.Focus(null), start.await())
        assertNull((start.await() as EntryTarget.Focus).taskId)
    }
}
