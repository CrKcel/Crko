package com.crko.state.today

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppJson
import com.crko.domain.coach.FocusState
import com.crko.i18n.Strings
import com.crko.state.ReminderAction
import com.crko.state.ReminderFingerprint
import com.crko.state.ReminderLevel
import com.crko.state.ReminderOrigin
import com.crko.state.ScheduledReminder
import kotlin.time.Clock

/** Result of ending a focus session: what to write back and which task to ask about. */
internal data class FocusEnd(val taskId: String?, val actualMinutes: Int)

/**
 * One focus session at a time, restored from wall-clock instants rather than a ticker. Pausing is
 * reflected in [FocusState.pausedTotalMs] and in a moved [FocusState.plannedEndMs], so a process
 * killed mid-session comes back with the remaining time the clock implies.
 */
@Stable
internal class FocusController(
    private val clock: Clock,
    private val support: SupportRepository,
    private val strings: () -> Strings,
    private val taskTitle: (String?) -> String?,
    /** Called whenever the session starts, pauses, resumes, extends or ends. */
    private val onChange: () -> Unit,
) {
    var session: FocusState? by mutableStateOf(restore())
        private set

    /** Wall-clock instant of the pause in progress; null while running or between sessions. */
    var pausedAtMs: Long? by mutableStateOf(session?.pausedAtMs)
        private set

    val isPaused: Boolean get() = pausedAtMs != null

    val isRunning: Boolean get() = session != null

    fun isFinished(nowMillis: Long = clock.now().toEpochMilliseconds()): Boolean {
        val current = session ?: return false
        return pausedAtMs == null && nowMillis >= current.plannedEndMs
    }

    /** Remaining wall-clock milliseconds; frozen while paused. */
    fun remainingMillis(nowMillis: Long = clock.now().toEpochMilliseconds()): Long {
        val current = session ?: return 0L
        val reference = pausedAtMs ?: nowMillis
        return (current.plannedEndMs - reference).coerceAtLeast(0L)
    }

    fun start(taskId: String?, minutes: Int) {
        val now = clock.now().toEpochMilliseconds()
        pausedAtMs = null
        session = FocusState(
            startedAtMs = now,
            pausedTotalMs = 0,
            plannedEndMs = now + minutes.coerceAtLeast(1) * 60_000L,
            taskId = taskId,
        )
        persist(session!!)
        onChange()
    }

    fun pause() {
        val current = session ?: return
        if (pausedAtMs != null) return
        val now = clock.now().toEpochMilliseconds()
        pausedAtMs = now
        // The pause start is part of the stored session: a process killed now comes back paused,
        // with the remaining time frozen at the instant the user paused.
        session = current.copy(pausedAtMs = now)
        persist(session!!)
        onChange()
    }

    fun resume() {
        val current = session ?: return
        val pausedAt = pausedAtMs ?: return
        val now = clock.now().toEpochMilliseconds()
        val remaining = (current.plannedEndMs - pausedAt).coerceAtLeast(0L)
        pausedAtMs = null
        session = current.copy(
            pausedTotalMs = current.pausedTotalMs + (now - pausedAt).coerceAtLeast(0L),
            plannedEndMs = now + remaining,
            pausedAtMs = null,
        )
        persist(session!!)
        onChange()
    }

    /** "继续" at the end: a new stretch of [minutes] starting now. */
    fun extend(minutes: Int) {
        val current = session ?: return
        val now = clock.now().toEpochMilliseconds()
        val pausedAt = pausedAtMs
        val pausedTotal = if (pausedAt == null) {
            current.pausedTotalMs
        } else {
            current.pausedTotalMs + (now - pausedAt).coerceAtLeast(0L)
        }
        pausedAtMs = null
        session = current.copy(
            pausedTotalMs = pausedTotal,
            plannedEndMs = now + minutes.coerceAtLeast(1) * 60_000L,
            pausedAtMs = null,
        )
        persist(session!!)
        onChange()
    }

    /**
     * Ends the session and clears the support row. The caller decides afterwards whether the task
     * counts as done; nothing is completed automatically.
     */
    fun finish(): FocusEnd {
        val current = session
        if (current == null) return FocusEnd(taskId = null, actualMinutes = 0)
        val now = clock.now().toEpochMilliseconds()
        val endAt = pausedAtMs ?: now
        val cappedEnd = if (endAt > current.plannedEndMs) current.plannedEndMs else endAt
        val elapsed = (cappedEnd - current.startedAtMs - current.pausedTotalMs).coerceAtLeast(0L)
        val minutes = if (elapsed <= 0L) 0 else ((elapsed + 59_999L) / 60_000L).toInt()
        session = null
        pausedAtMs = null
        support.delete(FocusCurrentId)
        onChange()
        return FocusEnd(taskId = current.taskId, actualMinutes = minutes)
    }

    /**
     * The quiet notification that fires when the session ends in the background. Paused sessions
     * have no upcoming end, so they contribute nothing until resumed.
     */
    fun sessionReminder(nowMillis: Long = clock.now().toEpochMilliseconds()): ScheduledReminder? {
        val current = session ?: return null
        if (pausedAtMs != null || current.plannedEndMs <= nowMillis) return null
        val text = strings()
        val rawTitle = taskTitle(current.taskId).orEmpty()
        return ScheduledReminder(
            key = "s:focus:${current.startedAtMs}",
            triggerAtMillis = current.plannedEndMs,
            title = rawTitle.ifBlank { text.focus },
            body = text.focusTimeUp,
            fingerprint = ReminderFingerprint(
                triggerAtMillis = current.plannedEndMs,
                title = rawTitle,
                body = "focus",
                level = ReminderLevel.Quiet,
                actions = listOf(ReminderAction.Open),
                deepLink = "crko://start",
            ),
            origin = ReminderOrigin.Session,
            level = ReminderLevel.Quiet,
            actions = listOf(ReminderAction.Open),
            deepLink = "crko://start",
            recordId = current.taskId,
        )
    }

    private fun restore(): FocusState? {
        val record = support.loadOne(FocusCurrentId) ?: return null
        val decoded = runCatching {
            AppJson.decodeFromString(FocusState.serializer(), record.payload)
        }.getOrNull()
        if (decoded == null) {
            support.delete(FocusCurrentId)
            return null
        }
        return decoded
    }

    private fun persist(state: FocusState) {
        support.save(
            recordId = FocusCurrentId,
            kind = FocusCurrentKind,
            day = null,
            payload = AppJson.encodeToString(FocusState.serializer(), state),
        )
    }
}
