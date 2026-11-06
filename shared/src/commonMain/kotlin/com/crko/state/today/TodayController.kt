package com.crko.state.today

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppState
import com.crko.domain.AppJson
import com.crko.domain.Todo
import com.crko.domain.coach.EnergyLevel
import com.crko.domain.coach.TodayPlan
import com.crko.domain.coach.defaultTodayTaskIds
import com.crko.domain.coach.nextOpenTask
import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate

/** Number of root tasks a day carries. */
const val TodayPlanSize = 3

/** Daily plans older than this are dropped the next time the plan is read. */
const val TodayPlanRetentionDays = 30

/**
 * The day's three things, its energy and the next step derived from them.
 *
 * A plan is stored per date, so a day rolled over at midnight starts with a fresh default plan.
 * The controller keeps the persisted copy beside the UI state: a replacement, a clear or an energy
 * change writes through [SupportRepository] immediately.
 */
@Stable
internal class TodayController(
    private val state: () -> AppState,
    private val today: () -> LocalDate,
    private val support: SupportRepository,
) {
    var plan: TodayPlan by mutableStateOf(loadOrCreate(today()))
        private set

    /** Set by "今天到这"; the next-step card stops pushing until the user says otherwise. */
    var stoppedForToday: Boolean by mutableStateOf(false)
        private set

    val energy: EnergyLevel get() = plan.energy

    /** Tasks in the order the plan stores them; missing records are simply left out. */
    val tasks: List<Todo>
        get() {
            val byId = state().visibleTodos.associateBy { it.id }
            return plan.taskIds.mapNotNull { byId[it] }
        }

    /** First open task of the plan after the energy ordering. */
    val nextTask: Todo?
        get() = nextOpenTask(state().visibleTodos, plan.taskIds, plan.energy)

    /** Candidate root tasks a plan editor can offer. */
    fun candidates(): List<Todo> {
        val current = state().visibleTodos.filterNot { it.done }
        val picked = plan.taskIds.toSet()
        // Keep the chosen tasks in the list so replacing one is a single tap.
        return current
            .filter { todo -> todo.relatedTo.none { it.isParent } || todo.id in picked }
            .sortedWith(
                compareBy<Todo>(
                    { it.dueDay?.toEpochDays() ?: Long.MAX_VALUE },
                    { it.createdAt.let { created -> -created } },
                ),
            )
    }

    fun setEnergy(energy: EnergyLevel) = update(plan.copy(energy = energy))

    /** Replaces the three things; at most [TodayPlanSize] ids are kept. */
    fun setTasks(taskIds: List<String>) = update(plan.copy(taskIds = taskIds.distinct().take(TodayPlanSize)))

    fun clearPlan() = update(plan.copy(taskIds = emptyList()))

    fun stopForToday() {
        stoppedForToday = true
    }

    /** "今天到这" only pauses the day; this lets the next-step card come back. */
    fun resumeDay() {
        stoppedForToday = false
    }

    /** Reloads at a midnight rollover and drops plans that fell out of the retention window. */
    fun refreshDay() {
        val currentDay = today()
        if (plan.day == currentDay.toString()) return
        stoppedForToday = false
        plan = loadOrCreate(currentDay)
    }

    private fun update(next: TodayPlan) {
        plan = next
        save(next)
    }

    private fun loadOrCreate(day: LocalDate): TodayPlan {
        val dayText = day.toString()
        support.pruneDailyPlans(day.plusDays(-TodayPlanRetentionDays).toString())
        val stored = support.loadOne(dailyPlanId(dayText))
        val decoded = stored?.let { record ->
            runCatching { AppJson.decodeFromString(TodayPlan.serializer(), record.payload) }.getOrNull()
        }
        if (decoded != null) {
            return decoded.copy(day = dayText, taskIds = decoded.taskIds.take(TodayPlanSize))
        }
        val fresh = TodayPlan(
            day = dayText,
            taskIds = defaultTodayTaskIds(state().visibleTodos, TodayPlanSize),
        )
        save(fresh)
        return fresh
    }

    private fun save(plan: TodayPlan) {
        support.save(
            recordId = dailyPlanId(plan.day),
            kind = DailyPlanKind,
            day = plan.day,
            payload = AppJson.encodeToString(TodayPlan.serializer(), plan),
        )
    }
}
