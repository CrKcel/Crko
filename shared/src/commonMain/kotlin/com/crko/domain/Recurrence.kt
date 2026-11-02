package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val OccurrenceGuard = 100_000

/** Largest `COUNT` whose rule-generated occurrence days are worth caching on the record. */
internal const val CountIndexLimit = 5_000

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
        // The index belongs to this exact rule instance; a modified copy keeps the ordinal walk.
        countIndex = index?.takeIf { it.first === rule }?.second,
    )
}

/** Time of day a typed recurrence value names, or [fallback] for a date-only value. */
private fun IcsTimeValue.occurrenceTime(fallback: OccurrenceTime?): OccurrenceTime? {
    if (this is IcsTimeValue.Date) return null
    val time = wallTime ?: return fallback
    return OccurrenceTime(time.hour * 60 + time.minute, time.second)
}

/**
 * Whether an `EXDATE` value excludes the occurrence at [candidate] and [time]. A date-only value
 * excludes every instance of that day; a timed one excludes only the matching wall-clock instance,
 * so a series with several starts per day keeps the others.
 */
private fun isExcluded(
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
 * matching timed exclusions. [countIndex] holds the rule-generated days of a `COUNT` series,
 * replacing the ordinal walk when it matches.
 */
internal fun isRuleOccurrence(
    anchor: LocalDate,
    rule: RepeatRule?,
    recurrence: Recurrence?,
    candidate: LocalDate,
    startTime: OccurrenceTime? = null,
    count: Int? = null,
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
    if (until != null && !occursBeforeUntil(until, candidate, startTime, zone)) return rdateMatch
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

/**
 * Epoch days of the rule-generated occurrences a `COUNT` series is made of, sorted. DTSTART is the
 * first occurrence and is not listed, so a day's ordinal is its position in the array plus two.
 *
 * Null when there is no usable `COUNT`, the series is too large to index, or the enumeration could
 * not prove it ran to the end — the caller then keeps answering by the ordinal walk.
 */
internal fun buildCountIndex(rule: RepeatRule, anchor: LocalDate): LongArray? {
    val count = rule.count ?: return null
    if (count <= 0 || count > CountIndexLimit) return null
    // The period enumeration mirrors the ordinal walk of MONTHLY and YEARLY rules; the other
    // frequencies answer through their own ordinal paths and keep the walk.
    if (rule.frequency != RepeatFrequency.Monthly && rule.frequency != RepeatFrequency.Yearly) return null
    val days = mutableListOf<Long>()
    val complete = runCatching {
        // The sentinel keeps the enumeration running until COUNT is exhausted; the period walk
        // treats an unreachable period as an incomplete answer. DTSTART is ordinal one and lives
        // outside the array, so a candidate belongs in the series while its ordinal is within COUNT.
        countPeriodMatches(
            rule,
            anchor,
            LocalDate(999999, 12, 31),
            count,
            datesInPeriod = { index ->
                // The same per-period enumeration the ordinal walk uses, so both answer alike.
                if (rule.frequency == RepeatFrequency.Monthly) {
                    val month = anchor.startOfMonth().plusMonths(index * rule.interval.coerceAtLeast(1))
                    if (rule.byMonth.isNotEmpty() && month.month.number !in rule.byMonth) {
                        emptyList()
                    } else {
                        monthCandidates(rule, anchor, month.year, month.month.number)
                            .filter { it > anchor && matchesRule(rule, anchor, it) }
                    }
                } else {
                    yearCandidates(rule, anchor, anchor.year + index * rule.interval.coerceAtLeast(1), withSetPos = true)
                        .filter { it > anchor && matchesRule(rule, anchor, it) }
                }
            },
            collect = { date, ordinal ->
                if (ordinal <= count) days += date.toEpochDays()
            },
        ) != null
    }.getOrDefault(false)
    if (!complete) return null
    return days.toLongArray().also { it.sort() }
}

/** Whether the occurrence starting on [candidate] is not after `UNTIL` (inclusive). */
private fun occursBeforeUntil(
    until: IcsTimeValue,
    candidate: LocalDate,
    startTime: OccurrenceTime?,
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
        is IcsTimeValue.Utc -> start.toInstant(zone) <= until.instant
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
private fun plainWeekdays(rule: RepeatRule): Set<Int> =
    rule.byDay.filter { it.ordinal == 0 }.map { it.weekday }.filter { it in 0..6 }.toSet()

/**
 * Dates generated by a weekly rule inside one week. `BYSETPOS` is applied after the other filters,
 * so a rule such as `BYDAY=MO..FR;BYSETPOS=-1` selects only the last weekday of each week.
 */
private fun weeklyCandidates(rule: RepeatRule, anchor: LocalDate, weekStartDate: LocalDate): List<LocalDate> {
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
        else -> listOfNotNull(LocalDate(year, month, anchor.day).takeIf { anchor.day <= days })
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

private fun LocalDate.weekStart(weekStart: Int): LocalDate =
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

/** 1-based occurrence index of [date] in the rule, or null when it is not generated by the rule. */
internal fun occurrenceOrdinal(
    rule: RepeatRule,
    anchor: LocalDate,
    date: LocalDate,
    limit: Int?,
): Int? {
    if (date < anchor) return null
    if (date == anchor) return 1
    val interval = rule.interval.coerceAtLeast(1)
    return when (rule.frequency) {
        RepeatFrequency.Daily -> {
            val simple = plainWeekdays(rule).isEmpty() && rule.byMonthDay.isEmpty() &&
                rule.byYearDay.isEmpty() && rule.byMonth.isEmpty()
            if (simple) {
                val diff = anchor.daysTo(date)
                if (diff % interval != 0) null else (diff / interval) + 1
            } else {
                countDailyMatches(rule, anchor, date, interval, limit)
            }
        }
        RepeatFrequency.Weekly -> weeklyOrdinal(rule, anchor, date, interval, limit)
        RepeatFrequency.Monthly -> {
            val simple = plainWeekdays(rule).isEmpty() && rule.byDay.isEmpty() &&
                rule.byMonthDay.isEmpty() && rule.byMonth.isEmpty() &&
                rule.byYearDay.isEmpty() && rule.byWeekNo.isEmpty() &&
                rule.bySetPos.isEmpty()
            if (simple && anchor.day <= 28) {
                // Every month has this day, so the ordinal is arithmetic instead of a walk from
                // the anchor through every intervening month.
                val monthDiff = anchor.monthsTo(date)
                if (monthDiff < 0 || monthDiff % interval != 0) {
                    null
                } else {
                    val expected = date.startOfMonth().plusDays(anchor.day - 1)
                    if (date == expected) monthDiff / interval + 1 else null
                }
            } else {
                countPeriodMatches(rule, anchor, date, limit, datesInPeriod = { monthIndex ->
                    val month = anchor.startOfMonth().plusMonths(monthIndex * interval)
                    if (rule.byMonth.isNotEmpty() && month.month.number !in rule.byMonth) {
                        emptyList()
                    } else {
                        monthCandidates(rule, anchor, month.year, month.month.number)
                            .filter { it > anchor && matchesRule(rule, anchor, it) }
                    }
                })
            }
        }
        RepeatFrequency.Yearly -> {
            val simple = plainWeekdays(rule).isEmpty() && rule.byDay.isEmpty() &&
                rule.byMonthDay.isEmpty() && rule.byMonth.isEmpty() &&
                rule.byYearDay.isEmpty() && rule.byWeekNo.isEmpty() &&
                rule.bySetPos.isEmpty() && !(anchor.month.number == 2 && anchor.day == 29)
            if (simple) {
                val yearDiff = date.year - anchor.year
                if (yearDiff < 0 || yearDiff % interval != 0 || date.month != anchor.month || date.day != anchor.day) {
                    null
                } else {
                    yearDiff / interval + 1
                }
            } else {
                countPeriodMatches(rule, anchor, date, limit, datesInPeriod = { yearIndex ->
                    yearCandidates(rule, anchor, anchor.year + yearIndex * interval, withSetPos = true)
                        .filter { it > anchor && matchesRule(rule, anchor, it) }
                })
            }
        }
    }
}

private fun countDailyMatches(
    rule: RepeatRule,
    anchor: LocalDate,
    date: LocalDate,
    interval: Int,
    limit: Int?,
): Int? {
    // DTSTART itself is the first occurrence even when it does not satisfy BY*.
    var cursor = anchor.plusDays(interval)
    var count = 1
    while (cursor <= date) {
        if (matchesRule(rule, anchor, cursor)) {
            count++
            if (cursor == date) return count
            if (limit != null && count > limit) return count
        }
        cursor = cursor.plusDays(interval)
        if (count > OccurrenceGuard) return null
    }
    return null
}

/**
 * A weekly rule that selects the same set of weekdays in every matching week. Only `BYDAY` without
 * ordinals leaves the per-week count constant, which is what makes the ordinal computable directly.
 */
private fun weeklyRuleIsUniform(rule: RepeatRule): Boolean =
    rule.byMonth.isEmpty() && rule.byMonthDay.isEmpty() && rule.byYearDay.isEmpty() &&
        rule.bySetPos.isEmpty() && rule.byDay.all { it.ordinal == 0 }

private fun weeklyOrdinal(
    rule: RepeatRule,
    anchor: LocalDate,
    date: LocalDate,
    interval: Int,
    limit: Int?,
): Int? {
    val weekStart = rule.weekStart ?: 0
    val anchorWeek = anchor.weekStart(weekStart)
    val targetWeek = date.weekStart(weekStart)
    val weekDiff = ((targetWeek.toEpochDays() - anchorWeek.toEpochDays()) / 7).toInt()
    if (weekDiff < 0 || weekDiff % interval != 0) return null
    if (weeklyRuleIsUniform(rule)) {
        // Counting week by week from a decades-old anchor is the hot path of the month grid, so the
        // position is computed instead: whole matching weeks in between contribute a constant count.
        val weekdays = plainWeekdays(rule).ifEmpty { setOf(anchor.mondayIndex) }
        val perWeek = weekdays.size
        val position = (0 until 7)
            .map { targetWeek.plusDays(it) }
            .filter { it.mondayIndex in weekdays }
            .filter { weekDiff != 0 || it > anchor }
            .indexOf(date)
        if (position < 0) return null
        val anchorWeekCount = (0 until 7)
            .map { anchorWeek.plusDays(it) }
            .count { it.mondayIndex in weekdays && it > anchor }
        val weeksBefore = weekDiff / interval - 1
        val before = if (weekDiff == 0) 0 else anchorWeekCount + weeksBefore * perWeek
        // DTSTART itself is occurrence one, then the earlier candidates, then the position in this week.
        return 1 + before + position + 1
    }
    // DTSTART counts as occurrence one even when it misses the BY* filters.
    var count = 1
    var weekIndex = 0
    while (weekIndex <= weekDiff) {
        val week = anchorWeek.plusDays(weekIndex * 7)
        for (candidate in weeklyCandidates(rule, anchor, week)) {
            if (candidate <= anchor) continue
            count++
            if (candidate == date) return count
            if (limit != null && count > limit) return count
        }
        weekIndex += interval
        if (weekIndex > OccurrenceGuard) return null
    }
    return null
}

private fun countPeriodMatches(
    rule: RepeatRule,
    anchor: LocalDate,
    date: LocalDate,
    limit: Int?,
    datesInPeriod: (Int) -> List<LocalDate>,
    collect: ((LocalDate, Int) -> Unit)? = null,
): Int? {
    var index = 0
    // DTSTART counts as the first occurrence.
    var count = 1
    // A week at a year boundary can appear in two yearly periods; it is still one occurrence.
    val counted = mutableSetOf<LocalDate>()
    while (index < OccurrenceGuard) {
        // An interval or anchor far outside the supported year range makes a period unreachable;
        // it yields no dates and the walk stops instead of crashing.
        val dates = runCatching { datesInPeriod(index) }.getOrDefault(emptyList())
        val nextPeriodStart = if (dates.isEmpty()) null else dates.first()
        if (nextPeriodStart != null && nextPeriodStart > date) return null
        // Stop once the period itself is past the target even if it produced no dates.
        val periodGuess = periodStartGuess(rule, anchor, index) ?: return null
        // An empty period past the target proves the date is not in the rule. A non-empty period
        // may legitimately start in the previous year when BYWEEKNO selects week one.
        if (dates.isEmpty() && periodGuess > date) return null
        for (candidate in dates) {
            if (!counted.add(candidate)) continue
            count++
            collect?.invoke(candidate, count)
            if (candidate == date) return count
            if (limit != null && count > limit) return count
        }
        index++
    }
    return null
}

/** First day of period [index], or null when the arithmetic leaves the supported year range. */
private fun periodStartGuess(rule: RepeatRule, anchor: LocalDate, index: Int): LocalDate? = try {
    if (rule.frequency == RepeatFrequency.Monthly) {
        anchor.startOfMonth().plusMonths(index * rule.interval.coerceAtLeast(1))
    } else {
        LocalDate(anchor.year + index * rule.interval.coerceAtLeast(1), 1, 1)
    }
} catch (_: IllegalArgumentException) {
    null
} catch (_: ArithmeticException) {
    null
}

// ----- time-of-day expansion (`BYHOUR` / `BYMINUTE` / `BYSECOND`) -----

/** One start time of an occurrence: minutes and seconds after midnight. */
data class OccurrenceTime(val minutes: Int, val seconds: Int)

/**
 * Start times [rule] puts on a rule-generated date: `BYHOUR` / `BYMINUTE` / `BYSECOND` multiply
 * the single start into several (RFC 5545 expands the time of day), and parts the rule omits keep
 * the anchor's own time. [startTime] is that anchor time.
 */
internal fun expandedStartTimes(rule: RepeatRule, startTime: OccurrenceTime): List<OccurrenceTime> {
    if (rule.byHour.isEmpty() && rule.byMinute.isEmpty() && rule.bySecond.isEmpty()) {
        return listOf(startTime)
    }
    val hours = rule.byHour.ifEmpty { listOf(startTime.minutes / 60) }
    val minutes = rule.byMinute.ifEmpty { listOf(startTime.minutes % 60) }
    val seconds = rule.bySecond.ifEmpty { listOf(startTime.seconds) }
    return hours.flatMap { hour ->
        minutes.flatMap { minute ->
            seconds.map { second -> OccurrenceTime(hour * 60 + minute, second) }
        }
    }.distinct().sortedBy { it.minutes * 60 + it.seconds }
}

/**
 * Start times of the occurrence starting on [date], where [date] is a wall-clock date in the
 * event's own zone. One per expanded time of day when the rule carries `BYHOUR` / `BYMINUTE` /
 * `BYSECOND`, the event's own start for a `DTSTART` that misses the `BY*` filters, and the `RDATE`
 * value's own time for an added instance. Timed `EXDATE` values remove only their exact start.
 */
fun Event.occurrenceTimesOn(date: LocalDate): List<OccurrenceTime> {
    val recurrence = recurrence
    val rule = recurrence?.rule
    if (recurrence == null) {
        val covered = date >= wallDay && date <= wallLastOccupiedDay()
        if (!covered) return emptyList()
        return if (start is IcsTimeValue.Date) listOf(OccurrenceTime(0, 0)) else listOf(wallStartTime())
    }
    if (start is IcsTimeValue.Date) {
        return if (isOccurrenceStart(date, rule)) listOf(OccurrenceTime(0, 0)) else emptyList()
    }
    if (!isOccurrenceStart(date, rule)) return emptyList()
    val anchorTime = wallStartTime()
    val times = mutableListOf<OccurrenceTime>()
    if (date == wallDay) times += anchorTime
    if (rule != null && matchesRule(rule, wallDay, date)) times += expandedStartTimes(rule, anchorTime)
    recurrence.rdates.filter { it.value.wallDate == date }.forEach { rdate ->
        rdate.value.occurrenceTime(anchorTime)?.let { times += it }
    }
    return times
        .filterNot { isExcluded(recurrence.exdates, date, it) }
        .distinct()
        .sortedBy { it.minutes * 60 + it.seconds }
}

/** Length of the occurrence starting on wall date [wallDate]; an `RDATE` period keeps its own. */
fun Event.occurrenceDurationOn(wallDate: LocalDate): Duration {
    val own = recurrence?.rdates?.firstOrNull { it.value.wallDate == wallDate }?.duration
    return own ?: occurrenceDurationSeconds.seconds
}

/** Wall-clock length of the anchor occurrence, in seconds, derived from its start and end. */
val Event.occurrenceDurationSeconds: Long
    get() {
        if (start is IcsTimeValue.Date) {
            val exclusive = end?.wallDate ?: wallDay.plusDays(1)
            return (exclusive.toEpochDays() - wallDay.toEpochDays()).coerceAtLeast(1) * 86_400L
        }
        val endValue = end ?: return 0
        return start.durationUntil(endValue, TimeZone.currentSystemDefault()).inWholeSeconds
    }

/** Instant at which the occurrence starting on wall date [date] at [time] begins. */
fun Event.occurrenceStartInstant(
    date: LocalDate,
    time: OccurrenceTime,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Instant {
    val dateTime = LocalDateTime(date, LocalTime(time.minutes / 60, time.minutes % 60, time.seconds))
    val value = start.withWallDateTime(dateTime)
    return value.instantOrNull(zone) ?: dateTime.toInstant(zone)
}

/** Instant at which the occurrence starting on [date] at [time] ends. */
fun Event.occurrenceEndInstant(
    date: LocalDate,
    time: OccurrenceTime,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Instant = occurrenceStartInstant(date, time, zone) + occurrenceDurationOn(date)

/**
 * Slices of [event] on [date] in [zone]: one range per occurrence instance covering the day,
 * clipped to it. Rules without `BYHOUR` / `BYMINUTE` / `BYSECOND`, recurrences without `RDATE` —
 * and all-day events — keep the single slice [timeRangeOn] computes.
 */
fun Event.occurrenceRangesOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<Pair<Int, Int>> {
    val recurrence = recurrence ?: return listOf(timeRangeOn(date, zone))
    val rule = recurrence.rule
    val expands = rule?.let { it.byHour.isNotEmpty() || it.byMinute.isNotEmpty() || it.bySecond.isNotEmpty() }
        ?: false
    if (allDay || (!expands && recurrence.rdates.isEmpty())) return listOf(timeRangeOn(date, zone))
    val anchor = day
    val span = anchor.daysTo(lastOccupiedDay).coerceAtLeast(0)
    // An instance occupies as many days as the anchor, so the covering instance may start up to a
    // whole span earlier. A rule never starts before the anchor, but an RDATE may.
    val firstOffset = minOf(span, anchor.daysTo(date).coerceAtLeast(0)).coerceAtLeast(0)
    val ranges = mutableListOf<Pair<Int, Int>>()
    for (offset in firstOffset downTo 0) {
        val displayStart = date.plusDays(-offset)
        val wallStart = wallDateFor(displayStart, zone)
        if (!isOccurrenceStart(wallStart, rule)) continue
        for (time in occurrenceTimesOn(wallStart)) {
            occurrenceRangeOn(date, wallStart, time, zone)?.let { ranges += it }
        }
    }
    // Not an occurrence day, or a slice the day cannot hold: keep the raw range for callers that
    // bypass occursOn.
    return ranges.ifEmpty { listOf(timeRangeOn(date, zone)) }
}

/** The slice of the instance starting on [wallStart] at [time] that falls on [date], or null. */
private fun Event.occurrenceRangeOn(
    date: LocalDate,
    wallStart: LocalDate,
    time: OccurrenceTime,
    zone: TimeZone,
): Pair<Int, Int>? {
    val startInstant = occurrenceStartInstant(wallStart, time, zone)
    val endInstant = occurrenceEndInstant(wallStart, time, zone)
    val startLocal = startInstant.toLocalDateTime(zone)
    val endLocal = endInstant.toLocalDateTime(zone)
    val lastDay = if (endLocal.date > startLocal.date && endLocal.time == LocalTime(0, 0)) {
        endLocal.date.plusDays(-1)
    } else {
        endLocal.date
    }
    if (date < startLocal.date || date > lastDay) return null
    val start = if (date == startLocal.date) startLocal.hour * 60 + startLocal.minute else 0
    val endMinutes = when {
        date < lastDay -> EndOfDayMinutes
        endLocal.time == LocalTime(0, 0) && lastDay != endLocal.date -> EndOfDayMinutes
        else -> endLocal.hour * 60 + endLocal.minute
    }
    return start to endMinutes
}

// ----- recurring todos (VTODO) -----

/** Anchor of a todo series: `DTSTART`, falling back to `DUE`; null when the todo has no dates. */
val Todo.seriesAnchor: LocalDate?
    get() = (start ?: due)?.let(::parseStoredDate)

/** Whole days from the series anchor to the date an occurrence is shown under. */
private fun Todo.displayOffset(anchor: LocalDate): Int =
    due?.let(::parseStoredDate)?.let { anchor.daysTo(it) } ?: 0

/** Date an occurrence starting on [occStart] is shown under: its due, or the start without a due. */
fun Todo.occurrenceDate(occStart: LocalDate): LocalDate {
    val anchor = seriesAnchor ?: return occStart
    return occStart.plusDays(displayOffset(anchor))
}

/** Recurrence set a todo series expands, including the date-only exclusions the model stores. */
private fun Todo.recurrenceSet(): Recurrence? {
    if (repeat == null && rDates.isEmpty() && excludedDates.isEmpty()) return null
    return Recurrence(
        rule = repeat,
        rdates = rDates,
        exdates = excludedDates.mapNotNull { parseStoredDate(it)?.let(IcsTimeValue::Date) },
    )
}

/**
 * Whether [todo] is shown on [date]: the stored date for a one-off todo, and the display date of
 * an occurrence for a series.
 */
fun Todo.occursOn(date: LocalDate): Boolean {
    val anchor = seriesAnchor ?: return false
    val rule = repeat
    val recurrence = recurrenceSet()
    if (rule == null && recurrence == null) return date == occurrenceDate(anchor)
    val occStart = date.plusDays(-displayOffset(anchor))
    return isRuleOccurrence(anchor, rule, recurrence, occStart, count = rule?.count)
}

/**
 * Occurrence start dates whose display date falls inside [from]..[to] (inclusive), ordered. Empty
 * for one-off todos, which are anchored on their stored date alone.
 */
fun Todo.occurrenceStartsBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
    val rule = repeat ?: return emptyList()
    val anchor = seriesAnchor ?: return emptyList()
    val offset = displayOffset(anchor)
    // Occurrences are shown under their display date, so the window sits offset days earlier here.
    val dates = mutableListOf<LocalDate>()
    var candidate = maxOf(anchor, from.plusDays(-offset))
    val last = to.plusDays(-offset)
    var steps = 0
    while (candidate <= last && steps <= NextOccurrenceSearchDays) {
        if (isRuleOccurrence(anchor, rule, recurrenceSet(), candidate, count = rule.count)) dates += candidate
        candidate = candidate.plusDays(1)
        steps++
    }
    return dates
}

/**
 * Display date of the next occurrence on or after [today]; the stored due date is the fallback
 * when the series has already ended or the search window ran out.
 */
fun Todo.nextOccurrenceDate(today: LocalDate): LocalDate? {
    val anchor = seriesAnchor ?: return null
    val rule = repeat ?: return occurrenceDate(anchor)
    val offset = displayOffset(anchor)
    // An occurrence is shown under its display date, so the first candidate starts that many days
    // before today.
    var candidate = maxOf(anchor, today.plusDays(-offset))
    var steps = 0
    while (steps <= NextOccurrenceSearchDays) {
        if (isRuleOccurrence(anchor, rule, recurrenceSet(), candidate, count = rule.count)) {
            return candidate.plusDays(offset)
        }
        candidate = candidate.plusDays(1)
        steps++
    }
    // The series is over, or its next occurrence lies beyond the search window: the stored due
    // stays what the todo is shown and sorted under, like the one-off branch above returns.
    return occurrenceDate(anchor)
}

/** Days the next-occurrence search walks before giving up and falling back to the stored due. */
private const val NextOccurrenceSearchDays = 366 * 5

/** Adds [slot] to the excluded set, creating the recurrence set when the series has none yet. */
fun Recurrence?.withExclusion(slot: IcsTimeValue): Recurrence =
    (this ?: Recurrence()).let { it.copy(exdates = (it.exdates + slot).distinct()) }

/** Moves every typed value of the recurrence set by whole wall days, keeping its value type. */
fun Recurrence.shiftedByWallDays(days: Int): Recurrence {
    if (days == 0) return this
    return copy(
        // A shifted rule no longer matches its original text.
        rule = rule?.copy(until = rule.until?.plusWallDays(days), raw = null),
        rdates = rdates.map { it.copy(value = it.value.plusWallDays(days)) },
        exdates = exdates.map { it.plusWallDays(days) },
    )
}

/**
 * `RECURRENCE-ID` an exception for the occurrence shown on [displayDate] carries: the same slot the
 * expansion engine generates for that occurrence, in the series' own value type.
 */
fun Event.recurrenceSlotOn(
    displayDate: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): IcsTimeValue = start.withWallDate(wallDateFor(displayDate, zone))

/** Wall date of a typed `RECURRENCE-ID`, i.e. the display slot the exception replaces. */
fun IcsTimeValue.recurrenceSlotDate(): LocalDate = wallDate
