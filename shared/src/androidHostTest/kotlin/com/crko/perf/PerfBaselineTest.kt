package com.crko.perf

import com.crko.domain.Alarm
import com.crko.domain.AlarmTrigger
import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarSyncState
import com.crko.domain.CalendarTimeZone
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.PersistedSettings
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.Recurrence
import com.crko.domain.RelatedTo
import com.crko.domain.Todo
import com.crko.domain.TodoStatus
import com.crko.domain.plusDays
import com.crko.domain.replaceIcsTimeZones
import com.crko.i18n.EnStrings
import com.crko.state.AppStateHolder
import com.crko.state.persistence.StateRepository
import com.crko.state.planReminders
import com.crko.storage.KeyValueStore
import com.crko.storage.LoadDamage
import com.crko.storage.SqliteStateStorage
import com.crko.storage.StateStorage
import com.crko.storage.db.CrkoDatabase
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.exists
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * F2.1 performance baseline. The test is inert unless the runner sets the system property, so the
 * normal test suite pays nothing for it. Run it with:
 *
 * `./gradlew :shared:testAndroidHostTest -PperfBaseline`
 *
 * Every dataset is built from index arithmetic only, so the same command produces the same payloads
 * on any machine. The wall clock and the JVM default zone are pinned (UTC, 2026-11-03) so the
 * recurrence and reminder measurements do not depend on when or where the test runs.
 */
class PerfBaselineTest {

    @Test
    fun printBaseline() {
        if (System.getProperty("crko.perfBaseline") != "true") return
        println("")
        println("## F2.1 persistence baseline")
        println("")
        println("| events | operation | median ms | min ms | max ms | n | notes |")
        println("|---|---|---|---|---|---|---|")
        for (eventCount in DatasetSizes) {
            measureDataset(eventCount).forEach { row ->
                println(
                    "| ${row.events} | ${row.name} | ${row.medianMs.fixed()} | ${row.minMs.fixed()} | " +
                        "${row.maxMs.fixed()} | ${row.samples} | ${row.note} |",
                )
            }
        }
    }

    private fun measureDataset(eventCount: Int): List<Row> {
        val directory = Files.createTempDirectory("crko-perf-$eventCount")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("crko.db")}", Properties(), CrkoDatabase.Schema)
        return try {
            val state = generateState(eventCount)
            val storage = SqliteStateStorage(CrkoDatabase(driver), { DirectoryKeyValueStore(directory) })
            // Materialize the database once so load samples measure a warm, valid file.
            storage.saveState(state)
            driver.execute(null, "PRAGMA wal_checkpoint(TRUNCATE)", 0)
            val databaseBytes = Files.size(directory.resolve("crko.db"))

            // The edit states are built outside the timed block so the numbers measure the storage
            // transaction, not fixture copying.
            val target = state.calendars.first { it.events.isNotEmpty() }
            val event = target.events.first()
            val edits = List(Warmups + Samples) { index ->
                state.copy(
                    calendars = state.calendars.map { calendar ->
                        if (calendar.id != target.id) {
                            calendar
                        } else {
                            calendar.copy(
                                events = calendar.events.map { existing ->
                                    if (existing.id != event.id) existing else existing.copy(title = "Edited $index")
                                },
                            )
                        }
                    },
                )
            }

            val rows = mutableListOf<Row>()
            rows += measure(
                "SqliteStateStorage.loadState",
                events = eventCount,
                note = "database=${bytes(databaseBytes)}",
            ) {
                loadedEvents += storage.loadState().calendars.sumOf { it.events.size }
            }
            var editIndex = 0
            rows += measure(
                "SqliteStateStorage.saveState (1 edit)",
                events = eventCount,
                note = "database=${bytes(databaseBytes)}",
            ) {
                storage.saveState(edits[editIndex++ % edits.size])
            }

            val counting = CountingStateStorage(storage)
            val repository = StateRepository(
                scope = scope,
                storage = counting,
                ioDispatcher = Dispatchers.Default,
            )
            rows += measure(
                "StateRepository.persist (1 edit + awaitIdle)",
                events = eventCount,
            ) {
                runBlocking {
                    repository.persist(edits[editIndex++ % edits.size])
                    repository.awaitIdle()
                }
            }
            rows += measureBurst(repository, counting, state, eventCount)

            rows += measure(
                "replaceIcsTimeZones",
                events = eventCount,
                note = "calendars=${state.calendars.size}",
            ) {
                replaceIcsTimeZones(state.calendars)
            }

            val reminderCount = planReminders(
                events = state.events,
                todos = state.todos,
                strings = EnStrings,
                now = FixedNow,
                zone = TimeZone.UTC,
            ).size
            rows += measure(
                "planReminders",
                events = eventCount,
                note = "events=${state.events.size}, reminders=$reminderCount",
            ) {
                reminderSink += planReminders(
                    events = state.events,
                    todos = state.todos,
                    strings = EnStrings,
                    now = FixedNow,
                    zone = TimeZone.UTC,
                ).size
            }

            val holder = AppStateHolder(
                scope = scope,
                initialSystemLocale = "en",
                clock = FixedClock,
                storage = FixedStateStorage(state),
            )
            val dayCount = holder.eventsOn(BenchmarkDate).size
            rows += measure(
                "eventsOn(day)",
                events = eventCount,
                note = "date=$BenchmarkDate, events=$dayCount",
            ) {
                daySink += holder.eventsOn(BenchmarkDate).size
            }

            val activeCount = holder.activeTodos().size
            rows += measure(
                "activeTodos",
                events = eventCount,
                note = "todos=${state.todos.size}, active=$activeCount",
            ) {
                todoSink += holder.activeTodos().size
            }
            rows
        } finally {
            driver.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }

