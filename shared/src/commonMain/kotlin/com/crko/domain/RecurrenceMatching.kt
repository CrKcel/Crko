package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant

internal fun Event.isOccurrenceStart(candidate: LocalDate, rule: RepeatRule?): Boolean {
    val count = rule?.count
    val index = if (count != null) countOccurrenceIndex else null
    return isRuleOccurrence(
        anchor = wallDay,
        rule = rule,
        recurrence = recurrence,
        candidate = candidate,
        startTime = wallStartTime(),
        count = count,
        // The occurrence's own value type decides where its start instant lies; the device zone
        // may name a different one for a TZID series.
        anchorValue = start,
        // The index belongs to this exact rule instance; a modified copy keeps the ordinal walk.
        countIndex = index?.takeIf { it.first === rule }?.second,
    )
}

/** Time of day a typed recurrence value names, or [fallback] for a date-only value. */
internal fun IcsTimeValue.occurrenceTime(fallback: OccurrenceTime?): OccurrenceTime? {
    if (this is IcsTimeValue.Date) return null
    val time = wallTime ?: return fallback
    return OccurrenceTime(time.hour * 60 + time.minute, time.second)
}

/**
 * Whether an `EXDATE` value excludes the occurrence at [candidate] and [time]. A date-only value
 * excludes every instance of that day; a timed one excludes only the matching wall-clock instance,
 * so a series with several starts per day keeps the others.
 */
internal fun isExcluded(
    exdates: List<IcsTimeValue>,
    candidate: LocalDate,
    time: OccurrenceTime?,
): Boolean = exdates.any { exdate ->
    exdate.wallDate == candidate && when {
        exdate is IcsTimeValue.Date -> true
        time == null -> true
        else -> exdate.wallTime?.let { it.hour * 60 + it.minute == time.minutes && it.second == time.seconds } == true
    }
}

private fun repeatsOnDate(recurrence: Recurrence?, candidate: LocalDate): List<RecurrenceDate> =
    recurrence?.rdates?.filter { it.value.wallDate == candidate }.orEmpty()

/**
 * Whether [candidate] holds an occurrence of the recurrence set. The rule is expanded on the anchor
 * date; `RDATE` adds instances (even without a rule and even before the anchor) and `EXDATE`
 * removes individual starts. [startTime] is the anchor's time of day, used for `UNTIL` and for
 * matching timed exclusions. [anchorValue] is the component's own `DTSTART`: a UTC `UNTIL` bounds
 * the instants the series produces in that value's zone, not the device's. [countIndex] holds the
 * rule-generated days of a `COUNT` series, replacing the ordinal walk when it matches.
 */
internal fun isRuleOccurrence(
    anchor: LocalDate,
    rule: RepeatRule?,
    recurrence: Recurrence?,
    candidate: LocalDate,
    startTime: OccurrenceTime? = null,
    count: Int? = null,
    anchorValue: IcsTimeValue? = null,
    countIndex: LongArray? = null,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    val exdates = recurrence?.exdates.orEmpty()
    val rdateTimes = repeatsOnDate(recurrence, candidate)
    val rdateMatch = rdateTimes.any { !isExcluded(exdates, candidate, it.value.occurrenceTime(startTime)) }
    if (candidate < anchor) return rdateMatch
    // RFC 5545 treats DTSTART as part of the recurrence set even when it predates or misses BY* filters.
    if (candidate == anchor) {
        val anchorKept = !isExcluded(exdates, candidate, startTime)
        val ruleKept = rule != null && expandedStartTimes(rule, startTime ?: OccurrenceTime(0, 0))
            .any { !isExcluded(exdates, candidate, it) }
        return anchorKept || ruleKept || rdateMatch
    }
    if (rule == null) return rdateMatch
    val until = rule.until
    if (until != null && !occursBeforeUntil(until, candidate, startTime, anchorValue, zone)) return rdateMatch
    val generated = if (countIndex != null) {
        // The index was built from the same rule's matches, so membership decides without matching again.
        countIndex.binaryFind(candidate.toEpochDays()) >= 0
    } else if (!matchesRule(rule, anchor, candidate)) {
        false
    } else if (count == null) {
        true
    } else {
        val ordinal = occurrenceOrdinal(rule, anchor, candidate, count) ?: return rdateMatch
        ordinal <= count
    }
    if (!generated) return rdateMatch
    return expandedStartTimes(rule, startTime ?: OccurrenceTime(0, 0))
        .any { !isExcluded(exdates, candidate, it) } || rdateMatch
}

