package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Attachment
import com.crko.domain.IcsImport
import com.crko.domain.IcsTimeValue
import com.crko.domain.IcsWarning
import com.crko.domain.IcsWarningDetail
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.domain.attachmentMimeType
import com.crko.domain.decodedBytes
import com.crko.domain.exportIcs
import com.crko.domain.importCalendar
import com.crko.i18n.EnStrings
import com.crko.state.EditScope
import com.crko.state.ImportOutcome
import com.crko.ui.common.importSummaryText
import com.crko.ui.transfer.importSelection
import com.crko.ui.transfer.toIcsImport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class ImportServiceTest {

    @Test
    fun importingEventsAssignsIdsAndSkipsKnownUids() {
        val f = Fixture()
        val imported = testEvent(null, "Imported", "2026-10-27", 540, 600, icsUid = "ics-1")
        assertEquals(1, f.holder.importEvents(listOf(imported)))
        assertEquals(0, f.holder.importEvents(listOf(imported)))
        val stored = f.holder.appState.events.single()
        assertEquals("id-0", stored.id)
        assertEquals("ics-1", stored.icsUid)
    }

    @Test
    fun reimportingAnExportOfNativeEventsDoesNotDuplicateThem() {
        val f = Fixture()
        f.holder.saveEvent(testEvent("native-1", "Native", "2026-10-27", 540, 600))
        val exported = testEvent(null, "Native", "2026-10-27", 540, 600, icsUid = "native-1@crko")
        assertEquals(0, f.holder.importEvents(listOf(exported)))
        assertEquals(1, f.holder.appState.events.size)
    }

    @Test
    fun importingAFileWritesEventsAndTodosTogether() {
        val f = Fixture()
        val import = IcsImport(
            events = listOf(testEvent(null, "Imported", "2026-10-27", 540, 600, icsUid = "file-1")),
            todos = listOf(Todo(id = "", title = "File todo", icsUid = "file-2")),
        )
        val writesBefore = f.storage.writes
        assertEquals(ImportOutcome(events = 1, todos = 1), f.holder.importCalendar(import))
        // Two writes meant a calendar left half-imported when the process died in between.
        assertEquals(1, f.storage.writes - writesBefore)
        assertEquals(1, f.holder.appState.events.size)
        assertEquals(1, f.holder.appState.todos.size)
        val afterFirst = f.storage.writes
        assertEquals(ImportOutcome(), f.holder.importCalendar(IcsImport(events = import.events, todos = import.todos)))
        // Nothing was new, so nothing was serialised either.
        assertEquals(afterFirst, f.storage.writes)
    }

    @Test
    fun reimportingTheSameFileStillCollapsesOwnExportsAndDuplicates() {
        val f = Fixture()
        val first = testEvent(null, "Meeting", "2026-10-27", 540, 600, icsUid = "uid-a")
        assertEquals(1, f.holder.importEvents(listOf(first, first.copy())))
        assertEquals(1, f.holder.appState.events.size)
        // An event created here is exported as "<id>@crko"; re-importing must not duplicate it.
        val stored = f.holder.appState.events.single()
        assertEquals(0, f.holder.importEvents(listOf(stored.copy(id = null, icsUid = "${stored.id}@crko"))))
        assertEquals(1, f.holder.appState.events.size)
    }

    @Test
    fun importingTwoChangesOnTheSameOccurrenceDateKeepsBoth() {
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:twice\r\nSUMMARY:Series\r\nDTSTART:20261027T090000\r\n" +
            "DTEND:20261027T100000\r\nRRULE:FREQ=DAILY;COUNT=5\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:twice\r\nRECURRENCE-ID:20261029T090000\r\nDTSTART:20261029T110000\r\n" +
            "DTEND:20261029T120000\r\nSUMMARY:First change\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:twice\r\nRECURRENCE-ID:20261029T130000\r\nDTSTART:20261029T150000\r\n" +
            "DTEND:20261029T160000\r\nSUMMARY:Second change\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val parsed = importCalendar(text, TimeZone.UTC, "Untitled")
        val f = Fixture()
        val (events, _) = f.holder.importCalendar(parsed)
        // Both changes share one occurrence date, so identical storage keys dropped the second one.
        assertEquals(3, events)
        assertEquals(
            listOf("First change", "Second change"),
            f.holder.appState.events.filter { it.recurrenceId != null }.map { it.title },
        )
    }

    @Test
    fun reimportingANewerCopyUpdatesTheStoredRecord() {
        val f = Fixture()
        val first = testEvent(
            null,
            "Old",
            "2026-10-27",
            540,
            600,
            icsUid = "ext-1",
            sequence = 1,
            dtStamp = "2026-10-01T00:00:00Z",
        )
        assertEquals(1, f.holder.importEvents(listOf(first)))
        val storedId = f.holder.appState.events.single().id

        val newer = first.copy(id = null, title = "New", sequence = 2, dtStamp = "2026-10-02T00:00:00Z")
        assertEquals(1, f.holder.importEvents(listOf(newer)))
        val stored = f.holder.appState.events.single()
        assertEquals("New", stored.title)
        assertEquals(storedId, stored.id)

        val stale = first.copy(id = null, title = "Stale", sequence = 0, dtStamp = "2026-09-01T00:00:00Z")
        assertEquals(0, f.holder.importEvents(listOf(stale)))
        assertEquals("New", f.holder.appState.events.single().title)
    }

    @Test
    fun reimportingACancelledCopyRemovesTheLocalSeries() {
        val f = Fixture()
        val series = testEvent(null, "Cancelled", "2026-10-27", 540, 600, icsUid = "cancel-uid")
        val exception = testEvent(
            null,
            "Moved",
            "2026-10-28",
            600,
            660,
            icsUid = "cancel-uid",
        ).copy(recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 28)))
        assertEquals(2, f.holder.importEvents(listOf(series, exception)))
        f.holder.importCalendar(IcsImport(cancelledUids = setOf("cancel-uid")))
        assertTrue(f.holder.appState.events.isEmpty())
    }

    @Test
    fun theImportFeedbackCountsJournalsAndRemovals() {
        val f = Fixture()
        val journal = Journal(id = "", title = "Note", icsUid = "j-1")
        assertEquals(ImportOutcome(journals = 1), f.holder.importCalendar(IcsImport(journals = listOf(journal))))
        assertEquals(1, f.holder.importEvents(listOf(testEvent(null, "Gone", "2026-10-27", 540, 600, icsUid = "gone"))))
        assertEquals(ImportOutcome(removed = 1), f.holder.importCalendar(IcsImport(cancelledUids = setOf("gone"))))
    }

    @Test
    fun reimportingTheSameSourceRemovesRecordsTheFileNoLongerLists() {
        val f = Fixture()
        val kept = testEvent(null, "Kept", "2026-10-27", 540, 600, icsUid = "kept-1")
        val gone = testEvent(null, "Gone", "2026-10-28", 540, 600, icsUid = "gone-1")
        assertEquals(ImportOutcome(events = 2), f.holder.importCalendar(IcsImport(events = listOf(kept, gone)), "plan.ics"))
        // The file still carries one of the two records, so the other one was removed upstream.
        assertEquals(
            ImportOutcome(removed = 1),
            f.holder.importCalendar(IcsImport(events = listOf(kept)), "plan.ics"),
        )
        assertEquals(listOf("Kept"), f.holder.appState.events.map { it.title })
    }

    @Test
    fun recordsFromOtherSourcesSurviveAReimport() {
        val f = Fixture()
        f.holder.importEvents(listOf(testEvent(null, "Mine", "2026-10-27", 540, 600, icsUid = "mine-1")))
        f.holder.importCalendar(
            IcsImport(events = listOf(testEvent(null, "Other", "2026-10-28", 540, 600, icsUid = "other-1"))),
            "other.ics",
        )
        // An empty file of a source says nothing about records the source does not own.
        assertEquals(ImportOutcome(), f.holder.importCalendar(IcsImport(), "other.ics"))
        assertEquals(2, f.holder.appState.events.size)
    }

    @Test
    fun locallyCreatedRecordsSurviveSourceReconciliation() {
        val f = Fixture()
        assertEquals(1, f.holder.importEvents(listOf(testEvent(null, "Mine", "2026-10-27", 540, 600))))
        val first = testEvent(null, "Imported", "2026-10-28", 540, 600, icsUid = "imp-1")
        f.holder.importCalendar(IcsImport(events = listOf(first)), "plan.ics")
        val second = testEvent(null, "Imported", "2026-10-29", 540, 600, icsUid = "imp-2")
        assertEquals(
            ImportOutcome(events = 1, removed = 1),
            f.holder.importCalendar(IcsImport(events = listOf(second)), "plan.ics"),
        )
        assertEquals(listOf("Imported", "Mine"), f.holder.appState.events.map { it.title }.sorted())
    }

    @Test
    fun uncheckingAPreviewRowDoesNotRemoveTheRecordTheFileStillLists() {
        val f = Fixture()
        val first = testEvent(null, "First", "2026-10-27", 540, 600, icsUid = "e-1")
        val second = testEvent(null, "Second", "2026-10-28", 540, 600, icsUid = "e-2")
        assertEquals(
            ImportOutcome(events = 2),
            f.holder.importCalendar(IcsImport(events = listOf(first, second)), "plan.ics"),
        )
        // The re-imported file still describes both records, but the user unchecked the first one:
        // the update is skipped, not turned into an upstream removal.
        val import = IcsImport(
            events = listOf(second.copy(sequence = 1, dtStamp = "2026-10-30T00:00:00Z")),
            describedUids = setOf("e-1", "e-2"),
        )
        assertEquals(ImportOutcome(events = 1), f.holder.importCalendar(import, "plan.ics"))
        assertEquals(listOf("First", "Second"), f.holder.appState.events.map { it.title }.sorted())
    }

    @Test
    fun thePreviewSelectionSkipsAnUpdateWithoutDeletingTheRecord() {
        val f = Fixture()
        val file = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:e-1\r\nDTSTAMP:20261001T000000Z\r\n" +
            "DTSTART:20261027T090000\r\nDTEND:20261027T100000\r\nSUMMARY:First\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:e-2\r\nDTSTAMP:20261001T000000Z\r\n" +
            "DTSTART:20261028T090000\r\nDTEND:20261028T100000\r\nSUMMARY:Second\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val parsed = importCalendar(file, TimeZone.UTC, "Untitled")
        val selection = importSelection(parsed)
        assertEquals(2, selection.rows.size)
        assertEquals(
            ImportOutcome(events = 2),
            f.holder.importCalendar(selection.toIcsImport(listOf(true, true)), "plan.ics"),
        )
        // The same file arrives again and the user unchecks the first row: only the second record
        // is updated, and the unchecked one stays exactly as it was.
        val newer = importSelection(parsed.copy(events = parsed.events.map { it.copy(sequence = 3) }))
        assertEquals(
            ImportOutcome(events = 1),
            f.holder.importCalendar(newer.toIcsImport(listOf(false, true)), "plan.ics"),
        )
        assertEquals(listOf("First", "Second"), f.holder.appState.events.map { it.title }.sorted())
        assertEquals(3, f.holder.appState.events.single { it.title == "Second" }.sequence)
        assertEquals(0, f.holder.appState.events.single { it.title == "First" }.sequence)
    }

    @Test
    fun decliningACancellationDoesNotRemoveTheSeriesThroughTheVanishedPass() {
        val f = Fixture()
        val cancelled = testEvent(null, "Cancelled", "2026-10-27", 540, 600, icsUid = "c-1")
        val live = testEvent(null, "Live", "2026-10-28", 540, 600, icsUid = "c-2")
        f.holder.importCalendar(IcsImport(events = listOf(cancelled, live)), "plan.ics")
        // The new file cancels one series and keeps the other; the user declines the removal row,
        // so the declined series must survive the reconciliation of the same import as well.
        assertEquals(
            ImportOutcome(),
            f.holder.importCalendar(
                IcsImport(events = listOf(live), describedUids = setOf("c-1", "c-2")),
                "plan.ics",
            ),
        )
        assertEquals(listOf("Cancelled", "Live"), f.holder.appState.events.map { it.title }.sorted())
    }

    @Test
    fun aRenamedSourceTakesOverProvenanceEvenWhenNothingElseChanges() {
        val f = Fixture()
        val original = testEvent(
            null,
            "Same",
            "2026-10-27",
            540,
            600,
            icsUid = "p-1",
            dtStamp = "2026-10-01T00:00:00Z",
        )
        f.holder.importCalendar(IcsImport(events = listOf(original)), "plan.ics")
        // The renamed file carries the same stale copy: nothing is imported, but provenance has to
        // follow it anyway, or a later reconciliation against the new name would see no owner.
        assertEquals(
            ImportOutcome(),
            f.holder.importCalendar(
                IcsImport(events = listOf(original.copy(id = null))),
                "plan-renamed.ics",
            ),
        )
        assertEquals("plan-renamed.ics", f.storage.state.events.single().importSource)
    }

    @Test
    fun embeddedAttachmentsAreStoredAsFilesAndReEncodedOnExport() {
        val f = Fixture()
        val attachment = Attachment(
            value = "VGVzdA==",
            parameters = linkedMapOf(
                "FMTTYPE" to "text/plain",
                "ENCODING" to "BASE64",
                "FILENAME" to "notes.txt",
            ),
            binary = true,
        )
        val event = testEvent(null, "With file", "2026-10-27", 540, 600, icsUid = "att-1", attachments = listOf(attachment))
        f.holder.importCalendar(IcsImport(events = listOf(event)), "plan.ics")
        val stored = f.holder.appState.events.single().attachments.single()
        assertEquals("", stored.data)
        assertTrue(!stored.storedFile.isNullOrBlank(), stored.toString())
        // The payload no longer lives inside the serialized state, or imports would accumulate it.
        val serialized = AppJson.encodeToString(AppState.serializer(), f.storage.state)
        assertFalse(serialized.contains("VGVzdA=="), serialized)
        // Export re-encodes the payload from the stored file, parameters included.
        val exported = exportIcs(
            listOf(f.holder.appState.events.single()),
            storedAttachment = f.holder::readStoredAttachment,
        )
        assertTrue(
            exported.contains("ATTACH;FMTTYPE=text/plain;ENCODING=BASE64;FILENAME=notes.txt:VGVzdA=="),
            exported,
        )
        // Round trip: the exported base64 is still the imported payload.
        val reimported = importCalendar(exported, TimeZone.UTC, "Untitled").events.single()
        assertEquals("Test", reimported.attachments.single().decodedBytes()?.decodeToString())
    }

    @Test
    fun attachmentMimeTypesAreDerivedFromFmttypeOrFileName() {
        assertEquals("image/png", attachmentMimeType(Attachment(parameters = mapOf("FMTTYPE" to "image/png"))))
        assertEquals("image/png", attachmentMimeType(Attachment(parameters = mapOf("FILENAME" to "picture.png"))))
        assertEquals("text/plain", attachmentMimeType(Attachment(parameters = mapOf("FILENAME" to "readme.txt"), binary = true)))
        // Without any hint the payload is an opaque binary download.
        assertEquals("application/octet-stream", attachmentMimeType(Attachment(binary = true)))
    }

    @Test
    fun reimportingAnOwnExportExceptionDoesNotReplaceItsMaster() {
        val f = Fixture()
        val master = testEvent(
            null,
            "Base",
            "2026-10-27",
            540,
            600,
            icsUid = "native-1@crko",
        )
        val exception = testEvent(
            null,
            "Moved",
            "2026-10-28",
            600,
            660,
            icsUid = "native-1@crko",
        ).copy(recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 28)))
        assertEquals(2, f.holder.importEvents(listOf(master, exception)))
        val updated = exception.copy(
            id = null,
            title = "Moved again",
            sequence = 2,
            dtStamp = "2026-10-02T00:00:00Z",
        )
        assertEquals(1, f.holder.importEvents(listOf(updated)))
        val events = f.holder.appState.events
        assertEquals(2, events.size)
        assertEquals("Base", events.first { it.recurrenceId == null }.title)
        assertEquals("Moved again", events.first { it.recurrenceId != null }.title)
    }

    @Test
    fun reimportingAFileKeepsAnExceptionTheUserCreatedLocally() {
        val f = Fixture()
        val text = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" +
            "BEGIN:VEVENT\r\nUID:series-1\r\nSUMMARY:Standup\r\n" +
            "DTSTART:20261027T090000\r\nDTEND:20261027T093000\r\n" +
            "RRULE:FREQ=DAILY;COUNT=5\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nUID:series-1\r\nRECURRENCE-ID:20261029T090000\r\n" +
            "DTSTART:20261029T110000\r\nDTEND:20261029T113000\r\n" +
            "SUMMARY:Standup moved\r\nEND:VEVENT\r\n" +
            "END:VCALENDAR\r\n"
        val imported = com.crko.domain.importCalendar(text, TimeZone.UTC, "Untitled")
        f.holder.importCalendar(imported)
        assertEquals(2, f.holder.appState.events.size)

        // The user moves the 10-30 occurrence: that exception exists only in local storage.
        val series = f.holder.appState.events.first { it.recurrenceId == null }
        f.holder.saveEvent(
            series.copy(id = series.id, title = "Standup local"),
            occurrenceDate = LocalDate(2026, 10, 30),
            scope = EditScope.ThisEvent,
        )
        assertEquals(3, f.holder.appState.events.size)

        // Importing the same file again must not silently drop the local exception: a record that is
        // simply absent from the file is not evidence that it was deleted there.
        f.holder.importCalendar(imported)
        val titles = f.holder.appState.events.map { it.title }
        assertTrue(titles.contains("Standup local"), titles.toString())
        assertEquals(3, f.holder.appState.events.size)
    }

    @Test
    fun theImportMessageNamesOnlyTheFirstFewBrokenRecords() {
        val details = (1..300).map { index ->
            IcsWarningDetail(IcsWarning.MissingStart, line = index, record = "Broken $index")
        }
        val text = importSummaryText(
            strings = EnStrings,
            events = 1,
            todos = 0,
            journals = 0,
            removed = 0,
            warnings = listOf(IcsWarning.MissingStart),
            details = details,
        )
        assertTrue(text.contains("(297 more)"), text)
        assertTrue(text.length < 1_000, "message is ${text.length} characters")
        assertEquals(3, Regex("line \\d").findAll(text).count(), text)
    }

    @Test
    fun embeddedAttachmentsTolerateFoldedAndUnpaddedBase64() {
        assertEquals("Test", Attachment(binary = true, value = "VGVzdA==").decodedBytes()?.decodeToString())
        assertEquals("Test", Attachment(binary = true, value = "VGVzdA").decodedBytes()?.decodeToString())
        assertEquals("Test", Attachment(binary = true, value = "VGVz\r\n dA=").decodedBytes()?.decodeToString())
        assertNull(Attachment(binary = true, value = "!!!").decodedBytes())
    }
}
