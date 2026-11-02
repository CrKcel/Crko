package com.crko

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.PersistedSettings
import com.crko.domain.isZeroDuration
import com.crko.storage.FileStateStorage
import com.crko.storage.KeyValueStore
import com.crko.storage.LoadDamage
import com.crko.storage.StateStorage
import com.crko.storage.decodeStateWithReport
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.domain.decodeCalendarBytes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values: MutableMap<String, String> = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

/** Payload with one readable record and one that cannot be decoded at all. */
private const val MIXED_PAYLOAD =
    """{"events":[{"id":null,"title":"Kept","date":"2026-10-27","startMinutes":540,"endMinutes":600},""" +
        """{"id":null,"title":"Broken"}],"todos":[]}"""

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
        val encoded = AppJson.encodeToString(AppState.serializer(), AppState(events = events))
        assertTrue(encoded.contains("\"schemaVersion\":$CurrentSchemaVersion"), encoded)
        val decoded = decodeStateWithReport(encoded)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(events, decoded.state.events)
    }

    @Test
    fun legacyPayloadMigratesToRfcTimeValues() {
        val payload = """{"events":[""" +
            """{"id":"all","title":"All","date":"2026-10-27","startMinutes":0,"endMinutes":0,"endDate":"2026-10-28","allDay":true},""" +
            """{"id":"tz","title":"Tz","date":"2026-10-27","startMinutes":540,"endMinutes":600,"sourceStart":"20261027T090000","sourceEnd":"20261027T100000","timeZoneId":"Europe/Berlin"},""" +
            """{"id":"zero","title":"Zero","date":"2026-10-27","startMinutes":720,"endMinutes":720}],""" +
            """"todos":[{"id":"t","title":"Todo"}],"journals":[{"id":"j","title":"Journal"}]}"""
        val decoded = decodeStateWithReport(payload)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(CurrentSchemaVersion, decoded.state.schemaVersion)
        assertEquals(3, decoded.state.events.size)
        assertEquals(1, decoded.state.todos.size)
        assertEquals(1, decoded.state.journals.size)

        val allDay = decoded.state.events.first { it.id == "all" }
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 27)), allDay.start)
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 29)), allDay.end)

        val zoned = decoded.state.events.first { it.id == "tz" }
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Europe/Berlin"),
            zoned.start,
        )
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 10, 0), "Europe/Berlin"),
            zoned.end,
        )

        val zero = decoded.state.events.first { it.id == "zero" }
        assertNull(zero.end)
        assertTrue(zero.isZeroDuration)
    }

    @Test
    fun legacyPayloadIsBackedUpBeforeItIsMigrated() {
        val legacy = """{"events":[{"id":"e","title":"Old","date":"2026-10-27","startMinutes":540,"endMinutes":600}]}"""
        val store = MemoryStore(mapOf("state.v1" to legacy))
        val storage = FileStateStorage({ store }, { MemoryStore() }, seedSampleOnFirstLaunch = false)
        val restored = storage.loadState()
        assertEquals(1, restored.events.size)
        assertEquals(legacy, store.values["state.v1.legacy-backup"])
        // Writing the migrated state must not overwrite the preserved original.
        storage.saveState(restored)
        assertEquals(legacy, store.values["state.v1.legacy-backup"])
        val resent = store.values.getValue("state.v1")
        assertTrue(resent.contains("\"schemaVersion\":$CurrentSchemaVersion"), resent)
        assertEquals(1, decodeStateWithReport(resent).state.events.size)
    }

    @Test
    fun oneUndecodableRecordNoLongerDiscardsTheWholeCalendar() {
        val decoded = decodeStateWithReport(MIXED_PAYLOAD)
        assertEquals(listOf("Kept"), decoded.state.events.map { it.title })
        assertEquals(1, decoded.damage.droppedRecords)
        assertTrue(!decoded.damage.unreadable)
    }

    @Test
    fun aRecordWithAnUnparseableDateIsDroppedInsteadOfCrashingLaterQueries() {
        // The payload decodes fine (the date is a plain string), but Event.day would throw on it.
        val payload = """{"events":[""" +
            """{"id":"a","title":"Broken","date":"not-a-date","startMinutes":0,"endMinutes":0},""" +
            """{"id":"b","title":"Kept","date":"2026-10-27","startMinutes":540,"endMinutes":600}],"todos":[]}"""
        val decoded = decodeStateWithReport(payload)
        assertEquals(listOf("Kept"), decoded.state.events.map { it.title })
        assertEquals(1, decoded.damage.droppedRecords)
        assertTrue(!decoded.damage.unreadable)
    }

    @Test
    fun anUnparseableEndDateKeepsTheRecordInsteadOfCrashing() {
        val payload = """{"events":[""" +
            """{"id":"a","title":"Kept","date":"2026-10-27","startMinutes":540,"endMinutes":600,"endDate":"oops"}],"todos":[]}"""
        val decoded = decodeStateWithReport(payload)
        assertEquals(0, decoded.damage.droppedRecords)
        // The broken end date behaves like an absent one: the record stays on its start day.
        assertEquals(LocalDate.parse("2026-10-27"), decoded.state.events.single().endDay)
    }

    @Test
    fun anUnreadablePayloadIsReportedInsteadOfLookingLikeAnEmptyCalendar() {
        val decoded = decodeStateWithReport("{not json")
        assertTrue(decoded.damage.unreadable)
        assertTrue(decoded.state.events.isEmpty())
        // A well-formed empty state is not damage and must not be flagged.
        assertTrue(!decodeStateWithReport("""{"events":[],"todos":[]}""").damage.unreadable)
    }

    @Test
    fun aDamagedPayloadIsBackedUpBeforeTheShrunkenStateCanReplaceIt() {
        val store = MemoryStore(mapOf("state.v1" to MIXED_PAYLOAD))
        val storage = FileStateStorage({ store }, { MemoryStore() }, seedSampleOnFirstLaunch = false)
        val restored = storage.loadState()
        assertEquals(listOf("Kept"), restored.events.map { it.title })
        assertEquals(1, storage.loadDamage().droppedRecords)
        assertEquals(MIXED_PAYLOAD, store.values["state.v1.corrupt"])
        // The first edit used to overwrite the original; the backup has to survive it.
        storage.saveState(restored.copy(events = restored.events + restored.events.first()))
        assertEquals(MIXED_PAYLOAD, store.values["state.v1.corrupt"])
        assertEquals(
            listOf("Kept", "Kept"),
            decodeStateWithReport(store.values.getValue("state.v1")).state.events.map { it.title },
        )
    }

    @Test
    fun theBackupKeepsTheFirstDamagedCopyAndHealthyDataIsLeftAlone() {
        val store = MemoryStore(mapOf("state.v1" to MIXED_PAYLOAD))
        val storage = FileStateStorage({ store }, { MemoryStore() }, seedSampleOnFirstLaunch = false)
        storage.loadState()
        storage.loadState()
        assertEquals(MIXED_PAYLOAD, store.values["state.v1.corrupt"])

        val healthy = MemoryStore(mapOf("state.v1" to """{"events":[],"todos":[]}"""))
        val healthyStorage = FileStateStorage({ healthy }, { MemoryStore() }, seedSampleOnFirstLaunch = false)
        assertEquals(0, healthyStorage.loadState().events.size)
        assertTrue(healthyStorage.loadDamage().isEmpty)
        assertNull(healthy.values["state.v1.corrupt"])
    }

    @Test
    fun settingsSurviveADamagedStatePayload() {
        val settings = MemoryStore()
        val storage = FileStateStorage({ MemoryStore() }, { settings }, seedSampleOnFirstLaunch = false)
        storage.saveSettings(PersistedSettings(language = "zh"))
        assertEquals("zh", storage.loadSettings().language)
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
    override fun loadState(): AppState = AppState()

    override fun saveState(state: AppState) = Unit

    override fun loadSettings(): PersistedSettings = PersistedSettings()

    override fun saveSettings(settings: PersistedSettings) = Unit

    override fun loadDamage(): LoadDamage = damage
}
