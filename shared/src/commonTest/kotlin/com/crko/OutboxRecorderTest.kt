package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarSyncState
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.storage.MemoryOutboxStore
import com.crko.storage.OutboxBackend
import com.crko.state.sync.OutboxRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun writableCalendar(id: String = "cal1") = Calendar(
    id = id,
    name = "Server",
    syncState = CalendarSyncState(url = "https://example.com/cal/", writable = true),
)

private fun utcEvent(
    id: String,
    title: String,
    uid: String? = null,
    sequence: Int = 0,
    href: String? = null,
    etag: String? = null,
) = Event(
    id = id,
    title = title,
    start = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-03-01T10:00:00Z")),
    end = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-03-01T11:00:00Z")),
    icsUid = uid,
    sequence = sequence,
    sync = href?.let { com.crko.domain.RecordSyncMeta(it, etag) },
)

class OutboxRecorderTest {

    @Test
    fun aNewRecordGetsItsPublishUidAndACreateRow() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val event = utcEvent("e1", "One")
        val next = AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(event))))

        val recorded = recorder.record(AppState(), next)
        val stored = recorded.calendars.last().events.single()
        assertEquals("e1@crko", stored.icsUid)
        val entry = assertNotNull(outbox.entry(OutboxBackend.CalDav, "cal1", "event", "e1@crko", ""))
        assertTrue(entry.isCreate)
        assertNull(entry.baseEtag)
        assertEquals(setOf("cal1"), recorder.touchedCalendars)
    }

    @Test
    fun aSecondEditReplacesThePendingRowInsteadOfStacking() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val created = recorder.record(AppState(), AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(utcEvent("e1", "One"))))))
        val uid = created.calendars.last().events.single().icsUid!!

        val edited = created.calendars.last().events.single().copy(title = "One edited", sequence = 1)
        val next = AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(edited))))
        val recorded = recorder.record(created, next)

        assertEquals(1, outbox.entries().size)
        val entry = assertNotNull(outbox.entry(OutboxBackend.CalDav, "cal1", "event", uid, ""))
        assertTrue(entry.isUpdate)
        assertEquals(1, entry.sequence)
    }

    @Test
    fun deletingARecordThatWasNeverPushedCancelsTheRow() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val created = recorder.record(AppState(), AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(utcEvent("e1", "One"))))))
        val uid = created.calendars.last().events.single().icsUid!!

        val next = AppState(calendars = listOf(Calendar.local(), writableCalendar()))
        recorder.record(created, next)

        assertNull(outbox.entry(OutboxBackend.CalDav, "cal1", "event", uid, ""))
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun deletingAPushedRecordQueuesADeleteWithItsLocation() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val pushed = utcEvent("e1", "One", uid = "a@x", href = "https://example.com/cal/a.ics", etag = "\"v1\"")
        val base = AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(pushed))))

        val next = AppState(calendars = listOf(Calendar.local(), writableCalendar()))
        recorder.record(base, next)

        val entry = assertNotNull(outbox.entry(OutboxBackend.CalDav, "cal1", "event", "a@x", ""))
        assertTrue(entry.isDelete)
        assertEquals("\"v1\"", entry.baseEtag)
        assertEquals("https://example.com/cal/a.ics", entry.href)
    }

    @Test
    fun anUndoAfterAPendingDeleteTurnsBackIntoAnUpload() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val pushed = utcEvent("e1", "One", uid = "a@x", href = "https://example.com/cal/a.ics", etag = "\"v1\"")
        val base = AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(pushed))))
        val deleted = AppState(calendars = listOf(Calendar.local(), writableCalendar()))
        val queued = recorder.record(base, deleted)
        assertTrue(outbox.entry(OutboxBackend.CalDav, "cal1", "event", "a@x", "")!!.isDelete)

        // The undo restores the same record: the pending delete becomes an update of the content.
        val restored = queued.copy(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(pushed))))
        recorder.record(queued, restored)
        val entry = assertNotNull(outbox.entry(OutboxBackend.CalDav, "cal1", "event", "a@x", ""))
        assertTrue(entry.isUpdate)
    }

    @Test
    fun aFreshEtagAloneIsNotAnEdit() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val old = utcEvent("e1", "One", uid = "a@x", href = "https://example.com/cal/a.ics", etag = "\"v1\"")
        val base = AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(old))))
        val refreshed = old.copy(sync = com.crko.domain.RecordSyncMeta(old.sync!!.href, "\"v2\""))
        val next = AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(refreshed))))

        recorder.record(base, next)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun removingAWritableCalendarClearsItsQueueWithoutTouches() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val base = AppState(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(utcEvent("e1", "One", uid = "a@x")))))
        // Simulate a pending row by recording a create first.
        val queued = recorder.record(AppState(), base)
        assertTrue(outbox.entries().isNotEmpty())

        val next = AppState(calendars = listOf(Calendar.local()))
        recorder.record(queued, next)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun readOnlyCalendarsPassThroughUntouched() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 })
        val subscription = Calendar(
            id = "feed",
            name = "Feed",
            syncState = CalendarSyncState(url = "https://example.com/feed.ics", writable = false),
        )
        val next = AppState(calendars = listOf(Calendar.local(), subscription.copy(events = listOf(utcEvent("e1", "One")))))

        val recorded = recorder.record(AppState(), next)
        assertTrue(outbox.entries().isEmpty())
        assertNull(recorded.calendars.last().events.single().icsUid)
    }

    // ----- cloud fan-out -----

    @Test
    fun anActiveCloudAccountQueuesLocalCalendarChangesAsCloudRows() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 }, cloudActive = { true })
        val next = AppState(calendars = listOf(Calendar.local(name = "Personal").copy(events = listOf(utcEvent("e1", "One")))))

        val recorded = recorder.record(AppState(), next)
        val stored = recorded.calendars.first().events.single()
        assertEquals("e1@crko", stored.icsUid)
        assertEquals(2, outbox.entriesForBackend(OutboxBackend.Cloud).size)
        val recordRow = assertNotNull(outbox.entry(OutboxBackend.Cloud, "local", "event", "e1@crko", ""))
        assertTrue(recordRow.isCreate)
        assertEquals(OutboxBackend.Cloud, recordRow.backend)
        // The calendar config row rides along with the record row.
        assertNotNull(outbox.entry(OutboxBackend.Cloud, "local", "calendar", "local", ""))
        assertTrue(outbox.entriesForBackend(OutboxBackend.CalDav).isEmpty())
    }

    @Test
    fun anInactiveCloudAccountNeverQueuesCloudRows() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 }, cloudActive = { false })
        val next = AppState(calendars = listOf(Calendar.local().copy(events = listOf(utcEvent("e1", "One")))))

        recorder.record(AppState(), next)
        assertTrue(outbox.entries().isEmpty())
    }

    @Test
    fun aLocalCalendarFansToCloudWhileACaldavCalendarFansToItsServer() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 }, cloudActive = { true })
        // A CalDAV calendar is never local, so the two backends never share a calendar: the local
        // calendar's edit queues as a cloud row (record plus renamed config), the writable CalDAV
        // calendar's edit queues for its own server.
        val next = AppState(
            calendars = listOf(
                Calendar.local(name = "Renamed").copy(events = listOf(utcEvent("e1", "One"))),
                writableCalendar("srv").copy(events = listOf(utcEvent("e2", "Two"))),
            ),
        )
        recorder.record(AppState(), next)

        assertEquals(2, outbox.entriesForBackend(OutboxBackend.Cloud).size)
        assertEquals(1, outbox.entriesForBackend(OutboxBackend.CalDav).size)
        assertEquals(3, outbox.entries().size)
        assertEquals("e1@crko", outbox.entry(OutboxBackend.Cloud, "local", "event", "e1@crko", "")!!.uid)
        assertEquals("e2@crko", outbox.entry(OutboxBackend.CalDav, "srv", "event", "e2@crko", "")!!.uid)
    }

    @Test
    fun aCloudDeletionOfADrainedRecordQueuesATombstone() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 }, cloudActive = { true })
        val base = AppState(calendars = listOf(Calendar.local().copy(events = listOf(utcEvent("e1", "One", uid = "a@x")))))
        recorder.record(AppState(), base)
        // Pretend the bootstrap already pushed everything: the queue is empty, so a deletion has
        // nothing to cancel and must answer with a tombstone the server can store.
        outbox.deleteForBackend(OutboxBackend.Cloud)

        val next = AppState(calendars = listOf(Calendar.local()))
        recorder.record(base, next)

        val row = assertNotNull(outbox.entry(OutboxBackend.Cloud, "local", "event", "a@x", ""))
        assertTrue(row.isDelete)
    }

    @Test
    fun aPendingCloudCreateCancelledByADeleteDropsTheRow() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 }, cloudActive = { true })
        val created = recorder.record(AppState(), AppState(calendars = listOf(Calendar.local().copy(events = listOf(utcEvent("e1", "One"))))))
        val uid = created.calendars.first().events.single().icsUid!!

        // The create never drained, so the server has nothing to delete: cancel instead of
        // tombstoning, exactly like the CalDAV half does for a never-pushed record.
        val next = AppState(calendars = listOf(Calendar.local()))
        recorder.record(created, next)

        assertNull(outbox.entry(OutboxBackend.Cloud, "local", "event", uid, ""))
        assertTrue(outbox.entriesForBackend(OutboxBackend.Cloud).isEmpty())
    }

    @Test
    fun removingALocalCalendarUnderCloudSyncQueuesTombstones() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 }, cloudActive = { true })
        val base = AppState(calendars = listOf(Calendar.local().copy(events = listOf(utcEvent("e1", "One", uid = "a@x")))))
        val queued = recorder.record(AppState(), base)
        outbox.deleteForBackend(OutboxBackend.Cloud) // pretend the bootstrap already pushed

        val next = AppState(calendars = listOf(writableCalendar()))
        recorder.record(queued, next)

        assertTrue(outbox.entry(OutboxBackend.Cloud, "local", "calendar", "local", "")!!.isDelete)
        assertTrue(outbox.entry(OutboxBackend.Cloud, "local", "event", "a@x", "")!!.isDelete)
    }

    @Test
    fun aCalendarRenameQueuesAConfigUpdate() {
        val outbox = MemoryOutboxStore()
        val recorder = OutboxRecorder(outbox, nowMillis = { 1_000 }, cloudActive = { true })
        val base = AppState(calendars = listOf(Calendar.local(name = "Personal")))
        recorder.record(AppState(), base)
        outbox.deleteForBackend(OutboxBackend.Cloud)

        val next = AppState(calendars = listOf(Calendar.local(name = "Renamed")))
        recorder.record(base, next)

        val row = assertNotNull(outbox.entry(OutboxBackend.Cloud, "local", "calendar", "local", ""))
        assertFalse(row.isDelete)
    }
}
