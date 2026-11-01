package com.crko.state

import com.crko.domain.Alarm
import com.crko.domain.Event
import com.crko.domain.Todo
import com.crko.domain.daysTo
import com.crko.domain.isOccurrenceStart
import com.crko.domain.plusDays
import com.crko.i18n.Strings
import com.crko.i18n.alarmLabel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** One reminder handed to the platform scheduler: it fires once at [triggerAtMillis]. */
data class ScheduledReminder(
    /** Stable identifier; rescheduling replaces requests with the same key. */
    val key: String,
    val triggerAtMillis: Long,
    val title: String,
    val body: String,
)

/** Occurrence dates further than this behind today no longer feed `RELATED=END` triggers. */
private const val MaxLookbackDays = 400

/** How far ahead reminders are planned, in days of occurrence starts. */
const val ReminderHorizonDays = 60

/**
 * Upper bound of reminders handed to the platform in one pass; iOS drops pending requests beyond
 * 64 per app, and the alarm manager should not be flooded either. The earliest ones win.
 */
const val MaxScheduledReminders = 64

/**
 * Upcoming system-notification triggers for every stored `VALARM`: one entry per alarm per
 * occurrence (and per `REPEAT` continuation) that falls between [now] and [now] + [horizonDays].
 *
 * Relative triggers resolve against the occurrence start, or against the occurrence end when the
 * alarm was imported with `RELATED=END`; negative minutes fire after the base. Absolute
 * `TRIGGER;VALUE=DATE-TIME` values fire once at their own instant. `EMAIL` alarms cannot be
 * delivered by a local notification and are skipped.
 */
fun planReminders(
    events: List<Event>,
    todos: List<Todo>,
    strings: Strings,
    now: Instant,
    zone: TimeZone,
    horizonDays: Int = ReminderHorizonDays,
    limit: Int = MaxScheduledReminders,
): List<ScheduledReminder> {
    val nowMillis = now.toEpochMilliseconds()
    val today = now.toLocalDateTime(zone).date
    val horizonEnd = today.plusDays(horizonDays)
    val reminders = mutableListOf<ScheduledReminder>()
    events.forEach { event ->
        collectEventReminders(event, today, horizonEnd, horizonDays, zone, nowMillis, strings, reminders)
    }
    todos.forEach { todo -> collectTodoReminders(todo, zone, nowMillis, strings, reminders) }
    return reminders
        .filter { it.triggerAtMillis > nowMillis }
        .sortedBy { it.triggerAtMillis }
        .take(limit)
}

private fun collectEventReminders(
    event: Event,
    today: LocalDate,
    horizonEnd: LocalDate,
    horizonDays: Int,
    zone: TimeZone,
    nowMillis: Long,
    strings: Strings,
    output: MutableList<ScheduledReminder>,
) {
    val relative = event.alarms
        .withIndex()
        .filter { (_, alarm) -> !alarm.action.equals("EMAIL", ignoreCase = true) && alarm.absoluteTrigger == null }
    val absolute = event.alarms
        .withIndex()
        .filter { (_, alarm) -> !alarm.action.equals("EMAIL", ignoreCase = true) && alarm.absoluteTrigger != null }
    if (relative.isEmpty() && absolute.isEmpty()) return
    val title = event.title.ifBlank { strings.untitledEvent }

    // An absolute trigger fires once regardless of the occurrences, so it is emitted outside the
    // occurrence loop; a relative trigger is resolved per occurrence.
    absolute.forEach { (index, alarm) ->
        val at = parseTriggerInstant(alarm.absoluteTrigger.orEmpty(), zone)?.toEpochMilliseconds() ?: return@forEach
        output += ScheduledReminder(
            key = "e:${event.id}:$index:abs",
            triggerAtMillis = at,
            title = title,
            body = alarm.description.ifBlank { alarmMoment(event.allDay, event.day, event.startMinutes, strings) },
        )
    }
    if (relative.isEmpty()) return

    // Start the walk no earlier than the occurrences that can still produce a trigger: an
    // end-based alarm reaches back one occurrence span plus the horizon past today. Ordinals for
    // `COUNT` rules are computed from the real anchor regardless of where the walk starts.
    val span = event.day.daysTo(event.endDay)

    fun emitOccurrence(candidate: LocalDate) {
        val startAt = candidate.atTime(event.startMinutes.toLocalTime(event.startSeconds)).toInstant(zone)
        val endDay = candidate.plusDays(span)
        val endAt = when {
            // All-day ranges are inclusive on the stored end date and end at the next midnight.
            event.allDay -> endDay.plusDays(1).atTime(LocalTime(0, 0)).toInstant(zone)
            else -> endDay.atTime(event.endMinutes.toLocalTime(event.endSeconds)).toInstant(zone)
        }
        relative.forEach { (index, alarm) ->
            val base = if (alarm.relatedToEnd) endAt else startAt
            emitWithRepeat(event, index, alarm, base, candidate, event.allDay, event.startMinutes, nowMillis, strings, output)
        }
    }

    if (event.repeat == null) {
        // A one-off event has exactly one occurrence; no walk over the horizon.
        if (event.day <= horizonEnd) emitOccurrence(event.day)
        return
    }

    val lookback = minOf(span + horizonDays, MaxLookbackDays)
    val excluded = event.excludedDates.toSet()
    var candidate = maxOf(event.day, today.plusDays(-1 - lookback))
    while (candidate <= horizonEnd) {
        if (candidate.toString() !in excluded && event.isOccurrenceStart(candidate, event.repeat)) {
            emitOccurrence(candidate)
        }
        candidate = candidate.plusDays(1)
    }
}

