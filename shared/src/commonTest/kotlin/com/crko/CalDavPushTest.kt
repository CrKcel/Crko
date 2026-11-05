package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarSyncState
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.PersistedSettings
import com.crko.domain.RecordSyncMeta
import com.crko.sync.xmlEscape
import com.crko.state.AppStateHolder
import com.crko.state.EditScope
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.MemoryOutboxStore
import com.crko.storage.OutboxStore
import com.crko.storage.StateStorage
import com.crko.storage.SubscriptionHttp
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/** Storage fake seeded with the calendar layout a push test needs. */
private class PushStorage(initial: AppState) : StateStorage {
    override val outbox: OutboxStore = MemoryOutboxStore()
    var state = initial
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

/** Fake sender that records requests and answers from the current handler. */
private class PushFakeHttp(initial: (HttpRequest) -> HttpResponse) : SubscriptionHttp {
    var handler: (HttpRequest) -> HttpResponse = initial
    val calls = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        calls += request
        return handler(request)
    }
}

private fun utcStart() = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-03-01T10:00:00Z"))

private fun utcEnd() = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-03-01T11:00:00Z"))

private fun icsOf(uid: String, title: String, sequence: Int, stamp: String = "20260101T000000Z") = buildString {
    append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//srv//EN\r\n")
    append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:$stamp\r\nSEQUENCE:$sequence\r\n")
    append("DTSTART:20260301T100000Z\r\nDTEND:20260301T110000Z\r\nSUMMARY:$title\r\nEND:VEVENT\r\n")
    append("END:VCALENDAR\r\n")
}

/** A calendar-query answer carrying one resource; the shape the pull expects after a push. */
private fun multistatusOf(href: String, etag: String, ics: String): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n")
    append("<d:multistatus xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\">\r\n")
    append("<d:response>\r\n<d:href>").append(xmlEscape(href)).append("</d:href>\r\n")
    append("<d:propstat>\r\n<d:prop>\r\n<d:getetag>").append(xmlEscape(etag)).append("</d:getetag>\r\n")
    append("<c:calendar-data><![CDATA[").append(ics).append("]]></c:calendar-data>\r\n")
    append("</d:prop>\r\n<d:status>HTTP/1.1 200 OK</d:status>\r\n</d:propstat>\r\n</d:response>\r\n")
    append("</d:multistatus>\r\n")
}

