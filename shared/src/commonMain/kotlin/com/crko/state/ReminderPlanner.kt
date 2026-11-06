package com.crko.state

import com.crko.domain.Alarm
import com.crko.domain.AlarmRelated
import com.crko.domain.AlarmTrigger
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.OccurrenceTime
import com.crko.domain.Todo
import com.crko.domain.coach.CoachPromptKind
import com.crko.domain.daysTo
import com.crko.domain.instantOrNull
import com.crko.domain.localStart
import com.crko.domain.occurrenceDate
import com.crko.domain.occurrenceEndInstant
import com.crko.domain.occurrenceStartInstant
import com.crko.domain.occurrenceStartsBetween
import com.crko.domain.occurrenceTimesOn
import com.crko.domain.plusDays
import com.crko.domain.wallDateFor
import com.crko.domain.withWallDate
import com.crko.i18n.Strings
import com.crko.i18n.alarmLabel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** Who created a reminder: the user's own `VALARM`, the app's gentle prompts, or a focus session. */
enum class ReminderOrigin { User, Coach, Session }

/** Sound level of a notification: user alarms keep the system sound, app prompts stay quiet. */
enum class ReminderLevel { Normal, Quiet }

/** Actions offered on a reminder notification. */
enum class ReminderAction { Open, Snooze, Start }

/**
 * Language-independent identity of one reminder: trigger instant, raw record title and the raw
 * alarm description (or the canonical ingredients of the derived body). Localized wording is
 * deliberately excluded, so a language switch does not read as a reminder change; the platform
 * only sees the new wording on the next change that actually touches the record.
 */
data class ReminderFingerprint(
    val triggerAtMillis: Long,
    val title: String,
    val body: String,
    /** A level or action change must re-register the request, so both are part of the identity. */
    val level: ReminderLevel = ReminderLevel.Normal,
    val actions: List<ReminderAction> = emptyList(),
    val deepLink: String? = null,
)

/** One reminder handed to the platform scheduler: it fires once at [triggerAtMillis]. */
data class ScheduledReminder(
    /** Stable identifier; rescheduling replaces requests with the same key. */
    val key: String,
    val triggerAtMillis: Long,
    val title: String,
    val body: String,
    /** What [diffReminders] compares to decide whether this reminder changed at all. */
    val fingerprint: ReminderFingerprint,
    /** `user` alarms pass the budget untouched; only `coach` prompts are filtered. */
    val origin: ReminderOrigin = ReminderOrigin.User,
    val level: ReminderLevel = ReminderLevel.Normal,
    val actions: List<ReminderAction> = listOf(ReminderAction.Open),
    /** In-app destination opened by the notification, e.g. `crko://today` or `crko://start`. */
    val deepLink: String? = null,
    /** Record the reminder belongs to, used by the "开始" action. */
    val recordId: String? = null,
    /** Coach prompt kind; only `origin = coach` entries carry one. */
    val coachKind: CoachPromptKind? = null,
)

/** Occurrence dates further than this behind today no longer feed `RELATED=END` triggers. */
private const val MaxLookbackDays = 400

private const val TodayDeepLink = "crko://today"

private val EventActions = listOf(ReminderAction.Open, ReminderAction.Snooze)
private val TodoActions = EventActions + ReminderAction.Start

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
    todos.forEach { todo ->
        collectTodoReminders(todo, today, horizonEnd, zone, nowMillis, strings, reminders)
    }
    return reminders
        .filter { it.triggerAtMillis > nowMillis }
        .sortedBy { it.triggerAtMillis }
        .take(limit)
}

/**
 * Reminders of one event, without the cross-record filter, sort and cap. `AppStateHolder` plans
 * record by record so an edit re-walks only the record that changed; [planReminders] keeps the
 * whole-library pass for callers that have no fragment cache.
 */
internal fun planEventReminders(
    event: Event,
    today: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
    strings: Strings,
): List<ScheduledReminder> {
    val reminders = mutableListOf<ScheduledReminder>()
    collectEventReminders(
        event,
        today,
        today.plusDays(ReminderHorizonDays),
        ReminderHorizonDays,
        zone,
        nowMillis,
        strings,
        reminders,
    )
    return reminders
}

