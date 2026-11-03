package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.CalendarMetadata
import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.OccurrenceTime
import com.crko.domain.PersistedSettings
import com.crko.domain.absoluteTrigger
import com.crko.domain.exportCalendar
import com.crko.domain.importCalendar
import com.crko.domain.instantOrNull
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.occurrenceStartInstant
import com.crko.domain.occursOn
import com.crko.domain.withDeviceDateTime
import com.crko.storage.StateStorage
import com.crko.state.AppStateHolder
import com.crko.ui.transfer.importSelection
import com.crko.ui.transfer.toIcsImport
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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

/** A zone that observes its own DST change on the last Sunday of March and October. */
private const val CUSTOM_DST_ZONE = """
    BEGIN:VTIMEZONE
    TZID:Custom/Summer
    BEGIN:STANDARD
    DTSTART:19700101T000000
    TZOFFSETFROM:+0200
    TZOFFSETTO:+0200
    RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU
    END:STANDARD
    BEGIN:DAYLIGHT
    DTSTART:20260329T020000
    TZOFFSETFROM:+0200
    TZOFFSETTO:+0300
    RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU
    END:DAYLIGHT
    END:VTIMEZONE
"""

/**
 * A zone whose id merely *ends* in a name the platform knows (`UTC`). Its own offsets are not the
 * platform's, so it is an unknown zone and its text has to survive.
 */
