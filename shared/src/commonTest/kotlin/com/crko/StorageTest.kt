package com.crko

import com.crko.domain.AlarmTrigger
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.PersistedSettings
import com.crko.domain.RecurrenceDate
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.decodeCalendarBytes
import com.crko.storage.KeyValueStore
import com.crko.storage.LoadDamage
import com.crko.storage.MemoryStateStorage
import com.crko.storage.StateStorage
import com.crko.storage.MemoryOutboxStore
import com.crko.storage.OutboxStore
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class MemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values: MutableMap<String, String> = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

private val utc = TimeZone.UTC

/** Record payloads live in SQLite as JSON, so the serializer itself must round-trip every shape. */
class StorageTest {

    @Test
    fun newPayloadRoundTripsEveryTimeValueVariant() {
        val events = listOf(
            Event(
                id = "date",
                title = "Date",
                start = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
                end = IcsTimeValue.Date(LocalDate(2026, 10, 29)),
            ),
            Event(
                id = "floating",
                title = "Floating",
                start = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 9, 0)),
                end = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 10, 0)),
            ),
            Event(
                id = "zoned",
                title = "Zoned",
                start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Europe/Berlin"),
                end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 10, 0), "Europe/Berlin"),
            ),
            Event(
                id = "utc",
                title = "Utc",
                start = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-10-27T09:00:00Z")),
                end = IcsTimeValue.Utc(kotlin.time.Instant.parse("2026-10-27T10:00:00Z")),
            ),
        )
        val state = AppState(calendars = listOf(Calendar.local().copy(events = events)))
        val encoded = AppJson.encodeToString(AppState.serializer(), state)
        assertEquals(events, AppJson.decodeFromString(AppState.serializer(), encoded).events)
    }

    @Test
    fun storedAlarmsAndPeriodLengthsRoundTripThroughJson() {
        // Stage 4 put a `Duration` on the always-written path: every relative `VALARM` and every
        // `RDATE;VALUE=PERIOD` length is one. A serializer whose descriptor reuses the serial name of
        // the value type it stands for makes the JVM registry throw, so saving such a state failed and
        // reading it back dropped the record instead.
        val events = listOf(
            Event(
                id = "alarmed",
                title = "With alarm",
                start = IcsTimeValue.Utc(Instant.parse("2026-10-27T09:00:00Z")),
                end = IcsTimeValue.Utc(Instant.parse("2026-10-27T10:00:00Z")),
                alarms = listOf(
                    com.crko.domain.Alarm(trigger = AlarmTrigger.Relative((-15).minutes)),
                ),
                recurrence = com.crko.domain.Recurrence(
                    rule = com.crko.domain.RepeatRule(RepeatFrequency.Daily, count = 3),
                    rdates = listOf(
                        RecurrenceDate(
                            IcsTimeValue.Utc(Instant.parse("2026-11-02T09:00:00Z")),
                            45.minutes,
                        ),
                    ),
                ),
            ),
        )
        val todos = listOf(
            com.crko.domain.Todo(
                id = "chore",
                title = "Repeating alarm",
                alarms = listOf(
                    com.crko.domain.Alarm(
                        trigger = AlarmTrigger.Relative((-5).minutes),
                        repeat = 2,
                        duration = 30.seconds,
                    ),
                ),
            ),
        )
        val state = AppState(
            calendars = listOf(Calendar.local().copy(events = events, todos = todos)),
        )
        val encoded = AppJson.encodeToString(AppState.serializer(), state)
        assertEquals(state, AppJson.decodeFromString(AppState.serializer(), encoded))
    }

    @Test
    fun completionInstancesRoundTripThroughThePayload() {
        val slot = IcsTimeValue.Date(LocalDate(2026, 10, 5))
        val master = Todo(
            id = "master",
            title = "Daily",
            due = slot,
            repeat = RepeatRule(RepeatFrequency.Daily),
        )
        val instance = Todo(
            id = "instance",
            title = "Daily",
            due = slot,
            icsUid = "master@crko",
            recurrenceId = slot,
            status = TodoStatus.Completed,
            percentComplete = 100,
            sequence = 1,
            completed = Instant.parse("2026-10-05T09:00:00Z"),
        )
        val state = AppState(calendars = listOf(Calendar.local().copy(todos = listOf(master, instance))))
        val encoded = AppJson.encodeToString(AppState.serializer(), state)
        assertEquals(
            state.calendars.single().todos,
            AppJson.decodeFromString(AppState.serializer(), encoded).calendars.single().todos,
        )
    }

    @Test
    fun settingsRoundTripThroughTheKeyValueStore() {
        val settings = MemoryStore()
        val storage = MemoryStateStorage({ settings })
        storage.saveSettings(PersistedSettings(language = "zh"))
        assertEquals("zh", storage.loadSettings().language)
        // An unreadable settings payload falls back to the defaults.
        settings.values["settings.v1"] = "{not json"
        assertEquals(PersistedSettings(), storage.loadSettings())
    }

    @Test
    fun calendarBytesDecodeUtf8AndFallBackToLegacyCharsets() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("BEGIN:VCALENDAR", decodeCalendarBytes(bom + "BEGIN:VCALENDAR".encodeToByteArray()))
        assertEquals("SUMMARY:日程", decodeCalendarBytes("SUMMARY:日程".encodeToByteArray()))
        // GBK bytes for "日程"; older exporters still write these.
        assertEquals("日程", decodeCalendarBytes(byteArrayOf(0xC8.toByte(), 0xD5.toByte(), 0xB3.toByte(), 0xCC.toByte())))
        // Windows-1252 bytes for "ÿ€" used to be rejected as invalid UTF-8. (A leading FF FE is now
        // read as a UTF-16 byte order mark; see utf16FilesAreDecodedThroughTheirByteOrderMark.)
        assertEquals("ÿ€", decodeCalendarBytes(byteArrayOf(0xFF.toByte(), 0x80.toByte())))
    }

    @Test
    fun utf16FilesAreDecodedThroughTheirByteOrderMark() {
        val text = "BEGIN:VCALENDAR\r\nSUMMARY:会议\r\nEND:VCALENDAR\r\n"
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertEquals(text, decodeCalendarBytes(bom + text.utf16LeBytes()))
        val bigEndian = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.utf16BeBytes()
        assertEquals(text, decodeCalendarBytes(bigEndian))
    }

    @Test
    fun accentedRunsAreReadAsWindows1252EvenWhenGb18030WouldAcceptThem() {
        // "ÄÖÜa" is two GB18030-readable pairs, but real Chinese text is built almost entirely from
        // pairs whose both bytes are high, so the statistics still pick Windows-1252 here.
        val bytes = "BEGIN:VCALENDAR\r\nSUMMARY:".encodeToByteArray() +
            byteArrayOf(0xC4.toByte(), 0xD6.toByte(), 0xDC.toByte(), 0x61.toByte()) +
            "\r\nEND:VCALENDAR\r\n".encodeToByteArray()
        val text = decodeCalendarBytes(bytes)
        assertTrue(text != null && text.contains("ÄÖÜa"), text ?: "null")
    }

    private fun String.utf16LeBytes(): ByteArray = flatMap { char ->
        listOf((char.code and 0xFF).toByte(), (char.code shr 8).toByte())
    }.toByteArray()

    private fun String.utf16BeBytes(): ByteArray = flatMap { char ->
        listOf((char.code shr 8).toByte(), (char.code and 0xFF).toByte())
    }.toByteArray()

    @Test
    fun singleHighBytesAreReadAsWindows1252InsteadOfChineseGarbage() {
        // "Résumé" in Windows-1252: every high byte stands alone, which GB18030 would happily pair
        // with the following letter and turn into an unrelated CJK character.
        val bytes = "BEGIN:VCALENDAR\r\nSUMMARY:R".encodeToByteArray() +
            byteArrayOf(0xE9.toByte()) +
            "sum".encodeToByteArray() +
            byteArrayOf(0xE9.toByte()) +
            "e\r\nEND:VCALENDAR\r\n".encodeToByteArray()
        val text = decodeCalendarBytes(bytes)
        assertTrue(text != null && text.contains("Résumé"), text ?: "null")
    }

    @Test
    fun doubleByteRunsStillWinForChineseExports() {
        val bytes = "BEGIN:VCALENDAR\r\nSUMMARY:".encodeToByteArray() +
            byteArrayOf(0xC8.toByte(), 0xD5.toByte(), 0xB3.toByte(), 0xCC.toByte()) +
            "\r\nEND:VCALENDAR\r\n".encodeToByteArray()
        val text = decodeCalendarBytes(bytes)
        assertTrue(text != null && text.contains("日程"), text ?: "null")
    }

    @Test
    fun aDeclaredCharsetParameterOverridesTheGuesses() {
        // "会议" in GB18030 behind a charset declaration the decoder would otherwise guess wrong.
        val bytes = "BEGIN:VCALENDAR\r\nSUMMARY;CHARSET=GB18030:".encodeToByteArray() +
            byteArrayOf(0xBB.toByte(), 0xE1.toByte(), 0xD2.toByte(), 0xE9.toByte()) +
            "\r\nEND:VCALENDAR\r\n".encodeToByteArray()
        val text = decodeCalendarBytes(bytes)
        assertTrue(text != null && text.contains("会议"), text ?: "null")
    }

    @Test
    fun theHolderAnnouncesThatStoredDataWasDamaged() = runTest {
        val holder = AppStateHolder(
            scope = this,
            initialSystemLocale = "en-US",
            storage = ReportingStorage(LoadDamage(droppedRecords = 2)),
        )
        assertEquals(AppMessage.StorageRecovered(2), holder.messages.first())
    }

    @Test
    fun aHealthyStoreAnnouncesNothing() = runTest {
        val holder = AppStateHolder(
            scope = this,
            initialSystemLocale = "en-US",
            storage = ReportingStorage(LoadDamage()),
        )
        // Virtual time makes the timeout fire without leaving a collector coroutine behind.
        assertNull(withTimeoutOrNull(1_000) { holder.messages.first() })
    }
}

private class ReportingStorage(private val damage: LoadDamage) : StateStorage {
    override val outbox: OutboxStore = MemoryOutboxStore()
    override fun loadState(): AppState = AppState()

    override fun saveState(state: AppState) = Unit

    override fun loadSettings(): PersistedSettings = PersistedSettings()

    override fun saveSettings(settings: PersistedSettings) = Unit

    override fun loadDamage(): LoadDamage = damage
}