/** Same per-record entry point for todos; see [planEventReminders]. */
internal fun planTodoReminders(
    todo: Todo,
    today: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
    strings: Strings,
): List<ScheduledReminder> {
    val reminders = mutableListOf<ScheduledReminder>()
    collectTodoReminders(todo, today, today.plusDays(ReminderHorizonDays), zone, nowMillis, strings, reminders)
    return reminders
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
        .filter { (_, alarm) -> !alarm.action.equals("EMAIL", ignoreCase = true) && alarm.trigger is AlarmTrigger.Relative }
    val absolute = event.alarms
        .withIndex()
        .filter { (_, alarm) -> !alarm.action.equals("EMAIL", ignoreCase = true) && alarm.trigger is AlarmTrigger.Absolute }
    if (relative.isEmpty() && absolute.isEmpty()) return
    val title = event.title.ifBlank { strings.untitledEvent }

    // An absolute trigger fires once regardless of the occurrences, so it is emitted outside the
    // occurrence loop; a relative trigger is resolved per occurrence.
    absolute.forEach { (index, alarm) ->
        val value = (alarm.trigger as AlarmTrigger.Absolute).value
        val at = value.instantOrNull(zone)?.toEpochMilliseconds() ?: return@forEach
        output += ScheduledReminder(
            key = "e:${event.id}:$index:abs",
            triggerAtMillis = at,
            title = title,
            body = alarm.description.ifBlank {
                val start = event.localStart(zone)
                alarmMoment(
                    allDay = event.start is IcsTimeValue.Date,
                    date = event.day,
                    startMinutes = start.hour * 60 + start.minute,
                    strings = strings,
                )
            },
            // The absolute trigger resolves outside the occurrence loop, so its derived body shows
            // the event's own moment; those are the language-free ingredients it is built from.
            fingerprint = ReminderFingerprint(
                triggerAtMillis = at,
                title = event.title,
                body = alarm.description.ifBlank {
                    "abs|${event.start is IcsTimeValue.Date}|${event.day}|${event.localStart(zone).let { it.hour * 60 + it.minute }}"
                },
                level = ReminderLevel.Normal,
                actions = EventActions,
                deepLink = TodayDeepLink,
            ),
            origin = ReminderOrigin.User,
            level = ReminderLevel.Normal,
            actions = EventActions,
            deepLink = TodayDeepLink,
            recordId = event.id,
        )
    }
    if (relative.isEmpty()) return

    // Start the walk no earlier than the occurrences that can still produce a trigger: an
    // end-based alarm reaches back one occurrence span plus the horizon past today. Ordinals for
    // `COUNT` rules are computed from the real anchor regardless of where the walk starts.
    val span = event.day.daysTo(event.endDay)

    fun emitOccurrence(candidate: LocalDate, times: List<OccurrenceTime>) {
        val wallDate = event.wallDateFor(candidate, zone)
        times.forEach { time ->
            val startAt = event.occurrenceStartInstant(wallDate, time, zone)
            val endAt = event.occurrenceEndInstant(wallDate, time, zone)
            val startLocal = startAt.toLocalDateTime(zone)
            relative.forEach { (index, alarm) ->
                val trigger = alarm.trigger as AlarmTrigger.Relative
                val base = if (trigger.related == AlarmRelated.End) endAt else startAt
                emitWithRepeat(
                    event,
                    index,
                    alarm,
                    trigger,
                    base,
                    candidate,
                    startLocal.time.let { it.hour * 60 + it.minute },
                    nowMillis,
                    strings,
                    output,
                )
            }
        }
    }

    if (event.recurrence == null) {
        // A one-off event has exactly one occurrence; no walk over the horizon.
        if (event.day <= horizonEnd) emitOccurrence(event.day, event.occurrenceTimesOn(event.wallDay))
        return
    }

    val lookback = minOf(span + horizonDays, MaxLookbackDays)
    var candidate = maxOf(event.day, today.plusDays(-1 - lookback))
    while (candidate <= horizonEnd) {
        // Skipped dates and expanded start times are both resolved here.
        val times = event.occurrenceTimesOn(event.wallDateFor(candidate, zone))
        if (times.isNotEmpty()) emitOccurrence(candidate, times)
        candidate = candidate.plusDays(1)
    }
}

