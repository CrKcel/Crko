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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Wall clock in [tzid]. An unknown zone's `VTIMEZONE` definition lives at the calendar level
     * (see [CalendarMetadata.timeZones]), so the value itself only carries the reference.
     */
    @Serializable
    @SerialName("zoned")
    data class Zoned(
        val dateTime: LocalDateTime,
        val tzid: String,
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

/** Whole seconds of a duration, the granularity an `RDATE;VALUE=PERIOD` length has.
 *
 * The descriptor name must not be the class name of the value it serializes: `kotlin.time.Duration`
 * is already registered by kotlinx.serialization's own `DurationSerializer`, and two serializers for
 * one serial name make the JVM implementation throw while initializing this object — which, since
 * every relative `VALARM` and every period length goes through it, would break saving and reading
 * the whole state, not one record.
 */
object DurationSecondsSerializer : KSerializer<Duration> {
    override val descriptor =
        PrimitiveSerialDescriptor("com.crko.domain.DurationSeconds", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeLong(value.inWholeSeconds)

    override fun deserialize(decoder: Decoder): Duration = decoder.decodeLong().seconds
}

/**
 * `Todo.excludedDates`: typed `IcsTimeValue` values, so a timed `EXDATE` keeps its own time of day.
 * The payload shape changed from bare ISO date strings to typed objects without a schema version
 * bump, so the decoder accepts both — a string becomes a DATE value, and a string no
 * [parseStoredDate] can read is dropped, exactly how persisted date strings are read everywhere
 * else. Encoding always writes the typed shape.
 */
object TodoExdatesSerializer : KSerializer<List<IcsTimeValue>> {
    private val values = ListSerializer(IcsTimeValue.serializer())

    override val descriptor = values.descriptor

    override fun serialize(encoder: Encoder, value: List<IcsTimeValue>) {
        encoder.encodeSerializableValue(values, value)
    }

    override fun deserialize(decoder: Decoder): List<IcsTimeValue> {
        val json = decoder as? JsonDecoder ?: return decoder.decodeSerializableValue(values)
        val array = json.decodeJsonElement() as? JsonArray ?: return emptyList()
        return buildList {
            array.forEach { element ->
                when {
                    element is JsonPrimitive && element.isString ->
                        parseStoredDate(element.content)?.let { add(IcsTimeValue.Date(it)) }
                    else -> runCatching { json.json.decodeFromJsonElement(IcsTimeValue.serializer(), element) }
                        .getOrNull()
                        ?.let(::add)
                }
            }
        }
    }
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
    is IcsTimeValue.Zoned -> zonedInstantOrNull(dateTime, tzid)
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
        // The picked clock is the device's, so it first becomes the instant the user meant and then
        // the wall clock of the event's own zone — including a zone only a calendar-level
        // `VTIMEZONE` describes. Reading it through the device instead kept the picked numbers as
        // the zone's wall clock, which moved the event by the difference of the two offsets every
        // time it was saved.
        val instant = dateTime.toInstant(deviceZone)
        IcsTimeValue.Zoned(zoneDateTimeOrNull(instant, tzid) ?: instant.toLocalDateTime(deviceZone), tzid)
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
    // A floating value has no instant of its own: resolving it through a zone first would let the
    // device's daylight-saving rules decide where the `DURATION` ends, so the same file would
    // import with a different length on a different device.
    is IcsTimeValue.Floating -> IcsTimeValue.Floating(wallDateTime.plusWallClock(duration))
    is IcsTimeValue.Zoned -> {
        val zone = icsZoneOrNull(tzid) ?: fallbackZone
        val end = (instantOrNull(fallbackZone) ?: dateTime.toInstant(zone)) + duration
        IcsTimeValue.Zoned(zoneDateTimeOrNull(end, tzid) ?: end.toLocalDateTime(zone), tzid)
    }
    is IcsTimeValue.Utc -> IcsTimeValue.Utc(instant + duration)
}

/** Adds [duration] to a wall clock, without any zone in between. */
private fun LocalDateTime.plusWallClock(duration: Duration): LocalDateTime {
    val total = date.toEpochDays() * 86_400L +
        time.hour * 3_600L + time.minute * 60L + time.second +
        duration.inWholeSeconds
    val day = total.floorDiv(86_400L)
    val secondOfDay = total.mod(86_400L).toInt()
    return LocalDateTime(
        LocalDate.fromEpochDays(day.toInt()),
        LocalTime(secondOfDay / 3_600, secondOfDay % 3_600 / 60, secondOfDay % 60),
    )
}

/** Duration from [this] to [other]; instants when both carry one, wall clock otherwise. */
fun IcsTimeValue.durationUntil(other: IcsTimeValue?, zone: TimeZone): Duration {
    if (other == null) return Duration.ZERO
    // Two floating values describe one wall clock and have no instant of their own: folding them
    // through the device zone let that zone's daylight-saving rules shrink or stretch the length
    // (the 02:30-03:30 occurrence on a spring-forward day became zero). Same call as the DURATION
    // and `UNTIL` decisions in stage 1: floating arithmetic stays on the wall clock.
    if (this is IcsTimeValue.Floating && other is IcsTimeValue.Floating) {
        return wallDateTime.wallSecondsUntil(other.wallDateTime).seconds
    }
    val start = instantOrNull(zone)
    val end = other.instantOrNull(zone)
    if (start != null && end != null) return end - start
    return wallDateTime.wallSecondsUntil(other.wallDateTime).seconds
}

private fun LocalDateTime.wallSecondsUntil(other: LocalDateTime): Long =
    date.daysTo(other.date) * 86_400L +
        (other.time.hour * 3_600L + other.time.minute * 60L + other.time.second) -
        (time.hour * 3_600L + time.minute * 60L + time.second)

/** IANA zone a value names, or null when the platform cannot resolve it. */
internal fun icsZoneOrNull(tzid: String): TimeZone? = resolveIcsZoneOrNull(tzid)

/** Absolute instant of a zoned value, resolving a calendar-level `VTIMEZONE` definition as fallback. */
internal fun zonedInstantOrNull(dateTime: LocalDateTime, tzid: String): Instant? {
    icsZoneOrNull(tzid)?.let { return dateTime.toInstant(it) }
    return customZoneInstantOrNull(dateTime, tzid)
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
fun Event.movedByWallDays(days: Int): Event {
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