/** Position of [day] in the sorted [days], or -1; the stdlib array search is JVM-only. */
private fun LongArray.binaryFind(day: Long): Int {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val mid = (low + high) ushr 1
        when {
            this[mid] < day -> low = mid + 1
            this[mid] > day -> high = mid - 1
            else -> return mid
        }
    }
    return -1
}

/** Whether the occurrence starting on [candidate] is not after `UNTIL` (inclusive). */
private fun occursBeforeUntil(
    until: IcsTimeValue,
    candidate: LocalDate,
    startTime: OccurrenceTime?,
    anchorValue: IcsTimeValue?,
    zone: TimeZone,
): Boolean {
    if (until is IcsTimeValue.Date) return candidate <= until.date
    val minutes = (startTime?.minutes ?: 0).coerceIn(0, 24 * 60 - 1)
    val seconds = (startTime?.seconds ?: 0).coerceIn(0, 59)
    val start = LocalDateTime(candidate, LocalTime(minutes / 60, minutes % 60, seconds))
    return when (until) {
        is IcsTimeValue.Date -> candidate <= until.date
        is IcsTimeValue.Floating -> start <= until.dateTime
        is IcsTimeValue.Zoned -> start <= until.dateTime
        // A UTC `UNTIL` bounds instants; the occurrence's own instant is the one its `DTSTART`
        // type names, not the same wall clock folded into the device zone.
        is IcsTimeValue.Utc -> (anchorValue?.withWallDateTime(start)?.instantOrNull(zone)
            ?: start.toInstant(zone)) <= until.instant
    }
}

internal fun matchesRule(rule: RepeatRule, anchor: LocalDate, date: LocalDate): Boolean {
    if (date < anchor) return false
    val interval = rule.interval.coerceAtLeast(1)
    if (rule.byMonth.isNotEmpty() && date.month.number !in rule.byMonth) return false
    return when (rule.frequency) {
        RepeatFrequency.Daily -> matchesDaily(rule, anchor, date, interval)
        RepeatFrequency.Weekly -> matchesWeekly(rule, anchor, date, interval)
        RepeatFrequency.Monthly -> {
            val monthDiff = anchor.monthsTo(date)
            monthDiff >= 0 && monthDiff % interval == 0 &&
                date in monthCandidates(rule, anchor, date.year, date.month.number, withSetPos = true)
        }
        RepeatFrequency.Yearly -> {
            // A BYWEEKNO week can start in the previous year or end in the next one, so the date
            // may belong to the recurrence year before or after its own calendar year.
            val years = if (rule.byWeekNo.isEmpty()) {
                listOf(date.year)
            } else {
                listOf(date.year - 1, date.year, date.year + 1)
            }
            years.any { year ->
                val yearDiff = year - anchor.year
                yearDiff >= 0 && yearDiff % interval == 0 &&
                    date in yearCandidates(rule, anchor, year, withSetPos = true)
            }
        }
    }
}

private fun matchesDaily(
    rule: RepeatRule,
    anchor: LocalDate,
    date: LocalDate,
    interval: Int,
): Boolean {
    if (anchor.daysTo(date) % interval != 0) return false
    val weekdays = plainWeekdays(rule)
    if (weekdays.isNotEmpty() && date.mondayIndex !in weekdays) return false
    if (rule.byMonthDay.isNotEmpty() && !matchesMonthDay(rule.byMonthDay, date)) return false
    if (rule.byYearDay.isNotEmpty() && !matchesYearDay(rule.byYearDay, date)) return false
    // DAILY has one instance per day in this model, so only positions 1 / -1 can select it.
    if (rule.bySetPos.isNotEmpty() && applySetPos(listOf(date), rule.bySetPos).isEmpty()) return false
    return true
}

private fun matchesWeekly(
    rule: RepeatRule,
    anchor: LocalDate,
    date: LocalDate,
    interval: Int,
): Boolean {
    val weekStart = rule.weekStart ?: 0
    val weekDiff = ((date.weekStart(weekStart).toEpochDays() - anchor.weekStart(weekStart).toEpochDays()) / 7).toInt()
    if (weekDiff < 0 || weekDiff % interval != 0) return false
    return date in weeklyCandidates(rule, anchor, date.weekStart(weekStart))
}

/** Weekdays of `BYDAY` entries that carry no ordinal. */
internal fun plainWeekdays(rule: RepeatRule): Set<Int> =
    rule.byDay.filter { it.ordinal == 0 }.map { it.weekday }.filter { it in 0..6 }.toSet()

