package com.crko.state

import com.crko.domain.coach.CoachPrompt
import com.crko.domain.coach.CoachPromptKind
import com.crko.domain.coach.selectCoachPrompts
import kotlinx.datetime.TimeZone

/**
 * Applies the app-prompt budget without touching anything the user scheduled. Only reminders with
 * `origin = coach` are grouped per day, merged per 15-minute window and capped; `user` alarms and
 * `session` notifications pass through unchanged.
 */
fun applyReminderPolicy(
    reminders: List<ScheduledReminder>,
    coachBudget: Int,
    zone: TimeZone,
): List<ScheduledReminder> {
    val coach = reminders.filter { it.origin == ReminderOrigin.Coach }
    if (coach.isEmpty()) return reminders
    val keep = selectCoachPrompts(
        prompts = coach.map { reminder ->
            CoachPrompt(
                id = reminder.key,
                triggerAtMillis = reminder.triggerAtMillis,
                kind = reminder.coachKind ?: CoachPromptKind.Overtime,
            )
        },
        dailyBudget = coachBudget,
        zone = zone,
    )
    return reminders.filter { reminder ->
        reminder.origin != ReminderOrigin.Coach || reminder.key in keep
    }
}
