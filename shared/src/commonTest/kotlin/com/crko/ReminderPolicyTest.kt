package com.crko

import com.crko.domain.coach.CoachPromptKind
import com.crko.state.ReminderAction
import com.crko.state.ReminderFingerprint
import com.crko.state.ReminderLevel
import com.crko.state.ReminderOrigin
import com.crko.state.ScheduledReminder
import com.crko.state.applyReminderPolicy
import com.crko.state.diffReminders
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only app prompts are budgeted; user alarms and session notifications pass through untouched. */
class ReminderPolicyTest {
    private val zone = TimeZone.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime(2026, 11, 6, hour, minute).toInstant(zone).toEpochMilliseconds()

    private fun reminder(
        key: String,
        millis: Long,
        origin: ReminderOrigin,
        level: ReminderLevel = ReminderLevel.Normal,
        actions: List<ReminderAction> = listOf(ReminderAction.Open),
        coachKind: CoachPromptKind? = null,
    ) = ScheduledReminder(
        key = key,
        triggerAtMillis = millis,
        title = key,
        body = "",
        fingerprint = ReminderFingerprint(
            triggerAtMillis = millis,
            title = key,
            body = "",
            level = level,
            actions = actions,
        ),
        origin = origin,
        level = level,
        actions = actions,
        coachKind = coachKind,
    )

    @Test
    fun aZeroBudgetDropsAppPromptsButNeverUserAlarms() {
        val plan = listOf(
            reminder("user:1", at(9, 0), ReminderOrigin.User),
            reminder("coach:1", at(9, 0), ReminderOrigin.Coach, coachKind = CoachPromptKind.Transition),
            reminder("session:1", at(9, 30), ReminderOrigin.Session, level = ReminderLevel.Quiet),
        )
        val filtered = applyReminderPolicy(plan, coachBudget = 0, zone = zone)
        assertEquals(listOf("user:1", "session:1"), filtered.map { it.key })
    }

    @Test
    fun theBudgetMergesFifteenMinuteWindowsAndCapsTheDay() {
        val plan = listOf(
            reminder("a", at(9, 0), ReminderOrigin.Coach, coachKind = CoachPromptKind.Transition),
            reminder("b", at(9, 5), ReminderOrigin.Coach, coachKind = CoachPromptKind.Overtime),
            reminder("c", at(9, 30), ReminderOrigin.Coach, coachKind = CoachPromptKind.Transition),
            reminder("d", at(10, 0), ReminderOrigin.Coach, coachKind = CoachPromptKind.Transition),
            reminder("user", at(10, 0), ReminderOrigin.User),
        )
        assertEquals(listOf("a", "c", "d", "user"), applyReminderPolicy(plan, 3, zone).map { it.key })
        // The cap only applies to coach prompts; the user alarm always survives.
        assertEquals(listOf("a", "user"), applyReminderPolicy(plan, 1, zone).map { it.key })
    }

    @Test
    fun anActionOrLevelChangeMovesTheFingerprint() {
        val base = reminder("k", at(9, 0), ReminderOrigin.User)
        val changedActions = base.copy(
            actions = listOf(ReminderAction.Open, ReminderAction.Snooze),
            fingerprint = base.fingerprint.copy(actions = listOf(ReminderAction.Open, ReminderAction.Snooze)),
        )
        val changedLevel = base.copy(
            level = ReminderLevel.Quiet,
            fingerprint = base.fingerprint.copy(level = ReminderLevel.Quiet),
        )
        assertTrue(diffReminders(mapOf(base.key to base.fingerprint), listOf(changedActions)).register.isNotEmpty())
        assertTrue(diffReminders(mapOf(base.key to base.fingerprint), listOf(changedLevel)).register.isNotEmpty())
    }
}
