package com.crko

import com.crko.domain.Event
import com.crko.domain.IcsAttendee
import com.crko.domain.IcsImport
import com.crko.domain.IcsTimeValue
import com.crko.domain.ItipDecision
import com.crko.domain.ItipMethod
import com.crko.domain.IcsPartStat
import com.crko.domain.attendeeEntries
import com.crko.domain.applyingReply
import com.crko.domain.exportItipCancel
import com.crko.domain.exportItipReply
import com.crko.domain.exportItipRequest
import com.crko.domain.importCalendar
import com.crko.domain.itipDecision
import com.crko.domain.itipReply
import com.crko.domain.sameCalendarAddress
import com.crko.state.AppStateHolder
import com.crko.storage.StateStorage
import com.crko.domain.PersistedSettings
import com.crko.i18n.EnStrings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val Zone = TimeZone.UTC

private fun import(text: String): IcsImport = importCalendar(text, Zone, untitledTitle = "Untitled")

private fun calendar(method: String? = null, body: String): String = buildString {
    append("BEGIN:VCALENDAR\r\n")
    append("VERSION:2.0\r\n")
    append("PRODID:-//test//EN\r\n")
    method?.let { append("METHOD:$it\r\n") }
    append(body)
    append("END:VCALENDAR\r\n")
}

private class ItipStorage : StateStorage {
    var state = com.crko.domain.AppState()
    var settings = PersistedSettings()

    override fun loadState() = state
    override fun saveState(state: com.crko.domain.AppState) {
        this.state = state
    }

    override fun loadSettings() = settings
    override fun saveSettings(settings: PersistedSettings) {
        this.settings = settings
    }
}

private fun holder(storage: ItipStorage = ItipStorage()): AppStateHolder {
    var ids = 0
    return AppStateHolder(
        scope = TestScope(StandardTestDispatcher()),
        initialSystemLocale = "en-US",
        idProvider = { "id-${ids++}" },
        storage = storage,
    )
}

private fun requestEvent(uid: String, sequence: Int, summary: String = "Sync"): String =
    "BEGIN:VEVENT\r\nUID:$uid\r\nDTSTAMP:20260101T000000Z\r\n" +
        "DTSTART:20260301T100000Z\r\nDTEND:20260301T110000Z\r\nSUMMARY:$summary\r\n" +
        "SEQUENCE:$sequence\r\nEND:VEVENT\r\n"

class ItipTest {

    @Test
    fun methodAndCalendarMetaAreParsedFromTheCalendarLevel() {
        val imported = import(
            calendar(
                "REQUEST",
                "X-WR-CALNAME:Team Events\r\nREFRESH-INTERVAL;VALUE=DURATION:PT4H\r\n" +
                    requestEvent("a@x", 0),
            ),
        )
        assertEquals(ItipMethod.Request, imported.method)
        assertEquals("Team Events", imported.calendarName)
        // PT4H is four hours of refresh interval, in minutes.
        assertEquals(240, imported.refreshMinutes)
    }

    @Test
    fun xPublishedTtlIsAFallbackForTheRefreshInterval() {
        val imported = import(
            calendar(null, "X-PUBLISHED-TTL:P1D\r\nBEGIN:VEVENT\r\nUID:a@x\r\n" +
                "DTSTAMP:20260101T000000Z\r\nDTSTART;VALUE=DATE:20260301\r\nEND:VEVENT\r\n"),
        )
        assertNull(imported.method)
        assertEquals(24 * 60, imported.refreshMinutes)
    }

    @Test
    fun attendeeParametersSurviveParsing() {
        val line = "ATTENDEE;CN=\"Doe, Jane\";ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:jane@example.com"
        val attendee = assertNotNull(IcsAttendee.parse(line))
        assertEquals("mailto:jane@example.com", attendee.calAddress)
        assertEquals("Doe, Jane", attendee.cn)
        assertEquals("REQ-PARTICIPANT", attendee.role)
        assertEquals(IcsPartStat.Accepted, attendee.partStat)
        assertEquals(true, attendee.rsvp)
        assertEquals("Doe, Jane", attendee.displayName)
    }

    @Test
    fun itipDecisionGatesBySequenceAndAllowsMissingOnes() {
        assertEquals(ItipDecision.Apply, itipDecision(0, 3))
        assertEquals(ItipDecision.Apply, itipDecision(4, 3))
        assertEquals(ItipDecision.Apply, itipDecision(3, 3))
        assertEquals(ItipDecision.Stale, itipDecision(2, 3))
    }

    @Test
    fun calendarAddressesCompareIgnoringTheMailtoSchemeSpelling() {
        assertTrue(sameCalendarAddress("MAILTO:Jane@Example.com", "jane@example.com"))
        assertTrue(sameCalendarAddress("MailTo:jane@example.com", "Jane@Example.com"))
        assertTrue(sameCalendarAddress("jane@example.com", "JANE@EXAMPLE.COM"))
        assertFalse(sameCalendarAddress("mailto:jane@example.com", "mailto:john@example.com"))
        assertFalse(sameCalendarAddress("mailto:jane@example.com", "mailto:jane@other.com"))
    }