/** Emits one reminder for the alarm trigger and, when `REPEAT` is set, for every continuation. */
private fun emitWithRepeat(
    event: Event,
    index: Int,
    alarm: Alarm,
    base: Instant,
    occurrence: LocalDate,
    allDay: Boolean,
    startMinutes: Int,
    nowMillis: Long,
    strings: Strings,
    output: MutableList<ScheduledReminder>,
) {
    val first = base.toEpochMilliseconds() - alarm.triggerMinutes * 60_000L
    val extra = alarm.repeat?.takeIf { it > 0 } ?: 0
    // REPEAT without a positive DURATION has no spacing; the extra firings would collapse onto the
    // first one, so they are ignored.
    val step = alarm.durationMinutes?.takeIf { it > 0 }?.let { it * 60_000L } ?: 0L
    val firings = if (extra > 0 && step > 0L) extra else 0
    for (round in 0..firings) {
        val at = first + round * step
        if (at <= nowMillis) continue
        output += ScheduledReminder(
            key = "e:${event.id}:$index:${at / 60_000L}",
            triggerAtMillis = at,
            title = event.title.ifBlank { strings.untitledEvent },
            body = alarm.description.ifBlank {
                "${strings.alarmLabel(alarm.triggerMinutes, alarm.relatedToEnd)} · ${alarmMoment(allDay, occurrence, startMinutes, strings)}"
            },
        )
    }
}

private fun collectTodoReminders(
    todo: Todo,
    zone: TimeZone,
    nowMillis: Long,
    strings: Strings,
    output: MutableList<ScheduledReminder>,
) {
    if (todo.done) return
    todo.alarms.forEachIndexed { index, alarm ->
        val base = when {
            alarm.absoluteTrigger != null -> parseTriggerInstant(alarm.absoluteTrigger, zone)
            alarm.relatedToEnd -> todo.dueTime?.let(::parseStoredInstant)
            else -> todo.startTime?.let(::parseStoredInstant)
        } ?: return@forEachIndexed
        val atMillis = if (alarm.absoluteTrigger != null) {
            base.toEpochMilliseconds()
        } else {
            base.toEpochMilliseconds() - alarm.triggerMinutes * 60_000L
        }
        if (atMillis <= nowMillis) return@forEachIndexed
        output += ScheduledReminder(
            key = "t:${todo.id}:$index:${atMillis / 60_000L}",
            triggerAtMillis = atMillis,
            title = todo.title,
            body = alarm.description.ifBlank { strings.alarmLabel(alarm.triggerMinutes, alarm.relatedToEnd) },
        )
    }
}

private fun alarmMoment(allDay: Boolean, date: LocalDate, startMinutes: Int, strings: Strings): String = when {
    allDay -> strings.dayMonth(date)
    else -> strings.time(startMinutes.toLocalTime(0))
}

private fun Int.toLocalTime(seconds: Int): LocalTime = LocalTime(this / 60, this % 60, seconds)

/** Stored `startTime`/`dueTime` values are ISO-8601 instants; broken ones are ignored. */
private fun parseStoredInstant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

/**
 * Parses an absolute `TRIGGER;VALUE=DATE-TIME` value: the ICS basic form (`20261028T090000Z`,
 * floating without the `Z`) as well as the extended ISO-8601 form are accepted; floating values
 * resolve against [zone].
 */
internal fun parseTriggerInstant(value: String, zone: TimeZone): Instant? {
    val text = value.trim()
    if (text.isEmpty()) return null
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
    val utc = text.endsWith("Z") || text.endsWith("z")
    val compact = if (utc) text.dropLast(1) else text
    val digits = compact.replace("-", "").replace(":", "")
    if (digits.length != 15 || digits[8] != 'T') return null
    val date = LocalDate(
        digits.substring(0, 4).toIntOrNull() ?: return null,
        digits.substring(4, 6).toIntOrNull() ?: return null,
        digits.substring(6, 8).toIntOrNull() ?: return null,
    )
    val time = LocalTime(
        digits.substring(9, 11).toIntOrNull() ?: return null,
        digits.substring(11, 13).toIntOrNull() ?: return null,
        digits.substring(13, 15).toIntOrNull() ?: return null,
    )
    return LocalDateTime(date, time).toInstant(if (utc) TimeZone.UTC else zone)
}
