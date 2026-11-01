package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.BusyInterval
import com.crko.domain.CalendarSubscription
import com.crko.domain.SubscriptionFailure
import com.crko.domain.SubscriptionFetch
import com.crko.domain.calendarQueryReport
import com.crko.domain.exportCalendar
import com.crko.domain.fetchSubscription
import com.crko.domain.parseMultiStatus
import com.crko.domain.resolveSubscriptionUrl
import com.crko.domain.syncCollectionReport
import com.crko.domain.xmlEscape
import com.crko.state.AppStateHolder
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.StateStorage
import com.crko.storage.SubscriptionHttp
import com.crko.domain.PersistedSettings
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

private class SyncStorage : StateStorage {
    var state = AppState()
    var settings = PersistedSettings()

    override fun loadState() = state
    override fun saveState(state: AppState) {
        this.state = state
    }

    override fun loadSettings() = settings
    override fun saveSettings(settings: PersistedSettings) {
        this.settings = settings
    }
}

/** Fake sender: every request is recorded, and the current handler decides the answer. */
private class FakeHttp(initial: (HttpRequest) -> HttpResponse) : SubscriptionHttp {
    var handler: (HttpRequest) -> HttpResponse = initial
    val calls = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        calls += request
        return handler(request)
    }
}

private fun icsOf(vararg events: Pair<String, String>, stamp: String = "20260101T000000Z"): String = buildString {
    append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//feed//EN\r\n")
    events.forEach { (uid, summary) ->
        append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\n")
        append("DTSTART:20260301T100000Z\r\nDTEND:20260301T110000Z\r\n")
        append("SUMMARY:$summary\r\nEND:VEVENT\r\n")
    }
    append("END:VCALENDAR\r\n")
}

private fun multiStatus(syncToken: String?, vararg entries: String): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
    append("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">\r\n")
    entries.forEach { append(it) }
    syncToken?.let { append("<d:sync-token>${xmlEscape(it)}</d:sync-token>\r\n") }
    append("</d:multistatus>\r\n")
}

private fun davEntry(href: String, etag: String?, ics: String?): String = buildString {
    append("<d:response>\r\n<d:href>$href</d:href>\r\n")
    if (ics != null) {
        append("<d:propstat>\r\n<d:prop>\r\n")
        etag?.let { append("<d:getetag>$it</d:getetag>\r\n") }
        append("<c:calendar-data><![CDATA[$ics]]></c:calendar-data>\r\n")
        append("</d:prop>\r\n<d:status>HTTP/1.1 200 OK</d:status>\r\n</d:propstat>\r\n")
    } else {
        append("<d:propstat>\r\n<d:prop/>\r\n<d:status>HTTP/1.1 404 Not Found</d:status>\r\n</d:propstat>\r\n")
    }
    append("</d:response>\r\n")
}

class SubscriptionSyncTest {

    @Test
    fun webcalUrlsResolveToHttpsAndPlainHttpIsKept() {
        assertEquals("https://example.com/cal.ics", resolveSubscriptionUrl("webcal://example.com/cal.ics"))
        assertEquals("https://example.com/cal.ics", resolveSubscriptionUrl("webcals://example.com/cal.ics"))
        assertEquals("http://example.com/cal.ics", resolveSubscriptionUrl("http://example.com/cal.ics"))
        assertEquals("https://example.com/cal.ics", resolveSubscriptionUrl(" HTTPS://example.com/cal.ics "))
        assertNull(resolveSubscriptionUrl("example.com/cal.ics"))
        assertNull(resolveSubscriptionUrl("ftp://example.com/cal.ics"))
        assertNull(resolveSubscriptionUrl("  "))
    }

    @Test
    fun plainFeedFetchReturnsTheContentAndRemembersTheEntityTag() = runTest {
        val text = icsOf("a@x" to "One")
        val http = FakeHttp { request ->
            assertEquals("GET", request.method)
            assertEquals("https://example.com/cal.ics", request.url)
            HttpResponse(200, mapOf("etag" to "\"v1\""), text.encodeToByteArray())
        }
        val subscription = CalendarSubscription(id = "s1", name = "Feed", url = "webcal://example.com/cal.ics")
        val full = assertNotNull(fetchSubscription(subscription, http) as? SubscriptionFetch.Full)
        assertEquals(text, full.text)
        assertEquals("\"v1\"", full.etag)
    }

    @Test
    fun aSecondFetchEchoesTheEntityTagAnd304MeansUnchanged() = runTest {
        val http = FakeHttp { request ->
            assertTrue("If-None-Match" in request.headers)
            HttpResponse(304, emptyMap(), ByteArray(0))
        }
        val subscription = CalendarSubscription(id = "s1", name = "Feed", url = "https://example.com/cal.ics", etag = "\"v1\"")
        assertEquals(SubscriptionFetch.Unchanged, fetchSubscription(subscription, http))
    }

