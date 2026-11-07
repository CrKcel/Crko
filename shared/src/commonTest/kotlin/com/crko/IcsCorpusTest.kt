package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.exportCalendar
import com.crko.domain.importCalendar
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Stage 0 safety net: a corpus of the calendar shapes the importer and exporter must keep stable.
 * A round trip is compared semantically — record counts, RFC time values and recurrence facts — not
 * byte for byte, because exporters may reorder or re-render equivalent values.
 */
class IcsCorpusTest {

    private val utc = TimeZone.UTC

    private val samples = listOf(
        "local timed, all-day, multi-day and zero-length" to """
            BEGIN:VEVENT
            UID:timed
            SUMMARY:Timed
            DTSTART:20261027T090000
            DTEND:20261027T100000
            END:VEVENT
            BEGIN:VEVENT
            UID:all-day
            SUMMARY:All day
            DTSTART;VALUE=DATE:20261027
            DTEND;VALUE=DATE:20261028
            END:VEVENT
            BEGIN:VEVENT
            UID:multi-day
            SUMMARY:Multi day
            DTSTART:20261027T220000
            DTEND:20261029T020000
            END:VEVENT
            BEGIN:VEVENT
            UID:zero
            SUMMARY:Zero
            DTSTART:20261027T120000
            END:VEVENT
        """.trimIndent(),
        "TZID values across a DST change" to """
            BEGIN:VEVENT
            UID:ny-weekly
            SUMMARY:NY weekly
            DTSTART;TZID=America/New_York:20260301T090000
            DTEND;TZID=America/New_York:20260301T100000
            RRULE:FREQ=WEEKLY;COUNT=4
            END:VEVENT
            BEGIN:VEVENT
            UID:berlin
            SUMMARY:Berlin
            DTSTART;TZID=Europe/Berlin:20261027T090000
            DTEND;TZID=Europe/Berlin:20261027T100000
            END:VEVENT
        """.trimIndent(),
        "unknown VTIMEZONE" to """
            BEGIN:VTIMEZONE
            TZID:Custom/Plus3
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0300
            TZOFFSETTO:+0300
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:custom-zone
            SUMMARY:Custom zone
            DTSTART;TZID=Custom/Plus3:20261027T090000
            DTEND;TZID=Custom/Plus3:20261027T100000
            END:VEVENT
        """.trimIndent(),
        "RRULE combinations" to """
            BEGIN:VEVENT
            UID:count
            SUMMARY:Count
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=DAILY;COUNT=3
            END:VEVENT
            BEGIN:VEVENT
            UID:until
            SUMMARY:Until
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=WEEKLY;UNTIL=20261201T090000Z;BYDAY=MO,WE
            END:VEVENT
            BEGIN:VEVENT
            UID:monthly
            SUMMARY:Monthly
            DTSTART:20261001T090000
            DTEND:20261001T100000
            RRULE:FREQ=MONTHLY;BYMONTHDAY=-1;BYSETPOS=-1
            END:VEVENT
            BEGIN:VEVENT
            UID:yearly
            SUMMARY:Yearly
            DTSTART:20260101T090000
            DTEND:20260101T100000
            RRULE:FREQ=YEARLY;BYWEEKNO=1;BYDAY=MO
            END:VEVENT
            BEGIN:VEVENT
            UID:hourly
            SUMMARY:Hourly
            DTSTART:20261027T090000
            DTEND:20261027T093000
            RRULE:FREQ=DAILY;BYHOUR=9,17;BYMINUTE=0
            END:VEVENT
        """.trimIndent(),
        "RDATE and EXDATE" to """
            BEGIN:VEVENT
            UID:rdate
            SUMMARY:Rdate
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RRULE:FREQ=WEEKLY;COUNT=3
            RDATE;TZID=Europe/Berlin:20261105T140000
            EXDATE:20261103T090000
            END:VEVENT
            BEGIN:VEVENT
            UID:rdate-period
            SUMMARY:Rdate period
            DTSTART:20261027T090000
            DTEND:20261027T100000
            RDATE;VALUE=PERIOD:20261105T140000Z/PT1H
            END:VEVENT
        """.trimIndent(),
        "RECURRENCE-ID exceptions" to """
            BEGIN:VEVENT
            UID:exceptions
            SUMMARY:Series
            DTSTART;TZID=America/New_York:20261027T090000
            DTEND;TZID=America/New_York:20261027T100000
            RRULE:FREQ=DAILY;COUNT=5
            END:VEVENT
            BEGIN:VEVENT
            UID:exceptions
            RECURRENCE-ID;TZID=America/New_York:20261028T090000
            DTSTART;TZID=America/New_York:20261028T110000
            DTEND;TZID=America/New_York:20261028T120000
            SUMMARY:Moved one
            END:VEVENT
            BEGIN:VEVENT
            UID:exceptions
            RECURRENCE-ID;TZID=America/New_York:20261029T090000
            DTSTART;TZID=America/New_York:20261029T160000
            DTEND;TZID=America/New_York:20261029T170000
            SUMMARY:Moved two
            END:VEVENT
        """.trimIndent(),
        "VALARM triggers" to """
            BEGIN:VEVENT
            UID:alarms
            SUMMARY:Alarms
            DTSTART:20261027T090000
            DTEND:20261027T100000
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT15M
            DESCRIPTION:Before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:PT5M
            REPEAT:2
            DURATION:PT5M
            END:VALARM
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER;VALUE=DATE-TIME:20261026T120000Z
            END:VALARM
            END:VEVENT
        """.trimIndent(),
        "participants, attachments, VTODO and VJOURNAL" to """
            BEGIN:VEVENT
            UID:participants
            SUMMARY:Participants
            DTSTART:20261027T090000
            DTEND:20261027T100000
            ORGANIZER;CN=Host:mailto:host@example.com
            ATTENDEE;CN=Guest;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=TRUE:mailto:guest@example.com
            ATTACH;FMTTYPE=text/plain;FILENAME=note.txt:https://example.com/note.txt
            END:VEVENT
            BEGIN:VTODO
            UID:todo-1
            SUMMARY:Todo
            DUE;VALUE=DATE:20261028
            STATUS:NEEDS-ACTION
            PRIORITY:5
            END:VTODO
            BEGIN:VJOURNAL
            UID:journal-1
            SUMMARY:Journal
            DTSTART;VALUE=DATE:20261027
            DESCRIPTION:Entry
            END:VJOURNAL
        """.trimIndent(),
    )

