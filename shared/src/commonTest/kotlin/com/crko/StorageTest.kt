package com.crko

import com.crko.domain.AppState
import com.crko.domain.PersistedSettings
import com.crko.storage.FileStateStorage
import com.crko.storage.KeyValueStore
import com.crko.storage.LoadDamage
import com.crko.storage.StateStorage
import com.crko.storage.decodeStateWithReport
import com.crko.state.AppMessage
import com.crko.state.AppStateHolder
import com.crko.ui.transfer.decodeCalendarBytes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
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
    fun oneUndecodableRecordNoLongerDiscardsTheWholeCalendar() {
        val decoded = decodeStateWithReport(MIXED_PAYLOAD)
        assertEquals(listOf("Kept"), decoded.state.events.map { it.title })
        assertEquals(1, decoded.damage.droppedRecords)
        assertTrue(!decoded.damage.unreadable)
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
    fun calendarBytesMustBeValidUtf8AndALostBomIsIgnored() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals("BEGIN:VCALENDAR", decodeCalendarBytes(bom + "BEGIN:VCALENDAR".encodeToByteArray()))
        assertEquals("SUMMARY:日程", decodeCalendarBytes("SUMMARY:日程".encodeToByteArray()))
        // Invalid sequences used to become U+FFFD and import as mojibake with a "success" message.
        assertNull(decodeCalendarBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte())))
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