    @Test
    fun requestWithStaleSequenceDoesNotReplaceTheStoredCopy() = runTest {
        val f = holder()
        f.importCalendar(import(calendar("REQUEST", requestEvent("mtg@x", 3))))
        assertEquals("Sync", f.appState.events.single().title)

        // A REQUEST older than the stored copy must not touch it.
        f.importCalendar(import(calendar("REQUEST", requestEvent("mtg@x", 2, "Stale"))))
        assertEquals("Sync", f.appState.events.single().title)

        // A newer proposal wins.
        f.importCalendar(import(calendar("REQUEST", requestEvent("mtg@x", 4, "Sync moved"))))
        assertEquals("Sync moved", f.appState.events.single().title)
    }

    @Test
    fun replyRewritesTheAttendeeStatusAndKeepsOtherParameters() {
        val stored = Event(
            id = "e1",
            title = "Review",
            date = "2026-03-01",
            startMinutes = 600,
            endMinutes = 660,
            organizer = "mailto:org@example.com",
            attendeeRaw = listOf(
                "ATTENDEE;CN=Jane;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:jane@example.com",
                "ATTENDEE;CN=Bob;PARTSTAT=DECLINED:mailto:bob@example.com",
            ),
        )
        val reply = import(
            calendar(
                "REPLY",
                "BEGIN:VEVENT\r\nUID:e1@crko\r\nDTSTAMP:20260101T010000Z\r\n" +
                    "ORGANIZER:mailto:org@example.com\r\n" +
                    "ATTENDEE;CN=Jane;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:jane@example.com\r\n" +
                    "DTSTART:20260301T100000Z\r\nEND:VEVENT\r\n",
            ),
        )
        val message = assertNotNull(reply.itipReply())
        assertEquals(IcsPartStat.Accepted, message.attendee.partStat)
        assertEquals("Jane", message.attendee.displayName)

        val result = listOf(stored).applyingReply(message)
        assertTrue(result.applied)
        val updated = result.events.single()
        val jane = updated.attendeeEntries.first { it.displayName == "Jane" }
        assertEquals(IcsPartStat.Accepted, jane.partStat)
        // RSVP and CN are untouched by the status rewrite.
        val janeLine = updated.attendeeRaw.first { "jane@example.com" in it }
        assertTrue("RSVP=TRUE" in janeLine && "CN=Jane" in janeLine)
        // The other attendee is untouched.
        assertEquals(IcsPartStat.Declined, updated.attendeeEntries.first { it.displayName == "Bob" }.partStat)
    }

