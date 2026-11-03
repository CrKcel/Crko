package com.crko

import com.crko.migration.legacyEvent
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * Fixtures shared by the `Ics*Test` classes. `IcsTest` was split by scenario (E1); these are the
 * same values the single test class used, kept in one place so the moved tests stay unchanged.
 */
abstract class IcsTestBase {
    protected val utc = TimeZone.UTC
    protected val shanghai = TimeZone.of("Asia/Shanghai")
    protected val now: Instant = LocalDateTime(2026, 10, 27, 9, 0).toInstant(utc)

    protected val baseEvent = legacyEvent(
        id = "e1",
        title = "Team sync",
        date = "2026-10-27",
        startMinutes = 9 * 60,
        endMinutes = 10 * 60,
    )

    protected fun calendar(vararg events: String): String = buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        events.forEach { append(it.trimIndent().replace("\n", "\r\n")).append("\r\n") }
        append("END:VCALENDAR\r\n")
    }
}
