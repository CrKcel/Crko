package com.crko.state.event

import com.crko.domain.Alarm
import com.crko.domain.AlarmRelated
import com.crko.domain.AlarmTrigger
import com.crko.domain.IcsTimeValue
import com.crko.domain.MaxEndMinutes
import com.crko.domain.OrdinalWeekday
import com.crko.domain.Recurrence
import com.crko.domain.RepeatFrequency
import com.crko.domain.RepeatRule
import com.crko.domain.wallDate
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Duration.Companion.minutes

/**
 * Pure editing rules shared by the event editor form. Kept out of the Composable so the draft
 * semantics (relative reminder rewriting, repeat-rule building, time clamping) can be tested
 * without composing UI.
 */

/** How the editor terminates a repeat rule: never, after N times, or on a date. */
internal enum class RepeatEndMode { Never, Count, Until }

/**
 * Changes only the one relative reminder the editor shows. Other imported `VALARM`s (audio/email,
 * absolute or extra relative alarms) survive an edit instead of collapsing into one simple alarm.
 * [atEnd] keeps the `RELATED=END` base: switching the base rewrites it, leaving it untouched
 * preserves an imported end-relative trigger instead of silently rebasing it onto the start.
 */
internal fun updateReminderAlarms(
    alarms: List<Alarm>,
    original: Int?,
    reminder: Int?,
    originalAtEnd: Boolean = false,
    atEnd: Boolean = false,
): List<Alarm> {
    if (reminder == original && atEnd == originalAtEnd) return alarms
    val index = alarms.indexOfFirst { it.trigger is AlarmTrigger.Relative }
    return when {
        reminder == null -> if (index < 0) alarms else alarms.filterIndexed { i, _ -> i != index }
        index < 0 -> alarms + Alarm(trigger = relativeTrigger(reminder, atEnd))
        else -> alarms.toMutableList().also {
            it[index] = it[index].copy(trigger = relativeTrigger(reminder, atEnd))
        }
    }
}

private fun relativeTrigger(minutesBefore: Int, atEnd: Boolean): AlarmTrigger.Relative =
    AlarmTrigger.Relative(
        duration = (-minutesBefore).minutes,
        related = if (atEnd) AlarmRelated.End else AlarmRelated.Start,
    )

internal fun buildRule(
    frequency: RepeatFrequency?,
    old: RepeatRule?,
    weekdays: Set<Int>,
    interval: Int,
    endMode: RepeatEndMode,
    count: Int?,
    until: LocalDate,
): RepeatRule? {
    if (frequency == null) return null
    val keep = old?.takeIf { it.frequency == frequency }
    val untilValue = if (endMode == RepeatEndMode.Until) {
        // Keep the imported value (instant/floating/TZID) when the picked date did not change.
        val original = keep?.until
        if (original != null && original.wallDate == until) original else IcsTimeValue.Date(until)
    } else {
        null
    }
    val byDay = when (frequency) {
        RepeatFrequency.Weekly -> weekdays.filter { it in 0..6 }.sorted()
            .map { OrdinalWeekday(0, it) }
        else -> keep?.byDay ?: emptyList()
    }
    val built = RepeatRule(
        frequency = frequency,
        interval = interval.coerceAtLeast(1),
        count = if (endMode == RepeatEndMode.Count) count else null,
        until = untilValue,
        byDay = byDay,
        byMonthDay = keep?.byMonthDay ?: emptyList(),
        byMonth = keep?.byMonth ?: emptyList(),
        bySetPos = keep?.bySetPos ?: emptyList(),
        byYearDay = keep?.byYearDay ?: emptyList(),
        byWeekNo = keep?.byWeekNo ?: emptyList(),
        weekStart = keep?.weekStart,
        byHour = keep?.byHour ?: emptyList(),
        byMinute = keep?.byMinute ?: emptyList(),
        bySecond = keep?.bySecond ?: emptyList(),
    )
    // An untouched rule keeps the imported text (including parts this model cannot represent).
    val unchanged = keep != null &&
        built.copy(raw = null, until = null) == keep.copy(raw = null, until = null) &&
        built.until?.wallDate == keep.until?.wallDate
    return built.copy(raw = if (unchanged) keep.raw else null)
}

/** Replaces the RRULE of a recurrence set, dropping the set when the rule is removed. */
internal fun Recurrence?.withRule(rule: RepeatRule?): Recurrence? = when {
    rule == null -> null
    this == null -> Recurrence(rule = rule)
    else -> copy(rule = rule)
}

internal fun minutesToTime(minutes: Int): LocalTime = LocalTime((minutes.coerceIn(0, MaxEndMinutes)) / 60, minutes.coerceIn(0, MaxEndMinutes) % 60)

/**
 * End minutes after the start time was picked. A zero-duration event keeps its zero length — the
 * end follows the new start; a start pushed past the end on the same day drags the end one hour
 * ahead. Null keeps the current end.
 */
internal fun endMinutesAfterStartChange(
    zeroDuration: Boolean,
    sameDay: Boolean,
    pickedMinutes: Int,
    endMinutes: Int,
): Int? = when {
    zeroDuration -> pickedMinutes
    sameDay && pickedMinutes >= endMinutes -> (pickedMinutes + 60).coerceAtMost(MaxEndMinutes)
    else -> null
}