    @Test
    fun corpusRoundTripsSemantically() {
        samples.forEach { (name, body) ->
            val first = importCalendar(calendar(body), utc, "Untitled")
            val exported = exportCalendar(first.events, first.todos, now = NOW, journals = first.journals)
            val second = importCalendar(exported, utc, "Untitled")
            assertEquals(signature(first), signature(second), name)
        }
    }

    @Test
    fun exportedCorpusKeepsItsKeyProperties() {
        val all = samples.joinToString("\n") { (_, body) -> body }
        val imported = importCalendar(calendar(all), utc, "Untitled")
        val exported = exportCalendar(imported.events, imported.todos, now = NOW, journals = imported.journals)
        // Only markers the semantic signature above does NOT already pin:
        // rule parts, alarm relatedness and participant parameters.
        listOf(
            "RRULE:FREQ=YEARLY;BYDAY=MO;BYWEEKNO=1",
            "RRULE:FREQ=DAILY;BYHOUR=9,17;BYMINUTE=0",
            "TRIGGER;RELATED=END:+PT5M",
            "ORGANIZER;CN=Host:mailto:host@example.com",
            "ATTENDEE;CN=Guest;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED",
        ).forEach { marker -> assertTrue(exported.contains(marker), "missing $marker\n$exported") }
    }

    private fun signature(imported: com.crko.domain.IcsImport): String = buildString {
        append("E:")
        imported.events
            .map { "${it.title}|${it.start}|${it.end}|${it.recurrenceId}|${it.repeat?.frequency}|${it.repeat?.count}|${it.repeat?.until}|${it.recurrence?.rdates}|${it.recurrence?.exdates}" }
            .sorted()
            .forEach { append(it).append(';') }
        append("|T:")
        imported.todos.map { "${it.icsUid}|${it.title}|${it.status}|${it.due}" }.sorted().forEach { append(it).append(';') }
        append("|J:")
        imported.journals.map { "${it.icsUid}|${it.title}|${it.date}" }.sorted().forEach { append(it).append(';') }
    }

    private fun calendar(body: String): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Crko//Test//EN\r\n")
        body.trim().split("\n").forEach { append(it.trimEnd()).append("\r\n") }
        append("END:VCALENDAR\r\n")
    }

    private companion object {
        val NOW: kotlin.time.Instant = kotlin.time.Instant.parse("2026-10-27T09:00:00Z")
    }
}