    @Test
    fun authFailuresAndNonCalendarBodiesMapToTheirOwnFailure() = runTest {
        val unauthorized = FakeHttp { HttpResponse(401, emptyMap(), ByteArray(0)) }
        val fetch1 = fetchSubscription(CalendarSubscription("s", "N", "https://x/cal"), unauthorized)
        assertEquals(SubscriptionFailure.Unauthorized, (fetch1 as? SubscriptionFetch.Failed)?.failure)

        val junk = FakeHttp { HttpResponse(200, emptyMap(), "not a calendar".encodeToByteArray()) }
        val fetch2 = fetchSubscription(CalendarSubscription("s", "N", "https://x/cal"), junk)
        assertEquals(SubscriptionFailure.NotCalendar, (fetch2 as? SubscriptionFetch.Failed)?.failure)

        val broken = FakeHttp { throw IllegalStateException("offline") }
        val fetch3 = fetchSubscription(CalendarSubscription("s", "N", "https://x/cal"), broken)
        assertEquals(SubscriptionFailure.Unreachable, (fetch3 as? SubscriptionFetch.Failed)?.failure)
    }

    @Test
    fun multistatusParsingReadsCalendarDataEntitiesAndDeletions() {
        val ics = icsOf("a@x" to "One")
        val xml = multiStatus(
            "token-2",
            davEntry("/cal/a.ics", "\"1\"", ics),
            davEntry("/cal/gone.ics", null, null),
        )
        val multi = parseMultiStatus(xml)
        assertEquals(listOf(ics), multi.documents)
        assertTrue(multi.deletions)
        assertEquals("token-2", multi.syncToken)
    }

    @Test
    fun characterReferencesInCalendarDataAreResolved() {
        val xml = "<d:multistatus><d:response><d:propstat><d:prop>" +
            "<c:calendar-data>BEGIN:VCALENDAR&#10;SUMMARY:A &amp; B &lt;x&gt;</c:calendar-data>" +
            "</d:prop></d:propstat></d:response></d:multistatus>"
        val text = parseMultiStatus(xml).documents.single()
        assertTrue("A & B <x>" in text)
        assertTrue("&#10;" !in text)
    }

    @Test
    fun reportBodiesRequestEtagsAndCalendarData() {
        assertTrue("<d:getetag/>" in calendarQueryReport())
        assertTrue("<c:calendar-data/>" in calendarQueryReport())
        assertTrue("<d:sync-token>tok</d:sync-token>" in syncCollectionReport("tok"))
    }

    @Test
    fun caldavCollectionIsDiscoveredFromAnXmlAnswer() = runTest {
        val ics = icsOf("a@x" to "One")
        val http = FakeHttp { request ->
            when (request.method) {
                "GET" -> HttpResponse(
                    200,
                    mapOf("content-type" to "text/xml"),
                    "<d:multistatus xmlns:d=\"DAV:\"/>".encodeToByteArray(),
                )
                "REPORT" -> {
                    assertEquals("https://example.com/cal/", request.url)
                    HttpResponse(
                        207,
                        emptyMap(),
                        multiStatus("token-1", davEntry("/cal/a.ics", null, ics)).encodeToByteArray(),
                    )
                }
                else -> HttpResponse(500, emptyMap(), ByteArray(0))
            }
        }
        val full = assertNotNull(
            fetchSubscription(CalendarSubscription("s", "C", "https://example.com/cal/"), http) as? SubscriptionFetch.Full,
        )
        assertEquals(ics, full.text)
        assertEquals("token-1", full.syncToken)
    }

    @Test
    fun caldavIncrementalSyncSendsTheStoredTokenAndFallsBackOnRejection() = runTest {
        val http = FakeHttp { request ->
            when {
                request.method == "REPORT" && "<d:sync-token>" in request.body.decodeToString() ->
                    HttpResponse(415, emptyMap(), ByteArray(0))
                request.method == "REPORT" -> HttpResponse(
                    207,
                    emptyMap(),
                    multiStatus("token-9", davEntry("/cal/a.ics", null, icsOf("a@x" to "One"))).encodeToByteArray(),
                )
                else -> HttpResponse(500, emptyMap(), ByteArray(0))
            }
        }
        val fetch = fetchSubscription(
            CalendarSubscription("s", "C", "https://example.com/cal/", caldav = true, syncToken = "token-1"),
            http,
        )
        val full = assertNotNull(fetch as? SubscriptionFetch.Full)
        assertEquals("token-9", full.syncToken)
    }

    @Test
    fun caldavIncrementalSyncReportsOnlyChangesAndDeletions() = runTest {
        val ics = icsOf("b@x" to "Two")
        val http = FakeHttp { request ->
            assertEquals("REPORT", request.method)
            assertTrue("<d:sync-token>token-1</d:sync-token>" in request.body.decodeToString())
            HttpResponse(
                207,
                emptyMap(),
                multiStatus(
                    "token-2",
                    davEntry("/cal/b.ics", null, ics),
                    davEntry("/cal/gone.ics", null, null),
                ).encodeToByteArray(),
            )
        }
        val changes = assertNotNull(
            fetchSubscription(
                CalendarSubscription("s", "C", "https://example.com/cal/", caldav = true, syncToken = "token-1"),
                http,
            ) as? SubscriptionFetch.Changes,
        )
        assertEquals(ics, changes.text)
        assertEquals("token-2", changes.syncToken)
        assertTrue(changes.deletions)
    }

