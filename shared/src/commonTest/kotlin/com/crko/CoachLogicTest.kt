package com.crko

import com.crko.domain.IcsTimeValue
import com.crko.domain.RelatedTo
import com.crko.domain.Todo
import com.crko.domain.coach.CoachPrompt
import com.crko.domain.coach.CoachPromptKind
import com.crko.domain.coach.EnergyLevel
import com.crko.domain.coach.TaskCoaching
import com.crko.domain.coach.defaultTodayTaskIds
import com.crko.domain.coach.nextOpenTask
import com.crko.domain.coach.orderForEnergy
import com.crko.domain.coach.selectCoachPrompts
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure ordering and budget functions behind the today page. */
class CoachLogicTest {
    private val zone = TimeZone.of("Asia/Shanghai")

    private fun todo(
        id: String,
        due: String? = null,
        priority: Int? = null,
        createdAt: Long = 0,
        done: Boolean = false,
        estimate: Int? = null,
        parentUid: String? = null,
    ): Todo = Todo(
        id = id,
        title = id,
        due = due?.let { IcsTimeValue.Date(LocalDate.parse(it)) },
        priority = priority,
        createdAt = createdAt,
        coaching = estimate?.let { TaskCoaching(estimateMinutes = it) },
        relatedTo = parentUid?.let { listOf(RelatedTo.parent(it)) }.orEmpty(),
        status = if (done) com.crko.domain.TodoStatus.Completed else com.crko.domain.TodoStatus.NeedsAction,
    )

    @Test
    fun aFreshDayPicksThreeRootTasksByDueThenPriority() {
        val todos = listOf(
            todo("late", due = "2026-11-08"),
            todo("high", due = "2026-11-07", priority = 1),
            todo("normal", due = "2026-11-07", priority = 5),
            todo("undated"),
            todo("done", due = "2026-11-06", done = true),
            todo("child", due = "2026-11-06", parentUid = "parent"),
            todo("parent", due = "2026-11-09"),
        )
        assertEquals(listOf("high", "normal", "late"), defaultTodayTaskIds(todos))
    }

    @Test
    fun lowEnergyMovesShortTasksAheadWithoutChangingThePlan() {
        val todos = listOf(
            todo("long", due = "2026-11-06", estimate = 40),
            todo("short", due = "2026-11-07", estimate = 5),
            todo("unknown", due = "2026-11-06", estimate = null),
        )
        val ids = listOf("long", "short", "unknown")
        // The plan order is the user's; only the next-step projection re-orders it.
        assertEquals(listOf("long", "short", "unknown"), orderForEnergy(todos, ids, EnergyLevel.Medium).map { it.id })
        assertEquals(listOf("short", "long", "unknown"), orderForEnergy(todos, ids, EnergyLevel.Low).map { it.id })
        assertEquals("long", nextOpenTask(todos, ids, EnergyLevel.Medium)?.id)
        assertEquals("short", nextOpenTask(todos, ids, EnergyLevel.Low)?.id)
    }

    @Test
    fun lowEnergyKeepsThePlanOrderForTasksAboveTheShortEstimate() {
        val todos = listOf(
            todo("later", due = "2026-11-08", estimate = 40),
            todo("sooner", due = "2026-11-06", estimate = 30),
        )
        // Both are long tasks; low energy must not re-sort them by due date behind the user's back.
        assertEquals(
            listOf("later", "sooner"),
            orderForEnergy(todos, listOf("later", "sooner"), EnergyLevel.Low).map { it.id },
        )
    }

    @Test
    fun theNextStepSkipsCompletedTasks() {
        val todos = listOf(todo("first", done = true), todo("second"), todo("third"))
        assertEquals("second", nextOpenTask(todos, listOf("first", "second", "third"), EnergyLevel.High)?.id)
    }

    @Test
    fun theBudgetKeepsTheEarliestPromptPerWindowAndCapsTheDay() {
        val day = LocalDate(2026, 11, 6)
        fun at(hour: Int, minute: Int) =
            LocalDateTime(day.year, day.month.number, day.day, hour, minute).toInstant(zone).toEpochMilliseconds()
        val prompts = listOf(
            CoachPrompt("a", at(9, 0), CoachPromptKind.Transition),
            CoachPrompt("b", at(9, 5), CoachPromptKind.Overtime),
            CoachPrompt("c", at(10, 0), CoachPromptKind.Overtime),
            CoachPrompt("d", at(10, 0), CoachPromptKind.Transition),
            CoachPrompt("e", at(11, 0), CoachPromptKind.Transition),
            CoachPrompt("f", at(12, 0), CoachPromptKind.Overtime),
        )
        // Same 15-minute window keeps a; the exact tie keeps the transition d; the budget stops e.
        assertEquals(setOf("a", "d"), selectCoachPrompts(prompts, dailyBudget = 2, zone = zone))
        assertEquals(setOf("a", "d", "e"), selectCoachPrompts(prompts, dailyBudget = 3, zone = zone))
        assertTrue(selectCoachPrompts(prompts, dailyBudget = 0, zone = zone).isEmpty())
    }

    @Test
    fun theBudgetIsPerCalendarDay() {
        val first = LocalDate(2026, 11, 6)
        val second = LocalDate(2026, 11, 7)
        fun millis(date: LocalDate, hour: Int) =
            LocalDateTime(date.year, date.month.number, date.day, hour, 0).toInstant(zone).toEpochMilliseconds()
        val prompts = (8..11).map { hour ->
            CoachPrompt("d1-$hour", millis(first, hour), CoachPromptKind.Transition)
        } + (8..11).map { hour ->
            CoachPrompt("d2-$hour", millis(second, hour), CoachPromptKind.Transition)
        }
        val kept = selectCoachPrompts(prompts, dailyBudget = 2, zone = zone)
        assertEquals(setOf("d1-8", "d1-9", "d2-8", "d2-9"), kept)
    }
}
