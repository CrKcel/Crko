package com.crko.state.event

import com.crko.domain.Event
import com.crko.domain.EventStatus
import com.crko.domain.IcsTimeValue
import com.crko.domain.RepeatRule
import com.crko.domain.instantOrNull
import com.crko.domain.plusDays
import com.crko.domain.wallDate
import com.crko.domain.wallDateTime
import com.crko.state.WriteResult
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Validation and normalisation rules for an event about to be stored. Kept free of state and
 * persistence so the editor and the service share one definition of a valid record and the rules
 * can be tested without a facade.
 */
internal object EventNormalizer {

    /** Validates and normalises an edit; null when the event cannot be stored as given. */
    fun normalize(event: Event): Event? {
        if (event.title.isBlank()) return null
        val recurrence = event.recurrence?.let { set ->
            set.copy(
                rule = set.rule?.copy(
                    interval = set.rule.interval.coerceAtLeast(1),
                    count = set.rule.count?.takeIf { it > 0 },
                ),
                rdates = set.rdates.distinct(),
                exdates = set.exdates.distinct(),
            )
        }?.takeIf { it.rule != null || it.rdates.isNotEmpty() || it.rawRule != null }
        val start = normalizeTime(event.start) ?: return null
        val end = event.end?.let { normalizeTime(it) ?: return null }
        if (end != null && !end.isAfter(start)) return null
        val normalizedEnd = if (start is IcsTimeValue.Date) {
            (end as? IcsTimeValue.Date)?.takeIf { it.date > start.date }
                ?: IcsTimeValue.Date(start.date.plusDays(1))
        } else {
            // A date-only end on a timed event cannot be represented; reject rather than silently
            // change the event's length.
            if (end is IcsTimeValue.Date) return null
            end
        }
        return event.copy(
            title = event.title.trim(),
            start = start,
            end = normalizedEnd,
            recurrence = recurrence,
            // A one-off event must not inherit stale recurrence values.
            status = if (event.status == EventStatus.Cancelled) EventStatus.Confirmed else event.status,
        )
    }

    /** Result to report when [event] cannot be stored; the UI maps it onto localized text. */
    fun validationError(event: Event): WriteResult = when {
        event.title.isBlank() -> WriteResult.EmptyTitle
        else -> WriteResult.InvalidTimeRange
    }

    /** Compares rules semantically: an editor round trip may reduce `UNTIL` to its calendar date. */
    fun sameRepeatRule(a: RepeatRule?, b: RepeatRule?): Boolean {
        if (a == null || b == null) return a == b
        return a.copy(until = null, raw = null) == b.copy(until = null, raw = null) &&
            a.until?.wallDate == b.until?.wallDate
    }

    /**
     * The time values already carry RFC semantics, so only the fields that can be out of range
     * (seconds) need coercing here.
     */
    private fun normalizeTime(value: IcsTimeValue): IcsTimeValue? {
        fun clean(dateTime: LocalDateTime): LocalDateTime =
            LocalDateTime(dateTime.date, LocalTime(dateTime.hour, dateTime.minute, dateTime.second.coerceIn(0, 59)))
        return when (value) {
            is IcsTimeValue.Date -> value
            is IcsTimeValue.Floating -> IcsTimeValue.Floating(clean(value.dateTime))
            is IcsTimeValue.Zoned -> value.copy(dateTime = clean(value.dateTime))
            is IcsTimeValue.Utc -> IcsTimeValue.Utc(clean(value.instant.toLocalDateTime(TimeZone.UTC)).toInstant(TimeZone.UTC))
        }
    }

    private fun IcsTimeValue.isAfter(other: IcsTimeValue): Boolean {
        val start = other.instantOrNull(TimeZone.UTC)
        val end = instantOrNull(TimeZone.UTC)
        return if (start != null && end != null) end > start else wallDateTime > other.wallDateTime
    }
}