/** Emits one reminder for the alarm trigger and, when `REPEAT` is set, for every continuation. */
private fun emitWithRepeat(
    event: Event,
    index: Int,
    alarm: Alarm,
    trigger: AlarmTrigger.Relative,
    base: Instant,
    occurrence: LocalDate,
    startMinutes: Int,
    nowMillis: Long,
    strings: Strings,
    output: MutableList<ScheduledReminder>,
) {
    val first = base.toEpochMilliseconds() + trigger.duration.inWholeMilliseconds
    val extra = alarm.repeat?.takeIf { it > 0 } ?: 0
    // REPEAT without a positive DURATION has no spacing; the extra firings would collapse onto the
    // first one, so they are ignored.
    val step = alarm.duration?.inWholeMilliseconds?.takeIf { it > 0 } ?: 0L
    val firings = if (extra > 0 && step > 0L) extra else 0
    for (round in 0..firings) {
        val at = first + round * step
        if (at <= nowMillis) continue
        output += ScheduledReminder(
            key = "e:${event.id}:$index:$at",
            triggerAtMillis = at,
            title = event.title.ifBlank { strings.untitledEvent },
            body = alarm.description.ifBlank {
                "${strings.alarmLabel((-trigger.duration).inWholeMinutes.toInt(), trigger.related == AlarmRelated.End)} · ${
                    alarmMoment(event.start is IcsTimeValue.Date, occurrence, startMinutes, strings)
                }"
            },
            // The derived body renders the trigger offset and the occurrence moment; both are
            // listed here in canonical form so only real trigger changes move the fingerprint.
            fingerprint = ReminderFingerprint(
                triggerAtMillis = at,
                title = event.title,
                body = alarm.description.ifBlank {
                    "rel|${trigger.duration}|${trigger.related}|${event.start is IcsTimeValue.Date}|$occurrence|$startMinutes"
                },
                level = ReminderLevel.Normal,
                actions = EventActions,
                deepLink = TodayDeepLink,
            ),
            origin = ReminderOrigin.User,
            level = ReminderLevel.Normal,
            actions = EventActions,
            deepLink = TodayDeepLink,
            recordId = event.id,
        )
    }
}

private fun collectTodoReminders(
    todo: Todo,
    today: LocalDate,
    horizonEnd: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
    strings: Strings,
    output: MutableList<ScheduledReminder>,
) {
    if (todo.done) return
    todo.alarms.forEachIndexed { index, alarm ->
        val trigger = alarm.trigger
        val anchor = when (trigger) {
            is AlarmTrigger.Absolute -> trigger.value.instantOrNull(zone)
            is AlarmTrigger.Relative -> {
                val value = if (trigger.related == AlarmRelated.End) todo.due else todo.start ?: todo.due
                value?.instantOrNull(zone)
            }
        } ?: return@forEachIndexed
        // An absolute trigger fires once regardless of the occurrences.
        if (trigger is AlarmTrigger.Absolute) {
            emitTodoReminder(todo, index, alarm, anchor.toEpochMilliseconds(), null, nowMillis, strings, output)
            return@forEachIndexed
        }
        val relative = trigger as AlarmTrigger.Relative
        val rule = todo.repeat
        if (rule == null) {
            emitTodoReminder(
                todo,
                index,
                alarm,
                anchor.toEpochMilliseconds() + relative.duration.inWholeMilliseconds,
                null,
                nowMillis,
                strings,
                output,
            )
            return@forEachIndexed
        }
        // A series plans one trigger per occurrence inside the window. The occurrence dates a rule
        // expands are dates of the todo's *own* wall clock (`seriesAnchor` is a wall date), so each
        // one resolves as the due/start value's own time of day on that date. Reading the anchor
        // through the device zone first and re-folding it moved the whole series by a day whenever
        // the todo's zone and the device's fell on opposite sides of midnight, and by the offset
        // difference whenever one of them changed daylight saving.
        val value = if (relative.related == AlarmRelated.End) todo.due ?: todo.start else todo.start ?: todo.due
        val lookback = minOf(ReminderHorizonDays, MaxLookbackDays)
        val windowStart = today.plusDays(-1 - lookback)
        todo.occurrenceStartsBetween(windowStart, horizonEnd).forEach { occStart ->
            val day = if (relative.related == AlarmRelated.End) todo.occurrenceDate(occStart) else occStart
            val base = value?.withWallDate(day)?.instantOrNull(zone) ?: return@forEach
            emitTodoReminder(
                todo,
                index,
                alarm,
                base.toEpochMilliseconds() + relative.duration.inWholeMilliseconds,
                occStart,
                nowMillis,
                strings,
                output,
            )
        }
    }
}

