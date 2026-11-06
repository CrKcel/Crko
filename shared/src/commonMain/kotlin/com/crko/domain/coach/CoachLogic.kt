package com.crko.domain.coach

import com.crko.domain.Todo
import com.crko.domain.todoPriorityRank
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** App-created prompt kinds; transitions outrank overtime when both land on the same minute. */
enum class CoachPromptKind { Transition, Overtime }

/** One candidate app prompt, identified by the reminder key it will be scheduled under. */
data class CoachPrompt(
    val id: String,
    val triggerAtMillis: Long,
    val kind: CoachPromptKind,
)

private const val QuarterHourMillis = 15 * 60 * 1000L

/**
 * Root tasks a fresh day starts with: open top-level todos, earliest due first, then priority, then
 * newest first. A subtask never becomes one of the three things on its own; it starts with its
 * parent.
 */
fun defaultTodayTaskIds(todos: List<Todo>, limit: Int = 3): List<String> =
    todos.asSequence()
        .filterNot { it.done }
        .filter { todo -> todo.relatedTo.none { it.isParent } }
        .sortedWith(
            compareBy<Todo>(
                { it.dueDay?.toEpochDays() ?: Long.MAX_VALUE },
                { todoPriorityRank(it.priority) },
                { -it.createdAt },
            ),
        )
        .take(limit.coerceAtLeast(0))
        .map { it.id }
        .toList()

/**
 * The plan order the next-step card reads. With low energy a task estimated at ten minutes or less
 * moves ahead of the others; everything else keeps the plan's own order.
 */
fun orderForEnergy(todos: List<Todo>, taskIds: List<String>, energy: EnergyLevel): List<Todo> {
    val byId = todos.associateBy { it.id }
    val selected = taskIds.mapNotNull { byId[it] }
    if (energy != EnergyLevel.Low) return selected
    // Only the estimate grouping decides the order; the stable sort keeps every other task in the
    // plan's own order instead of re-sorting it by due date or priority.
    return selected.sortedWith(
        compareBy<Todo> { todo ->
            val estimate = todo.coaching?.estimateMinutes
            estimate == null || estimate > 10
        },
    )
}

/** First still-open task in the order the next-step card shows; null when the day is done. */
fun nextOpenTask(todos: List<Todo>, taskIds: List<String>, energy: EnergyLevel): Todo? =
    orderForEnergy(todos, taskIds, energy).firstOrNull { !it.done }

/**
 * Applies the daily coach-prompt budget. Only `origin = coach` entries pass through here; user
 * alarms and focus-session notifications never reach it.
 *
 * At most [dailyBudget] prompts survive per calendar day. Inside a day, every 15-minute window
 * keeps only the earliest prompt, and an exact tie keeps a transition over an overtime prompt.
 * The return value is the set of ids to schedule; the caller drops everything else.
 */
fun selectCoachPrompts(
    prompts: List<CoachPrompt>,
    dailyBudget: Int,
    zone: TimeZone,
): Set<String> {
    if (dailyBudget <= 0 || prompts.isEmpty()) return emptySet()
    val kept = mutableSetOf<String>()
    prompts
        .groupBy { prompt ->
            Instant.fromEpochMilliseconds(prompt.triggerAtMillis).toLocalDateTime(zone).date
        }
        .entries
        .sortedBy { it.key }
        .forEach { (_, dayPrompts) ->
            var count = 0
            var lastWindow: Long? = null
            dayPrompts
                .sortedWith(compareBy<CoachPrompt>({ it.triggerAtMillis }, { it.kind.ordinal }))
                .forEach { prompt ->
                    if (count >= dailyBudget) return@forEach
                    val window = prompt.triggerAtMillis / QuarterHourMillis
                    if (window == lastWindow) return@forEach
                    lastWindow = window
                    kept += prompt.id
                    count++
                }
        }
    return kept
}