/**
 * Dates generated by a weekly rule inside one week. `BYSETPOS` is applied after the other filters,
 * so a rule such as `BYDAY=MO..FR;BYSETPOS=-1` selects only the last weekday of each week.
 */
internal fun weeklyCandidates(rule: RepeatRule, anchor: LocalDate, weekStartDate: LocalDate): List<LocalDate> {
    val weekdays = plainWeekdays(rule).ifEmpty { setOf(anchor.mondayIndex) }
    val dates = (0 until 7).map { weekStartDate.plusDays(it) }
        .filter { it.mondayIndex in weekdays }
        .filter { rule.byMonth.isEmpty() || it.month.number in rule.byMonth }
        .filter { rule.byMonthDay.isEmpty() || matchesMonthDay(rule.byMonthDay, it) }
        .filter { rule.byYearDay.isEmpty() || matchesYearDay(rule.byYearDay, it) }
    return if (rule.bySetPos.isEmpty()) dates else applySetPos(dates, rule.bySetPos)
}

/**
 * Dates selected by BY* rules inside one month. `BYMONTHDAY` and `BYDAY` limit each other when both
 * are present, as required by RFC 5545. [withSetPos] is false while enumerating a year so BYSETPOS
 * is applied once to the whole year instead of to each month.
 */
internal fun monthCandidates(
    rule: RepeatRule,
    anchor: LocalDate,
    year: Int,
    month: Int,
    withSetPos: Boolean = true,
): List<LocalDate> {
    val days = daysInMonth(year, month)
    val ordinalDates = rule.byDay
        .filter { it.ordinal != 0 }
        .flatMap { weekdayDatesInMonth(year, month, it.weekday, it.ordinal) }
        .toSet()
    val plainDays = plainWeekdays(rule)
    val hasByDay = ordinalDates.isNotEmpty() || plainDays.isNotEmpty()

    val dates = when {
        rule.byMonthDay.isNotEmpty() || hasByDay -> {
            (1..days)
                .filter { day ->
                    val date = LocalDate(year, month, day)
                    (rule.byMonthDay.isEmpty() || matchesMonthDayValue(rule.byMonthDay, day, days)) &&
                        (!hasByDay || date in ordinalDates || date.mondayIndex in plainDays)
                }
                .map { LocalDate(year, month, it) }
        }
        // The day must be a real date in this month (a Feb 29 anchor has no candidate in
        // common years) — guard before constructing, LocalDate rejects the clamped value.
        else -> if (anchor.day <= days) listOf(LocalDate(year, month, anchor.day)) else emptyList()
    }
    val sorted = dates.distinct().sorted()
    return if (withSetPos && rule.bySetPos.isNotEmpty()) applySetPos(sorted, rule.bySetPos) else sorted
}

internal fun yearCandidates(rule: RepeatRule, anchor: LocalDate, year: Int, withSetPos: Boolean): List<LocalDate> {
    val dates = mutableListOf<LocalDate>()
    val limitedByMonth: (LocalDate) -> Boolean =
        { date -> rule.byMonth.isEmpty() || date.month.number in rule.byMonth }
    // BYWEEKNO/BYYEARDAY can be limited further by BYDAY; without an ordinal BYDAY entry only the
    // weekday matters, which is the meaningful interpretation for these combinations.
    val limitedByWeekday: (LocalDate) -> Boolean = { date ->
        if (rule.byDay.isEmpty()) {
            true
        } else {
            rule.byDay.any { it.weekday == date.mondayIndex }
        }
    }
    when {
        rule.byYearDay.isNotEmpty() -> {
            val total = daysInYear(year)
            for (day in rule.byYearDay) {
                val resolved = if (day > 0) day else total + day + 1
                if (resolved in 1..total) dates += LocalDate(year, 1, 1).plusDays(resolved - 1)
            }
            dates.retainAll { limitedByMonth(it) && limitedByWeekday(it) }
        }
        rule.byWeekNo.isNotEmpty() -> {
            rule.byWeekNo.forEach { weekNo ->
                dates += datesInWeekNo(year, weekNo, rule.weekStart ?: 0)
                    .filter { limitedByMonth(it) && limitedByWeekday(it) }
            }
        }
        rule.byDay.isNotEmpty() && rule.byMonth.isEmpty() -> {
            rule.byDay.filter { it.ordinal != 0 }.forEach { entry ->
                dates += weekdayDatesInYear(year, entry.weekday, entry.ordinal)
            }
            val plain = plainWeekdays(rule)
            if (plain.isNotEmpty()) {
                val total = daysInYear(year)
                val first = LocalDate(year, 1, 1)
                for (offset in 0 until total) {
                    val date = first.plusDays(offset)
                    if (date.mondayIndex in plain) dates += date
                }
            }
        }
        else -> {
            val months = when {
                rule.byMonth.isNotEmpty() -> rule.byMonth
                rule.byMonthDay.isNotEmpty() || rule.byDay.isNotEmpty() -> (1..12).toList()
                else -> listOf(anchor.month.number)
            }
            months.distinct().sorted().forEach { month ->
                dates += monthCandidates(rule, anchor, year, month, withSetPos = false)
            }
        }
    }
    val sorted = dates.distinct().sorted()
    return if (withSetPos && rule.bySetPos.isNotEmpty()) applySetPos(sorted, rule.bySetPos) else sorted
}

