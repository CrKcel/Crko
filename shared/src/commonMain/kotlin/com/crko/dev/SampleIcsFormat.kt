package com.crko.dev

import com.crko.domain.mondayIndex
import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.number
import kotlin.time.Instant

/** Most recent occurrence of [isoWeekday] (Monday = 1) on or before [today]. */
internal fun recentWeekday(today: LocalDate, isoWeekday: Int): LocalDate =
    today.plusDays(-((today.mondayIndex - isoWeekday + 7) % 7))

internal fun hoursAgo(nowMillis: Long, hours: Long): String =
    utcStamp(Instant.fromEpochMilliseconds(nowMillis - hours * 3_600_000))

internal fun utcAt(day: LocalDate, hour: Int, minute: Int, zone: TimeZone): String =
    utcStamp(LocalDateTime(day, LocalTime(hour, minute)).toInstant(zone))

internal const val Crlf = "\r\n"

/** One `VEVENT` block; [extra] carries properties and nested `VALARM` lines. */
internal fun icsEvent(
    uid: String,
    summary: String,
    stamp: String,
    start: String,
    startParam: String = "",
    end: String? = null,
    endParam: String = startParam,
    extra: List<String> = emptyList(),
): String = buildString {
    append("BEGIN:VEVENT$Crlf")
    append("UID:$uid$Crlf")
    append("DTSTAMP:$stamp$Crlf")
    append("SUMMARY:$summary$Crlf")
    append("DTSTART$startParam:$start$Crlf")
    if (end != null) append("DTEND$endParam:$end$Crlf")
    extra.forEach { append(it).append(Crlf) }
    append("END:VEVENT$Crlf")
}

/** One `VTODO` block; both `DTSTART` and `DUE` are optional. */
internal fun icsTodo(
    uid: String,
    summary: String,
    stamp: String,
    start: String? = null,
    startParam: String = "",
    due: String? = null,
    dueParam: String = startParam,
    extra: List<String> = emptyList(),
): String = buildString {
    append("BEGIN:VTODO$Crlf")
    append("UID:$uid$Crlf")
    append("DTSTAMP:$stamp$Crlf")
    append("SUMMARY:$summary$Crlf")
    if (start != null) append("DTSTART$startParam:$start$Crlf")
    if (due != null) append("DUE$dueParam:$due$Crlf")
    extra.forEach { append(it).append(Crlf) }
    append("END:VTODO$Crlf")
}

/** One `VJOURNAL` block; an undated entry simply omits `DTSTART`. */
internal fun icsJournal(
    uid: String,
    summary: String,
    stamp: String,
    start: String? = null,
    startParam: String = "",
    extra: List<String> = emptyList(),
): String = buildString {
    append("BEGIN:VJOURNAL$Crlf")
    append("UID:$uid$Crlf")
    append("DTSTAMP:$stamp$Crlf")
    append("SUMMARY:$summary$Crlf")
    if (start != null) append("DTSTART$startParam:$start$Crlf")
    extra.forEach { append(it).append(Crlf) }
    append("END:VJOURNAL$Crlf")
}

/** `YYYYMMDD` for a `DATE` value. */
internal fun icsDate(day: LocalDate): String = buildString {
    append(day.year.toString().padStart(4, '0'))
    append(day.month.number.toString().padStart(2, '0'))
    append(day.day.toString().padStart(2, '0'))
}

/** Floating wall-clock `YYYYMMDDTHHMMSS`. */
internal fun icsLocal(day: LocalDate, hour: Int, minute: Int = 0): String = buildString {
    append(icsDate(day))
    append('T')
    append(hour.toString().padStart(2, '0'))
    append(minute.toString().padStart(2, '0'))
    append("00")
}

/** UTC `YYYYMMDDTHHMMSSZ`, the value type `DTSTAMP` and `COMPLETED` use. */
internal fun utcStamp(instant: Instant): String {
    val local = instant.toLocalDateTime(TimeZone.UTC)
    return icsLocal(local.date, local.time.hour, local.time.minute) + "Z"
}
