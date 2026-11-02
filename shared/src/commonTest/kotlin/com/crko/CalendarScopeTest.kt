package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarSubscription
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.LocalRecordMeta
import com.crko.domain.PersistedSettings
import com.crko.domain.StageFiveAppState
import com.crko.domain.legacyEvent
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.StateStorage
import com.crko.storage.SubscriptionHttp
import com.crko.storage.decodeStateWithReport
import com.crko.state.AppStateHolder
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScopeStorage(var state: AppState = AppState()) : StateStorage {
    override fun loadState() = state
    override fun saveState(state: AppState) {
        this.state = state
    }

    override fun loadSettings() = PersistedSettings()
    override fun saveSettings(settings: PersistedSettings) = Unit
}

private class ScopeHttp(private val handler: (HttpRequest) -> HttpResponse) : SubscriptionHttp {
    var respond: (HttpRequest) -> HttpResponse = handler

    override suspend fun send(request: HttpRequest): HttpResponse = respond(request)
}

private fun scopeFeed(vararg events: Pair<String, String>, stamp: String = "20260101T000000Z"): String =
    buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//feed//EN\r\n")
        events.forEach { (uid, summary) ->
            append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\n")
            append("DTSTART:20260301T100000Z\r\nDTEND:20260301T110000Z\r\n")
            append("SUMMARY:$summary\r\nEND:VEVENT\r\n")
        }
        append("END:VCALENDAR\r\n")
    }

private fun AppState.calendar(id: String): Calendar = calendars.single { it.id == id }

class CalendarScopeTest {

    @Test
    fun theSameUidInTwoCalendarsDoesNotOverwriteTheOtherCalendar() {
        val sharedUid = "shared@example.com"
        val remote = legacyEvent(
            id = "remote-1",
            title = "Remote",
            date = "2026-10-27",
            startMinutes = 540,
            endMinutes = 600,
            icsUid = sharedUid,
            dtStamp = "2026-10-01T00:00:00Z",
        )
        val local = legacyEvent(
            id = "local-1",
            title = "Local",
            date = "2026-10-27",
            startMinutes = 540,
            endMinutes = 600,
            icsUid = sharedUid,
            dtStamp = "2026-10-01T00:00:00Z",
        )
        val storage = ScopeStorage(
            AppState(
                calendars = listOf(
                    Calendar.local().copy(events = listOf(local)),
                    Calendar(id = "work", name = "Work").copy(events = listOf(remote)),
                ),
            ),
        )
        var ids = 0
        val holder = AppStateHolder(
            scope = TestScope(StandardTestDispatcher()),
            initialSystemLocale = "en-US",
            idProvider = { "new-${ids++}" },
            storage = storage,
        )

        val updated = local.copy(title = "Local updated", sequence = 1, dtStamp = "2026-10-02T00:00:00Z")
        assertEquals(1, holder.importEvents(listOf(updated.copy(id = null))))

        assertEquals("Local updated", holder.appState.calendar("local").events.single().title)
        assertEquals("Remote", holder.appState.calendar("work").events.single().title)
        assertEquals(2, holder.appState.events.size)
    }

