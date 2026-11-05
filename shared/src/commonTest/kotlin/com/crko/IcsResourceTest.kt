package com.crko

import com.crko.domain.Calendar
import com.crko.domain.CalendarTimeZone
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.RecordSyncMeta
import com.crko.domain.toIcsText
import com.crko.domain.uidGroup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IcsResourceTest {

    private fun master() = Event(
        id = "m1",
        title = "Series",
        start = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-03-01T10:00:00Z")),
        end = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-03-01T11:00:00Z")),
        icsUid = "s@x",
        recurrenceId = null,
    )

    private fun exception() = Event(
        id = "x1",
        title = "Series moved",
        // This exception names the calendar's own zone, so its definition must ride along on upload.
        start = IcsTimeValue.Zoned(kotlinx.datetime.LocalDateTime(2026, 3, 2, 12, 0), "Custom/Zone"),
        end = IcsTimeValue.Zoned(kotlinx.datetime.LocalDateTime(2026, 3, 2, 13, 0), "Custom/Zone"),
        icsUid = "s@x",
        recurrenceId = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-03-02T10:00:00Z")),
    )

    @Test
    fun aUidGroupCollectsMasterAndExceptions() {
        val calendar = Calendar(id = "c", name = "C", events = listOf(master(), exception()))
        val group = assertNotNull(calendar.uidGroup("s@x"))
        assertEquals(listOf("m1", "x1"), group.events.map { it.id })
        assertNull(calendar.uidGroup("missing@x"))
    }

    @Test
    fun anUnsyncedRecordIsRecognizedByItsOwnExportUid() {
        val calendar = Calendar(id = "c", name = "C", events = listOf(master().copy(id = "m1", icsUid = null)))
        val group = assertNotNull(calendar.uidGroup("m1@crko"))
        assertEquals("m1", group.events.single().id)
    }

    @Test
    fun serializationProducesOneResourcePerGroup() {
        val calendar = Calendar(
            id = "c",
            name = "C",
            timeZones = listOf(
                CalendarTimeZone(
                    "Custom/Zone",
                    "BEGIN:VTIMEZONE\r\nTZID:Custom/Zone\r\n" +
                        "BEGIN:STANDARD\r\nDTSTART:19701025T030000\r\nTZOFFSETFROM:+0200\r\n" +
                        "TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nEND:STANDARD\r\n" +
                        "BEGIN:DAYLIGHT\r\nDTSTART:19700329T020000\r\nTZOFFSETFROM:+0100\r\n" +
                        "TZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n" +
                        "END:VTIMEZONE",
                ),
            ),
            events = listOf(master(), exception()),
        )
        val text = assertNotNull(calendar.uidGroup("s@x")).toIcsText(calendar)
        assertTrue(text.startsWith("BEGIN:VCALENDAR"))
        assertTrue(text.endsWith("END:VCALENDAR\r\n"))
        assertEquals(2, Regex("BEGIN:VEVENT").findAll(text).count())
        assertTrue("UID:s@x" in text)
        assertTrue("RECURRENCE-ID" in text)
        // The calendar's stored zone definitions ride along for clients that cannot resolve them.
        assertTrue("BEGIN:VTIMEZONE" in text)
    }

    @Test
    fun theResourceLocationSurvivesACopyOfTheRecord() {
        val withMeta = master().copy(sync = RecordSyncMeta("https://x/s.ics", "\"v\""))
        val group = Calendar(id = "c", name = "C", events = listOf(withMeta)).uidGroup("s@x")
        assertEquals("https://x/s.ics", group?.components?.single()?.sync?.href)
    }
}
