package com.crko

import com.crko.domain.AlarmRelated
import com.crko.domain.AlarmTrigger
import com.crko.domain.CalAddress
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.EventClass
import com.crko.domain.IcsPartStat
import com.crko.domain.IcsTimeValue
import com.crko.domain.ItipReply
import com.crko.domain.JournalStatus
import com.crko.domain.RelatedTo
import com.crko.domain.RepeatFrequency
import com.crko.domain.TodoStatus
import com.crko.domain.applyingReply
import com.crko.domain.exportCalendar
import com.crko.domain.importCalendar
import com.crko.domain.triggerMinutes
import com.crko.storage.decodeStateWithReport
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StageFourTest {

    private val utc = kotlinx.datetime.TimeZone.UTC

    private fun calendar(vararg components: String): String = buildString {
        append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n")
        components.forEach { component ->
            component.trimIndent().split('\n').forEach { line -> append(line.trimEnd()).append("\r\n") }
        }
        append("END:VCALENDAR\r\n")
    }

    @Test
    fun attendeeParametersSurviveAnEditAndAPartStatReply() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:attendee@x
            SUMMARY:Review
            DTSTART:20261027T090000Z
            ORGANIZER;CN=Org;SENT-BY="mailto:assistant@x":mailto:org@x
            ATTENDEE;CN=Jane;ROLE=OPT-PARTICIPANT;PARTSTAT=NEEDS-ACTION;CUTYPE=INDIVIDUAL;RSVP=TRUE;
             X-VENDOR=jane;DELEGATED-FROM="mailto:lead@x":mailto:jane@x
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        val organizer = assertNotNull(event.organizer)
        assertEquals("Org", organizer.cn)
        assertEquals("mailto:assistant@x", organizer.sentBy)

        val jane = event.attendees.single()
        assertEquals("Jane", jane.cn)
        assertEquals("OPT-PARTICIPANT", jane.role)
        assertEquals(IcsPartStat.NeedsAction, jane.partStat)
        assertEquals("INDIVIDUAL", jane.cutype)
        assertEquals(true, jane.rsvp)
        assertEquals(listOf("mailto:lead@x"), jane.delegatedFrom)
        assertEquals("jane", assertNotNull(jane.parameters["X-VENDOR"]))

        // An editor round trip keeps every parameter it does not model.
        val exported = exportCalendar(listOf(event), now = Instant.parse("2026-10-27T00:00:00Z"))
        assertTrue(exported.contains("CN=Jane"), exported)
        assertTrue(exported.contains("ROLE=OPT-PARTICIPANT"), exported)
        assertTrue(exported.contains("CUTYPE=INDIVIDUAL"), exported)
        assertTrue(exported.contains("RSVP=TRUE"), exported)
        assertTrue(exported.contains("X-VENDOR=jane"), exported)
        assertTrue(exported.contains("DELEGATED-FROM=\"mailto:lead@x\""), exported)

        val result = listOf(event).applyingReply(
            ItipReply(
                uid = "attendee@x",
                attendee = CalAddress("mailto:jane@x", partStat = IcsPartStat.Accepted),
            ),
        )
        assertTrue(result.applied)
        val replied = result.events.single().attendees.single()
        assertEquals(IcsPartStat.Accepted, replied.partStat)
        // Only PARTSTAT moved; CN/ROLE/CUTYPE/RSVP and unknown parameters are untouched.
        assertEquals("Jane", replied.cn)
        assertEquals("OPT-PARTICIPANT", replied.role)
        assertEquals("INDIVIDUAL", replied.cutype)
        assertEquals(true, replied.rsvp)
        assertEquals("jane", assertNotNull(replied.parameters["X-VENDOR"]))
    }

    @Test
    fun alarmsRoundTripRelativeAbsoluteRepeatAndUnknownProperties() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:alarms@x
            SUMMARY:Alarms
            DTSTART:20261027T090000Z
            DTEND:20261027T100000Z
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT30M
            DESCRIPTION:Before
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER;RELATED=END:+PT10M
            REPEAT:2
            DURATION:PT5M
            X-VENDOR:keep
            END:VALARM
            BEGIN:VALARM
            ACTION:AUDIO
            TRIGGER;VALUE=DATE-TIME:20261027T083000Z
            END:VALARM
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        assertEquals(3, event.alarms.size)
        assertEquals(
            AlarmTrigger.Relative((-30).minutes, AlarmRelated.Start),
            event.alarms[0].trigger,
        )
        assertEquals(30, event.alarms[0].triggerMinutes)
        assertEquals(
            AlarmTrigger.Relative((10).minutes, AlarmRelated.End),
            event.alarms[1].trigger,
        )
        assertEquals(2, event.alarms[1].repeat)
        assertEquals(5.minutes, event.alarms[1].duration)
        assertTrue(event.alarms[1].extraProperties.any { it.startsWith("X-VENDOR") })
        assertEquals(
            AlarmTrigger.Absolute(IcsTimeValue.Utc(Instant.parse("2026-10-27T08:30:00Z"))),
            event.alarms[2].trigger,
        )

        val exported = exportCalendar(listOf(event), now = Instant.parse("2026-10-27T00:00:00Z"))
        assertTrue(exported.contains("TRIGGER:-PT30M"), exported)
        assertTrue(exported.contains("TRIGGER;RELATED=END:+PT10M"), exported)
        assertTrue(exported.contains("REPEAT:2"), exported)
        assertTrue(exported.contains("DURATION:PT5M"), exported)
        assertTrue(exported.contains("X-VENDOR:keep"), exported)
        assertTrue(exported.contains("TRIGGER;VALUE=DATE-TIME:20261027T083000Z"), exported)

        val back = importCalendar(exported, utc, "Untitled").events.single()
        assertEquals(event.alarms, back.alarms)
    }

    @Test
    fun attachmentsKeepUnknownParametersAndTheirPayloadKind() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:attach@x
            SUMMARY:Files
            DTSTART:20261027T090000Z
            ATTACH;FMTTYPE=application/pdf;FILENAME=agenda.pdf;X-EXTRA=yes:https://example.com/agenda.pdf
            ATTACH;ENCODING=BASE64;FMTTYPE=text/plain;FILENAME=note.txt:VGVzdA==
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        assertEquals(2, event.attachments.size)
        val uriAttachment = event.attachments[0]
        assertEquals("https://example.com/agenda.pdf", uriAttachment.uri)
        assertEquals("agenda.pdf", uriAttachment.name)
        assertEquals("application/pdf", uriAttachment.mimeType)
        assertEquals("yes", assertNotNull(uriAttachment.parameter("X-EXTRA")))
        val binaryAttachment = event.attachments[1]
        assertTrue(binaryAttachment.binary)
        assertEquals("VGVzdA==", binaryAttachment.data)
        assertEquals("note.txt", binaryAttachment.name)
        assertEquals("text/plain", binaryAttachment.mimeType)

        val exported = exportCalendar(listOf(event), now = Instant.parse("2026-10-27T00:00:00Z"))
            .replace("\r\n ", "")
        assertTrue(
            exported.contains(
                "ATTACH;FMTTYPE=application/pdf;FILENAME=agenda.pdf;X-EXTRA=yes:https://example.com/agenda.pdf",
            ),
            exported,
        )
        assertTrue(
            exported.contains("ATTACH;ENCODING=BASE64;FMTTYPE=text/plain;FILENAME=note.txt:VGVzdA=="),
            exported,
        )
        assertEquals(event.attachments, importCalendar(exported, utc, "Untitled").events.single().attachments)
    }

    @Test
    fun todoPriorityZeroThroughNineRoundTrips() {
        for (priority in 0..9) {
            val text = calendar(
                """
                BEGIN:VTODO
                UID:priority-$priority
                SUMMARY:Priority $priority
                PRIORITY:$priority
                END:VTODO
                """,
            )
            val todo = importCalendar(text, utc, "Untitled").todos.single()
            assertEquals(priority, todo.priority)
            val exported = exportCalendar(emptyList(), listOf(todo), now = Instant.parse("2026-10-27T00:00:00Z"))
            assertTrue(exported.contains("PRIORITY:$priority"), exported)
        }
    }

    @Test
    fun timedTodoKeepsDueCompletedAndEveryRelatedToType() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo@x
            SUMMARY:Report
            DUE:20261101T093000Z
            STATUS:COMPLETED
            COMPLETED:20261031T123456Z
            PRIORITY:4
            RELATED-TO:parent-todo
            RELATED-TO;RELTYPE=CHILD:child-todo
            RELATED-TO;RELTYPE=SIBLING:sibling-todo
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(IcsTimeValue.Utc(Instant.parse("2026-11-01T09:30:00Z")), todo.due)
        assertEquals(Instant.parse("2026-10-31T12:34:56Z"), todo.completed)
        assertEquals(4, todo.priority)
        assertTrue(todo.done)
        assertEquals(TodoStatus.Completed, todo.status)
        assertEquals(
            listOf(
                RelatedTo("parent-todo", "PARENT"),
                RelatedTo("child-todo", "CHILD"),
                RelatedTo("sibling-todo", "SIBLING"),
            ),
            todo.relatedTo,
        )
        assertEquals("2026-10-31", todo.completedAt)

        val exported = exportCalendar(emptyList(), listOf(todo), now = Instant.parse("2026-10-27T00:00:00Z"))
        assertTrue(exported.contains("DUE:20261101T093000Z"), exported)
        assertTrue(exported.contains("COMPLETED:20261031T123456Z"), exported)
        assertTrue(exported.contains("PRIORITY:4"), exported)
        assertTrue(exported.contains("RELATED-TO:parent-todo"), exported)
        assertTrue(exported.contains("RELATED-TO;RELTYPE=CHILD:child-todo"), exported)
        assertTrue(exported.contains("RELATED-TO;RELTYPE=SIBLING:sibling-todo"), exported)
        assertEquals(todo.relatedTo, importCalendar(exported, utc, "Untitled").todos.single().relatedTo)
    }

    @Test
    fun journalRecurrenceStatusAndClassRoundTrip() {
        val text = calendar(
            """
            BEGIN:VJOURNAL
            UID:journal@x
            SUMMARY:Notes
            DTSTART;VALUE=DATE:20261027
            RRULE:FREQ=WEEKLY;COUNT=3
            RDATE;VALUE=DATE:20261110
            EXDATE;VALUE=DATE:20261103
            STATUS:FINAL
            CLASS:PRIVATE
            END:VJOURNAL
            """,
        )
        val journal = importCalendar(text, utc, "Untitled").journals.single()
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 27)), journal.start)
        assertEquals("2026-10-27", journal.date)
        val recurrence = assertNotNull(journal.recurrence)
        assertEquals(RepeatFrequency.Weekly, recurrence.rule?.frequency)
        assertEquals(3, recurrence.rule?.count)
        assertEquals(listOf(IcsTimeValue.Date(LocalDate(2026, 11, 10))), recurrence.rdates.map { it.value })
        assertEquals(listOf(IcsTimeValue.Date(LocalDate(2026, 11, 3))), recurrence.exdates)
        assertEquals(JournalStatus.Final, journal.status)
        assertEquals(EventClass.Private, journal.eventClass)

        val exported = exportCalendar(emptyList(), now = Instant.parse("2026-10-27T00:00:00Z"), journals = listOf(journal))
        assertTrue(exported.contains("RRULE:FREQ=WEEKLY;COUNT=3"), exported)
        assertTrue(exported.contains("RDATE;VALUE=DATE:20261110"), exported)
        assertTrue(exported.contains("EXDATE;VALUE=DATE:20261103"), exported)
        assertTrue(exported.contains("STATUS:FINAL"), exported)
        assertTrue(exported.contains("CLASS:PRIVATE"), exported)

        val back = importCalendar(exported, utc, "Untitled").journals.single()
        assertEquals(journal.recurrence, back.recurrence)
        assertEquals(journal.status, back.status)
        assertEquals(journal.eventClass, back.eventClass)
    }

    @Test
    fun schemaFourPayloadMigratesParticipantsAlarmsAttachmentsTodoAndJournal() {
        val payload = """
            {"schemaVersion":4,
             "events":[{"id":"e","title":"Old","start":{"type":"floating","dateTime":"2026-10-27T09:00"},
               "end":{"type":"floating","dateTime":"2026-10-27T10:00"},
               "organizer":"mailto:org@x",
               "organizerRaw":"ORGANIZER;CN=Org:mailto:org@x",
               "attendees":["mailto:jane@x"],
               "attendeeRaw":["ATTENDEE;CN=Jane;PARTSTAT=ACCEPTED;X-VENDOR=1:mailto:jane@x"],
               "alarms":[{"triggerMinutes":15,"relatedToEnd":false,"description":"Old alarm"}],
               "attachments":[{"raw":"ATTACH;FMTTYPE=text/plain;FILENAME=n.txt","uri":"https://example.com/n.txt","name":"n.txt","mimeType":"text/plain"}],
               "importSource":"old.ics","sourceLine":3}],
             "todos":[{"id":"t","title":"Old todo","done":true,"priority":"High",
               "start":"2026-10-27","startTime":"2026-10-27T01:00:00Z",
               "due":"2026-10-28","dueTime":"2026-10-28T02:00:00Z",
               "completedAt":"2026-10-28","completedTime":"2026-10-28T02:30:00Z",
               "relatedTo":["parent"],"status":"NeedsAction",
               "alarms":[{"triggerMinutes":30,"relatedToEnd":true}],
               "importSource":"old.ics","sourceLine":5}],
             "journals":[{"id":"j","title":"Old journal","date":"2026-10-27",
               "startTime":"2026-10-27T03:00:00Z","importSource":"old.ics","sourceLine":7}]}
        """.trimIndent()
        val decoded = decodeStateWithReport(payload)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(CurrentSchemaVersion, decoded.state.schemaVersion)

        val event = decoded.state.events.single()
        assertEquals("Org", event.organizer?.cn)
        assertEquals(IcsPartStat.Accepted, event.attendees.single().partStat)
        assertEquals("1", event.attendees.single().parameters["X-VENDOR"])
        assertEquals(
            AlarmTrigger.Relative((-15).minutes, AlarmRelated.Start),
            event.alarms.single().trigger,
        )
        assertEquals("https://example.com/n.txt", event.attachments.single().uri)
        assertEquals("old.ics", event.importSource)
        assertEquals(3, event.sourceLine)

        val todo = decoded.state.todos.single()
        assertEquals(TodoStatus.Completed, todo.status)
        assertTrue(todo.done)
        assertEquals(1, todo.priority)
        assertEquals(IcsTimeValue.Utc(Instant.parse("2026-10-27T01:00:00Z")), todo.start)
        assertEquals(IcsTimeValue.Utc(Instant.parse("2026-10-28T02:00:00Z")), todo.due)
        assertEquals(Instant.parse("2026-10-28T02:30:00Z"), todo.completed)
        assertEquals(listOf(RelatedTo("parent", "PARENT")), todo.relatedTo)
        assertEquals(AlarmRelated.End, (todo.alarms.single().trigger as AlarmTrigger.Relative).related)
        assertEquals("old.ics", todo.importSource)
        assertEquals(5, todo.sourceLine)

        val journal = decoded.state.journals.single()
        assertEquals(IcsTimeValue.Utc(Instant.parse("2026-10-27T03:00:00Z")), journal.start)
        assertEquals("old.ics", journal.importSource)
        assertEquals(7, journal.sourceLine)
    }

    companion object {
        private val now = Instant.parse("2026-10-27T00:00:00Z")
    }
}
