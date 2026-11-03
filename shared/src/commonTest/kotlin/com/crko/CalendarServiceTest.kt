package com.crko

import com.crko.domain.IcsImport
import com.crko.domain.TodoPriority
import com.crko.domain.effectiveUid
import com.crko.state.calendar.CalendarDeleteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CalendarServiceTest {

    @Test
    fun localCalendarsCanBeCreatedRenamedAndDeleted() {
        val f = Fixture()
        val created = assertNotNull(f.holder.createLocalCalendar("Work", 0xFF00FF00))
        assertEquals("Work", created.name)
        assertEquals(0xFF00FF00, created.colorArgb)
        assertTrue(created.visible)
        assertEquals(2, f.holder.localCalendars.size)

        assertTrue(f.holder.renameCalendar(created.id, "Job"))
        assertEquals("Job", f.holder.localCalendars.single { it.id == created.id }.name)
        assertFalse(f.holder.renameCalendar(created.id, "   "))
        assertEquals("Job", f.holder.localCalendars.single { it.id == created.id }.name)

        assertEquals(CalendarDeleteResult.Success, f.holder.deleteCalendar(created.id))
        assertNull(f.holder.localCalendars.firstOrNull { it.id == created.id })
    }

    @Test
    fun theLastLocalCalendarCannotBeDeleted() {
        val f = Fixture()
        assertNotNull(f.holder.addSubscription("Feed", "https://example.com/cal.ics"))
        val local = f.holder.localCalendars.single()

        assertEquals(CalendarDeleteResult.LastLocalCalendar, f.holder.deleteCalendar(local.id))
        assertEquals(listOf(local.id), f.holder.localCalendars.map { it.id })
    }

    @Test
    fun deletingACalendarRemovesOnlyItsRecordsAndClearsTheDefault() {
        val f = Fixture()
        val localId = f.holder.localCalendars.single().id
        val work = assertNotNull(f.holder.createLocalCalendar("Work", null))
        f.holder.saveEvent(
            testEvent(null, "Work event", f.holder.today.toString(), 540, 600),
            calendarId = work.id,
        )
        f.holder.saveEvent(testEvent(null, "Home event", f.holder.today.toString(), 600, 660))
        f.holder.addTodo("Work todo", TodoPriority.Normal, null, calendarId = work.id)
        f.holder.addTodo("Home todo", TodoPriority.Normal, null)
        f.holder.addJournal("Work journal", "note", f.holder.today, work.id)
        f.holder.addJournal("Home journal", "note", f.holder.today)
        assertTrue(f.holder.setDefaultCalendar(work.id))
        assertEquals(work.id, f.holder.newRecordCalendarId)

        assertEquals(CalendarDeleteResult.Success, f.holder.deleteCalendar(work.id))

        assertEquals(listOf("Home event"), f.holder.appState.events.map { it.title })
        assertEquals(listOf("Home todo"), f.holder.appState.todos.map { it.title })
        assertEquals(listOf("Home journal"), f.holder.appState.journals.map { it.title })
        assertNull(f.holder.defaultCalendarId)
        assertEquals(localId, f.holder.newRecordCalendarId)
    }

    @Test
    fun newRecordsFollowTheDefaultCalendarImmediately() {
        val f = Fixture()
        val localId = f.holder.localCalendars.single().id
        val work = assertNotNull(f.holder.createLocalCalendar("Work", null))

        f.holder.saveEvent(testEvent(null, "First", f.holder.today.toString(), 540, 600))
        f.holder.addTodo("First todo", TodoPriority.Normal, null)
        f.holder.addJournal("First journal", "", f.holder.today)
        assertTrue(f.holder.setDefaultCalendar(work.id))

        f.holder.saveEvent(testEvent(null, "Second", f.holder.today.toString(), 660, 720))
        f.holder.addTodo("Second todo", TodoPriority.Normal, null)
        f.holder.addJournal("Second journal", "", f.holder.today)

        val home = f.holder.appState.calendars.single { it.id == localId }
        val workCalendar = f.holder.appState.calendars.single { it.id == work.id }
        assertEquals(listOf("First"), home.events.map { it.title })
        assertEquals(listOf("First todo"), home.todos.map { it.title })
        assertEquals(listOf("First journal"), home.journals.map { it.title })
        assertEquals(listOf("Second"), workCalendar.events.map { it.title })
        assertEquals(listOf("Second todo"), workCalendar.todos.map { it.title })
        assertEquals(listOf("Second journal"), workCalendar.journals.map { it.title })
    }

    @Test
    fun theDefaultCalendarSurvivesAReload() {
        val storage = MemoryStorage()
        val first = Fixture(storage)
        val work = assertNotNull(first.holder.createLocalCalendar("Work", null))
        assertTrue(first.holder.setDefaultCalendar(work.id))

        val reloaded = Fixture(storage)
        assertEquals(work.id, reloaded.holder.defaultCalendarId)
        assertEquals(work.id, reloaded.holder.newRecordCalendarId)
    }

    @Test
    fun hiddenCalendarRecordsLeaveTheListsButStayStored() {
        val f = Fixture()
        val work = assertNotNull(f.holder.createLocalCalendar("Work", null))
        f.holder.saveEvent(
            testEvent(null, "Hidden event", f.holder.today.toString(), 540, 600),
            calendarId = work.id,
        )
        f.holder.addTodo("Hidden todo", TodoPriority.Normal, null, calendarId = work.id)
        f.holder.addJournal("Hidden journal", "", f.holder.today, work.id)
        assertEquals(1, f.holder.activeTodos().size)

        assertTrue(f.holder.setCalendarVisible(work.id, false))

        assertTrue(f.holder.eventsOn(f.holder.today).isEmpty())
        assertTrue(f.holder.activeTodos().isEmpty())
        assertTrue(f.holder.journalsSorted().isEmpty())
        assertEquals(1, f.holder.appState.events.size)
        assertEquals(1, f.holder.appState.todos.size)
        assertEquals(1, f.holder.appState.journals.size)

        assertTrue(f.holder.setCalendarVisible(work.id, true))
        assertEquals(1, f.holder.eventsOn(f.holder.today).size)
        assertEquals(1, f.holder.activeTodos().size)
        assertEquals(1, f.holder.journalsSorted().size)
    }

    @Test
    fun importsLandInTheSelectedCalendarOnly() {
        val f = Fixture()
        val work = assertNotNull(f.holder.createLocalCalendar("Work", null))
        val import = IcsImport(
            events = listOf(
                testEvent(null, "Imported", f.holder.today.toString(), 540, 600, icsUid = "imported-uid"),
            ),
        )

        f.holder.importCalendar(import, calendarId = work.id)

        val workCalendar = f.holder.appState.calendars.single { it.id == work.id }
        val home = f.holder.appState.calendars.single { it.id != work.id }
        assertEquals(listOf("Imported"), workCalendar.events.map { it.title })
        assertTrue(home.events.isEmpty())
    }

    @Test
    fun aSubtaskStaysInItsParentsCalendar() {
        val f = Fixture()
        val work = assertNotNull(f.holder.createLocalCalendar("Work", null))
        f.holder.addTodo("Parent", TodoPriority.Normal, null, calendarId = work.id)
        val parent = f.holder.activeTodos().single { it.title == "Parent" }

        f.holder.addTodo("Child", TodoPriority.Normal, null, parentUid = parent.effectiveUid)

        val workCalendar = f.holder.appState.calendars.single { it.id == work.id }
        val home = f.holder.appState.calendars.single { it.id != work.id }
        assertEquals(listOf("Parent", "Child"), workCalendar.todos.map { it.title })
        assertTrue(home.todos.isEmpty())
    }

    @Test
    fun oneCalendarExportsWithoutTheOthers() = runTest {
        val f = Fixture()
        val work = assertNotNull(f.holder.createLocalCalendar("Work", null))
        f.holder.saveEvent(
            testEvent(null, "Work event", f.holder.today.toString(), 540, 600),
            calendarId = work.id,
        )
        f.holder.saveEvent(testEvent(null, "Home event", f.holder.today.toString(), 660, 720))

        val single = f.holder.exportCalendarIcs(work.id)
        assertTrue(single.contains("Work event"), single)
        assertFalse(single.contains("Home event"), single)
        assertTrue(single.contains("X-WR-CALNAME:Work"), single)

        val all = f.holder.exportCalendarIcs()
        assertTrue(all.contains("Work event"), all)
        assertTrue(all.contains("Home event"), all)
    }
}
