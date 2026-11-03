package com.crko.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.BusyInterval
import com.crko.domain.BusyType
import com.crko.domain.Calendar
import com.crko.domain.CalendarSyncState
import com.crko.domain.CalendarTimeZone
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.IcsTimeValue
import com.crko.domain.Journal
import com.crko.domain.JournalStatus
import com.crko.domain.PersistedSettings
import com.crko.domain.Recurrence
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.replaceIcsTimeZones
import com.crko.storage.db.CrkoDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

private class MemoryStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values: MutableMap<String, String> = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

private fun inMemoryDatabase(path: String? = null): Pair<JdbcSqliteDriver, CrkoDatabase> {
    val url = path?.let { "jdbc:sqlite:$it" } ?: JdbcSqliteDriver.IN_MEMORY
    val driver = JdbcSqliteDriver(url, Properties(), CrkoDatabase.Schema)
    // Production turns foreign keys on; without it REPLACE/DELETE cascades would go unnoticed.
    driver.applyPersistencePragmas()
    return driver to CrkoDatabase(driver)
}

class SqlitePersistenceTest {

    @Test
    fun everyTimeValueAndRecordKindRoundTripsThroughTheDatabase() {
        val (_, database) = inMemoryDatabase()
        val zone = CalendarTimeZone(
            tzid = "Custom/StageOne5",
            definition = "BEGIN:VTIMEZONE\r\nTZID:Custom/StageOne5\r\n" +
                "BEGIN:STANDARD\r\nDTSTART:19700101T000000\r\nTZOFFSETFROM:+0500\r\n" +
                "TZOFFSETTO:+0500\r\nEND:STANDARD\r\nEND:VTIMEZONE\r\n",
        )
        val event = Event(
            id = "date-event",
            title = "Date",
            start = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
            end = IcsTimeValue.Date(LocalDate(2026, 10, 29)),
            status = EventStatus.Tentative,
            priority = 3,
        )
        val floating = Event(
            id = "floating",
            title = "Floating",
            start = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 9, 0)),
            end = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 10, 0)),
            recurrence = Recurrence(rule = RepeatRule(RepeatFrequency.Weekly, count = 3)),
        )
        val zoned = Event(
            id = "zoned",
            title = "Zoned",
            icsUid = "shared@example.com",
            start = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Custom/StageOne5"),
            end = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 10, 0), "Custom/StageOne5"),
            recurrenceId = IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Custom/StageOne5"),
        )
        val utc = Event(
            id = "utc",
            title = "Utc",
            start = IcsTimeValue.Utc(Instant.parse("2026-10-27T09:00:00Z")),
            end = IcsTimeValue.Utc(Instant.parse("2026-10-27T10:00:00Z")),
        )
        val todos = listOf(
            Todo(
                id = "todo",
                title = "Todo",
                due = IcsTimeValue.Date(LocalDate(2026, 10, 28)),
                status = TodoStatus.Completed,
                completed = Instant.parse("2026-10-27T08:00:00Z"),
                priority = 5,
                recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 28)),
            ),
        )
        val journals = listOf(
            Journal(
                id = "journal",
                title = "Journal",
                start = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
                status = JournalStatus.Final,
            ),
        )
        val subscription = Calendar(
            id = "sub",
            name = "Subscription",
            colorArgb = 0xFF00FF00,
            visible = false,
            timeZones = listOf(zone),
            events = listOf(floating, zoned, utc),
            syncState = CalendarSyncState(
                url = "https://example.com/a.ics",
                enabled = false,
                refreshMinutes = 60,
                lastSync = "2026-10-27T00:00:00Z",
                etag = "\"abc\"",
                syncToken = "token",
                caldav = true,
            ),
        )
        val state = AppState(
            calendars = listOf(
                Calendar.local().copy(timeZones = listOf(zone), events = listOf(event), todos = todos, journals = journals),
                subscription,
            ),
            busyPeriods = listOf(
                BusyInterval(BusyType.Busy, 1_700_000_000_000, 1_700_000_360_000),
                BusyInterval(BusyType.Free, 1_700_000_720_000, 1_700_001_080_000),
            ),
        )
        replaceIcsTimeZones(state.calendars)
        val storage = SqliteStateStorage(database, { MemoryStore() }, nowMillis = { 42L })

        storage.saveState(state)
        val restored = storage.loadState()

        assertEquals(false, storage.loadDamage().unreadable, storage.loadDamage().toString())
        assertEquals(0, storage.loadDamage().droppedRecords)
        assertEquals(state, restored)
        // Order is part of the contract: the local calendar stays first and record positions survive.
        assertEquals(listOf("local", "sub"), restored.calendars.map { it.id })
        assertEquals(
            listOf("date-event"),
            restored.calendars[0].events.map { it.id },
        )
        assertEquals(
            listOf("floating", "zoned", "utc"),
            restored.calendars[1].events.map { it.id },
        )
        assertEquals(zone, restored.calendars[1].timeZones.single())
        assertEquals(subscription.syncState, restored.calendars[1].syncState)
    }

    @Test
    fun indexColumnsAreProjectedFromThePayload() {
        val (_, database) = inMemoryDatabase()
        val local = IcsTimeValue.Date(LocalDate(2026, 10, 27))
        val state = AppState(
            calendars = listOf(
                Calendar.local().copy(
                    events = listOf(
                        Event(id = "d", title = "Date", start = local, end = IcsTimeValue.Date(LocalDate(2026, 10, 28))),
                        Event(
                            id = "f",
                            title = "Floating",
                            start = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 9, 0)),
                        ),
                        Event(
                            id = "u",
                            title = "Utc",
                            icsUid = "uid@example.com",
                            start = IcsTimeValue.Utc(Instant.parse("2026-10-27T09:00:00Z")),
                        ),
                    ),
                    todos = listOf(
                        Todo(
                            id = "t",
                            title = "Todo",
                            due = IcsTimeValue.Floating(LocalDateTime(2026, 10, 27, 18, 0)),
                            status = TodoStatus.InProcess,
                            priority = 1,
                            recurrenceId = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
                        ),
                    ),
                    journals = listOf(
                        Journal(
                            id = "j",
                            title = "Journal",
                            start = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
                            status = JournalStatus.Draft,
                        ),
                    ),
                ),
            ),
        )
        val storage = SqliteStateStorage(database, { MemoryStore() }, nowMillis = { 1L })
        storage.saveState(state)

        val rows = database.crkoQueries.selectRecords().executeAsList().associateBy { it.record_id }
        val dateMs = LocalDate(2026, 10, 27).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val nineUtc = LocalDateTime(2026, 10, 27, 9, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        val dueFloating = LocalDateTime(2026, 10, 27, 18, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()
        assertEquals("event", rows.getValue("d").kind)
        assertEquals(dateMs, rows.getValue("d").start_ms)
        assertEquals(dateMs + 86_400_000, rows.getValue("d").end_ms)
        assertEquals(null, rows.getValue("d").recurrence_key)
        assertEquals("Floating", rows.getValue("f").title)
        assertEquals(nineUtc, rows.getValue("f").start_ms)
        assertEquals("uid@example.com", rows.getValue("u").ics_uid)
        assertEquals("todo", rows.getValue("t").kind)
        assertEquals(dueFloating, rows.getValue("t").due_ms)
        assertEquals("InProcess", rows.getValue("t").status)
        assertEquals(1L, rows.getValue("t").priority)
        assertEquals("date:2026-10-27", rows.getValue("t").recurrence_key)
        assertEquals("journal", rows.getValue("j").kind)
        assertEquals("Draft", rows.getValue("j").status)
        assertEquals(CurrentSchemaVersion.toLong(), rows.getValue("j").payload_version)
    }

    @Test
    fun savingAnUnchangedStateWritesNoRecordRowsAndOnlyTheEditedRecordIsUpdated() {
        val (_, database) = inMemoryDatabase()
        var now = 100L
        val first = Event(
            id = "a",
            title = "First",
            start = IcsTimeValue.Date(LocalDate(2026, 10, 27)),
        )
        val second = Event(
            id = "b",
            title = "Second",
            start = IcsTimeValue.Date(LocalDate(2026, 10, 28)),
        )
        val state = AppState(calendars = listOf(Calendar.local().copy(events = listOf(first, second))))
        val storage = SqliteStateStorage(database, { MemoryStore() }, nowMillis = { now })

        storage.saveState(state)
        storage.saveState(state)
        val unchanged = database.crkoQueries.selectRecords().executeAsList()
        assertEquals(listOf(100L, 100L), unchanged.map { it.updated_ms })

        // Only the first event is replaced; the untouched second keeps its previous batch row.
        now += 1
        storage.saveState(
            state.copy(
                calendars = listOf(state.calendars.single().copy(events = listOf(first.copy(title = "Edited"), second))),
            ),
        )
        val rows = database.crkoQueries.selectRecords().executeAsList().associateBy { it.record_id }
        assertEquals(101L, rows.getValue("a").updated_ms)
        assertEquals(100L, rows.getValue("b").updated_ms)
        assertEquals("Edited", rows.getValue("a").title)
        assertEquals(2, rows.size)

        // A deletion removes exactly that row.
        now += 1
        storage.saveState(state.copy(calendars = listOf(state.calendars.single().copy(events = listOf(first.copy(title = "Edited"))))))
        assertEquals(setOf("a"), database.crkoQueries.selectRecords().executeAsList().map { it.record_id }.toSet())
    }

    @Test
    fun aDamagedPayloadMovesToDamagedRecordAndKeepsTheRest() {
        val (_, database) = inMemoryDatabase()
        database.crkoQueries.insertCalendar(
            "local", "Crko", null, 1, null, null, 0, null, null, null, null, null, null, null,
        )
        database.crkoQueries.insertRecord(
            calendar_id = "local",
            record_id = "bad",
            kind = "event",
            ics_uid = null,
            recurrence_key = null,
            title = "Broken",
            start_ms = null,
            end_ms = null,
            due_ms = null,
            completed_ms = null,
            status = null,
            priority = null,
            payload = "{not json",
            payload_version = 6,
            position = 0,
            updated_ms = 1,
        )
        database.crkoQueries.insertRecord(
            calendar_id = "local",
            record_id = "good",
            kind = "event",
            ics_uid = null,
            recurrence_key = null,
            title = "Good",
            start_ms = null,
            end_ms = null,
            due_ms = null,
            completed_ms = null,
            status = null,
            priority = null,
            payload = AppJson.encodeToString(
                Event.serializer(),
                Event(id = "good", title = "Good", start = IcsTimeValue.Date(LocalDate(2026, 10, 27))),
            ),
            payload_version = 6,
            position = 1,
            updated_ms = 1,
        )
        val storage = SqliteStateStorage(database, { MemoryStore() }, nowMillis = { 2L })
        val restored = storage.loadState()
        assertEquals(listOf("good"), restored.events.map { it.id })
        assertEquals(1, storage.loadDamage().droppedRecords)
        assertFalse(storage.loadDamage().unreadable)
        // The live table no longer carries the damaged row, but the backup keeps the first copy.
        assertEquals(setOf("good"), database.crkoQueries.selectRecords().executeAsList().map { it.record_id }.toSet())
        assertEquals("{not json", database.crkoQueries.selectDamaged().executeAsList().single().payload)
        // A later write must not overwrite the backup.
        storage.saveState(restored)
        assertEquals("{not json", database.crkoQueries.selectDamaged().executeAsList().single().payload)
    }

    @Test
    fun theFactorySeedsTheDatabaseOnFirstLaunchOnlyOnce() {
        val directory = kotlin.io.path.createTempDirectory("crko-seed-test").toFile()
        try {
            val path = "${directory.absolutePath}/crko.db"
            var seeds = 0
            val seed = AppState(
                calendars = listOf(
                    Calendar.local().copy(
                        events = listOf(Event(id = "seed", title = "Seed", start = IcsTimeValue.Date(LocalDate(2026, 10, 27)))),
                    ),
                ),
            )
            val first = createAppStateStorage(
                settingsStore = { MemoryStore() },
                seedProvider = { seeds++; seed },
                driverFactory = { JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), CrkoDatabase.Schema) },
                idProvider = { "generated" },
            )
            assertEquals(listOf("Seed"), first.loadState().events.map { it.title })
            assertEquals(1, seeds)

            // A later launch against the same database keeps the stored records and does not reseed.
            val second = createAppStateStorage(
                settingsStore = { MemoryStore() },
                seedProvider = { seeds++; AppState() },
                driverFactory = { JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), CrkoDatabase.Schema) },
                idProvider = { "generated" },
            )
            assertEquals(listOf("Seed"), second.loadState().events.map { it.title })
            assertEquals(1, seeds)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun theSerializedBackgroundAccessWritesThroughTheSameStorage() = runBlocking {
        val (_, database) = inMemoryDatabase()
        val storage = SqliteStateStorage(database, { MemoryStore() }, nowMillis = { 1L })
        val access = SerializedStateAccess(storage = { storage }, idProvider = { "background-id" })
        val initial = AppState(
            calendars = listOf(
                Calendar.local().copy(
                    events = listOf(
                        Event(id = "a", title = "A", start = IcsTimeValue.Date(LocalDate(2026, 10, 27))),
                    ),
                ),
            ),
        )
        storage.saveState(initial)

        val updated = access.update { state ->
            state.copy(
                calendars = listOf(
                    state.calendars.single().copy(
                        events = state.calendars.single().events + Event(
                            id = null,
                            title = "Background",
                            start = IcsTimeValue.Date(LocalDate(2026, 10, 28)),
                        ),
                    ),
                ),
            )
        }
        assertEquals("background-id", updated.events.last().id)
        assertEquals(listOf("a", "background-id"), access.read().events.map { it.id })
    }

    @Test
    fun aSecondConnectionReadsTheLastCommittedState() {
        val directory = kotlin.io.path.createTempDirectory("crko-wal-test").toFile()
        try {
            val path = "${directory.absolutePath}/crko.db"
            val (writerDriver, writerDatabase) = inMemoryDatabase(path)
            val (readerDriver, readerDatabase) = inMemoryDatabase(path)
            try {
                val writer = SqliteStateStorage(writerDatabase, { MemoryStore() }, nowMillis = { 1L })
                val first = AppState(
                    calendars = listOf(
                        Calendar.local().copy(
                            events = listOf(Event(id = "a", title = "First", start = IcsTimeValue.Date(LocalDate(2026, 10, 27)))),
                        ),
                    ),
                )
                writer.saveState(first)
                val reader = SqliteStateStorage(readerDatabase, { MemoryStore() }, nowMillis = { 1L })
                assertEquals(listOf("First"), reader.loadState().events.map { it.title })

                writer.saveState(
                    first.copy(calendars = listOf(first.calendars.single().copy(events = listOf(first.events.single().copy(title = "Second"))))),
                )
                // A committed transaction is visible to the next read even though the writer's
                // driver stays open (WAL mode, single writer, reader sees the last complete state).
                val secondReader = SqliteStateStorage(readerDatabase, { MemoryStore() }, nowMillis = { 1L })
                assertEquals(listOf("Second"), secondReader.loadState().events.map { it.title })
            } finally {
                writerDriver.close()
                readerDriver.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun theFactoryProbeDoesNotSwallowLoadDamage() {
        val (driver, database) = inMemoryDatabase()
        database.crkoQueries.insertCalendar(
            "local", "Crko", null, 1, null, null, 0, null, null, null, null, null, null, null,
        )
        database.crkoQueries.insertRecord(
            calendar_id = "local",
            record_id = "bad",
            kind = "event",
            ics_uid = null,
            recurrence_key = null,
            title = "Broken",
            start_ms = null,
            end_ms = null,
            due_ms = null,
            completed_ms = null,
            status = null,
            priority = null,
            payload = "{not json",
            payload_version = 6,
            position = 0,
            updated_ms = 1,
        )
        // Mark the database initialized so the factory's first-launch seed cannot wipe the row.
        database.crkoQueries.putMeta(FirstInitKey, "1")
        val storage = createAppStateStorage(
            settingsStore = { MemoryStore() },
            driverFactory = { driver },
            idProvider = { "id" },
        )
        // The factory has to read once to decide whether the database is usable; the repository's
        // first frame must still see the damaged-record count from that probe.
        assertTrue(storage is SqliteStateStorage)
        assertEquals(1, storage.loadDamage().droppedRecords)
        assertTrue(storage.loadState().events.isEmpty())
        driver.close()
    }

    @Test
    fun aFactoryWithoutADriverRunsOnMemoryForThisSession() {
        val settings = MemoryStore()
        val storage = createAppStateStorage(
            settingsStore = { settings },
            driverFactory = { null },
            idProvider = { "id" },
        )
        assertTrue(storage is MemoryStateStorage)
        // The process keeps working: writes stay in memory and settings still reach their store.
        assertTrue(storage.loadState().events.isEmpty())
        assertTrue(storage.loadDamage().unreadable)
        storage.saveState(
            AppState(
                calendars = listOf(
                    Calendar.local().copy(
                        events = listOf(Event(id = "a", title = "Memory", start = IcsTimeValue.Date(LocalDate(2026, 10, 27)))),
                    ),
                ),
            ),
        )
        assertEquals("Memory", storage.loadState().events.single().title)
        storage.saveSettings(PersistedSettings(language = "zh"))
        assertEquals("zh", storage.loadSettings().language)
        assertTrue(settings.values.containsKey(SETTINGS_KEY))
    }

    @Test
    fun aFactoryWhoseDatabaseCannotBeReadRunsOnMemoryAndKeepsTheFile() {
        val directory = kotlin.io.path.createTempDirectory("crko-broken-test").toFile()
        val path = "${directory.absolutePath}/crko.db"
        try {
            // A file that is not a database: opening the schema must fail, and the file stays on
            // disk for diagnosis instead of being overwritten.
            File(path).writeText("this is not a sqlite database")
            val storage = createAppStateStorage(
                settingsStore = { MemoryStore() },
                driverFactory = { JdbcSqliteDriver("jdbc:sqlite:$path", Properties(), CrkoDatabase.Schema) },
                idProvider = { "id" },
            )
            assertTrue(storage is MemoryStateStorage)
            assertTrue(File(path).readText().startsWith("this is not a sqlite database"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