private const val LOOKALIKE_ZONE = """
    BEGIN:VTIMEZONE
    TZID:Custom/UTC
    BEGIN:STANDARD
    DTSTART:19700101T000000
    TZOFFSETFROM:-0500
    TZOFFSETTO:-0500
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
            calendars = listOf(
                com.crko.domain.Calendar.local().copy(
                    timeZones = imported.timeZones,
                    events = imported.events,
                ),
            ),
        )
        val reloaded = AppJson.decodeFromString(
            AppState.serializer(),
            AppJson.encodeToString(AppState.serializer(), state),
        )
        val event = reloaded.events.single()

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
    fun calendarLevelPropertiesSurviveAnImportAndAReload() = runTest {
        val text = calendar(
            "METHOD:REQUEST\r\n" +
                "X-WR-CALNAME:Team Events\r\n" +
                "REFRESH-INTERVAL;VALUE=DURATION:PT4H\r\n" +
                "BEGIN:VEVENT\r\nUID:a@x\r\nDTSTAMP:20260101T000000Z\r\n" +
                "DTSTART:20260301T100000Z\r\nDTEND:20260301T110000Z\r\nSUMMARY:Sync\r\nEND:VEVENT\r\n",
        )
        val storage = MetadataStorage()
        val holder = AppStateHolder(
            scope = this,
            initialSystemLocale = "en-US",
            storage = storage,
            // The reload below reads the fake storage; keep the write on the test scheduler.
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        // The preview path rebuilds the import from the kept rows; calendar metadata must survive it.
        val selection = importSelection(importCalendar(text, utc, "Untitled"))
        holder.importCalendar(selection.toIcsImport(listOf(true)), source = "team.ics")

        assertEquals("Team Events", holder.appState.calendar.name)
        assertEquals(240, holder.appState.calendar.refreshMinutes)
        assertEquals("REQUEST", holder.appState.calendar.method)

        val reloaded = AppJson.decodeFromString(
            AppState.serializer(),
            AppJson.encodeToString(AppState.serializer(), storage.state),
        )
        assertEquals(CalendarMetadata(name = "Team Events", refreshMinutes = 240, method = "REQUEST"), reloaded.calendar)
        val exported = exportCalendar(reloaded.events, calendarName = reloaded.calendar.name)
        assertTrue("X-WR-CALNAME:Team Events" in exported, exported)
    }

    @Test
    fun aDurationOnACustomZoneEventKeepsItsLength() {
        // `DURATION` has to be added to the instant and written back as a wall clock in the event's
        // own zone. Rendering it through the device zone instead moved the end by the whole offset,
        // and the end landed before the start, so the length was dropped as invalid.
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:duration
            SUMMARY:Duration
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DURATION:PT1H
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        assertEquals(Instant.parse("2026-10-27T06:00:00Z"), event.start.instantOrNull(utc))
        val end = assertNotNull(event.end, "the DURATION end vanished: $event")
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 10, 0), "Custom/Plus3"), end)
        assertEquals(Instant.parse("2026-10-27T07:00:00Z"), end.instantOrNull(utc))
    }

    @Test
    fun anEndWrittenInUtcOnACustomZoneEventUsesTheEventsOwnZone() {
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:utc-end
            SUMMARY:Utc end
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DTEND:20261027T070000Z
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        // 07:00Z is 10:00 in a +03:00 zone; the stored wall clock has to say the same.
        val end = assertNotNull(event.end)
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 10, 0), "Custom/Plus3"), end)
        assertEquals(Instant.parse("2026-10-27T07:00:00Z"), end.instantOrNull(utc))
        assertEquals(LocalDateTime(2026, 10, 27, 7, 0), event.localEnd(utc))
    }

    @Test
    fun editingACustomZoneEventMovesOnlyTheInstantTheUserPicked() {
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:edit
            SUMMARY:Edit me
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DTEND;TZID=Custom/Plus3:20261027T100000
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        // The editor hands over the device wall clock (06:00 here is the event's own 09:00): saving
        // it unchanged must not move the event, and moving it by an hour must move it by an hour.
        val kept = event.start.withDeviceDateTime(LocalDateTime(2026, 10, 27, 6, 0), utc)
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Custom/Plus3"), kept)
        assertEquals(Instant.parse("2026-10-27T06:00:00Z"), kept.instantOrNull(utc))
        val moved = event.start.withDeviceDateTime(LocalDateTime(2026, 10, 27, 7, 0), utc)
        assertEquals(Instant.parse("2026-10-27T07:00:00Z"), moved.instantOrNull(utc))
        assertEquals(LocalDateTime(2026, 10, 27, 10, 0), assertNotNull(moved as? IcsTimeValue.Zoned).dateTime)
    }

    @Test
    fun aUtcExdateOnACustomZoneSeriesStillSkipsItsInstance() {
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:custom-exdate
            SUMMARY:Daily
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DTEND;TZID=Custom/Plus3:20261027T100000
            RRULE:FREQ=DAILY;COUNT=3
            EXDATE:20261028T060000Z
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val event = imported.events.single()
        assertFalse(IcsWarning.ExdateMismatch in imported.warnings, imported.warnings.toString())
        assertTrue(event.occursOn(LocalDate(2026, 10, 27), utc))
        assertFalse(event.occursOn(LocalDate(2026, 10, 28), utc))
        assertTrue(event.occursOn(LocalDate(2026, 10, 29), utc))
    }

    @Test
    fun aCustomZoneSeriesCrossingItsOwnDstChangeKeepsWallClockAndLength() {
        val text = calendar(
            CUSTOM_DST_ZONE + """
            BEGIN:VEVENT
            UID:custom-dst
            SUMMARY:Weekly
            DTSTART;TZID=Custom/Summer:20260322T090000
            DURATION:PT1H
            RRULE:FREQ=WEEKLY;COUNT=3
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        val end = assertNotNull(event.end, "the length was lost: $event")
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 3, 22, 10, 0), "Custom/Summer"), end)
        val wall = OccurrenceTime(9 * 60, 0)
        // +02:00 before the zone's own DST change on the last Sunday of March, +03:00 after it.
        assertEquals(Instant.parse("2026-03-22T07:00:00Z"), event.occurrenceStartInstant(LocalDate(2026, 3, 22), wall, utc))
        assertEquals(Instant.parse("2026-03-29T06:00:00Z"), event.occurrenceStartInstant(LocalDate(2026, 3, 29), wall, utc))
        assertEquals(Instant.parse("2026-04-05T06:00:00Z"), event.occurrenceStartInstant(LocalDate(2026, 4, 5), wall, utc))
    }

    @Test
    fun aCustomZoneReferencedOnlyByATodoOrAlarmTravelsWithTheExport() {
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:floating-with-custom-alarm
            SUMMARY:Alarm in a custom zone
            DTSTART:20261027T090000
            DTEND:20261027T100000
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;VALUE=DATE-TIME;TZID=Custom/Plus3:20261026T090000
            DESCRIPTION:Custom alarm
            END:VALARM
            END:VEVENT
            BEGIN:VTODO
            UID:todo-custom
            SUMMARY:Custom todo
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DUE;TZID=Custom/Plus3:20261027T100000
            END:VTODO
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        // Neither record's DTSTART names the unknown zone: only the alarm trigger and the todo do.
        val exported = exportCalendar(
            imported.events,
            imported.todos,
            now = Instant.parse("2026-10-27T09:00:00Z"),
            journals = imported.journals,
            timeZones = imported.timeZones,
        )
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(exported).count(), exported)
        val again = importCalendar(exported, utc, "Untitled")
        assertFalse(IcsWarning.UnresolvedTimeZone in again.warnings, again.warnings.toString())
        val todo = again.todos.single()
        assertEquals(Instant.parse("2026-10-27T06:00:00Z"), assertNotNull(todo.start).instantOrNull(utc))
        assertEquals(Instant.parse("2026-10-27T07:00:00Z"), assertNotNull(todo.due).instantOrNull(utc))
        val trigger = assertNotNull(again.events.single().alarms.single().absoluteTrigger)
        assertEquals(Instant.parse("2026-10-26T06:00:00Z"), trigger.instantOrNull(utc))
    }

    @Test
    fun aCustomZoneSeriesUntilIsExportedInTheUtcFormRfcRequires() {
        val text = calendar(
            CUSTOM_ZONE + """
            BEGIN:VEVENT
            UID:custom-until
            SUMMARY:Weekly until
            DTSTART;TZID=Custom/Plus3:20260105T090000
            DTEND;TZID=Custom/Plus3:20260105T100000
            RRULE:FREQ=WEEKLY;UNTIL=20260209
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        val exported = exportCalendar(imported.events, timeZones = imported.timeZones)
        // A TZID series must carry a UTC `UNTIL`; a bare local time is read as floating by any
        // client, which moves the last instance by the zone offset.
        assertTrue("UNTIL=20260209T060000Z" in exported, exported)
        val again = importCalendar(exported, utc, "Untitled")
        val until = assertNotNull(again.events.single().recurrence?.rule?.until)
        assertEquals(Instant.parse("2026-02-09T06:00:00Z"), until.instantOrNull(utc))
    }

    @Test
    fun aZoneWhoseIdEndsInARealZoneNameKeepsItsOwnDefinition() {
        val text = calendar(
            LOOKALIKE_ZONE + """
            BEGIN:VEVENT
            UID:lookalike
            SUMMARY:Minus five
            DTSTART;TZID=Custom/UTC:20261027T090000
            DTEND;TZID=Custom/UTC:20261027T100000
            END:VEVENT
            """,
        )
        val imported = importCalendar(text, utc, "Untitled")
        // "Custom/UTC" is not the platform's UTC: resolving its name by suffix instead of storing the
        // block moved every reload and every export five hours away from the imported instant.
        assertEquals("Custom/UTC", imported.timeZones.singleOrNull()?.tzid, imported.timeZones.toString())
        assertEquals(Instant.parse("2026-10-27T14:00:00Z"), imported.events.single().start.instantOrNull(utc))

        val exported = exportCalendar(imported.events, timeZones = imported.timeZones)
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(exported).count(), exported)
        val again = importCalendar(exported, utc, "Untitled")
        assertFalse(IcsWarning.UnresolvedTimeZone in again.warnings, again.warnings.toString())
        assertEquals(Instant.parse("2026-10-27T14:00:00Z"), again.events.single().start.instantOrNull(utc))
    }

    @Test
    fun aProducerPrefixedZoneWhoseTailIsAPlatformNameKeepsItsOwnDefinition() {
        val lookalike = """
            BEGIN:VTIMEZONE
            TZID:example.org/20050126_1/Custom/UTC
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:-0500
            TZOFFSETTO:-0500
            END:STANDARD
            END:VTIMEZONE
        """
        val text = calendar(
            lookalike + """
            BEGIN:VEVENT
            UID:prefixed-lookalike
            SUMMARY:Prefixed minus five
            DTSTART;TZID=example.org/20050126_1/Custom/UTC:20261027T090000
            DTEND;TZID=example.org/20050126_1/Custom/UTC:20261027T100000
            END:VEVENT
            """,
        )
        // One segment deeper than `Custom/UTC` is the same trap: stripping the producer's prefix down
        // to the very last token leaves `UTC`, which the platform does know — so the block declaring
        // -05:00 is dropped as "already known" and every reload and export of this zone sits five
        // hours away from the instant the file wrote. A stripped suffix has to read as `Region/City`.
        val imported = importCalendar(text, utc, "Untitled")
        assertEquals(
            listOf("example.org/20050126_1/Custom/UTC"),
            imported.timeZones.map { it.tzid },
            imported.timeZones.toString(),
        )
        assertEquals(Instant.parse("2026-10-27T14:00:00Z"), imported.events.single().start.instantOrNull(utc))

        val exported = exportCalendar(imported.events, timeZones = imported.timeZones)
        assertEquals(1, Regex("BEGIN:VTIMEZONE").findAll(exported).count(), exported)
        val again = importCalendar(exported, utc, "Untitled")
        assertFalse(IcsWarning.UnresolvedTimeZone in again.warnings, again.warnings.toString())
        assertEquals(Instant.parse("2026-10-27T14:00:00Z"), again.events.single().start.instantOrNull(utc))

        // The prefixed form the rule exists for still resolves through the platform: the remainder
        // after the producer's prefix is a real `Region/City` id.
        val mozilla = importCalendar(
            calendar(
                """
                BEGIN:VEVENT
                UID:prefixed-real
                SUMMARY:Mozilla prefix
                DTSTART;TZID=/mozilla.org/20050126_1/America/New_York:20261027T090000
                END:VEVENT
                """,
            ),
            utc,
            "Untitled",
        )
        assertTrue(mozilla.timeZones.isEmpty(), mozilla.timeZones.toString())
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "America/New_York"), mozilla.events.single().start)
        assertEquals(Instant.parse("2026-10-27T13:00:00Z"), mozilla.events.single().start.instantOrNull(utc))
    }
}
