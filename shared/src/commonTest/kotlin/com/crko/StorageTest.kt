package com.crko

import com.crko.domain.AlarmTrigger
import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.OrdinalWeekday
import com.crko.domain.PersistedSettings
import com.crko.domain.RecurrenceDate
import com.crko.domain.RepeatFrequency
import com.crko.domain.exportCalendar
import com.crko.domain.instantOrNull
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

/** Payload with one readable record and one that cannot be decoded at all. */
private const val MIXED_PAYLOAD =
    """{"events":[{"id":null,"title":"Kept","date":"2026-10-27","startMinutes":540,"endMinutes":600},""" +
        """{"id":null,"title":"Broken"}],"todos":[]}"""

private val utc = TimeZone.UTC

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
        val encoded = AppJson.encodeToString(
            AppState.serializer(),
            AppState(calendars = listOf(com.crko.domain.Calendar.local().copy(events = events))),
        )
        assertTrue(encoded.contains("\"schemaVersion\":$CurrentSchemaVersion"), encoded)
        val decoded = decodeStateWithReport(encoded)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(events, decoded.state.events)
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
            calendars = listOf(com.crko.domain.Calendar.local().copy(events = events, todos = todos)),
        )
        val encoded = AppJson.encodeToString(AppState.serializer(), state)
        val decoded = decodeStateWithReport(encoded)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(state, decoded.state)
    }

    @Test
    fun aStageThreePayloadKeepsTheZoneNestedInItsTodoValues() {
        // Before stage 3 the `VTIMEZONE` text of an unknown zone lived inside the time value that
        // referenced it. A `VTODO` rule's `UNTIL` and its added `RDATE` carried one just like an event
        // did; collecting the definitions from the events only left those `TZID` references dangling.
        val definition = "BEGIN:VTIMEZONE\\r\\nTZID:Custom/TodoZone3\\r\\n" +
            "BEGIN:STANDARD\\r\\nDTSTART:19700101T000000\\r\\n" +
            "TZOFFSETFROM:+0300\\r\\nTZOFFSETTO:+0300\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\n"
        val payload = """{"schemaVersion":3,"todos":[{"id":"t","title":"Zone todo",""" +
            """"repeat":{"frequency":"Daily","until":{"type":"zoned",""" +
            """"dateTime":"2026-12-31T09:00:00","tzid":"Custom/TodoZone3","definition":"$definition"}},""" +
            """"rDates":[{"value":{"type":"zoned","dateTime":"2026-11-20T09:00:00",""" +
            """"tzid":"Custom/TodoZone3","definition":"$definition"}}]}]}"""
        val decoded = decodeStateWithReport(payload)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        val todo = decoded.state.todos.single()
        assertEquals(
            IcsTimeValue.Zoned(LocalDateTime(2026, 12, 31, 9, 0), "Custom/TodoZone3"),
            todo.repeat?.until,
        )
        assertEquals(Instant.parse("2026-12-31T06:00:00Z"), todo.repeat?.until?.instantOrNull(utc))
        assertEquals(Instant.parse("2026-11-20T06:00:00Z"), todo.rDates.single().value.instantOrNull(utc))
        assertTrue(
            decoded.state.calendar.timeZones.any { it.tzid == "Custom/TodoZone3" },
            decoded.state.calendar.timeZones.toString(),
        )
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
    fun legacyRecurrencePayloadMigratesToTheTypedSet() {
        val payload = """{"schemaVersion":2,"events":[""" +
            """{"id":"series","title":"Series","date":"2026-10-27","startMinutes":540,"endMinutes":600,""" +
            """"repeat":{"frequency":"Weekly","weekdays":[0,2],"until":"2026-12-01","rDates":["2026-11-20"]},""" +
            """"excludedDates":["2026-10-29"]},""" +
            """{"id":"override","title":"Moved","date":"2026-10-28","startMinutes":600,"endMinutes":660,""" +
            """"icsUid":"series#override-2026-10-28","seriesUid":"series","recurrenceId":"2026-10-28"},""" +
            """{"id":"custom","title":"Custom","date":"2026-10-27","startMinutes":540,"endMinutes":600,""" +
            """"repeat":{"frequency":"Custom","weekdays":[1]}}],""" +
            """"todos":[{"id":"todo","title":"Report","due":"2026-10-06",""" +
            """"repeat":{"frequency":"Weekly","weekdays":[0],"rDates":["2026-12-24"]},""" +
            """"excludedDates":["2026-10-12"]}],""" +
            """"journals":[{"id":"j","title":"Journal","date":"2026-10-27"}]}"""
        val decoded = decodeStateWithReport(payload)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        assertEquals(CurrentSchemaVersion, decoded.state.schemaVersion)

        val series = decoded.state.events.first { it.id == "series" }
        assertEquals(RepeatFrequency.Weekly, series.repeat?.frequency)
        assertEquals(listOf(OrdinalWeekday(0, 0), OrdinalWeekday(0, 2)), series.repeat?.byDay)
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 12, 1)), series.repeat?.until)
        assertEquals(
            listOf(RecurrenceDate(IcsTimeValue.Date(LocalDate(2026, 11, 20)))),
            series.recurrence?.rdates,
        )
        assertEquals(
            listOf(IcsTimeValue.Date(LocalDate(2026, 10, 29))),
            series.recurrence?.exdates,
        )

        // The old synthetic exception identity collapses onto the master's shared UID.
        val override = decoded.state.events.first { it.id == "override" }
        assertEquals("series", override.icsUid)
        assertEquals(IcsTimeValue.Date(LocalDate(2026, 10, 28)), override.recurrenceId)

        val custom = decoded.state.events.first { it.id == "custom" }
        assertEquals(RepeatFrequency.Weekly, custom.repeat?.frequency)
        assertEquals(listOf(OrdinalWeekday(0, 1)), custom.repeat?.byDay)

        val todo = decoded.state.todos.single()
        assertEquals(RepeatFrequency.Weekly, todo.repeat?.frequency)
        assertEquals(listOf(OrdinalWeekday(0, 0)), todo.repeat?.byDay)
        assertEquals(
            listOf(RecurrenceDate(IcsTimeValue.Date(LocalDate(2026, 12, 24)))),
            todo.rDates,
        )
        assertEquals(listOf(IcsTimeValue.Date(LocalDate(2026, 10, 12))), todo.excludedDates)
        assertEquals("Journal", decoded.state.journals.single().title)
    }

    @Test
    fun aCurrentPayloadWithLegacyStringExdatesStillDecodes() {
        // The version-6 payloads the previous release wrote carried bare ISO date strings; the
        // typed list must read them instead of dropping every todo as undecodable.
        val payload = """{"schemaVersion":6,"calendars":[{"id":"local","name":"Crko","todos":[""" +
            """{"id":"t1","title":"Report","due":"2026-10-06","excludedDates":["2026-10-12"]}]}]}"""
        val decoded = decodeStateWithReport(payload)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        val todo = decoded.state.calendars.single().todos.single()
        assertEquals(listOf(IcsTimeValue.Date(LocalDate(2026, 10, 12))), todo.excludedDates)
        // The next save writes the typed shape, and that round-trips through the same decoder.
        val encoded = AppJson.encodeToString(AppState.serializer(), decoded.state)
        val redecoded = decodeStateWithReport(encoded)
        assertEquals(decoded.state, redecoded.state)
    }

    @Test
    fun aStageOnePayloadKeepsTheZoneDefinitionNestedInItsTimeValue() {
        // Stage 1 stored the VTIMEZONE text of an unknown zone as `definition` inside the typed
        // value. The current Zoned only keeps the TZID, so decoding must lift that text onto the
        // calendar level; otherwise the zone id survives without its offset and every instant of
        // the record is silently wrong.
        val definition = "BEGIN:VTIMEZONE\\r\\nTZID:Custom/StageOne5\\r\\n" +
            "BEGIN:STANDARD\\r\\nDTSTART:19700101T000000\\r\\n" +
            "TZOFFSETFROM:+0500\\r\\nTZOFFSETTO:+0500\\r\\nEND:STANDARD\\r\\nEND:VTIMEZONE\\r\\n"
        val payload = """{"schemaVersion":2,"events":[{""" +
            """"id":"early","title":"Early",""" +
            """"start":{"type":"zoned","dateTime":"2026-10-27T09:00:00","tzid":"Custom/StageOne5","definition":"$definition"},""" +
            """"end":{"type":"zoned","dateTime":"2026-10-27T10:00:00","tzid":"Custom/StageOne5","definition":"$definition"}}]}"""
        val decoded = decodeStateWithReport(payload)
        assertTrue(decoded.damage.isEmpty, decoded.damage.toString())
        val event = decoded.state.events.single()
        assertEquals(IcsTimeValue.Zoned(LocalDateTime(2026, 10, 27, 9, 0), "Custom/StageOne5"), event.start)
        assertEquals(Instant.parse("2026-10-27T04:00:00Z"), event.start.instantOrNull(utc))

        val zone = decoded.state.calendar.timeZones.single()
        assertEquals("Custom/StageOne5", zone.tzid)
        assertTrue("TZOFFSETTO:+0500" in zone.definition, zone.definition)

        val exported = exportCalendar(decoded.state.events, timeZones = decoded.state.calendar.timeZones)
        assertTrue("DTSTART;TZID=Custom/StageOne5:20261027T090000" in exported, exported)
        assertTrue("BEGIN:VTIMEZONE" in exported, exported)
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
    fun versionedLegacyPayloadsAreBackedUpBeforeMigration() {
        val payloads = listOf(
            """{"schemaVersion":2,"events":[{"id":"e","title":"Old","date":"2026-10-27","startMinutes":540,"endMinutes":600}]}""",
            """{"schemaVersion":3,"events":[],"todos":[],"journals":[],"subscriptions":[],"busyPeriods":[],"calendar":{}}""",
            """{"schemaVersion":4,"events":[],"todos":[],"journals":[],"subscriptions":[],"busyPeriods":[],"calendar":{}}""",
            """{"schemaVersion":5,"events":[],"todos":[],"journals":[],"subscriptions":[],"busyPeriods":[],"calendar":{}}""",
        )
        payloads.forEach { payload ->
            val store = MemoryStore(mapOf("state.v1" to payload))
            val storage = FileStateStorage({ store }, { MemoryStore() }, seedSampleOnFirstLaunch = false)
            val restored = storage.loadState()
            assertEquals(CurrentSchemaVersion, restored.schemaVersion, payload)
            storage.saveState(restored)
            assertEquals(payload, store.values["state.v1.migration-backup"], payload)
        }
    }

    @Test
    fun onlyTheFirstMigratedPayloadIsBackedUpAndCurrentPayloadsAreLeftAlone() {
        val first = """{"schemaVersion":5,"events":[],"todos":[],"journals":[],"subscriptions":[],"busyPeriods":[],"calendar":{}}"""
        val store = MemoryStore(mapOf("state.v1" to first))
        val storage = FileStateStorage({ store }, { MemoryStore() }, seedSampleOnFirstLaunch = false)
        storage.loadState()
        store.putString("state.v1", """{"schemaVersion":4,"events":[],"todos":[],"journals":[],"subscriptions":[],"busyPeriods":[],"calendar":{}}""")
        storage.loadState()
        assertEquals(first, store.values["state.v1.migration-backup"])

        val current = AppJson.encodeToString(AppState.serializer(), AppState())
        val currentStore = MemoryStore(mapOf("state.v1" to current))
        val currentStorage = FileStateStorage({ currentStore }, { MemoryStore() }, seedSampleOnFirstLaunch = false)
        currentStorage.loadState()
        assertNull(currentStore.values["state.v1.migration-backup"])
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
        val first = restored.events.first()
        storage.saveState(
            restored.copy(
                calendars = restored.calendars.map { calendar ->
                    if (calendar.events.any { it.id == first.id }) calendar.copy(events = calendar.events + first) else calendar
                },
            ),
        )
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