    private fun measureBurst(
        repository: StateRepository,
        storage: CountingStateStorage,
        state: AppState,
        events: Int,
    ): Row {
        fun round(): Pair<Double, Int> {
            storage.stateWrites = 0
            val started = System.nanoTime()
            repeat(BurstEdits) { repository.persist(state) }
            runBlocking { repository.awaitIdle() }
            return (System.nanoTime() - started) / 1_000_000.0 to storage.stateWrites
        }
        repeat(Warmups) { round() }
        val rounds = List(Samples) { round() }
        val times = rounds.map { it.first }.sorted()
        val writes = rounds.map { it.second }.sorted()
        return Row(
            events = events,
            name = "StateRepository.persist burst ($BurstEdits edits + awaitIdle)",
            medianMs = times[times.size / 2],
            minMs = times.first(),
            maxMs = times.last(),
            samples = Samples,
            note = "coalesced writes=${writes[writes.size / 2]}",
        )
    }

    private fun measure(
        name: String,
        events: Int,
        note: String = "",
        samples: Int = Samples,
        warmups: Int = Warmups,
        block: () -> Unit,
    ): Row {
        repeat(warmups) { block() }
        val times = DoubleArray(samples) {
            val started = System.nanoTime()
            block()
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        return Row(
            events = events,
            name = name,
            medianMs = times[times.size / 2],
            minMs = times.first(),
            maxMs = times.last(),
            samples = samples,
            note = note,
        )
    }

    private data class Row(
        val events: Int,
        val name: String,
        val medianMs: Double,
        val minMs: Double,
        val maxMs: Double,
        val samples: Int,
        val note: String,
    )

    private var loadedEvents = 0
    private var reminderSink = 0
    private var daySink = 0
    private var todoSink = 0

    // The generator below is deterministic: no random source, no wall clock, no device zone.
    private fun generateState(eventCount: Int): AppState {
        val subscriptions = List(SubscriptionCalendars) { index ->
            Calendar(
                id = "sub-$index",
                name = "Perf subscription $index",
                colorArgb = 0xFF000000 or (index.toLong() * 0x101010),
                refreshMinutes = 60,
                timeZones = listOf(
                    CalendarTimeZone(
                        tzid = "X-CRKO-PERF/z$index",
                        definition = customTimeZone(index),
                    ),
                ),
                syncState = CalendarSyncState(
                    url = "https://example.com/perf-$index.ics",
                    enabled = true,
                    refreshMinutes = 1440,
                    lastSync = "2026-11-03T07:00:00Z",
                    etag = "\"perf-etag-$index\"",
                    syncToken = "perf-sync-token-$index",
                    caldav = index % 2 == 0,
                ),
            )
        }
        val eventsByCalendar = Array(SubscriptionCalendars) { mutableListOf<Event>() }
        repeat(eventCount) { index ->
            eventsByCalendar[index % SubscriptionCalendars] += event(index)
        }
        val calendars = buildList {
            add(
                Calendar.local().copy(
                    todos = todos(TodoCount),
                ),
            )
            subscriptions.forEachIndexed { index, calendar ->
                add(calendar.copy(events = eventsByCalendar[index]))
            }
        }
        return AppState(calendars = calendars)
    }

    /** Mixes one-off, all-day and weekly repeating events with alarms, notes and categories. */
    private fun event(index: Int): Event {
        val day = BenchmarkDate.plusDays((index * 37 % 500) - 250)
        val allDay = index % 9 == 0
        val recurring = index % 7 == 0
        val start = if (allDay) {
            IcsTimeValue.Date(day)
        } else {
            IcsTimeValue.Floating(LocalDateTime(day, LocalTime(8 + index % 10, (index % 4) * 15)))
        }
        val end = if (allDay) {
            IcsTimeValue.Date(day.plusDays(1))
        } else {
            IcsTimeValue.Floating(LocalDateTime(day, LocalTime(9 + index % 10, (index % 4) * 15)))
        }
        return Event(
            id = "e-$index",
            icsUid = "perf-event-uid-$index@example.com",
            title = "Perf event $index",
            start = start,
            end = end,
            note = "Deterministic benchmark notes for event $index; categories and a location are " +
                "present so the serialized payload resembles real records.",
            location = if (index % 3 == 0) "Room ${index % 12}" else "",
            categories = listOf(Categories[index % Categories.size]),
            recurrence = if (recurring) {
                Recurrence(
                    rule = RepeatRule(
                        frequency = RepeatFrequency.Weekly,
                        interval = 1,
                        count = 52,
                    ),
                )
            } else {
                null
            },
            alarms = if (index % 4 == 0) {
                listOf(
                    Alarm(trigger = AlarmTrigger.Relative((-15).minutes)),
                    Alarm(trigger = AlarmTrigger.Relative((-24 * 60).minutes)),
                )
            } else {
                emptyList()
            },
            created = "2026-01-01T00:00:00Z",
            lastModified = "2026-06-01T00:00:00Z",
            dtStamp = "2026-06-01T00:00:00Z",
        )
    }

    /** One thousand one-off todos; a quarter are completed, the rest have mixed due dates. */
    private fun todos(count: Int): List<Todo> = List(count) { index ->
        val day = BenchmarkDate.plusDays((index % 30) - 10)
        Todo(
            id = "t-$index",
            icsUid = "perf-todo-uid-$index@example.com",
            title = "Perf todo $index",
            start = if (index % 5 == 0) {
                IcsTimeValue.Floating(LocalDateTime(day, LocalTime(9, 0)))
            } else {
                null
            },
            due = IcsTimeValue.Floating(LocalDateTime(day, LocalTime(18, 0))),
            completed = if (index % 4 == 0) {
                Instant.parse("2026-11-02T10:00:00Z")
            } else {
                null
            },
            priority = when (index % 5) {
                0 -> 1
                1 -> 5
                2 -> 9
                else -> null
            },
            createdAt = 1_700_000_000_000L + index,
            note = "Deterministic benchmark note for todo $index.",
            location = if (index % 3 == 0) "Store ${index % 8}" else "",
            categories = listOf(Categories[index % Categories.size]),
            relatedTo = if (index % 10 == 0 && index > 0) {
                listOf(RelatedTo.parent("t-${index - 1}"))
            } else {
                emptyList()
            },
            status = when {
                index % 4 == 0 -> TodoStatus.Completed
                index % 17 == 0 -> TodoStatus.InProcess
                else -> TodoStatus.NeedsAction
            },
            alarms = if (index % 8 == 0) {
                listOf(Alarm(trigger = AlarmTrigger.Relative((-10).minutes)))
            } else {
                emptyList()
            },
        )
    }

    private fun customTimeZone(index: Int): String = """
        BEGIN:VTIMEZONE
        TZID:X-CRKO-PERF/z$index
        BEGIN:STANDARD
        DTSTART:19701101T020000
        TZOFFSETFROM:+0000
        TZOFFSETTO:+0000
        END:STANDARD
        END:VTIMEZONE
    """.trimIndent()

    private class DirectoryKeyValueStore(private val root: Path) : KeyValueStore {
        override fun getString(key: String): String? {
            val file = root.resolve(fileName(key))
            return if (file.exists()) Files.readString(file) else null
        }

        override fun putString(key: String, value: String) {
            Files.createDirectories(root)
            Files.writeString(root.resolve(fileName(key)), value)
        }

        private fun fileName(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private class CountingStateStorage(private val delegate: StateStorage) : StateStorage {
        var stateWrites = 0

        override fun loadState(): AppState = delegate.loadState()

        override fun saveState(state: AppState) {
            stateWrites += 1
            delegate.saveState(state)
        }

        override fun loadSettings(): PersistedSettings = delegate.loadSettings()

        override fun saveSettings(settings: PersistedSettings) = delegate.saveSettings(settings)

        override fun loadDamage(): LoadDamage = delegate.loadDamage()
    }

    private class FixedStateStorage(private val state: AppState) : StateStorage {
        private var settings = PersistedSettings()

        override fun loadState(): AppState = state

        override fun saveState(state: AppState) = Unit

        override fun loadSettings(): PersistedSettings = settings

        override fun saveSettings(settings: PersistedSettings) {
            this.settings = settings
        }
    }

    private object FixedClock : Clock {
        override fun now(): Instant = FixedNow
    }

    companion object {
        private val DatasetSizes = listOf(1_000, 5_000, 20_000)
        private const val TodoCount = 1_000
        private const val SubscriptionCalendars = 10
        private const val BurstEdits = 20
        private const val Samples = 9
        private const val Warmups = 3
        private val BenchmarkDate = LocalDate(2026, 11, 3)
        private val FixedNow = Instant.parse("2026-11-03T09:00:00Z")
        private val Categories = listOf(
            "work", "personal", "team", "health", "travel", "study", "errand", "family",
        )
    }
}

private fun Double.fixed(): String = String.format(java.util.Locale.ROOT, "%.3f", this)

private fun bytes(value: Long): String = when {
    value >= 1_048_576 -> String.format(java.util.Locale.ROOT, "%.1f MiB", value / 1_048_576.0)
    value >= 1_024 -> String.format(java.util.Locale.ROOT, "%.1f KiB", value / 1_024.0)
    else -> "$value B"
}
