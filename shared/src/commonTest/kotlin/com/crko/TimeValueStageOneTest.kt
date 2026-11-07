package com.crko

import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.OccurrenceTime
import com.crko.domain.exportIcs
import com.crko.domain.importIcs
import com.crko.domain.isZeroDuration
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.occurrenceStartInstant
import com.crko.domain.occursOn
import com.crko.domain.timeRangeOn
import com.crko.domain.withDeviceDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Stage 1 acceptance: RFC time values survive import, editing and device-zone changes. */
class TimeValueStageOneTest {

    private val utc = TimeZone.UTC
    private val shanghai = TimeZone.of("Asia/Shanghai")
    private val newYork = TimeZone.of("America/New_York")
    private val now = Instant.parse("2026-10-27T09:00:00Z")

    @Test
    fun newYorkWeeklyAcrossDstKeepsAbsoluteInstantsOnAShanghaiDevice() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:ny-weekly
            SUMMARY:NY weekly
            DTSTART;TZID=America/New_York:20260301T090000
            DTEND;TZID=America/New_York:20260301T100000
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            """,
        )
        val event = importIcs(text, shanghai, "Untitled").single()
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 3, 1, 9, 0), "America/New_York"),
            event.start,
        )

        val time = OccurrenceTime(9 * 60, 0)
        val first = event.occurrenceStartInstant(LocalDate(2026, 3, 1), time, shanghai)
        val beforeDst = event.occurrenceStartInstant(LocalDate(2026, 3, 8), time, shanghai)
        val afterDst = event.occurrenceStartInstant(LocalDate(2026, 3, 15), time, shanghai)

        // 09:00 in New York is 14:00Z in EST and 13:00Z in EDT; the wall clock never drifts.
        assertEquals(Instant.parse("2026-03-01T14:00:00Z"), first)
        assertEquals(Instant.parse("2026-03-08T13:00:00Z"), beforeDst)
        assertEquals(Instant.parse("2026-03-15T13:00:00Z"), afterDst)
        assertEquals(LocalDateTime(2026, 3, 1, 22, 0), first.toLocalDateTime(shanghai))
        assertEquals(LocalDateTime(2026, 3, 8, 21, 0), beforeDst.toLocalDateTime(shanghai))
    }

    @Test
    fun editingATzidEventKeepsItsZoneOnExport() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:ny-edit
            SUMMARY:NY edit
            DTSTART;TZID=America/New_York:20260301T090000
            DTEND;TZID=America/New_York:20260301T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, shanghai, "Untitled").single()
        // The editor works in the device clock: 23:00 in Shanghai is 10:00 in New York.
        val edited = event.copy(
            start = event.start.withDeviceDateTime(LocalDateTime(2026, 3, 1, 23, 0), shanghai),
            end = event.end!!.withDeviceDateTime(LocalDateTime(2026, 3, 2, 0, 0), shanghai),
        )
        val exported = exportIcs(listOf(edited), now)
        assertTrue(exported.contains("DTSTART;TZID=America/New_York:20260301T100000\r\n"), exported)
        assertTrue(exported.contains("DTEND;TZID=America/New_York:20260301T110000\r\n"), exported)
        assertEquals("America/New_York", importIcs(exported, shanghai, "Untitled").single().timeZoneId)
    }

    @Test
    fun allDayEndIsExclusive() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:all-day
            SUMMARY:All day
            DTSTART;VALUE=DATE:20261027
            DTEND;VALUE=DATE:20261029
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 27)), event.start)
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 29)), event.end)
        assertEquals(LocalDate(2026, 10, 28), event.endDay)

        val exported = exportIcs(listOf(event), now)
        assertTrue(exported.contains("DTSTART;VALUE=DATE:20261027\r\n"), exported)
        assertTrue(exported.contains("DTEND;VALUE=DATE:20261029\r\n"), exported)
        assertEquals(event.start, importIcs(exported, utc, "Untitled").single().start)
        assertEquals(event.end, importIcs(exported, utc, "Untitled").single().end)
    }

    @Test
    fun anAllDayEndGivenAsADateTimeKeepsTheDayTheFileNames() {
        // A `DTEND` date-time on a DATE `DTSTART` names its own day; projecting it through the
        // device zone first would lose the last day for every user west of the written zone.
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:allday-tzend
            SUMMARY:All day with a TZID end
            DTSTART;VALUE=DATE:20260310
            DTEND;TZID=Asia/Shanghai:20260312T000000
            END:VEVENT
            BEGIN:VEVENT
            UID:allday-utcend
            SUMMARY:All day with a UTC end
            DTSTART;VALUE=DATE:20260310
            DTEND:20260312T000000Z
            END:VEVENT
            """,
        )
        val zones = listOf(newYork, shanghai, utc)
        zones.forEach { device ->
            val imported = importIcs(text, device, "Untitled").associateBy { it.icsUid }
            assertEquals(IcsTimeValue.Date(LocalDate(2026, 3, 12)), imported.getValue("allday-tzend").end)
            assertEquals(IcsTimeValue.Date(LocalDate(2026, 3, 12)), imported.getValue("allday-utcend").end)
            assertEquals(
                listOf(LocalDate(2026, 3, 10), LocalDate(2026, 3, 11)),
                (9..13).map { LocalDate(2026, 3, it) }.filter { imported.getValue("allday-tzend").occursOn(it, device) },
            )
        }
        // The normalized exclusive date is what comes back out again.
        val exported = exportIcs(importIcs(text, newYork, "Untitled"), now)
        assertEquals(2, Regex("DTEND;VALUE=DATE:20260312").findAll(exported).count(), exported)
    }

    @Test
    fun aFloatingDurationEndDoesNotDependOnTheDeviceZone() {
        // A floating DTSTART has no instant, so a DURATION is added to its wall clock; resolving it
        // through the device zone first made the New York daylight-saving jump move the stored end.
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:float-duration
            SUMMARY:Floating duration
            DTSTART:20260308T013000
            DURATION:PT1H
            END:VEVENT
            """,
        )
        val expected = IcsTimeValue.Floating(LocalDateTime(2026, 3, 8, 2, 30))
        listOf(utc, newYork, shanghai).forEach { device ->
            val event = importIcs(text, device, "Untitled").single()
            assertEquals(expected, event.end, device.id)
            // Export writes the end as DTEND, so the wall-clock length has to survive the round trip.
            assertEquals(expected, importIcs(exportIcs(listOf(event), now), device, "Untitled").single().end, device.id)
        }
        assertEquals(60, importIcs(text, utc, "Untitled").single().timeRangeOn(LocalDate(2026, 3, 8), utc).let { (s, e) -> e - s })
    }

    @Test
    fun zeroDurationCrossMidnightAndMultiDayRanges() {
        val zero = importIcs(
            calendar("BEGIN:VEVENT\r\nUID:zero\r\nSUMMARY:Zero\r\nDTSTART:20261027T120000\r\nEND:VEVENT"),
            utc,
            "Untitled",
        ).single()
        assertNull(zero.end)
        assertTrue(zero.isZeroDuration)
        val zeroExport = exportIcs(listOf(zero), now)
        assertTrue(!zeroExport.contains("DTEND"), zeroExport)
        assertNull(importIcs(zeroExport, utc, "Untitled").single().end)

        val crossMidnight = importIcs(
            calendar(
                "BEGIN:VEVENT\r\nUID:cross\r\nSUMMARY:Cross\r\n" +
                    "DTSTART:20261027T220000\r\nDTEND:20261028T020000\r\nEND:VEVENT",
            ),
            utc,
            "Untitled",
        ).single()
        assertEquals(22 * 60 to 1440, crossMidnight.timeRangeOn(LocalDate(2026, 10, 27), utc))
        assertEquals(0 to 2 * 60, crossMidnight.timeRangeOn(LocalDate(2026, 10, 28), utc))
        assertTrue(crossMidnight.occursOn(LocalDate(2026, 10, 28), utc))

        val multiDay = importIcs(
            calendar(
                "BEGIN:VEVENT\r\nUID:multi\r\nSUMMARY:Multi\r\n" +
                    "DTSTART:20261027T220000\r\nDTEND:20261029T150000\r\nEND:VEVENT",
            ),
            utc,
            "Untitled",
        ).single()
        assertEquals(22 * 60 to 1440, multiDay.timeRangeOn(LocalDate(2026, 10, 27), utc))
        assertEquals(0 to 1440, multiDay.timeRangeOn(LocalDate(2026, 10, 28), utc))
        assertEquals(0 to 15 * 60, multiDay.timeRangeOn(LocalDate(2026, 10, 29), utc))
    }

    @Test
    fun occupancyFollowsTheZoneArgumentNotTheDeviceZone() {
        // 2026-03-08 00:30 Tokyo is 2026-03-07 15:30Z: the event occupies March 8 in Tokyo but
        // March 7 in UTC, whatever zone the device runs in.
        val tokyo = TimeZone.of("Asia/Tokyo")
        val event = Event(
            id = "z",
            title = "Tokyo midnight",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 3, 8, 0, 30), "Asia/Tokyo"),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 3, 8, 1, 30), "Asia/Tokyo"),
        )
        assertTrue(event.occursOn(LocalDate(2026, 3, 8), tokyo))
        assertFalse(event.occursOn(LocalDate(2026, 3, 7), tokyo))
        assertTrue(event.occursOn(LocalDate(2026, 3, 7), utc))
        assertFalse(event.occursOn(LocalDate(2026, 3, 8), utc))
        // The day slice follows the same zone: 15:30-16:30 UTC.
        assertEquals(30 to 90, event.timeRangeOn(LocalDate(2026, 3, 8), tokyo))
        assertEquals(930 to 990, event.timeRangeOn(LocalDate(2026, 3, 7), utc))
    }

    @Test
    fun changingTheDeviceZoneOnlyChangesTheProjection() {        val text = calendar(
            """
            BEGIN:VEVENT
            UID:berlin
            SUMMARY:Berlin
            DTSTART;TZID=Europe/Berlin:20261027T090000
            DTEND;TZID=Europe/Berlin:20261027T100000
            END:VEVENT
            """,
        )
        val event = importIcs(text, utc, "Untitled").single()
        val stored = event.start
        // 27 October 2026 is after the DST change, so Berlin is UTC+1.
        assertEquals(LocalDateTime(2026, 10, 27, 8, 0), event.localStart(utc))
        assertEquals(LocalDateTime(2026, 10, 27, 9, 0), event.localEnd(utc))
        assertEquals(LocalDateTime(2026, 10, 27, 16, 0), event.localStart(shanghai))
        // Display projection must never rewrite the persisted value.
        assertEquals(stored, event.start)
        assertEquals("Europe/Berlin", event.timeZoneId)
        assertEquals(17 * 60, event.localEnd(shanghai)!!.hour * 60 + event.localEnd(shanghai)!!.minute)
    }

    private fun calendar(body: String): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Crko//Test//EN\r\n")
        body.trim().split("\n").forEach { append(it.trim()).append("\r\n") }
        append("END:VCALENDAR\r\n")
    }
}