    @Test
    fun syncingOneSubscriptionNeverTouchesAnotherCalendarWithTheSameUid() = runTest {
        val localEvent = legacyEvent(
            id = "local-1",
            title = "Mine",
            date = "2026-03-01",
            startMinutes = 600,
            endMinutes = 660,
            icsUid = "shared@example.com",
            dtStamp = "2026-01-01T00:00:00Z",
        )
        val storage = ScopeStorage(
            AppState(calendars = listOf(Calendar.local().copy(events = listOf(localEvent)))),
        )
        val http = ScopeHttp { HttpResponse(200, emptyMap(), scopeFeed("shared@example.com" to "From feed").encodeToByteArray()) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        var ids = 0
        val holder = AppStateHolder(
            scope = scope,
            initialSystemLocale = "en-US",
            idProvider = { "id-${ids++}" },
            storage = storage,
            subscriptionHttp = http,
            parseDispatcher = scope.coroutineContext[ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
        )
        val subscription = assertNotNull(holder.addSubscription("Feed", "webcal://example.com/cal.ics"))
        holder.syncSubscription(subscription.id)
        scope.advanceUntilIdle()

        val feedCalendar = holder.appState.calendar(subscription.id)
        assertEquals("From feed", feedCalendar.events.single().title)
        assertEquals("Mine", holder.appState.calendar("local").events.single().title)
        assertEquals("Mine", holder.appState.events.single { it.id == "local-1" }.title)

        // The feed moves on; only its own calendar follows the update.
        http.respond = {
            HttpResponse(
                200,
                mapOf("etag" to "\"v2\""),
                scopeFeed("shared@example.com" to "From feed v2", stamp = "20260102T000000Z").encodeToByteArray(),
            )
        }
        holder.syncSubscription(subscription.id)
        scope.advanceUntilIdle()

        assertEquals("From feed v2", holder.appState.calendar(subscription.id).events.single().title)
        assertEquals("Mine", holder.appState.calendar("local").events.single().title)
    }

    @Test
    fun removingASubscriptionDeletesOnlyItsOwnCalendar() = runTest {
        val localEvent = legacyEvent(
            id = "local-1",
            title = "Mine",
            date = "2026-03-01",
            startMinutes = 600,
            endMinutes = 660,
            icsUid = "mine@example.com",
        )
        val storage = ScopeStorage(
            AppState(calendars = listOf(Calendar.local().copy(events = listOf(localEvent)))),
        )
        val http = ScopeHttp {
            HttpResponse(200, emptyMap(), scopeFeed("feed@example.com" to "Feed event").encodeToByteArray())
        }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val holder = AppStateHolder(
            scope = scope,
            initialSystemLocale = "en-US",
            idProvider = { "sub-1" },
            storage = storage,
            subscriptionHttp = http,
            parseDispatcher = scope.coroutineContext[ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
        )
        val subscription = assertNotNull(holder.addSubscription("Feed", "https://example.com/cal.ics"))
        holder.syncSubscription(subscription.id)
        scope.advanceUntilIdle()
        assertEquals(2, holder.appState.events.size)

        holder.removeSubscription(subscription.id)

        assertNull(holder.appState.calendars.firstOrNull { it.id == subscription.id })
        assertEquals(listOf("Mine"), holder.appState.events.map { it.title })
        assertNotNull(holder.appState.calendars.firstOrNull { it.isLocal })
    }

    @Test
    fun aStageFiveFlatPayloadMigratesIntoCalendars() {
        val local = legacyEvent(
            id = "local-1",
            title = "Local",
            date = "2026-10-27",
            startMinutes = 540,
            endMinutes = 600,
            icsUid = "local@example.com",
        )
        val remote = legacyEvent(
            id = "remote-1",
            title = "Remote",
            date = "2026-10-28",
            startMinutes = 600,
            endMinutes = 660,
            icsUid = "remote@example.com",
        ).copy(local = LocalRecordMeta(importSource = "sub:s1"))
        val subscription = CalendarSubscription(id = "s1", name = "Feed", url = "https://example.com/cal.ics")
        val flat = StageFiveAppState(
            events = listOf(local, remote),
            subscriptions = listOf(subscription),
        )
        val payload = AppJson.encodeToString(StageFiveAppState.serializer(), flat)
            .replaceFirst("{", "{\"schemaVersion\":5,")

        val decoded = decodeStateWithReport(payload)

        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(CurrentSchemaVersion, decoded.state.schemaVersion)
        assertEquals(2, decoded.state.calendars.size)
        val localCalendar = decoded.state.localCalendar
        assertNotNull(localCalendar)
        assertEquals(listOf("Local"), localCalendar.events.map { it.title })
        assertEquals(listOf("Remote"), decoded.state.calendar("s1").events.map { it.title })
        assertEquals("Feed", decoded.state.calendar("s1").name)
        assertEquals(subscription.url, decoded.state.calendar("s1").syncState?.url)
    }
}
