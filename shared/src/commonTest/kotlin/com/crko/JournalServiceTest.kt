package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.Journal
import com.crko.state.WriteResult
import com.crko.ui.more.journalWithEdits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime

class JournalServiceTest {

    @Test
    fun journalsAreAddedUpdatedAndDeleted() {
        val f = Fixture()
        assertEquals(WriteResult.EmptyTitle, f.holder.addJournal("  ", "  ", null))
        f.holder.addJournal("Trip", "Notes from the road", f.holder.today)
        f.holder.addJournal("", "Quick thought", null)

        val sorted = f.holder.journalsSorted()
        // The blank/blank add was rejected; a dated journal sorts before the undated one.
        assertEquals(listOf("Trip", ""), sorted.map { it.title })
        assertEquals(f.holder.today.toString(), sorted.first().date)

        val updated = sorted.first().copy(title = "Trip 2026", note = "Revised")
        assertEquals(WriteResult.Success, f.holder.updateJournal(updated))
        val stored = f.holder.journalsSorted().first()
        assertEquals("Trip 2026", stored.title)
        assertEquals(1, stored.sequence)

        f.holder.deleteJournal(stored.id)
        assertEquals(1, f.holder.journalsSorted().size)
        // Updating or deleting an unknown id is a safe no-op.
        assertEquals(WriteResult.Success, f.holder.updateJournal(stored))
        f.holder.deleteJournal("missing")
        assertEquals(1, f.holder.journalsSorted().size)
    }

    @Test
    fun editingAJournalKeepsItsInstantOnlyWhileTheDateStays() {
        val imported = Journal(
            id = "j1",
            title = "Trip",
            note = "Notes",
            start = IcsTimeValue.Utc(Instant.parse("2026-10-27T09:30:00Z")),
        )
        // An untouched date keeps the imported instant.
        val kept = journalWithEdits(imported, "Trip 2026", "Revised", LocalDate.parse("2026-10-27"))
        assertEquals(IcsTimeValue.Utc(Instant.parse("2026-10-27T09:30:00Z")), kept.start)
        // A changed or cleared date drops the instant instead of exporting it over the picked date.
        val moved = journalWithEdits(imported, "Trip 2026", "Revised", LocalDate.parse("2026-11-01"))
        assertEquals(IcsTimeValue.Date(LocalDate.parse("2026-11-01")), moved.start)
        assertEquals("2026-11-01", moved.date)
        assertNull(journalWithEdits(imported, "Trip 2026", "Revised", null).start)
        // The title is trimmed; a journal without an instant is untouched by the rule.
        assertEquals("Trip 2026", kept.title)
        assertEquals("T", journalWithEdits(imported.copy(start = null), "  T  ", "n", null).title)
    }

    @Test
    fun editingAJournalWithoutTouchingItsShownDateKeepsTheInstant() {
        // The editor is seeded with the journal's displayed date and hands back exactly what it showed,
        // so "the date did not move" has to be decided in that same space. Compared against the value's
        // own wall date, a journal whose instant falls on another day in the display zone lost its time
        // and its zone on every save.
        val imported = Journal(
            id = "j2",
            title = "Late note",
            note = "Written before midnight",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 23, 30), "America/New_York"),
        )
        val shown = imported.date?.let { LocalDate.parse(it) }
        val edited = journalWithEdits(imported, "Late note", "Revised", shown)
        assertEquals(imported.start, edited.start)
        assertEquals(imported.date, edited.date)
        assertEquals("Revised", edited.note)
    }
}