/** One reminder for a todo occurrence, dropped when it would fire in the past. */
private fun emitTodoReminder(
    todo: Todo,
    index: Int,
    alarm: Alarm,
    triggerMillis: Long,
    occurrence: LocalDate?,
    nowMillis: Long,
    strings: Strings,
    output: MutableList<ScheduledReminder>,
) {
    val relative = alarm.trigger as? AlarmTrigger.Relative
    // `REPEAT` counts the firings after the first one, spaced by `DURATION`. Reading only the first
    // trigger dropped every continuation, so an imported todo alarm that repeats fired once.
    val extra = alarm.repeat?.takeIf { it > 0 } ?: 0
    // REPEAT without a positive DURATION has no spacing; the extra firings would collapse onto the
    // first one, so they are ignored.
    val step = alarm.duration?.inWholeMilliseconds?.takeIf { it > 0 } ?: 0L
    val firings = if (extra > 0 && step > 0L) extra else 0
    for (round in 0..firings) {
        val at = triggerMillis + round * step
        if (at <= nowMillis) continue
        output += ScheduledReminder(
            key = "t:${todo.id}:$index:${occurrence ?: "once"}:$at",
            triggerAtMillis = at,
            title = todo.title,
            body = alarm.description.ifBlank {
                relative?.let {
                    strings.alarmLabel((-it.duration).inWholeMinutes.toInt(), it.related == AlarmRelated.End)
                }.orEmpty()
            },
            // A todo key already carries the occurrence, so the offset and its relation are what
            // the derived body would render; the absolute branch has no `relative` at all.
            fingerprint = ReminderFingerprint(
                triggerAtMillis = at,
                title = todo.title,
                body = alarm.description.ifBlank { "rel|${relative?.duration}|${relative?.related}" },
                level = ReminderLevel.Normal,
                actions = TodoActions,
                deepLink = TodayDeepLink,
            ),
            origin = ReminderOrigin.User,
            level = ReminderLevel.Normal,
            actions = TodoActions,
            deepLink = TodayDeepLink,
            recordId = todo.id,
        )
    }
}

private fun alarmMoment(allDay: Boolean, date: LocalDate, startMinutes: Int, strings: Strings): String = when {
    allDay -> strings.dayMonth(date)
    else -> strings.time(startMinutes.toLocalTime(0))
}

private fun Int.toLocalTime(seconds: Int): LocalTime = LocalTime(this / 60, this % 60, seconds)

/**
 * Parses an absolute `TRIGGER;VALUE=DATE-TIME` value: the ICS basic form (`20261028T090000Z`,
 * floating without the `Z`) as well as the extended ISO-8601 form are accepted; a numeric UTC
 * offset (`20261028T090000+0800`) resolves in UTC, floating values against [zone].
 */
internal fun parseTriggerInstant(value: String, zone: TimeZone): Instant? {
    val text = value.trim()
    if (text.isEmpty()) return null
    runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
    val utc = text.endsWith("Z") || text.endsWith("z")
    val withoutZone = if (utc) text.dropLast(1) else text
    // A legal ICS DATE-TIME may carry a numeric offset; the wall clock then resolves in UTC minus
    // the offset instead of the device zone. Extended-with-offset forms parse via Instant above.
    val (compact, offsetMinutes) = trailingUtcOffset(withoutZone) ?: (withoutZone to null)
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
    val atUtc = LocalDateTime(date, time).toInstant(TimeZone.UTC)
    return when {
        utc -> atUtc
        offsetMinutes != null -> atUtc - offsetMinutes.minutes
        else -> LocalDateTime(date, time).toInstant(zone)
    }
}

/** Trailing `+hh:mm`/`+hhmm` (or `-…`) offset of an ICS DATE-TIME as the remaining text to minutes. */
private fun trailingUtcOffset(text: String): Pair<String, Int>? {
    val match = offsetSuffix.find(text) ?: return null
    val total = match.groupValues[2].toInt() * 60 + match.groupValues[3].toInt()
    val minutes = if (match.groupValues[1] == "-") -total else total
    return text.dropLast(match.value.length) to minutes
}

private val offsetSuffix = Regex("""([+-])(\d{2}):?(\d{2})$""")