    @Test
    fun emptyIncrementalReportMeansNothingChanged() = runTest {
        val http = FakeHttp { HttpResponse(207, emptyMap(), multiStatus("token-1").encodeToByteArray()) }
        val fetch = fetchSubscription(
            CalendarSubscription("s", "C", "https://example.com/cal/", caldav = true, syncToken = "token-1"),
            http,
        )
        assertEquals(SubscriptionFetch.Unchanged, fetch)
    }

    @Test
    fun syncingMergesFeedRecordsAndRemovesVanishedOnes() = runTest {
        val storage = SyncStorage()
        var ids = 0
        val http = FakeHttp { HttpResponse(200, mapOf("etag" to "\"v1\""), icsOf("a@x" to "One", "b@x" to "Two").encodeToByteArray()) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val f = AppStateHolder(
            scope = scope,
            initialSystemLocale = "en-US",
            idProvider = { "id-${ids++}" },
            storage = storage,
            subscriptionHttp = http,
            parseDispatcher = scope.coroutineContext[ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
        )
        val subscription = assertNotNull(f.addSubscription("Feed", "webcal://example.com/cal.ics"))
        assertEquals(1, f.appState.subscriptions.size)
        f.syncSubscription(subscription.id)
        scope.advanceUntilIdle()
        assertEquals(2, f.appState.events.size)
        assertEquals("sub:${subscription.id}", f.appState.events.single { it.icsUid == "a@x" }.importSource)

        // The feed drops one record and renames the other; the sync follows the source.
        http.handler = { HttpResponse(200, mapOf("etag" to "\"v2\""), icsOf("a@x" to "One renamed", stamp = "20260102T000000Z").encodeToByteArray()) }
        f.syncSubscription(subscription.id)
        scope.advanceUntilIdle()
        assertEquals(1, f.appState.events.size)
        assertEquals("One renamed", f.appState.events.single().title)
        assertEquals("\"v2\"", f.appState.subscriptions.single().etag)
        assertNotNull(f.appState.subscriptions.single().lastSync)
    }

    @Test
    fun anInvalidUrlIsRejectedWhenAdding() {
        val f = AppStateHolder(
            scope = TestScope(StandardTestDispatcher()),
            initialSystemLocale = "en-US",
            idProvider = { "id-0" },
            storage = SyncStorage(),
        )
        assertNull(f.addSubscription("X", "not-a-url"))
        assertTrue(f.appState.subscriptions.isEmpty())
    }

    @Test
    fun syncFailureIsReportedAndDoesNotTouchTheState() = runTest {
        val http = FakeHttp { HttpResponse(401, emptyMap(), ByteArray(0)) }
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val f = AppStateHolder(
            scope = scope,
            initialSystemLocale = "en-US",
            idProvider = { "id-0" },
            storage = SyncStorage(),
            subscriptionHttp = http,
        )
        val subscription = assertNotNull(f.addSubscription("Feed", "https://example.com/cal.ics"))
        f.syncSubscription(subscription.id)
        scope.advanceUntilIdle()
        assertTrue(f.appState.events.isEmpty())
        // A failed sync does not advance the bookkeeping, so the next due check retries.
        assertNull(f.appState.subscriptions.single().lastSync)
    }

    @Test
    fun subscriptionsDueHonorsTheRefreshInterval() {
        val storage = SyncStorage()
        val overdue = CalendarSubscription("overdue", "Overdue", "https://x/a.ics", refreshMinutes = 15, lastSync = "2026-01-01T00:00:00Z")
        val fresh = CalendarSubscription("fresh", "Fresh", "https://x/b.ics", refreshMinutes = 15, lastSync = "2999-01-01T00:00:00Z")
        val manual = CalendarSubscription("manual", "Manual", "https://x/c.ics")
        val disabled = CalendarSubscription("disabled", "Off", "https://x/d.ics", refreshMinutes = 15, enabled = false)
        storage.state = AppState(subscriptions = listOf(overdue, fresh, manual, disabled))
        val f = AppStateHolder(
            scope = TestScope(StandardTestDispatcher()),
            initialSystemLocale = "en-US",
            storage = storage,
        )
        assertEquals(listOf("overdue"), f.subscriptionsDue().map { it.id })
    }

    @Test
    fun stateSerializationKeepsSubscriptionsAndBusyPeriods() {
        val state = AppState(
            subscriptions = listOf(CalendarSubscription(id = "s1", name = "Feed", url = "webcal://x/cal.ics")),
            busyPeriods = listOf(BusyInterval()),
        )
        val decoded = AppJson.decodeFromString(AppState.serializer(), AppJson.encodeToString(AppState.serializer(), state))
        assertEquals(state, decoded)
        // Exporting a calendar with the extra state still works.
        assertTrue(exportCalendar(emptyList()).contains("BEGIN:VCALENDAR"))
    }
}
