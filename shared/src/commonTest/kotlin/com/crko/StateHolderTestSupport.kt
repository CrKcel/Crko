package com.crko

import com.crko.domain.AppState
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.PersistedSettings
import com.crko.domain.daysTo
import com.crko.domain.movedByWallDays
import com.crko.domain.wallDateTime
import com.crko.state.AppStateHolder
import com.crko.state.ReminderScheduler
import com.crko.state.ScheduledReminder
import com.crko.storage.StateStorage
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * Fixtures shared by the state-holder test classes after the `StateHolderTest` split (E1).
 */
internal class FakeClock(private val millis: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
}

internal class MemoryStorage(
    var state: AppState = AppState(),
    var settings: PersistedSettings = PersistedSettings(),
) : StateStorage {
    var writes = 0

    override fun loadState() = state
    override fun saveState(state: AppState) {
        writes++
        this.state = state
    }

    override fun loadSettings() = settings
    override fun saveSettings(settings: PersistedSettings) {
        this.settings = settings
    }
}

/**
 * Records every scheduling pass so tests can assert exactly which keys the platform would have
 * seen. A pass with no keys never reaches it, because the holder skips empty deltas.
 */
internal class RecordingReminderScheduler : ReminderScheduler {
    sealed interface Pass {
        /** Every key the pass touched: cancelled plus (re)registered. */
        val keys: Set<String>

        /** What the pass asked the platform to (re)register, in plan order. */
        val registered: List<String>

        data class Execute(
            val cancelled: Set<String>,
            override val registered: List<String>,
        ) : Pass {
            override val keys: Set<String> get() = cancelled + registered
        }

        data class ReplaceAll(override val registered: List<String>) : Pass {
            override val keys: Set<String> get() = registered.toSet()
        }
    }

    val passes = mutableListOf<Pass>()

    /** When false, the platform reports "not applied" so the holder keeps its bookkeeping open. */
    var granted = true

    override suspend fun execute(cancelKeys: Set<String>, register: List<ScheduledReminder>): Boolean {
        passes += Pass.Execute(cancelKeys, register.map { it.key })
        return granted
    }

    override suspend fun replaceAll(plan: List<ScheduledReminder>): Boolean {
        passes += Pass.ReplaceAll(plan.map { it.key })
        return granted
    }

    override suspend fun ensurePermission(): Boolean = granted
}

internal class Fixture(
    val storage: MemoryStorage = MemoryStorage(),
    locale: String = "en-US",
) {
    var ids = 0
    private val scope = TestScope(StandardTestDispatcher())
    // 2026-10-27T09:00:00Z == 17:00 in Singapore.
    val holder = AppStateHolder(
        scope = scope,
        initialSystemLocale = locale,
        clock = FakeClock(1_793_101_200_000),
        idProvider = { "id-${ids++}" },
        storage = storage,
        // Persistence is off-thread in production; the fixture keeps writes on the test scheduler
        // and runs them eagerly, so storage assertions still see the committed state.
        persistenceDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
    )
}

/** Moves a floating fixture to [date], keeping its wall clock and length. */
internal fun Event.movedTo(date: LocalDate): Event {
    val delta = start.wallDateTime.date.daysTo(date)
    return movedByWallDays(delta)
}

/** Floating fixture at [date] with new wall-clock minutes; end equal to start is zero length. */
internal fun Event.withTimes(
    date: LocalDate,
    startMinutes: Int,
    endMinutes: Int,
    endDate: LocalDate = date,
): Event = copy(
    start = IcsTimeValue.Floating(LocalDateTime(date, LocalTime(startMinutes / 60, startMinutes % 60))),
    end = if (endDate == date && endMinutes == startMinutes) {
        null
    } else {
        IcsTimeValue.Floating(LocalDateTime(endDate, LocalTime(endMinutes / 60, endMinutes % 60)))
    },
)
