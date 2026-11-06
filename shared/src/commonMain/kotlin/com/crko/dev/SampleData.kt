package com.crko.dev

import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.DefaultLocalCalendarName
import com.crko.domain.importCalendar
import com.crko.domain.mergeBusyPeriods
import com.crko.domain.mergeCalendarTimeZones
import com.crko.domain.mergeEvents
import com.crko.domain.mergeJournals
import com.crko.domain.mergeTodos
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Seed content for debug builds that start without saved data, so every screen can be checked on
 * a real device without manual entry.
 *
 * The content lives in [sampleIcs] and is parsed through the same import reader a picked file goes
 * through; only then are the records merged into a calendar with the import pipeline's rules. The
 * seed therefore also keeps the import path exercised, instead of building domain records that
 * could stay in sync with the model while the reader silently drifted.
 *
 * Dates are relative to [today] so the data always lands in the current week and month.
 */
fun sampleState(
    today: LocalDate,
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
): AppState {
    val zone = TimeZone.currentSystemDefault()
    val imported = importCalendar(sampleIcs(today, zone, nowMillis), zone, "未命名日程")
    var nextId = 0
    val idProvider = { "sample-${++nextId}" }
    val events = mergeEvents(
        base = emptyList(),
        incoming = imported.events,
        cancelledUids = imported.cancelledUids,
        source = null,
        removeVanished = false,
        describedUids = imported.describedUids,
        idProvider = idProvider,
    ).items
    val todos = mergeTodos(
        base = emptyList(),
        incoming = imported.todos,
        source = null,
        removeVanished = false,
        describedUids = imported.describedUids,
        idProvider = idProvider,
    ).items
    val journals = mergeJournals(
        base = emptyList(),
        incoming = imported.journals,
        source = null,
        removeVanished = false,
        describedUids = imported.describedUids,
        idProvider = idProvider,
    ).items
    val calendar = Calendar.local().copy(
        name = imported.calendarName ?: DefaultLocalCalendarName,
        refreshMinutes = imported.refreshMinutes,
        method = imported.method?.wireName,
        timeZones = mergeCalendarTimeZones(emptyList(), imported.timeZones),
        events = events,
        todos = todos,
        journals = journals,
    )
    return AppState(
        calendars = listOf(calendar),
        busyPeriods = mergeBusyPeriods(
            emptyList(),
            imported.freeBusy,
            Instant.fromEpochMilliseconds(nowMillis),
        ),
    )
}
