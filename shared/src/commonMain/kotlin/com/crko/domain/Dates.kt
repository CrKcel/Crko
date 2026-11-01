package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn

/** A single day at a point in time, refreshed by the host every minute. */
data class LocalClock(val instant: Instant, val date: LocalDate, val time: LocalTime)

fun LocalDate.startOfMonth(): LocalDate = LocalDate(year, month.number, 1)

fun LocalDate.startOfYear(): LocalDate = LocalDate(year, 1, 1)

fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

/** Calendar years, clamping Feb 29 onto Feb 28 in common years. */
fun LocalDate.plusYears(years: Int): LocalDate {
    val target = year + years
    return LocalDate(target, month.number, day.coerceAtMost(daysInMonth(target, month.number)))
}

/** Whole months from this date to [other], negative when [other] is earlier. */
fun LocalDate.monthsTo(other: LocalDate): Int =
    (other.year - year) * 12 + (other.month.number - month.number)

/** One-based day of the year. */
fun LocalDate.dayOfYear(): Int = (toEpochDays() - startOfYear().toEpochDays()).toInt() + 1

fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

fun daysInYear(year: Int): Int = if (isLeapYear(year)) 366 else 365

/** Monday-first index (0..6). */
val LocalDate.mondayIndex: Int get() = dayOfWeek.isoDayNumber - 1

/** The Monday that starts the week containing this date. */
fun LocalDate.startOfWeek(): LocalDate = plusDays(-mondayIndex)

fun LocalDate.weekDates(): List<LocalDate> = (0 until 7).map { plusDays(it) }

fun LocalDate.nextMonthStart(): LocalDate =
    if (month.number == 12) LocalDate(year + 1, 1, 1) else LocalDate(year, month.number + 1, 1)

/**
 * Every date shown in the month grid, from the Monday on or before the 1st through the Sunday on
 * or after the last day, so the length is always a multiple of seven.
 */
fun LocalDate.monthGrid(): List<LocalDate> {
    val first = startOfMonth()
    val last = nextMonthStart().plusDays(-1)
    val gridStart = first.startOfWeek()
    val gridEnd = last.plusDays(6 - last.mondayIndex)
    return generateSequence(gridStart) { it.plusDays(1) }
        .takeWhile { it <= gridEnd }
        .toList()
}

fun daysInMonth(year: Int, month: Int): Int = LocalDate(year, month, 1).nextMonthStart().plusDays(-1).day

/** Month arithmetic that clamps the day, e.g. Jan 31 + 1 month = Feb 28. */
fun LocalDate.plusMonths(months: Int): LocalDate {
    val total = year * 12 + (month.number - 1) + months
    val newYear = total.floorDiv(12)
    val newMonth = total.mod(12) + 1
    return LocalDate(newYear, newMonth, day.coerceAtMost(daysInMonth(newYear, newMonth)))
}

fun today(clock: Clock = Clock.System, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    clock.todayIn(zone)

/** Whole days from this date to [other]; negative when [other] is earlier. */
fun LocalDate.daysTo(other: LocalDate): Int = (other.toEpochDays() - toEpochDays()).toInt()

/**
 * Parsed ISO date, or null when [text] is not a calendar date. Stored payloads survive a crash with
 * arbitrary field values, so every read of a date field has to be able to say "unreadable".
 */
fun parseStoredDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text) }.getOrNull()

fun LocalTime.minutesOfDay(): Int = hour * 60 + minute

/**
 * Parsed `UNTIL` value. [instant] is set for UTC values, [localDateTime] for floating values and
 * both are null for a date-only value, whose whole day is included.
 */
data class RepeatUntil(
    val date: LocalDate,
    val localDateTime: LocalDateTime? = null,
    val instant: Instant? = null,
)

fun parseRepeatUntil(text: String): RepeatUntil? {
    val value = text.trim()
    if (value.isEmpty()) return null
    if (value.endsWith("Z", ignoreCase = true)) {
        return runCatching { Instant.parse(value) }.getOrNull()
            ?.let { RepeatUntil(it.toLocalDateTime(TimeZone.UTC).date, instant = it) }
    }
    if ('T' !in value) {
        return runCatching { LocalDate.parse(value) }.getOrNull()?.let { RepeatUntil(it) }
    }
    runCatching { LocalDateTime.parse(value) }.getOrNull()?.let {
        return RepeatUntil(it.date, localDateTime = it)
    }
    return runCatching { Instant.parse(value) }.getOrNull()
        ?.let { RepeatUntil(it.toLocalDateTime(TimeZone.UTC).date, instant = it) }
}

/** Calendar date of an `UNTIL` value in [zone]; date-only values are zone-independent. */
fun RepeatUntil.localDate(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate =
    instant?.toLocalDateTime(zone)?.date ?: date
