package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarMetadata
import com.crko.domain.CalendarSubscription
import com.crko.domain.CalendarTimeZone
import com.crko.domain.IcsTimeValue
import com.crko.domain.LocalCalendarId
import com.crko.domain.PersistedSettings
import com.crko.domain.icsTimeZoneConflicts
import com.crko.domain.icsTimeZoneDefinition
import com.crko.domain.instantOrNull
import com.crko.domain.registerIcsTimeZones
import com.crko.domain.replaceIcsTimeZones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.StateStorage
import com.crko.storage.SubscriptionHttp
import com.crko.state.AppStateHolder
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
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
import kotlin.time.Instant

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

/** A raw `VTIMEZONE` block for a zone the platform does not know, at a fixed [offset]. */
private fun zoneText(tzid: String, offset: String): String = """
    BEGIN:VTIMEZONE
    TZID:$tzid
    BEGIN:STANDARD
    DTSTART:19700101T000000
    TZOFFSETFROM:$offset
    TZOFFSETTO:$offset
    END:STANDARD
    END:VTIMEZONE
""".trimIndent().trimEnd()

/** [zoneText] as CRLF lines, wrapped in a one-event feed. */
private fun zoneFeed(tzid: String, offset: String, uid: String): String = buildString {
    append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//feed//EN\r\n")
    zoneText(tzid, offset).split("\n").forEach { append(it.trim()).append("\r\n") }
    append("BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\n")
    append("DTSTART;TZID=$tzid:20260301T100000\r\nSUMMARY:Feed event\r\nEND:VEVENT\r\n")
    append("END:VCALENDAR\r\n")
}

/** A `VTIMEZONE` whose observances expand yearly, so offset lookups grow the transition cache. */
private fun dstZoneText(tzid: String, offset: String): String = """
    BEGIN:VTIMEZONE
    TZID:$tzid
    BEGIN:STANDARD
    DTSTART:19701025T020000
    RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
    TZOFFSETFROM:$offset
    TZOFFSETTO:+0000
    END:STANDARD
    BEGIN:DAYLIGHT
    DTSTART:19700329T010000
    RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
    TZOFFSETFROM:+0000
    TZOFFSETTO:$offset
    END:DAYLIGHT
    END:VTIMEZONE
""".trimIndent().trimEnd()

class CalendarScopeTest {

    @Test
    fun theSameUidInTwoCalendarsDoesNotOverwriteTheOtherCalendar() {
        val sharedUid = "shared@example.com"
        val remote = testEvent(
            id = "remote-1",
            title = "Remote",
            date = "2026-10-27",
            startMinutes = 540,
            endMinutes = 600,
            icsUid = sharedUid,
            dtStamp = "2026-10-01T00:00:00Z",
        )
        val local = testEvent(
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
        val localEvent = testEvent(
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
        val localEvent = testEvent(
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
    fun twoCalendarsNamingTheSameZoneDoNotRedefineEachOther() {
        val east = zoneText("Shared/Name", "+0300")
        val west = zoneText("Shared/Name", "-0500")
        val storage = ScopeStorage(
            AppState(
                calendars = listOf(
                    Calendar.local().copy(timeZones = listOf(CalendarTimeZone("Shared/Name", east))),
                    Calendar(id = "west", name = "West").copy(timeZones = listOf(CalendarTimeZone("Shared/Name", west))),
                ),
            ),
        )
        AppStateHolder(
            scope = TestScope(StandardTestDispatcher()),
            initialSystemLocale = "en-US",
            idProvider = { "unused" },
            storage = storage,
        )

        // Each calendar's own definition stays readable for its own export.
        assertEquals(east, icsTimeZoneDefinition("Shared/Name", calendarId = LocalCalendarId))
        assertEquals(west, icsTimeZoneDefinition("Shared/Name", calendarId = "west"))
        assertTrue("Shared/Name" in icsTimeZoneConflicts(), icsTimeZoneConflicts().toString())
        // A reference whose calendar is not known resolves through the first declaring one — the state
        // order — so loading the second calendar no longer moves the first calendar's events.
        val instant = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Shared/Name")
            .instantOrNull(TimeZone.UTC)
        assertEquals(Instant.parse("2026-10-27T06:00:00Z"), instant)
    }

    @Test
    fun aDeletedSubscriptionZoneStopsResolving() = runTest {
        val storage = ScopeStorage(AppState(calendars = listOf(Calendar.local())))
        val http = ScopeHttp {
            HttpResponse(200, emptyMap(), zoneFeed("Gone/After", "+0300", "gone@example.com").encodeToByteArray())
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
        assertNotNull(icsTimeZoneDefinition("Gone/After"))

        holder.removeSubscription(subscription.id)

        // The lookup cache is a derived index of the stored calendars: a definition no calendar
        // declares any more must not keep steering later imports of the same zone name.
        assertNull(icsTimeZoneDefinition("Gone/After"))
        assertTrue(icsTimeZoneConflicts().isEmpty(), icsTimeZoneConflicts().toString())
    }

    @Test
    fun concurrentZoneLookupsSurviveARebuiltCache() {
        // Subscription parses and exports run on Dispatchers.Default while the main thread persists;
        // the lookup cache used to be a shared mutable map (and a lazily grown transition list) that a
        // rebuild could tear out from under an iterating reader.
        val first = dstZoneText("Race/Zone", "+0300")
        val second = dstZoneText("Race/Zone", "-0500")
        val wide = (1..60).map { index ->
            Calendar(id = "c$index", name = "Cal $index")
                .copy(timeZones = listOf(CalendarTimeZone("Race/Zone", if (index % 2 == 0) second else first)))
        }
        val value = IcsTimeValue.Zoned(LocalDateTime(2026, 7, 1, 12, 0), "Race/Zone")
        replaceIcsTimeZones(wide)

        runBlocking {
            coroutineScope {
                val readers = List(3) {
                    async(Dispatchers.Default) {
                        repeat(3000) {
                            icsTimeZoneDefinition("Race/Zone")
                            value.instantOrNull(TimeZone.UTC)
                        }
                    }
                }
                val writer = async(Dispatchers.Default) {
                    repeat(2000) { round ->
                        replaceIcsTimeZones(if (round % 2 == 0) wide.take(30) else wide)
                        registerIcsTimeZones(listOf(CalendarTimeZone("Preview/Zone", first)), "preview")
                    }
                }
                (readers + writer).awaitAll()
            }
        }

        // Every snapshot is a complete map: after the last write the definitions still resolve, and
        // the yearly rule still yields the right offset for a July instant.
        replaceIcsTimeZones(wide)
        assertNotNull(icsTimeZoneDefinition("Race/Zone"))
        assertEquals(
            kotlin.time.Instant.parse("2026-07-01T09:00:00Z"),
            value.instantOrNull(TimeZone.UTC),
        )
        replaceIcsTimeZones(emptyList())
    }
}
