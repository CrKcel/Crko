package com.crko

import com.crko.domain.AlarmRelated
import com.crko.domain.AlarmTrigger
import com.crko.domain.CalAddress
import com.crko.domain.CalendarComponent
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
import com.crko.domain.occursOn
import com.crko.domain.triggerMinutes
import com.crko.storage.decodeStateWithReport
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
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

    @Test
    fun delegatedAddressListsStayOneParameterAndSurviveTheRoundTrip() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:delegate-list@x
            SUMMARY:Handed on twice
            DTSTART:20261027T090000Z
            ATTENDEE;CN=Jane;PARTSTAT=DELEGATED;ROLE=REQ-PARTICIPANT;
             DELEGATED-FROM="mailto:lead@x";DELEGATED-TO="mailto:a@x","mailto:b@x":mailto:jane@x
            END:VEVENT
            """,
        )
        val jane = importCalendar(text, utc, "Untitled").events.single().attendees.single()
        // A list parameter names every value it carries, not only the last one.
        assertEquals(listOf("mailto:a@x", "mailto:b@x"), jane.delegatedTo)
        assertEquals(listOf("mailto:lead@x"), jane.delegatedFrom)

        val exported = exportCalendar(
            listOf(importCalendar(text, utc, "Untitled").events.single()),
            now = Instant.parse("2026-10-27T00:00:00Z"),
        ).replace("\r\n ", "")
        assertTrue(
            exported.contains("DELEGATED-TO=\"mailto:a@x\",\"mailto:b@x\""),
            exported,
        )
        val back = importCalendar(exported, utc, "Untitled").events.single().attendees.single()
        assertEquals(jane, back)
    }

    @Test
    fun alarmTriggersAndRepeatSpacingKeepTheirSeconds() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:alarm-seconds@x
            SUMMARY:Precise
            DTSTART:20261027T090000Z
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT45S
            END:VALARM
            BEGIN:VALARM
            ACTION:DISPLAY
            TRIGGER:-PT1H30M30S
            REPEAT:3
            DURATION:PT30S
            END:VALARM
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        // A duration is second-precise: rounding a trigger to whole minutes turned `-PT45S` into a
        // trigger at the very start, and a `DURATION` shorter than a minute into no spacing at all.
        assertEquals(AlarmTrigger.Relative((-45).seconds, AlarmRelated.Start), event.alarms[0].trigger)
        assertEquals(
            AlarmTrigger.Relative((-90).minutes + (-30).seconds, AlarmRelated.Start),
            event.alarms[1].trigger,
        )
        assertEquals(3, event.alarms[1].repeat)
        assertEquals(30.seconds, event.alarms[1].duration)

        val exported = exportCalendar(listOf(event), now = Instant.parse("2026-10-27T00:00:00Z"))
        assertTrue(exported.contains("TRIGGER:-PT45S"), exported)
        assertTrue(exported.contains("TRIGGER:-PT1H30M30S"), exported)
        assertTrue(exported.contains("DURATION:PT30S"), exported)
        assertEquals(event.alarms, importCalendar(exported, utc, "Untitled").events.single().alarms)
    }

    @Test
    fun todoExdateOnAZoneEastOfTheDeviceKeepsTheDayTheFileNames() {
        val newYork = kotlinx.datetime.TimeZone.of("America/New_York")
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo-exdate@x
            SUMMARY:Weekly report
            DTSTART;TZID=Asia/Shanghai:20261105T090000
            RRULE:FREQ=WEEKLY;BYDAY=TH;COUNT=4
            EXDATE;TZID=Asia/Shanghai:20261112T090000
            END:VTODO
            """,
        )
        val todo = importCalendar(text, newYork, "Untitled").todos.single()
        // The series expands on its own wall clock, so the exclusion is the day the file names —
        // projecting it through the device zone moved it to the day before and dropped it on export.
        assertEquals(listOf("2026-11-12"), todo.excludedDates)
        assertFalse(todo.occursOn(LocalDate(2026, 11, 12)))
        val exported = exportCalendar(emptyList(), listOf(todo), now = Instant.parse("2026-11-01T00:00:00Z"))
        assertTrue(exported.contains("EXDATE;VALUE=DATE:20261112"), exported)
    }

    @Test
    fun todoAddedDatesAreExportedEvenWithoutARule() {
        val text = calendar(
            """
            BEGIN:VTODO
            UID:todo-rdate@x
            SUMMARY:Extra day
            DTSTART;VALUE=DATE:20261105
            RDATE;VALUE=DATE:20261120
            EXDATE;VALUE=DATE:20261105
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertNull(todo.repeat)
        assertEquals(1, todo.rDates.size)
        assertEquals(listOf("2026-11-05"), todo.excludedDates)
        // The added and excluded instances belong to the recurrence set, which a `VTODO` may carry
        // without any `RRULE`; writing them only next to a rule dropped them from the export.
        val exported = exportCalendar(emptyList(), listOf(todo), now = Instant.parse("2026-11-01T00:00:00Z"))
        assertTrue(exported.contains("RDATE;VALUE=DATE:20261120"), exported)
        assertTrue(exported.contains("EXDATE;VALUE=DATE:20261105"), exported)
        val back = importCalendar(exported, utc, "Untitled").todos.single()
        assertEquals(todo.rDates, back.rDates)
        assertEquals(todo.excludedDates, back.excludedDates)
        assertTrue(back.occursOn(LocalDate(2026, 11, 20)))
        assertFalse(back.occursOn(LocalDate(2026, 11, 5)))
    }

    @Test
    fun journalExtensionStatusAndClassAreNotDropped() {
        val text = calendar(
            """
            BEGIN:VJOURNAL
            UID:journal-extension@x
            SUMMARY:Archived note
            DTSTART;VALUE=DATE:20261027
            STATUS:X-ARCHIVED
            CLASS:X-TEAM
            END:VJOURNAL
            """,
        )
        val journal = importCalendar(text, utc, "Untitled").journals.single()
        // Neither value has an entry in the model's enums; keeping the lines verbatim is what stops a
        // round trip from silently dropping what the file declared.
        assertNull(journal.status)
        assertNull(journal.eventClass)
        assertEquals(listOf("STATUS:X-ARCHIVED", "CLASS:X-TEAM"), journal.extraProperties)
        val exported = exportCalendar(
            emptyList(),
            now = Instant.parse("2026-10-27T00:00:00Z"),
            journals = listOf(journal),
        )
        assertTrue(exported.contains("STATUS:X-ARCHIVED"), exported)
        assertTrue(exported.contains("CLASS:X-TEAM"), exported)
        val back = importCalendar(exported, utc, "Untitled").journals.single()
        assertEquals(journal.extraProperties, back.extraProperties)
        // Exactly one STATUS and one CLASS, so the extension line does not duplicate a written value.
        assertEquals(1, exported.lines().count { it.startsWith("STATUS:") })
        assertEquals(1, exported.lines().count { it.startsWith("CLASS:") })
    }

    @Test
    fun everyCommonFieldTravelsWithEveryComponentKind() {
        listOf("VEVENT", "VTODO", "VJOURNAL").forEach { kind ->
            val timed = if (kind == "VEVENT") "DTSTART:20261027T090000Z\r\n" else ""
            val text = buildString {
                append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:").append(kind).append("\r\n")
                append("UID:common-").append(kind).append("@x\r\nSUMMARY:Common\r\nDESCRIPTION:A note\r\n")
                append("CATEGORIES:one,two\r\nURL:https://example.com/x\r\n")
                append("ATTACH;FMTTYPE=text/plain:https://example.com/a.txt\r\n")
                append("CREATED:20261001T080000Z\r\nLAST-MODIFIED:20261002T080000Z\r\n")
                append("DTSTAMP:20261003T080000Z\r\nSEQUENCE:5\r\nX-VENDOR:kept\r\n")
                append(timed)
                append("END:").append(kind).append("\r\nEND:VCALENDAR\r\n")
            }
            val imported = importCalendar(text, utc, "Untitled")
            val component: CalendarComponent = when (kind) {
                "VEVENT" -> imported.events.single()
                "VTODO" -> imported.todos.single()
                else -> imported.journals.single()
            }
            assertEquals("Common", component.title, kind)
            assertEquals("A note", component.note, kind)
            assertEquals(listOf("one", "two"), component.categories, kind)
            assertEquals("https://example.com/x", component.url, kind)
            assertEquals(1, component.attachments.size, kind)
            assertEquals(5, component.sequence, kind)
            assertNotNull(component.created, kind)
            assertNotNull(component.lastModified, kind)
            assertEquals(listOf("X-VENDOR:kept"), component.extraProperties, kind)

            val exported = exportCalendar(
                imported.events,
                imported.todos,
                now = Instant.parse("2026-10-27T00:00:00Z"),
                journals = imported.journals,
            ).replace("\r\n ", "")
            listOf(
                "UID:common-$kind@x", "DTSTAMP:20261003T080000Z", "CREATED:20261001T080000Z",
                "LAST-MODIFIED:20261002T080000Z", "SEQUENCE:5", "SUMMARY:Common", "DESCRIPTION:A note",
                "CATEGORIES:one,two", "URL:https://example.com/x",
                "ATTACH;FMTTYPE=text/plain:https://example.com/a.txt", "X-VENDOR:kept",
            ).forEach { property ->
                assertTrue(exported.contains(property), "$kind dropped [$property]:\n$exported")
            }
            listOf(
                "UID:", "DTSTAMP:", "CREATED:", "LAST-MODIFIED:", "SEQUENCE:", "SUMMARY:",
                "DESCRIPTION:", "CATEGORIES:", "URL:", "ATTACH",
            ).forEach { prefix ->
                assertEquals(
                    1,
                    exported.lines().count { it.startsWith(prefix) },
                    "$kind wrote [$prefix] twice:\n$exported",
                )
            }
        }
    }

    @Test
    fun longBase64AttachmentsFoldAndComeBackByteForByte() {
        val payload = (0 until 400).joinToString("") { (it % 10).toString() }
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:long-attach@x
            SUMMARY:Big file
            DTSTART:20261027T090000Z
            ATTACH;ENCODING=BASE64;VALUE=BINARY;FMTTYPE=application/octet-stream;X-EXTRA=1:$payload
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        val attachment = event.attachments.single()
        assertTrue(attachment.binary)
        assertEquals(payload, attachment.data)
        val exported = exportCalendar(listOf(event), now = Instant.parse("2026-10-27T00:00:00Z"))
        assertTrue(exported.split("\r\n").all { it.encodeToByteArray().size <= 75 }, exported)
        val back = importCalendar(exported, utc, "Untitled").events.single().attachments.single()
        assertEquals(attachment, back)
        assertEquals(payload, back.data)
    }

    @Test
    fun quotedParameterValuesRoundTripInsteadOfBreakingTheLine() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:param-quotes@x
            SUMMARY:Quoted
            DTSTART:20261027T090000Z
            ATTENDEE;CN="Doe, \"J\"";ROLE=CHAIR;PARTSTAT=ACCEPTED;CUTYPE=RESOURCE;RSVP=FALSE;
             SENT-BY="mailto:boss@x";DELEGATED-TO="mailto:a@x","mailto:b@x";X-DIR="mailto:d@x";
             X-NOTE="semi;colon,and:colon":mailto:jane@x
            END:VEVENT
            """,
        )
        val event = importCalendar(text, utc, "Untitled").events.single()
        val jane = event.attendees.single()
        // A quoted value keeps the commas, semicolons and colons inside it, and the quote that a
        // producer escaped with a backslash; none of them may be read as a separator.
        assertEquals("Doe, \"J\"", jane.cn)
        assertEquals("CHAIR", jane.role)
        assertEquals("RESOURCE", jane.cutype)
        assertEquals(false, jane.rsvp)
        assertEquals("mailto:boss@x", jane.sentBy)
        assertEquals(listOf("mailto:a@x", "mailto:b@x"), jane.delegatedTo)
        assertEquals("mailto:d@x", jane.parameters["X-DIR"])
        assertEquals("semi;colon,and:colon", jane.parameters["X-NOTE"])

        val exported = exportCalendar(listOf(event), now = Instant.parse("2026-10-27T00:00:00Z"))
        // Re-imported byte for byte: the quotes an exporter wrote are the ones the reader strips.
        val back = importCalendar(exported, utc, "Untitled").events.single()
        assertEquals(event.attendees, back.attendees)
        assertEquals(listOf("mailto:a@x", "mailto:b@x"), back.attendees.single().delegatedTo)
    }

    @Test
    fun anUnescapedQuoteInAParameterNoLongerDropsTheAttendee() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:param-stray@x
            SUMMARY:Stray quote
            DTSTART:20261027T090000Z
            ATTENDEE;CN=Bob "B" Smith;PARTSTAT=NEEDS-ACTION:mailto:bob@x
            END:VEVENT
            """,
        )
        // Producers do write an unescaped DQUOTE in a `CN` even though the RFC forbids it. Counting it
        // as the start of a quoted section hid the value separator, so the whole line parsed to nothing
        // and the attendee disappeared.
        val attendee = importCalendar(text, utc, "Untitled").events.single().attendees.single()
        assertEquals("Bob \"B\" Smith", attendee.cn)
        assertEquals("mailto:bob@x", attendee.calAddress)
        assertEquals(IcsPartStat.NeedsAction, attendee.partStat)
    }

    @Test
    fun anEscapedQuoteInsideAParameterListElementStaysInsideThatElement() {
        val text = calendar(
            """
            BEGIN:VEVENT
            UID:list-escape@x
            SUMMARY:Escaped list
            DTSTART:20261027T090000Z
            ATTENDEE;CN=Jane;DELEGATED-TO="mailto:a\"one@x","mailto:b@x":mailto:jane@x
            END:VEVENT
            """,
        )
        // A `\"` belongs to the quoted element it sits in: neither the quote nor the comma after it
        // may be read as a separator, or the first address swallows the second one.
        val attendee = importCalendar(text, utc, "Untitled").events.single().attendees.single()
        assertEquals(listOf("mailto:a\"one@x", "mailto:b@x"), attendee.delegatedTo, attendee.delegatedTo.toString())

        val exported = exportCalendar(
            listOf(importCalendar(text, utc, "Untitled").events.single()),
            now = Instant.parse("2026-10-27T00:00:00Z"),
        ).replace("\r\n ", "")
        assertTrue(
            exported.contains("DELEGATED-TO=\"mailto:a\\\"one@x\",\"mailto:b@x\""),
            exported,
        )
        assertEquals(attendee, importCalendar(exported, utc, "Untitled").events.single().attendees.single())
    }

    @Test
    fun aTodoExdateAfterItsOwnUntilIsNotExportedByTheDeviceClock() {
        val text = calendar(
            """
            BEGIN:VTIMEZONE
            TZID:Custom/Plus3
            BEGIN:STANDARD
            DTSTART:19700101T000000
            TZOFFSETFROM:+0300
            TZOFFSETTO:+0300
            END:STANDARD
            END:VTIMEZONE
            """,
            """
            BEGIN:VTODO
            UID:todo-until
            SUMMARY:Daily until
            DUE;TZID=Custom/Plus3:20261101T090000
            RRULE:FREQ=DAILY;UNTIL=20261102T190000Z
            EXDATE;VALUE=DATE:20261102,20261103
            END:VTODO
            """,
        )
        val todo = importCalendar(text, utc, "Untitled").todos.single()
        assertEquals(listOf("2026-11-02", "2026-11-03"), todo.excludedDates, todo.excludedDates.toString())
        // The series' real extent, before any exclusion: its own +03:00 clock passes the UTC `UNTIL`
        // on the third day, so that day has no instance at all.
        val unexcluded = todo.copy(excludedDates = emptyList())
        assertTrue(unexcluded.occursOn(LocalDate(2026, 11, 2)), "the 2nd is still inside the series")
        assertFalse(unexcluded.occursOn(LocalDate(2026, 11, 3)), "the 3rd is past the series' own UNTIL")

        val exported = exportCalendar(emptyList(), listOf(todo), now = Instant.parse("2026-11-01T00:00:00Z"))
        // The series runs on the todo's own +03:00 clock, so `UNTIL=…T190000Z` is passed on the third
        // and only the second day is still an instance: only that one may come back as `EXDATE`.
        // Reading the candidate day through the device zone instead (Shanghai reaches 19:00Z an hour
        // earlier) exported an exclusion for a day the series never has an instance on.
        assertTrue(exported.contains("EXDATE;VALUE=DATE:20261102"), exported)
        assertFalse("20261103" in exported, exported)
    }

    companion object {
        private val now = Instant.parse("2026-10-27T00:00:00Z")
    }
}
