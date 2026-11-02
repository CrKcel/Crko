package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.number
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * An RFC 5545 time value: a `DATE`, a floating `DATE-TIME`, a `DATE-TIME` in a `TZID`, or a UTC
 * `DATE-TIME`. The stored value is the one the calendar file describes; the device zone is only
 * applied by the display projections below.
 */
@Serializable
sealed interface IcsTimeValue {
    @Serializable
    @SerialName("date")
    data class Date(val date: LocalDate) : IcsTimeValue

    @Serializable
    @SerialName("floating")
    data class Floating(val dateTime: LocalDateTime) : IcsTimeValue

    /** [definition] is the original `VTIMEZONE` text when the zone is not an IANA one. */
    @Serializable
    @SerialName("zoned")
    data class Zoned(
        val dateTime: LocalDateTime,
        val tzid: String,
        val definition: String? = null,
    ) : IcsTimeValue

    @Serializable
    @SerialName("utc")
    data class Utc(
        @Serializable(with = InstantIso8601Serializer::class)
        val instant: Instant,
    ) : IcsTimeValue
}

/** ISO-8601 string form; `kotlin.time.Instant` has no built-in serializer in this toolchain. */
object InstantIso8601Serializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("kotlin.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** Wall-clock date of the value, i.e. the date the calendar file itself names. */
val IcsTimeValue.wallDate: LocalDate
    get() = when (this) {
        is IcsTimeValue.Date -> date
        is IcsTimeValue.Floating -> dateTime.date
        is IcsTimeValue.Zoned -> dateTime.date
        is IcsTimeValue.Utc -> instant.toLocalDateTime(TimeZone.UTC).date
    }

/** Wall-clock time of day, or null for a date-only value. */
val IcsTimeValue.wallDateTime: LocalDateTime
    get() = when (this) {
        is IcsTimeValue.Date -> date.atTime(0, 0)
        is IcsTimeValue.Floating -> dateTime
        is IcsTimeValue.Zoned -> dateTime
        is IcsTimeValue.Utc -> instant.toLocalDateTime(TimeZone.UTC)
    }

val IcsTimeValue.wallTime: LocalTime?
    get() = if (this is IcsTimeValue.Date) null else wallDateTime.time

/** `TZID` this value names, or null for floating/date values. UTC is reported as `UTC`. */
val IcsTimeValue.tzid: String?
    get() = when (this) {
        is IcsTimeValue.Zoned -> tzid
        is IcsTimeValue.Utc -> "UTC"
        else -> null
    }

/**
 * Display projection of [this] value into [zone]. Date-only values keep their date, and floating
 * values keep their wall clock: neither carries an instant of its own.
 */
fun IcsTimeValue.displayDateTime(zone: TimeZone): LocalDateTime = when (this) {
    is IcsTimeValue.Date -> date.atTime(0, 0)
    is IcsTimeValue.Floating -> dateTime
    is IcsTimeValue.Zoned -> instantOrNull(zone)?.toLocalDateTime(zone) ?: dateTime
    is IcsTimeValue.Utc -> instant.toLocalDateTime(zone)
}

fun IcsTimeValue.displayDate(zone: TimeZone): LocalDate = when (this) {
    is IcsTimeValue.Date -> date
    is IcsTimeValue.Floating -> dateTime.date
    else -> displayDateTime(zone).date
}

/** Absolute instant of [this] value, or null for a date-only value. */
fun IcsTimeValue.instantOrNull(zone: TimeZone): Instant? = when (this) {
    is IcsTimeValue.Date -> null
    is IcsTimeValue.Floating -> dateTime.toInstant(zone)
    is IcsTimeValue.Zoned -> zonedInstantOrNull(dateTime, tzid, definition)
    is IcsTimeValue.Utc -> instant
}

/** Same wall-clock value on [date]; the time of day and the zone are kept. */
fun IcsTimeValue.withWallDate(date: LocalDate): IcsTimeValue = when (this) {
    is IcsTimeValue.Date -> IcsTimeValue.Date(date)
    is IcsTimeValue.Floating -> IcsTimeValue.Floating(LocalDateTime(date, dateTime.time))
    is IcsTimeValue.Zoned -> copy(dateTime = LocalDateTime(date, dateTime.time))
    is IcsTimeValue.Utc -> IcsTimeValue.Utc(LocalDateTime(date, instant.toLocalDateTime(TimeZone.UTC).time).toInstant(TimeZone.UTC))
}

/** Same zone and date, with the given wall clock. */
fun IcsTimeValue.withWallDateTime(dateTime: LocalDateTime): IcsTimeValue = when (this) {
    is IcsTimeValue.Date -> IcsTimeValue.Date(dateTime.date)
    is IcsTimeValue.Floating -> IcsTimeValue.Floating(dateTime)
    is IcsTimeValue.Zoned -> copy(dateTime = dateTime)
    is IcsTimeValue.Utc -> IcsTimeValue.Utc(dateTime.toInstant(TimeZone.UTC))
}

/**
 * Editor round trip: [dateTime] is the wall clock the user picked in [deviceZone]. A zoned value
 * keeps its `TZID` and only its instant moves; a UTC value stays UTC; everything else becomes
 * floating.
 */
fun IcsTimeValue.withDeviceDateTime(dateTime: LocalDateTime, deviceZone: TimeZone): IcsTimeValue = when (this) {
    is IcsTimeValue.Zoned -> {
        val zone = icsZoneOrNull(tzid, definition) ?: deviceZone
        IcsTimeValue.Zoned(dateTime.toInstant(deviceZone).toLocalDateTime(zone), tzid, definition)
    }
    is IcsTimeValue.Utc -> IcsTimeValue.Utc(dateTime.toInstant(deviceZone))
    else -> IcsTimeValue.Floating(dateTime)
}

/** Moves the value by whole wall days, keeping its time of day and zone. */
fun IcsTimeValue.plusWallDays(days: Int): IcsTimeValue =
    if (days == 0) this else withWallDate(wallDate.plusDays(days))

/** Moves a timed value by [duration]; date-only values move by whole days. */
fun IcsTimeValue.plusDuration(duration: Duration, fallbackZone: TimeZone): IcsTimeValue = when (this) {
    is IcsTimeValue.Date -> IcsTimeValue.Date(date.plusDays(duration.inWholeDays.toInt().coerceAtLeast(1)))
    is IcsTimeValue.Floating -> IcsTimeValue.Floating(
        (instantOrNull(fallbackZone) ?: wallDateTime.toInstant(fallbackZone)).plus(duration)
            .toLocalDateTime(fallbackZone),
    )
    is IcsTimeValue.Zoned -> {
        val zone = icsZoneOrNull(tzid, definition) ?: fallbackZone
        val end = (instantOrNull(fallbackZone) ?: dateTime.toInstant(zone)) + duration
        IcsTimeValue.Zoned(end.toLocalDateTime(zone), tzid, definition)
    }
    is IcsTimeValue.Utc -> IcsTimeValue.Utc(instant + duration)
}

/** Duration from [this] to [other]; instants when both carry one, wall clock otherwise. */
fun IcsTimeValue.durationUntil(other: IcsTimeValue?, zone: TimeZone): Duration {
    if (other == null) return Duration.ZERO
    val start = instantOrNull(zone)
    val end = other.instantOrNull(zone)
    if (start != null && end != null) return end - start
    return wallDateTime.wallSecondsUntil(other.wallDateTime).seconds
}

private fun LocalDateTime.wallSecondsUntil(other: LocalDateTime): Long =
    date.daysTo(other.date) * 86_400L +
        (other.time.hour * 3_600L + other.time.minute * 60L + other.time.second) -
        (time.hour * 3_600L + time.minute * 60L + time.second)

/** IANA zone a value names, or null when the platform (and any stored definition) cannot resolve it. */
internal fun icsZoneOrNull(tzid: String, definition: String?): TimeZone? = resolveIcsZoneOrNull(tzid, definition)

/** Absolute instant of a zoned value, resolving a stored `VTIMEZONE` definition as a fallback. */
internal fun zonedInstantOrNull(dateTime: LocalDateTime, tzid: String, definition: String?): Instant? {
    icsZoneOrNull(tzid, definition)?.let { return dateTime.toInstant(it) }
    return customZoneInstantOrNull(dateTime, tzid, definition)
}

// ----- Event projections -----

/** Local start of [this] event in [zone], always present (a date-only start is its midnight). */
fun Event.localStart(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime =
    start.displayDateTime(zone)

/** Exclusive local end of [this] event in [zone]; null when the event has zero length. */
fun Event.localEnd(zone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime? =
    end?.displayDateTime(zone)

/** Wall-clock start of [this] event, in the event's own zone. */
internal fun Event.wallStartDateTime(): LocalDateTime = start.wallDateTime

internal fun Event.wallStartTime(): OccurrenceTime {
    val time = start.wallTime ?: LocalTime(0, 0)
    return OccurrenceTime(time.hour * 60 + time.minute, time.second)
}

/** Wall date of the last day the event occupies. */
internal fun Event.wallLastOccupiedDay(): LocalDate {
    val first = start.wallDate
    val end = end ?: return first
    val endWall = end.wallDateTime
    return when {
        end is IcsTimeValue.Date -> endWall.date.plusDays(-1).coerceAtLeast(first)
        endWall.date > first && endWall.time == LocalTime(0, 0) -> endWall.date.plusDays(-1)
        else -> endWall.date
    }
}

/** Display date of the anchor occurrence, the value the calendar grids group by. */
internal fun Event.displayStartDate(zone: TimeZone): LocalDate = start.displayDate(zone)

/** Display date of the last day the anchor occurrence occupies. */
internal fun Event.displayLastOccupiedDay(zone: TimeZone): LocalDate {
    val first = displayStartDate(zone)
    val end = end ?: return first
    if (end is IcsTimeValue.Date) {
        val endDisplay = end.date.plusDays(-1)
        return if (endDisplay > first) endDisplay else first
    }
    val endDisplay = end.displayDateTime(zone)
    return if (endDisplay.date > first && endDisplay.time == LocalTime(0, 0)) {
        endDisplay.date.plusDays(-1)
    } else {
        endDisplay.date
    }
}

/** Inclusive display end date, matching the old `endDate` field. */
internal fun Event.displayEndDay(zone: TimeZone): LocalDate = displayLastOccupiedDay(zone).let { last ->
    val start = displayStartDate(zone)
    // The old model stored a midnight end on the next calendar date; keep that date for callers
    // that display an exclusive end.
    val endDateTime = end?.displayDateTime(zone)
    if (endDateTime != null && end !is IcsTimeValue.Date && endDateTime.time == LocalTime(0, 0) && endDateTime.date > start) {
        endDateTime.date
    } else {
        last
    }
}

/** Moves the event by whole wall days, keeping the times of day and zones. */
fun Event.shiftedByDays(days: Int): Event {
    if (days == 0) return this
    return copy(start = start.plusWallDays(days), end = end?.plusWallDays(days))
}

/** Wall date the occurrence shown under [displayDate] starts on. */
fun Event.wallDateFor(displayDate: LocalDate, zone: TimeZone = TimeZone.currentSystemDefault()): LocalDate {
    if (start is IcsTimeValue.Date) return displayDate
    val anchorShift = day.daysTo(wallDay)
    val candidates = listOf(
        displayDate.plusDays(anchorShift),
        displayDate.plusDays(anchorShift - 1),
        displayDate.plusDays(anchorShift + 1),
    )
    return candidates.firstOrNull { displayStartDate(it, zone) == displayDate } ?: candidates.first()
}

private fun Event.displayStartDate(wallDate: LocalDate, zone: TimeZone): LocalDate =
    start.withWallDate(wallDate).displayDate(zone)
