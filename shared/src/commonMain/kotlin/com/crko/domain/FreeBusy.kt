package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * RFC 5545 busy/free data: `VFREEBUSY` periods and the busy time an event list implies. A period is
 * half-open — [start] is occupied, [end] is not.
 */

/** `FBTYPE` of a busy period. */
enum class BusyType {
    /** Explicitly free time, reported by a `FREEBUSY;FBTYPE=FREE` period. */
    Free,

    /** `FBTYPE=BUSY` (the default) or an unknown value. */
    Busy,

    /** `FBTYPE=BUSY-UNAVAILABLE`, e.g. a blocked-off slot. */
    BusyUnavailable,

    /** `FBTYPE=BUSY-TENTATIVE`, or an event with `STATUS:TENTATIVE`. */
    Tentative,
}

/**
 * One busy period, serializable by plain numbers so it can live inside the persisted app state.
 * Wall-clock meaning comes from the zone the period was created in; the instants themselves are UTC.
 */
@kotlinx.serialization.Serializable
data class BusyInterval(
    val type: BusyType = BusyType.Busy,
    /** UTC epoch milliseconds of the period start (inclusive). */
    val startMs: Long = 0,
    /** UTC epoch milliseconds of the period end (exclusive). */
    val endMs: Long = 0,
) {
    val start: Instant get() = Instant.fromEpochMilliseconds(startMs)
    val end: Instant get() = Instant.fromEpochMilliseconds(endMs)

    val isEmpty: Boolean get() = endMs <= startMs

    fun overlaps(other: BusyInterval): Boolean = startMs < other.endMs && other.startMs < endMs

    fun contains(instant: Instant): Boolean {
        val ms = instant.toEpochMilliseconds()
        return startMs <= ms && ms < endMs
    }

    companion object {
        fun of(type: BusyType, start: Instant, end: Instant) =
            BusyInterval(type = type, startMs = start.toEpochMilliseconds(), endMs = end.toEpochMilliseconds())
    }
}

/** Time spent busy on [date] between [startMinutes] and [endMinutes] of [zone]. */
internal fun busyIntervalOf(type: BusyType, date: LocalDate, startMinutes: Int, endMinutes: Int, zone: TimeZone): BusyInterval {
    val dayStart = LocalDateTime(date, LocalTime(0, 0)).toInstant(zone).toEpochMilliseconds()
    return BusyInterval(
        type = type,
        startMs = dayStart + startMinutes * 60_000L,
        endMs = dayStart + endMinutes.coerceAtLeast(startMinutes) * 60_000L,
    )
}

/**
 * Busy periods of [this] event list within [from] (inclusive) and [to] (exclusive). Cancelled and
 * transparent (`TRANSP:TRANSPARENT`) events occupy nothing; a tentative event reports as
 * [BusyType.Tentative]. Every occurrence of a repeating series inside the range is expanded.
 */
fun List<Event>.busyIntervals(
    from: Instant,
    to: Instant,
    zone: TimeZone,
    includeTentative: Boolean = true,
): List<BusyInterval> {
    if (from >= to || isEmpty()) return emptyList()
    val fromMs = from.toEpochMilliseconds()
    val toMs = to.toEpochMilliseconds()
    val firstDay = from.toLocalDateTime(zone).date
    val lastDay = to.toLocalDateTime(zone).date
    val intervals = mutableListOf<BusyInterval>()
    forEach { event ->
        if (event.status == EventStatus.Cancelled) return@forEach
        if (event.transparency == EventTransparency.Transparent) return@forEach
        val type = when {
            event.status == EventStatus.Tentative && includeTentative -> BusyType.Tentative
            else -> BusyType.Busy
        }
        // An occurrence can start before the window and still cover part of it; back up by the
        // event's own span so multi-day records are not clipped to the window's first day.
        val backOffset = event.day.daysTo(event.endDay) + 1
        val scanStart = maxOf(event.day, firstDay.plusDays(-backOffset))
        var date = scanStart
        while (date <= lastDay) {
            if (event.occursOn(date, zone)) {
                val (startMinutes, endMinutes) = event.timeRangeOn(date, zone)
                if (endMinutes > startMinutes) {
                    val interval = busyIntervalOf(type, date, startMinutes, endMinutes, zone)
                    // Only periods that really intersect the window are reported, clipped to it.
                    val clippedStart = maxOf(interval.startMs, fromMs)
                    val clippedEnd = minOf(interval.endMs, toMs)
                    if (clippedEnd > clippedStart) {
                        intervals += interval.copy(startMs = clippedStart, endMs = clippedEnd)
                    }
                }
            }
            date = date.plusDays(1)
        }
    }
    return intervals.sortedWith(compareBy({ it.startMs }, { it.endMs })).mergeAdjacent()
}

