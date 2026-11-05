package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarSyncState
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.storage.MemoryOutboxStore
import com.crko.state.sync.OutboxRecorder
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val entry = assertNotNull(outbox.entry("cal1", "event", "e1@crko", ""))
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
        val entry = assertNotNull(outbox.entry("cal1", "event", uid, ""))
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

        assertNull(outbox.entry("cal1", "event", uid, ""))
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

        val entry = assertNotNull(outbox.entry("cal1", "event", "a@x", ""))
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
        assertTrue(outbox.entry("cal1", "event", "a@x", "")!!.isDelete)

        // The undo restores the same record: the pending delete becomes an update of the content.
        val restored = queued.copy(calendars = listOf(Calendar.local(), writableCalendar().copy(events = listOf(pushed))))
        recorder.record(queued, restored)
        val entry = assertNotNull(outbox.entry("cal1", "event", "a@x", ""))
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
}