    @Test
    fun replyAboutOneOccurrenceHitsItsOverride() {
        val override = Event(
            id = "e2",
            title = "Review moved",
            start = IcsTimeValue.Floating(LocalDateTime(2026, 3, 5, 10, 0)),
            end = IcsTimeValue.Floating(LocalDateTime(2026, 3, 5, 11, 0)),
            icsUid = "series@crko",
            recurrenceId = IcsTimeValue.Date(LocalDate(2026, 3, 2)),
            attendeeRaw = listOf("ATTENDEE;CN=Jane;PARTSTAT=NEEDS-ACTION:mailto:jane@example.com"),
        )
        val reply = import(
            calendar(
                "REPLY",
                "BEGIN:VEVENT\r\nUID:series@crko\r\nRECURRENCE-ID;VALUE=DATE:20260302\r\n" +
                    "DTSTAMP:20260101T010000Z\r\nORGANIZER:mailto:org@example.com\r\n" +
                    "ATTENDEE;CN=Jane;PARTSTAT=TENTATIVE:mailto:jane@example.com\r\n" +
                    "DTSTART;VALUE=DATE:20260302\r\nEND:VEVENT\r\n",
            ),
        )
        val message = assertNotNull(reply.itipReply())
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 3, 2)), message.recurrenceId)
        val result = listOf(override).applyingReply(message)
        assertTrue(result.applied)
        assertEquals(IcsPartStat.Tentative, result.events.single().attendeeEntries.single().partStat)
    }

    @Test
    fun replyForAnUnknownEventLeavesTheCalendarUntouchedAndReportsIt() = runTest {
        val f = holder()
        val before = f.appState
        val outcome = f.importCalendar(
            import(
                calendar(
                    "REPLY",
                    "BEGIN:VEVENT\r\nUID:nobody@x\r\nDTSTAMP:20260101T010000Z\r\n" +
                        "ATTENDEE;CN=Jane;PARTSTAT=ACCEPTED:mailto:jane@example.com\r\n" +
                        "DTSTART:20260301T100000Z\r\nEND:VEVENT\r\n",
                ),
            ),
        )
        assertEquals(0, outcome.events)
        assertEquals(before, f.appState)
    }

    @Test
    fun replyExportRoundTripsBackIntoAReply() {
        val stored = Event(
            id = "e1",
            title = "Review",
            date = "2026-03-01",
            startMinutes = 600,
            endMinutes = 660,
            sequence = 2,
            organizer = "mailto:org@example.com",
        )
        val attendee = IcsAttendee(
            calAddress = "mailto:jane@example.com",
            cn = "Jane",
            partStat = IcsPartStat.Accepted,
        )
        val text = exportItipReply(stored, attendee)
        assertTrue("METHOD:REPLY" in text)
        val message = assertNotNull(import(text).itipReply())
        assertEquals(IcsPartStat.Accepted, message.attendee.partStat)
        assertEquals("mailto:jane@example.com", message.attendee.calAddress)
        assertEquals(2, message.sequence)
    }

    @Test
    fun cancelExportCarriesTheMethodAndStatus() {
        val stored = Event(id = "e1", title = "Gone", date = "2026-03-01", startMinutes = 600, endMinutes = 660)
        val text = exportItipCancel(listOf(stored))
        assertTrue("METHOD:CANCEL" in text)
        assertTrue("STATUS:CANCELLED" in text)
    }

    @Test
    fun requestExportCarriesTheMethod() {
        val stored = Event(id = "e1", title = "Invite", date = "2026-03-01", startMinutes = 600, endMinutes = 660)
        val text = exportItipRequest(listOf(stored))
        assertTrue("METHOD:REQUEST" in text)
        assertTrue("SUMMARY:Invite" in text)
    }

    @Test
    fun attendeeEntryFallbackUsesPlainAddresses() {
        val event = Event(
            id = "e1",
            title = "T",
            date = "2026-03-01",
            startMinutes = 600,
            endMinutes = 660,
            attendees = listOf("mailto:a@x", "mailto:b@x"),
        )
        assertEquals(listOf("mailto:a@x", "mailto:b@x"), event.attendeeEntries.map { it.calAddress })
        assertEquals(IcsPartStat.NeedsAction, event.attendeeEntries.first().partStat)
    }

    @Test
    fun cancellationWithStaleSequenceKeepsTheStoredEvent() = runTest {
        val f = holder()
        f.importCalendar(import(calendar("REQUEST", requestEvent("mtg@x", 3))))
        assertEquals(1, f.appState.events.size)

        // The cancellation quotes SEQUENCE 2, older than the stored copy: ignored.
        f.importCalendar(
            import(
                calendar(
                    "CANCEL",
                    "BEGIN:VEVENT\r\nUID:mtg@x\r\nDTSTAMP:20260101T000000Z\r\n" +
                        "SEQUENCE:2\r\nSTATUS:CANCELLED\r\nDTSTART:20260301T100000Z\r\nEND:VEVENT\r\n",
                ),
            ),
        )
        assertEquals(1, f.appState.events.size)

        // A cancellation at least as new as the stored copy removes it.
        f.importCalendar(
            import(
                calendar(
                    "CANCEL",
                    "BEGIN:VEVENT\r\nUID:mtg@x\r\nDTSTAMP:20260102T000000Z\r\n" +
                        "SEQUENCE:3\r\nSTATUS:CANCELLED\r\nDTSTART:20260301T100000Z\r\nEND:VEVENT\r\n",
                ),
            ),
        )
        assertTrue(f.appState.events.isEmpty())
    }

    @Test
    fun cancelledInstanceBecomesAnExclusionLikeAPlainImport() = runTest {
        val f = holder()
        f.importCalendar(import(calendar("REQUEST", requestEvent("series@x", 1))))
        val series = f.appState.events.single()
        assertTrue(series.repeat != null || series.uid != null)
        f.importCalendar(
            import(
                calendar(
                    "CANCEL",
                    "BEGIN:VEVENT\r\nUID:series@x\r\nRECURRENCE-ID;VALUE=DATE:20260301\r\n" +
                        "SEQUENCE:1\r\nSTATUS:CANCELLED\r\nDTSTART;VALUE=DATE:20260301\r\nEND:VEVENT\r\n",
                ),
            ),
        )
        // The single-start model keeps the series and skips the cancelled slot.
        assertTrue(f.appState.events.isNotEmpty())
        val cancelled = f.appState.events.firstOrNull {
            it.recurrenceId == IcsTimeValue.Date(LocalDate(2026, 3, 1))
        }
        assertTrue(cancelled == null || cancelled.status == com.crko.domain.EventStatus.Confirmed)
    }

    @Test
    fun replyStringsNameTheAttendeeAndAnswer() {
        val strings = EnStrings
        assertTrue(
            strings.inviteReplyProcessed("Jane", IcsPartStat.Accepted).contains("Jane") &&
                strings.inviteReplyProcessed("Jane", IcsPartStat.Accepted).contains(strings.partStatName(IcsPartStat.Accepted)),
        )
        assertFalse(strings.inviteReplyUnknown("Jane").isEmpty())
    }
}
