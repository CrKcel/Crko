package com.crko.domain.coach

import kotlinx.serialization.Serializable

/**
 * Guidance attached to a todo that lives only on this device: the first concrete step, the
 * estimate it was planned with, and the time it actually took. None of these values are RFC 5545
 * properties, so an ICS export or a CalDAV round trip never carries them back.
 */
@Serializable
data class TaskCoaching(
    /** The one action that starts the task; short enough to read from the next-step card. */
    val firstStep: String = "",
    val estimateMinutes: Int? = null,
    val actualMinutes: Int? = null,
)

/** How much energy the user says they have; only the next-step ordering reads it. */
@Serializable
enum class EnergyLevel { Low, Medium, High }

/**
 * The three tasks chosen for one day. The plan is stored per date and replaced when a later day
 * takes over; [taskIds] are root task ids, [energy] is the choice shown in the header.
 */
@Serializable
data class TodayPlan(
    /** ISO date (`yyyy-MM-dd`) the plan belongs to. */
    val day: String,
    val taskIds: List<String> = emptyList(),
    val energy: EnergyLevel = EnergyLevel.Medium,
)

/**
 * A focus session that survives the process. The remaining time is projected from wall-clock
 * instants instead of a ticker, so a killed process resumes where the clock says it should be:
 * `plannedEndMs - pausedTotalMs` minus any pause currently running.
 */
@Serializable
data class FocusState(
    /** Instant the focused work started, in epoch milliseconds. */
    val startedAtMs: Long,
    /** Wall-clock milliseconds spent paused before the current running stretch. */
    val pausedTotalMs: Long = 0,
    /** Instant the session should end unless paused. */
    val plannedEndMs: Long,
    /** Todo being focused on; null for a session started from an entry without a task. */
    val taskId: String? = null,
    /** Instant the pause in progress started; null while running. Persisted so a kill keeps it. */
    val pausedAtMs: Long? = null,
)
