package com.crko

import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.displayEndDay
import com.crko.domain.displayStartDate
import com.crko.migration.legacySourceText
import com.crko.domain.localEnd
import com.crko.domain.localStart
import com.crko.domain.tzid
import kotlinx.datetime.TimeZone

/*
 * Projections of the pre-migration event fields. They stay in the test source set so migration and
 * fixture assertions can describe the old shape without putting those fields back on the model.
 */

internal val Event.allDay: Boolean get() = start is IcsTimeValue.Date

internal val Event.date: String get() = day.toString()

internal val Event.endDate: String?
    get() = displayEndDay(TimeZone.currentSystemDefault())
        .takeIf { it != displayStartDate(TimeZone.currentSystemDefault()) }
        ?.toString()

internal val Event.startMinutes: Int
    get() = if (allDay) 0 else localStart().time.let { it.hour * 60 + it.minute }

internal val Event.endMinutes: Int
    get() {
        if (allDay) return 0
        val local = localEnd() ?: return startMinutes
        return local.time.hour * 60 + local.time.minute
    }

internal val Event.startSeconds: Int get() = if (allDay) 0 else localStart().second

internal val Event.endSeconds: Int get() = if (allDay) 0 else localEnd()?.second ?: startSeconds

internal val Event.timeZoneId: String? get() = start.tzid

internal val Event.sourceStart: String? get() = legacySourceText(start)

internal val Event.sourceEnd: String? get() = legacySourceText(end)
