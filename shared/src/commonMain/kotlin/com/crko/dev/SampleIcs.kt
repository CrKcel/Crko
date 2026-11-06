package com.crko.dev

import com.crko.domain.plusDays
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * The dev sample as a real `VCALENDAR` document. [sampleState] parses it through the same reader a
 * picked file goes through, so the seed also exercises the import path instead of constructing
 * domain records by hand.
 *
 * Dates are relative to [today]: one-off records always land in the current week or month, and the
 * repeating series are anchored around the same day so every calendar view has something to show.
 */
internal fun sampleIcs(
    today: LocalDate,
    zone: TimeZone,
    nowMillis: Long,
): String {
    val stamp = utcStamp(Instant.fromEpochMilliseconds(nowMillis))
    val events = sampleEvents(today, zone, stamp, nowMillis)
    val todos = sampleTodos(today, stamp, nowMillis)
    val journals = sampleJournals(today, stamp)
    val freeBusy = buildString {
        append("BEGIN:VFREEBUSY$Crlf")
        append("UID:sample-freebusy@crko$Crlf")
        append("DTSTAMP:$stamp$Crlf")
        append("FREEBUSY;FBTYPE=BUSY:${utcAt(today, 9, 0, zone)}/${utcAt(today, 9, 30, zone)}$Crlf")
        append("FREEBUSY;FBTYPE=BUSY-TENTATIVE:${utcAt(today.plusDays(1), 14, 0, zone)}/${utcAt(today.plusDays(1), 16, 0, zone)}$Crlf")
        append("END:VFREEBUSY$Crlf")
    }

    return buildString {
        append("BEGIN:VCALENDAR$Crlf")
        append("VERSION:2.0$Crlf")
        append("PRODID:-//Crko//Dev sample//CN$Crlf")
        append("CALSCALE:GREGORIAN$Crlf")
        append("X-WR-CALNAME:示例日历$Crlf")
        append("X-WR-CALDESC:开发构建首次启动时写入的示例日程$Crlf")
        events.forEach { append(it) }
        todos.forEach { append(it) }
        journals.forEach { append(it) }
        append(freeBusy)
        append("END:VCALENDAR$Crlf")
    }
}
