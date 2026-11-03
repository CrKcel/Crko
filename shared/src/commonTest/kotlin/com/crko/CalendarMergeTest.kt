package com.crko

import com.crko.domain.CancelledIcsRecord
import com.crko.domain.IcsImport
import com.crko.domain.IcsTimeValue
import com.crko.domain.Journal
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.gatedCancellations
import com.crko.domain.mergeEvents
import com.crko.domain.mergeJournals
import com.crko.domain.mergeTodos
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarMergeTest {

    private fun event(
        title: String,
        uid: String?,
        id: String? = "stored-$uid",
        sequence: Int = 0,
        dtStamp: String? = null,
        lastModified: String? = null,
        importSource: String? = null,
    ) = testEvent(
        id = id,
        title = title,
        date = "2026-10-27",
        startMinutes = 540,
        endMinutes = 600,
        icsUid = uid,
        sequence = sequence,
        dtStamp = dtStamp,
        lastModified = lastModified,
        importSource = importSource,
    )

    private fun newIds(): () -> String {
        var next = 0
        return { "new-${next++}" }
    }

    @Test
    fun sequenceWinsThenDtStampThenLastModified() {
        val base = event("Stored", "e-1", sequence = 1, dtStamp = "2026-10-01T00:00:00Z")
        val idProvider = newIds()

        val newerSequence = mergeEvents(
            base = listOf(base),
            incoming = listOf(event("New", "e-1", id = null, sequence = 2, dtStamp = "2026-09-01T00:00:00Z")),
            cancelledUids = emptySet(),
            source = null,
            removeVanished = false,
            idProvider = idProvider,
        )
        assertEquals(1, newerSequence.changed)
        assertEquals("New", newerSequence.items.single().title)
        assertEquals(base.id, newerSequence.items.single().id)

        val newerStamp = mergeEvents(
            base = listOf(base),
            incoming = listOf(event("New", "e-1", id = null, sequence = 1, dtStamp = "2026-10-02T00:00:00Z")),
            cancelledUids = emptySet(),
            source = null,
            removeVanished = false,
            idProvider = idProvider,
        )
        assertEquals(1, newerStamp.changed)
        assertEquals("New", newerStamp.items.single().title)

        val newerLastModified = mergeEvents(
            base = listOf(base),
            incoming = listOf(event("Last modified", "e-1", id = null, sequence = 1, lastModified = "2026-10-02T00:00:00Z")),
            cancelledUids = emptySet(),
            source = null,
            removeVanished = false,
            idProvider = idProvider,
        )
        assertEquals(1, newerLastModified.changed)
        assertEquals("Last modified", newerLastModified.items.single().title)
    }

    @Test
    fun missingOrOlderMetadataNeverOverwritesTheStoredCopy() {
        val base = event("Stored", "e-1", sequence = 1, dtStamp = "2026-10-01T00:00:00Z")
        val stale = mergeEvents(
            base = listOf(base),
            incoming = listOf(event("Stale", "e-1", id = null, sequence = 1, dtStamp = "2026-09-01T00:00:00Z")),
            cancelledUids = emptySet(),
            source = null,
            removeVanished = false,
            idProvider = newIds(),
        )
        assertEquals(0, stale.changed)
        assertEquals("Stored", stale.items.single().title)

        val withoutMetadata = mergeEvents(
            base = listOf(base),
            incoming = listOf(event("No metadata", "e-1", id = null, sequence = 1)),
            cancelledUids = emptySet(),
            source = null,
            removeVanished = false,
            idProvider = newIds(),
        )
        assertEquals(0, withoutMetadata.changed)
        assertEquals("Stored", withoutMetadata.items.single().title)
    }

    @Test
    fun aRenamedSourceTakesOverProvenanceWithoutReplacingTheRecord() {
        val base = event("Same", "e-1", sequence = 0, importSource = "old.ics")
        val result = mergeEvents(
            base = listOf(base),
            incoming = listOf(event("Same", "e-1", id = null, sequence = 0)),
            cancelledUids = emptySet(),
            source = "new.ics",
            removeVanished = false,
            idProvider = newIds(),
        )
        assertEquals(0, result.changed)
        assertEquals("new.ics", result.items.single().importSource)
    }

    @Test
    fun cancellationsApplyOnlyWhenAtLeastAsNewAsTheStoredCopy() {
        val stored = event("Stored", "e-1", sequence = 2)
        fun cancelled(sequence: Int) = IcsImport(
            cancelledUids = setOf("e-1"),
            cancelled = listOf(CancelledIcsRecord("e-1", "Stored", sequence = sequence)),
        )

        assertTrue(gatedCancellations(cancelled(sequence = 1), listOf(stored)).isEmpty())
        assertEquals(setOf("e-1"), gatedCancellations(cancelled(sequence = 2), listOf(stored)))
        assertEquals(setOf("e-1"), gatedCancellations(cancelled(sequence = 3), listOf(stored)))
    }

    @Test
    fun incrementalAndFullImportsRemoveVanishedRecordsDifferently() {
        val kept = event("Kept", "a", id = "id-a", importSource = "plan.ics")
        val vanished = event("Vanished", "b", id = "id-b", importSource = "plan.ics")
        val foreign = event("Foreign", "c", id = "id-c", importSource = "other.ics")
        val incoming = event("Kept", "a", id = null)

        val incremental = mergeEvents(
            base = listOf(kept, vanished, foreign),
            incoming = listOf(incoming),
            cancelledUids = emptySet(),
            source = "plan.ics",
            removeVanished = false,
            idProvider = newIds(),
        )
        // An incremental view only lists changes, so absence is not evidence of an upstream deletion.
        assertEquals(0, incremental.removed)
        assertEquals(listOf("Kept", "Vanished", "Foreign"), incremental.items.map { it.title })

        val full = mergeEvents(
            base = listOf(kept, vanished, foreign),
            incoming = listOf(incoming),
            cancelledUids = emptySet(),
            source = "plan.ics",
            removeVanished = true,
            idProvider = newIds(),
        )
        // A file import only owns what it brought in; the other source's record stays.
        assertEquals(1, full.removed)
        assertEquals(listOf("Kept", "Foreign"), full.items.map { it.title })

        val subscription = mergeEvents(
            base = listOf(kept, vanished, foreign),
            incoming = listOf(incoming),
            cancelledUids = emptySet(),
            source = null,
            removeVanished = true,
            describedUids = setOf("a"),
            idProvider = newIds(),
        )
        // A subscription owns its whole calendar, so every UID it no longer describes is gone.
        assertEquals(2, subscription.removed)
        assertEquals(listOf("Kept"), subscription.items.map { it.title })
    }

    @Test
    fun todosAndJournalsMergeByUidAndKeepTheStoredIdentity() {
        val storedTodo = Todo(id = "todo-id", title = "Old", icsUid = "t-1", sequence = 1, dtStamp = "2026-10-01T00:00:00Z")
        val todos = mergeTodos(
            base = listOf(storedTodo),
            incoming = listOf(Todo(id = "", title = "New", icsUid = "t-1", sequence = 2, dtStamp = "2026-10-02T00:00:00Z")),
            source = null,
            removeVanished = false,
            idProvider = newIds(),
        )
        assertEquals(1, todos.changed)
        assertEquals("todo-id", todos.items.single().id)
        assertEquals("New", todos.items.single().title)

        val storedJournal = Journal(id = "journal-id", title = "Old note", icsUid = "j-1", dtStamp = "2026-10-01T00:00:00Z")
        val journals = mergeJournals(
            base = listOf(storedJournal),
            incoming = listOf(Journal(id = "", title = "New note", icsUid = "j-1", dtStamp = "2026-10-02T00:00:00Z")),
            source = null,
            removeVanished = false,
            idProvider = newIds(),
        )
        assertEquals(1, journals.changed)
        assertEquals("journal-id", journals.items.single().id)
        assertEquals("New note", journals.items.single().title)
    }

    @Test
    fun todoInstancesKeepTheirSlotWhenTheyShareTheMasterUid() {
        val master = Todo(
            id = "todo-id",
            title = "Series",
            icsUid = "t-series",
            due = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            repeat = RepeatRule(RepeatFrequency.Daily),
        )
        val instance = Todo(
            id = "",
            title = "Series",
            icsUid = "t-series",
            due = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 5)),
            status = TodoStatus.Completed,
            sequence = 2,
            dtStamp = "2026-10-06T00:00:00Z",
        )
        val merged = mergeTodos(
            base = listOf(master),
            incoming = listOf(master.copy(id = ""), instance),
            source = null,
            removeVanished = false,
            idProvider = newIds(),
        )
        assertEquals(2, merged.items.size)
        assertEquals("todo-id", merged.items.first { it.recurrenceId == null }.id)
        val storedInstance = merged.items.first { it.recurrenceId != null }
        assertEquals("new-0", storedInstance.id)

        // A re-import updates that instance in place instead of adding a second one.
        val again = mergeTodos(
            base = merged.items,
            incoming = listOf(instance.copy(sequence = 3, dtStamp = "2026-10-07T00:00:00Z")),
            source = null,
            removeVanished = false,
            idProvider = newIds(),
        )
        assertEquals(2, again.items.size)
        assertTrue(again.items.first { it.recurrenceId != null }.sequence == 3)
    }
}