internal fun LocalDate.weekStart(weekStart: Int): LocalDate =
    plusDays(-((mondayIndex - weekStart + 7) % 7))

private fun applySetPos(dates: List<LocalDate>, positions: List<Int>): List<LocalDate> {
    if (dates.isEmpty()) return dates
    val selected = mutableListOf<LocalDate>()
    positions.forEach { position ->
        val index = when {
            position > 0 -> position - 1
            position < 0 -> dates.size + position
            else -> -1
        }
        if (index in dates.indices) selected += dates[index]
    }
    return selected.distinct().sorted()
}

private fun matchesMonthDay(values: List<Int>, date: LocalDate): Boolean =
    matchesMonthDayValue(values, date.day, daysInMonth(date.year, date.month.number))

private fun matchesMonthDayValue(values: List<Int>, day: Int, days: Int): Boolean =
    values.any { value ->
        when {
            value > 0 -> value == day
            value < 0 -> days + value + 1 == day
            else -> false
        }
    }

private fun matchesYearDay(values: List<Int>, date: LocalDate): Boolean {
    val total = daysInYear(date.year)
    val dayOfYear = date.dayOfYear()
    return values.any { value ->
        when {
            value > 0 -> value == dayOfYear
            value < 0 -> total + value + 1 == dayOfYear
            else -> false
        }
    }
}

private fun weekdayDatesInMonth(year: Int, month: Int, weekday: Int, ordinal: Int): List<LocalDate> {
    val days = daysInMonth(year, month)
    val all = (1..days).map { LocalDate(year, month, it) }
        .filter { it.mondayIndex == weekday }
    return when {
        ordinal > 0 -> listOfNotNull(all.getOrNull(ordinal - 1))
        ordinal < 0 -> listOfNotNull(all.getOrNull(all.size + ordinal))
        else -> all
    }
}

private fun weekdayDatesInYear(year: Int, weekday: Int, ordinal: Int): List<LocalDate> {
    val total = daysInYear(year)
    val all = (1..total).map { LocalDate(year, 1, 1).plusDays(it - 1) }
        .filter { it.mondayIndex == weekday }
    return when {
        ordinal > 0 -> listOfNotNull(all.getOrNull(ordinal - 1))
        ordinal < 0 -> listOfNotNull(all.getOrNull(all.size + ordinal))
        else -> all
    }
}

/** Dates of RFC week number [weekNo] in [year]; negative values count from the last week. */
private fun datesInWeekNo(year: Int, weekNo: Int, weekStart: Int): List<LocalDate> {
    val total = totalWeeks(year, weekStart)
    val resolved = if (weekNo < 0) total + weekNo + 1 else weekNo
    if (resolved !in 1..total) return emptyList()
    val start = weekOneStart(year, weekStart).plusDays((resolved - 1) * 7)
    return (0 until 7).map { start.plusDays(it) }
}

/**
 * First day of week one: the week (starting at [weekStart]) containing January 4 always has at
 * least four days in the year, for every WKST value.
 */
private fun weekOneStart(year: Int, weekStart: Int): LocalDate {
    val jan4 = LocalDate(year, 1, 4)
    val shift = (jan4.mondayIndex - weekStart + 7) % 7
    return jan4.plusDays(-shift)
}

private fun totalWeeks(year: Int, weekStart: Int): Int {
    val first = weekOneStart(year, weekStart)
    val next = weekOneStart(year + 1, weekStart)
    return ((next.toEpochDays() - first.toEpochDays()) / 7).toInt()
}