/**
 * Busy periods imported from `VFREEBUSY` components within the same window. `FBTYPE=FREE` periods
 * are kept with their own type so callers can tell declared free time from busy time.
 */
fun List<BusyInterval>.within(from: Instant, to: Instant): List<BusyInterval> =
    filter { it.endMs > from.toEpochMilliseconds() && it.startMs < to.toEpochMilliseconds() }
        .sortedWith(compareBy({ it.startMs }, { it.endMs }))

/**
 * Coalesces overlapping and adjacent periods of the same type into one, so a busy query does not
 * report three overlapping meetings as six busy intervals.
 */
fun List<BusyInterval>.mergeAdjacent(): List<BusyInterval> {
    if (size <= 1) return this
    val result = mutableListOf<BusyInterval>()
    forEach { interval ->
        if (interval.isEmpty) return@forEach
        val last = result.lastOrNull()
        if (last != null && last.type == interval.type && interval.startMs <= last.endMs) {
            result[result.size - 1] = last.copy(endMs = maxOf(last.endMs, interval.endMs))
        } else {
            result += interval
        }
    }
    return result
}

/** The busy time of [this] within [from]..[to]: stored periods plus the events' own time. */
fun busySchedule(
    events: List<Event>,
    storedPeriods: List<BusyInterval>,
    from: Instant,
    to: Instant,
    zone: TimeZone,
): List<BusyInterval> =
    (events.busyIntervals(from, to, zone) + storedPeriods.within(from, to).filterNot { it.type == BusyType.Free })
        .sortedWith(compareBy({ it.startMs }, { it.endMs }))
        .mergeAdjacent()

/** Whether [instant] falls into any busy period. Free periods never make a moment busy. */
fun busyAt(busy: List<BusyInterval>, instant: Instant): Boolean =
    busy.any { it.type != BusyType.Free && it.startMs <= instant.toEpochMilliseconds() && instant.toEpochMilliseconds() < it.endMs }

/** How long an imported busy period is kept for queries before it is pruned. */
private const val BusyRetentionMs = 7L * 24 * 60 * 60 * 1000

/**
 * Adds newly imported busy periods to the stored set: exact duplicates are dropped and periods that
 * ended more than a week ago are pruned, so a feed that keeps shipping the same busy data cannot
 * grow the stored state forever.
 */
fun mergeBusyPeriods(stored: List<BusyInterval>, imported: List<BusyInterval>, now: Instant): List<BusyInterval> {
    if (imported.isEmpty()) return stored
    val cutoff = now.toEpochMilliseconds() - BusyRetentionMs
    return (stored + imported)
        .distinct()
        .filter { it.endMs >= cutoff }
        .sortedWith(compareBy({ it.startMs }, { it.endMs }))
}

/**
 * Gaps of at least [minMinutes] without busy time inside [from]..[to]. Declared free slots reopen
 * exactly the busy time they cover, instead of hiding the whole overlapping meeting.
 */