private fun holder(scope: TestScope, storage: PushStorage, http: PushFakeHttp): AppStateHolder = AppStateHolder(
    scope = scope,
    initialSystemLocale = "en-US",
    idProvider = { "id-0" },
    storage = storage,
    subscriptionHttp = http,
    parseDispatcher = scope.coroutineContext[ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher,
)

class CalDavPushTest {

    @Test
    fun aNewEventUploadsAndStampsTheRecord() = runTest {
        val storage = PushStorage(
            AppState(calendars = listOf(Calendar.local(), Calendar(id = "cal1", name = "S", syncState = CalendarSyncState(url = "https://example.com/cal/", writable = true)))),
        )
        var pushedIcs: String? = null
        val http = PushFakeHttp { request ->
            when (request.method) {
                "PUT" -> {
                    pushedIcs = request.body.decodeToString()
                    HttpResponse(201, mapOf("etag" to "\"e1\""), ByteArray(0))
                }
                "REPORT" -> HttpResponse(
                    207,
                    mapOf("content-type" to "application/xml"),
                    multistatusOf("/cal/id-0@crko.ics", "\"e1\"", pushedIcs ?: "").encodeToByteArray(),
                )
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val state = holder(TestScope(StandardTestDispatcher(testScheduler)), storage, http)

        val result = state.saveEvent(
            Event(id = null, title = "Local", start = utcStart(), end = utcEnd()),
            calendarId = "cal1",
        )
        assertEquals(com.crko.state.WriteResult.Success, result)
        advanceUntilIdle()

        val put = http.calls.filter { it.method == "PUT" }.single()
        assertEquals("https://example.com/cal/id-0@crko.ics", put.url)
        val body = pushedIcs.orEmpty()
        assertTrue("UID:id-0@crko" in body, "upload must carry the assigned UID: $body")
        // A first upload has no etag yet, so no precondition may be sent.
        assertNull(put.headers["If-Match"])

        val stored = storage.state.calendars.last().events.single()
        assertEquals("\"e1\"", stored.sync?.etag)
        assertEquals("https://example.com/cal/id-0@crko.ics", stored.sync?.href)
        assertTrue(storage.outbox.entries().isEmpty())
    }

    @Test
    fun aConflictTheServerWinsReplacesTheLocalRecord() = runTest {
        val stored = Event(
            id = "e1",
            title = "Local",
            start = utcStart(),
            end = utcEnd(),
            icsUid = "a@x",
            sequence = 0,
            dtStamp = "20260101T000000Z",
            sync = RecordSyncMeta("https://example.com/cal/a.ics", "\"v1\""),
        )
        val storage = PushStorage(
            AppState(calendars = listOf(Calendar.local(), Calendar(id = "cal1", name = "S", syncState = CalendarSyncState(url = "https://example.com/cal/", writable = true), events = listOf(stored)))),
        )
        val serverIcs = icsOf("a@x", "Server", sequence = 2, stamp = "20260201T000000Z")
        val puts = mutableListOf<HttpRequest>()
        val http = PushFakeHttp { request ->
            when (request.method) {
                "PUT" -> {
                    puts += request
                    HttpResponse(412, emptyMap(), ByteArray(0))
                }
                "GET" -> HttpResponse(200, mapOf("etag" to "\"v2\""), serverIcs.encodeToByteArray())
                "REPORT" -> HttpResponse(207, mapOf("content-type" to "application/xml"), multistatusOf("/cal/a.ics", "\"v2\"", serverIcs).encodeToByteArray())
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val state = holder(TestScope(StandardTestDispatcher(testScheduler)), storage, http)

        state.saveEvent(
            Event(id = "e1", title = "Local edited", start = utcStart(), end = utcEnd(), icsUid = "a@x", sequence = 0, dtStamp = "20260101T000000Z"),
            calendarId = "cal1",
        )
        advanceUntilIdle()

        // One refused upload, then the server copy replaces the local one: no second PUT.
        assertEquals(1, puts.size)
        assertEquals("\"v1\"", puts.single().headers["If-Match"])
        val record = storage.state.calendars.last().events.single()
        assertEquals("Server", record.title)
        assertEquals(2, record.sequence)
        assertEquals("\"v2\"", record.sync?.etag)
        assertTrue(storage.outbox.entries().isEmpty())
    }

    @Test
    fun aConflictTheLocalWinsReUploadsWithTheFreshEtag() = runTest {
        val stored = Event(
            id = "e1",
            title = "Local",
            start = utcStart(),
            end = utcEnd(),
            icsUid = "a@x",
            sequence = 3,
            dtStamp = "20260101T000000Z",
            sync = RecordSyncMeta("https://example.com/cal/a.ics", "\"v1\""),
        )
        val storage = PushStorage(
            AppState(calendars = listOf(Calendar.local(), Calendar(id = "cal1", name = "S", syncState = CalendarSyncState(url = "https://example.com/cal/", writable = true), events = listOf(stored)))),
        )
        val serverIcs = icsOf("a@x", "Server", sequence = 0, stamp = "20260101T000000Z")
        val puts = mutableListOf<HttpRequest>()
        val http = PushFakeHttp { request ->
            when (request.method) {
                "PUT" -> {
                    puts += request
                    if (puts.size == 1) HttpResponse(412, emptyMap(), ByteArray(0)) else HttpResponse(200, mapOf("etag" to "\"e2\""), ByteArray(0))
                }
                "GET" -> HttpResponse(200, mapOf("etag" to "\"v0\""), serverIcs.encodeToByteArray())
                "REPORT" -> HttpResponse(207, mapOf("content-type" to "application/xml"), multistatusOf("/cal/a.ics", "\"e2\"", icsOf("a@x", "Local", sequence = 4)).encodeToByteArray())
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val state = holder(TestScope(StandardTestDispatcher(testScheduler)), storage, http)

        state.saveEvent(
            Event(id = "e1", title = "Local edited", start = utcStart(), end = utcEnd(), icsUid = "a@x", sequence = 3, dtStamp = "20260101T000000Z"),
            calendarId = "cal1",
        )
        advanceUntilIdle()

        assertEquals(2, puts.size)
        assertEquals("\"v1\"", puts[0].headers["If-Match"])
        // The re-upload carries the etag the conflict read returned.
        assertEquals("\"v0\"", puts[1].headers["If-Match"])
        val record = storage.state.calendars.last().events.single()
        assertEquals("Local edited", record.title)
        assertEquals("\"e2\"", record.sync?.etag)
        assertTrue(storage.outbox.entries().isEmpty())
    }

    @Test
    fun aDeleteRemovesTheServerResourceAndClearsTheQueue() = runTest {
        val stored = Event(
            id = "e1",
            title = "Local",
            start = utcStart(),
            end = utcEnd(),
            icsUid = "a@x",
            sequence = 0,
            dtStamp = "20260101T000000Z",
            sync = RecordSyncMeta("https://example.com/cal/a.ics", "\"v1\""),
        )
        val storage = PushStorage(
            AppState(calendars = listOf(Calendar.local(), Calendar(id = "cal1", name = "S", syncState = CalendarSyncState(url = "https://example.com/cal/", writable = true, caldav = true), events = listOf(stored)))),
        )
        val deletes = mutableListOf<HttpRequest>()
        val http = PushFakeHttp { request ->
            when (request.method) {
                "DELETE" -> {
                    deletes += request
                    HttpResponse(204, emptyMap(), ByteArray(0))
                }
                "REPORT" -> HttpResponse(207, mapOf("content-type" to "application/xml"), "<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>".encodeToByteArray())
                else -> HttpResponse(404, emptyMap(), ByteArray(0))
            }
        }
        val state = holder(TestScope(StandardTestDispatcher(testScheduler)), storage, http)

        state.deleteEvent(stored, scope = EditScope.AllEvents)
        advanceUntilIdle()

        val delete = deletes.single()
        assertEquals("https://example.com/cal/a.ics", delete.url)
        assertEquals("\"v1\"", delete.headers["If-Match"])
        assertTrue(storage.outbox.entries().isEmpty())
        assertTrue(storage.state.calendars.last().events.isEmpty())
    }

    @Test
    fun aFailedPushStaysQueuedWithBackoff() = runTest {
        val storage = PushStorage(
            AppState(calendars = listOf(Calendar.local(), Calendar(id = "cal1", name = "S", syncState = CalendarSyncState(url = "https://example.com/cal/", writable = true, caldav = true)))),
        )
        val http = PushFakeHttp { _ -> HttpResponse(503, emptyMap(), ByteArray(0)) }
        val state = holder(TestScope(StandardTestDispatcher(testScheduler)), storage, http)

        state.saveEvent(
            Event(id = null, title = "Local", start = utcStart(), end = utcEnd()),
            calendarId = "cal1",
        )
        advanceUntilIdle()

        val entry = assertNotNull(storage.outbox.entries().single())
        assertEquals(1, entry.attempts)
        assertNotNull(entry.nextAttemptMs)
        assertEquals("HTTP 503", entry.lastError)
        // The record itself still carries no server location: nothing was confirmed.
        assertNull(storage.state.calendars.last().events.single().sync?.etag)
    }
}
