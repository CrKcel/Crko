package com.crko

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.Todo
import com.crko.domain.coach.EnergyLevel
import com.crko.domain.coach.TaskCoaching
import com.crko.i18n.EnStrings
import com.crko.state.ReminderLevel
import com.crko.state.ReminderOrigin
import com.crko.state.today.DailyPlanKind
import com.crko.state.today.FocusController
import com.crko.state.today.SupportRepository
import com.crko.state.today.TodayController
import com.crko.storage.MemorySupportStore
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** The stored today plan and the wall-clock focus session behind the today page. */
class TodayFocusTest {
    private val day = LocalDate(2026, 11, 6)

    private class MutableClock(var millis: Long) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    private fun state(vararg todos: Todo): AppState =
        AppState(calendars = listOf(Calendar.local().copy(todos = todos.toList())))

    private fun todo(
        id: String,
        due: String? = null,
        estimate: Int? = null,
        done: Boolean = false,
    ): Todo = Todo(
        id = id,
        title = id,
        due = due?.let { com.crko.domain.IcsTimeValue.Date(LocalDate.parse(it)) },
        coaching = estimate?.let { TaskCoaching(estimateMinutes = it) },
        status = if (done) com.crko.domain.TodoStatus.Completed else com.crko.domain.TodoStatus.NeedsAction,
    )

    @Test
    fun aFreshDayPicksAndStoresThreeRootTasks() {
        val store = MemorySupportStore()
        val repository = SupportRepository(store)
        val todos = state(
            todo("later", due = "2026-11-08"),
            todo("sooner", due = "2026-11-07"),
            todo("undated"),
        )
        val controller = TodayController({ todos }, { day }, repository)
        assertEquals(listOf("sooner", "later", "undated"), controller.plan.taskIds)
        assertTrue(store.recordsOfKind(DailyPlanKind).single().recordId.startsWith("daily_plan:2026-11-06"))
    }

    @Test
    fun energyAndReplacementSurviveARestart() {
        val store = MemorySupportStore()
        val todos = state(
            todo("long", due = "2026-11-06", estimate = 40),
            todo("short", due = "2026-11-07", estimate = 5),
            todo("third"),
        )
        val first = TodayController({ todos }, { day }, SupportRepository(store))
        first.setTasks(listOf("short", "long"))
        first.setEnergy(EnergyLevel.Low)
        assertEquals(listOf("short", "long"), first.plan.taskIds)
        assertEquals(EnergyLevel.Low, first.energy)

        val restarted = TodayController({ todos }, { day }, SupportRepository(store))
        assertEquals(listOf("short", "long"), restarted.plan.taskIds)
        assertEquals(EnergyLevel.Low, restarted.energy)
        assertEquals("short", restarted.nextTask?.id)
    }

    @Test
    fun clearingThePlanIsPersisted() {
        val store = MemorySupportStore()
        val todos = state(todo("one"))
        val controller = TodayController({ todos }, { day }, SupportRepository(store))
        controller.clearPlan()
        assertEquals(emptyList(), controller.plan.taskIds)
        val restarted = TodayController({ todos }, { day }, SupportRepository(store))
        assertEquals(emptyList(), restarted.plan.taskIds)
        assertNull(restarted.nextTask)
    }

    @Test
    fun aRunningFocusSessionComesBackFromTheWallClock() {
        val store = MemorySupportStore()
        val clock = MutableClock(1_000_000)
        val controller = FocusController(
            clock = clock,
            support = SupportRepository(store),
            strings = { EnStrings },
            taskTitle = { "Task" },
            onChange = {},
        )
        controller.start("t1", 5)
        assertEquals(300_000L, controller.remainingMillis())
        assertEquals(1_000_000L, controller.session?.startedAtMs)

        // A new process: the timer is projected from instants, not from anything held in memory.
        clock.millis += 180_000
        val restarted = FocusController(
            clock = clock,
            support = SupportRepository(store),
            strings = { EnStrings },
            taskTitle = { "Task" },
            onChange = {},
        )
        assertEquals(120_000L, restarted.remainingMillis())
        assertFalse(restarted.isFinished())
    }

    @Test
    fun aSessionPastItsDeadlineComesBackAsFinished() {
        val store = MemorySupportStore()
        val clock = MutableClock(1_000_000)
        val controller = FocusController(clock, SupportRepository(store), { EnStrings }, { "Task" }, {})
        controller.start("t1", 5)
        clock.millis += 360_000
        val restarted = FocusController(clock, SupportRepository(store), { EnStrings }, { "Task" }, {})
        assertTrue(restarted.isFinished())
        assertEquals(0L, restarted.remainingMillis())
    }

    @Test
    fun pausingFreezesTheRemainingTimeAndFinishCountsOnlyFocusedMinutes() {
        val store = MemorySupportStore()
        val clock = MutableClock(1_000_000)
        val controller = FocusController(clock, SupportRepository(store), { EnStrings }, { "Task" }, {})
        controller.start("t1", 5)
        clock.millis += 180_000
        assertEquals(120_000L, controller.remainingMillis())

        controller.pause()
        val frozen = controller.remainingMillis()
        clock.millis += 600_000
        assertEquals(frozen, controller.remainingMillis())
        assertTrue(controller.isPaused)

        controller.resume()
        clock.millis += 60_000
        val ended = controller.finish()
        assertEquals("t1", ended.taskId)
        // 4 focused minutes: start -> pause (3 min) plus resume -> end (1 min); the pause is excluded.
        assertEquals(4, ended.actualMinutes)
        assertNull(controller.session)
        assertNull(store.recordsOfKind("focus_current").firstOrNull())
    }

    @Test
    fun aRunningSessionSchedulesOneQuietSessionReminder() {
        val store = MemorySupportStore()
        val clock = MutableClock(1_000_000)
        val controller = FocusController(clock, SupportRepository(store), { EnStrings }, { "Task" }, {})
        controller.start("t1", 5)
        val reminder = assertNotNull(controller.sessionReminder())
        assertEquals(ReminderOrigin.Session, reminder.origin)
        assertEquals(ReminderLevel.Quiet, reminder.level)
        assertEquals("crko://start", reminder.deepLink)
        assertEquals("t1", reminder.recordId)
        assertEquals(1_000_000L + 300_000L, reminder.triggerAtMillis)

        controller.pause()
        assertNull(controller.sessionReminder())
    }
}