fun List<BusyInterval>.freeGaps(
    from: Instant,
    to: Instant,
    minMinutes: Int = 0,
): List<Pair<Instant, Instant>> {
    if (from >= to) return emptyList()
    val fromMs = from.toEpochMilliseconds()
    val toMs = to.toEpochMilliseconds()
    val free = filter { it.type == BusyType.Free }
    val busy = filter { it.type != BusyType.Free }
        .flatMap { period -> minusFree(period, free) }
        .map { (start, end) -> start.coerceAtLeast(fromMs) to end.coerceAtMost(toMs) }
        .filter { (start, end) -> end > start }
        .sortedBy { it.first }
    val minMs = minMinutes * 60_000L
    val gaps = mutableListOf<Pair<Long, Long>>()
    var cursor = fromMs
    busy.forEach { (start, end) ->
        if (start > cursor) gaps += cursor to start
        cursor = maxOf(cursor, end)
    }
    if (cursor < toMs) gaps += cursor to toMs
    return gaps.mapNotNull { (start, end) ->
        if (end - start >= minMs) Instant.fromEpochMilliseconds(start) to Instant.fromEpochMilliseconds(end) else null
    }
}

/** Splits [period] into the parts not covered by declared free time. */
private fun minusFree(period: BusyInterval, free: List<BusyInterval>): List<Pair<Long, Long>> {
    var segments = listOf(period.startMs to period.endMs)
    free.filter { it.startMs < period.endMs && period.startMs < it.endMs }.forEach { slot ->
        segments = segments.flatMap { (start, end) ->
            when {
                slot.endMs <= start || slot.startMs >= end -> listOf(start to end)
                else -> buildList {
                    if (slot.startMs > start) add(start to slot.startMs)
                    if (slot.endMs < end) add(slot.endMs to end)
                }
            }
        }
    }
    return segments
}

/** Groups [intervals] by `FBTYPE` in the order RFC 5545 lists them. */
private fun groupedByType(intervals: List<BusyInterval>): List<Pair<String, List<BusyInterval>>> {
    val order = listOf(
        BusyType.Busy to "BUSY",
        BusyType.Tentative to "BUSY-TENTATIVE",
        BusyType.BusyUnavailable to "BUSY-UNAVAILABLE",
        BusyType.Free to "FREE",
    )
    return order.mapNotNull { (type, name) ->
        val matching = intervals.filter { it.type == type && !it.isEmpty }
        if (matching.isEmpty()) null else name to matching
    }
}

/**
 * Serializes busy periods into a `VFREEBUSY` calendar, the shape a busy-time query response takes.
 * All values are written as UTC instants, so the periods mean the same moment in every zone.
 */
fun exportFreeBusy(
    intervals: List<BusyInterval>,
    now: Instant = Clock.System.now(),
    uid: String = "crko-freebusy@local",
    organizer: String? = null,
): String {
    val kept = intervals.filter { !it.isEmpty }.sortedWith(compareBy({ it.startMs }, { it.endMs }))
    val start = kept.minOfOrNull(BusyInterval::startMs)
    val end = kept.maxOfOrNull(BusyInterval::endMs)
    return buildString {
        append("BEGIN:VCALENDAR").append("\r\n")
        append("VERSION:2.0").append("\r\n")
        append("PRODID:-//Crko//Crko Calendar//EN").append("\r\n")
        append("CALSCALE:GREGORIAN").append("\r\n")
        append("METHOD:PUBLISH").append("\r\n")
        append("BEGIN:VFREEBUSY").append("\r\n")
        append("UID:").append(uid).append("\r\n")
        append("DTSTAMP:").append(formatUtc(now)).append("\r\n")
        organizer?.takeIf { it.isNotBlank() }?.let { append("ORGANIZER:").append(it).append("\r\n") }
        start?.let { append("DTSTART:").append(formatUtc(Instant.fromEpochMilliseconds(it))).append("\r\n") }
        end?.let { append("DTEND:").append(formatUtc(Instant.fromEpochMilliseconds(it))).append("\r\n") }
        groupedByType(kept).forEach { (type, group) ->
            val values = group.joinToString(",") { interval ->
                "${formatUtc(interval.start)}/${formatUtc(interval.end)}"
            }
            append("FREEBUSY;FBTYPE=").append(type).append(':').append(values).append("\r\n")
        }
        append("END:VFREEBUSY").append("\r\n")
        append("END:VCALENDAR").append("\r\n")
    }
}
