package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.CalendarMetadata
import com.crko.domain.OccurrenceTime
import com.crko.domain.PersistedSettings
import com.crko.domain.exportCalendar
import com.crko.domain.importCalendar
import com.crko.domain.instantOrNull
import com.crko.domain.localStart
import com.crko.domain.occurrenceStartInstant
import com.crko.storage.StateStorage
import com.crko.storage.decodeStateWithReport
import com.crko.state.AppStateHolder
import com.crko.ui.transfer.importSelection
import com.crko.ui.transfer.toIcsImport
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val utc = TimeZone.UTC

private fun calendar(body: String): String = buildString {
    append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Crko//Test//EN\r\n")
    body.trim().split("\n").forEach { append(it.trim()).append("\r\n") }
    append("END:VCALENDAR\r\n")
}

private const val CUSTOM_ZONE = """
    BEGIN:VTIMEZONE
    TZID:Custom/Plus3
    BEGIN:STANDARD
    DTSTART:19700101T000000
    TZOFFSETFROM:+0300
    TZOFFSETTO:+0300
    END:STANDARD
    END:VTIMEZONE
"""

private class MetadataStorage(var state: AppState = AppState()) : StateStorage {
    override fun loadState(): AppState = state

    override fun saveState(state: AppState) {
        this.state = state
    }

    override fun loadSettings(): PersistedSettings = PersistedSettings()

    override fun saveSettings(settings: PersistedSettings) = Unit
}

class TimeZoneStageThreeTest {

    @Test
    fun aSharedCustomZoneIsStoredAndExportedOnlyOnce() {
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:first
            SUMMARY:First
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DTEND;TZID=Custom/Plus3:20261027T100000
            END:VEVENT
            BEGIN:VEVENT
            UID:second
            SUMMARY:Second
            DTSTART;TZID=Custom/Plus3:20261103T090000
            DTEND;TZID=Custom/Plus3:20261103T100000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(1, imported.timeZones.size)
        assertEquals("Custom/Plus3", imported.timeZones.single().tzid)

        val exported = exportCalendar(imported.events, timeZones = imported.timeZones)
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(exported).count(), exported)
        assertEquals(2, Regex("DTSTART;TZID=Custom/Plus3").findAll(exported).count(), exported)
    }

    @Test
    fun anUnknownZoneKeepsItsOffsetAcrossReloadAndRecurrenceExpansion() {
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:weekly
            SUMMARY:Weekly
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DTEND;TZID=Custom/Plus3:20261027T100000
            RRULE:FREQ=WEEKLY;COUNT=2
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val state = AppState(
            events = imported.events,
            calendar = CalendarMetadata(timeZones = imported.timeZones),
        )
        val reloaded = decodeStateWithReport(AppJson.encodeToString(AppState.serializer(), state))
        assertTrue(reloaded.damage.isEmpty, reloaded.damage.toString())
        val event = reloaded.state.events.single()

        // 09:00 at +03:00 is 06:00 UTC, and the second weekly occurrence keeps that offset.
        assertEquals(6, event.localStart(utc).hour)
        assertEquals(
            Instant.parse("2026-11-03T06:00:00Z"),
            event.occurrenceStartInstant(LocalDate(2026, 11, 3), OccurrenceTime(9 * 60, 0), utc),
        )
        assertEquals(Instant.parse("2026-10-27T06:00:00Z"), event.start.instantOrNull(utc))
    }

    @Test
    fun aKnownIanaZoneStoresOnlyItsTzid() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:Europe/Berlin
            BEGIN:STANDARD
            DTSTART:19701025T030000
            TZOFFSETFROM:+0200
            TZOFFSETTO:+0100
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:berlin
            SUMMARY:Berlin
            DTSTART;TZID=Europe/Berlin:20261027T090000
            DTEND;TZID=Europe/Berlin:20261027T100000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        assertTrue(imported.timeZones.isEmpty())
        val exported = exportCalendar(imported.events, timeZones = imported.timeZones)
        assertTrue("BEGIN:VTIMEZONE" !in exported, exported)
        assertTrue("DTSTART;TZID=Europe/Berlin:20261027T090000" in exported, exported)
    }

    @Test
    fun aStageTwoPayloadMovesItsZoneDefinitionToTheCalendarLevel() {
        val definition = "BEGIN:VTIMEZONE\\r\\nTZID:Custom/Plus3\\r\\n" +
            "BEGIN:STANDARD\\r\\nDTSTART:19700101T000000\\r\\n" +
            "TZOFFSETFROM:+0300\\r\\nTZOFFSETTO:+0300\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\n"
        val payload = """{"schemaVersion":3,"events":[{""" +
            """"id":"weekly","title":"Weekly",""" +
            """"start":{"type":"zoned","dateTime":"2026-10-27T09:00:00","tzid":"Custom/Plus3","definition":"$definition"},""" +
            """"timeZoneDefinition":"$definition",""" +
            """"recurrence":{"rule":{"frequency":"Weekly","count":3}}}]}"""
        val decoded = decodeStateWithReport(payload)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(com.crko.domain.CurrentSchemaVersion, decoded.state.schemaVersion)
        val event = decoded.state.events.single()
        // The typed recurrence set survives: stage-3 migration must not go through LegacyEvent.
        assertEquals(3, event.recurrence?.rule?.count)
        assertEquals(6, event.localStart(utc).hour)
        val zone = decoded.state.calendar.timeZones.single()
        assertEquals("Custom/Plus3", zone.tzid)
        assertTrue("TZOFFSETTO:+0300" in zone.definition)

        val exported = exportCalendar(decoded.state.events, timeZones = decoded.state.calendar.timeZones)
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(exported).count(), exported)
    }

    @Test
    fun calendarLevelPropertiesSurviveAnImportAndAReload() = runTest {
        val text = calendar(
            "METHOD:REQUEST\r\n" +
                "X-WR-CALNAME:Team Events\r\n" +
                "REFRESH-INTERVAL;VALUE=DURATION:PT4H\r\n" +
                "BEGIN:VEVENT\r\nUID:a@x\r\nDTSTAMP:20260101T000000Z\r\n" +
                "DTSTART:20260301T100000Z\r\nDTEND:20260301T110000Z\r\nSUMMARY:Sync\r\nEND:VEVENT\r\n",
        )
        val storage = MetadataStorage()
        val holder = AppStateHolder(scope = this, initialSystemLocale = "en-US", storage = storage)
        // The preview path rebuilds the import from the kept rows; calendar metadata must survive it.
        val selection = importSelection(importCalendar(text, utc, "Untitled"))
        holder.importCalendar(selection.toIcsImport(listOf(true)), source = "team.ics")

        assertEquals("Team Events", holder.appState.calendar.name)
        assertEquals(240, holder.appState.calendar.refreshMinutes)
        assertEquals("REQUEST", holder.appState.calendar.method)

        val reloaded = decodeStateWithReport(AppJson.encodeToString(AppState.serializer(), storage.state))
        assertTrue(reloaded.damage.isEmpty, reloaded.damage.toString())
        assertEquals(CalendarMetadata(name = "Team Events", refreshMinutes = 240, method = "REQUEST"), reloaded.state.calendar)
        val exported = exportCalendar(reloaded.state.events, calendarName = reloaded.state.calendar.name)
        assertTrue("X-WR-CALNAME:Team Events" in exported, exported)
    }
}
