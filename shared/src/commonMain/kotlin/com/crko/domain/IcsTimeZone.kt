package com.crko.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.concurrent.atomics.AtomicReference

private val windowsTimeZones = mapOf(
    "dateline standard time" to "Etc/GMT+12",
    "utc-11" to "Etc/GMT+11",
    "aleutian standard time" to "America/Adak",
    "hawaiian standard time" to "Pacific/Honolulu",
    "alaskan standard time" to "America/Anchorage",
    "pacific standard time" to "America/Los_Angeles",
    "pacific standard time (mexico)" to "America/Tijuana",
    "us mountain standard time" to "America/Phoenix",
    "mountain standard time" to "America/Denver",
    "central standard time" to "America/Chicago",
    "central america standard time" to "America/Guatemala",
    "canada central standard time" to "America/Regina",
    "central standard time (mexico)" to "America/Mexico_City",
    "eastern standard time" to "America/New_York",
    "us eastern standard time" to "America/Indiana/Indianapolis",
    "atlantic standard time" to "America/Halifax",
    "newfoundland standard time" to "America/St_Johns",
    "sa eastern standard time" to "America/Cayenne",
    "argentina standard time" to "America/Argentina/Buenos_Aires",
    "brasilia standard time" to "America/Sao_Paulo",
    "gmt standard time" to "Europe/London",
    "greenwich standard time" to "Atlantic/Reykjavik",
    "w. europe standard time" to "Europe/Berlin",
    "central europe standard time" to "Europe/Budapest",
    "central european standard time" to "Europe/Warsaw",
    "romance standard time" to "Europe/Paris",
    "e. europe standard time" to "Europe/Chisinau",
    "fle standard time" to "Europe/Kiev",
    "gtb standard time" to "Europe/Bucharest",
    "turkey standard time" to "Europe/Istanbul",
    "israel standard time" to "Asia/Jerusalem",
    "south africa standard time" to "Africa/Johannesburg",
    "russian standard time" to "Europe/Moscow",
    "arabian standard time" to "Asia/Dubai",
    "iran standard time" to "Asia/Tehran",
    "india standard time" to "Asia/Kolkata",
    "sri lanka standard time" to "Asia/Colombo",
    "nepal standard time" to "Asia/Kathmandu",
    "bangladesh standard time" to "Asia/Dhaka",
    "se asia standard time" to "Asia/Bangkok",
    "china standard time" to "Asia/Shanghai",
    "taipei standard time" to "Asia/Taipei",
    "singapore standard time" to "Asia/Singapore",
    "w. australia standard time" to "Australia/Perth",
    "tokyo standard time" to "Asia/Tokyo",
    "korea standard time" to "Asia/Seoul",
    "cen. australia standard time" to "Australia/Adelaide",
    "aus eastern standard time" to "Australia/Sydney",
    "e. australia standard time" to "Australia/Brisbane",
    "tasmania standard time" to "Australia/Hobart",
    "new zealand standard time" to "Pacific/Auckland",
    "fiji standard time" to "Pacific/Fiji",
)

internal sealed interface IcsTime {
    data class AllDay(val date: LocalDate) : IcsTime
    data class DateTime(
        val value: LocalDateTime,
        val zone: IcsZone,
        val source: String,
        /** Length of an `RDATE;VALUE=PERIOD` occurrence; null for a plain DATE-TIME. */
        val periodSeconds: Long? = null,
    ) : IcsTime
}

/**
 * Length carried by an `RDATE;VALUE=PERIOD` value, if any. RFC 5545 durations are second-precise, so
 * the length is carried in seconds rather than rounded to whole minutes on the way in.
 */
internal fun IcsTime.periodDuration(): Duration? =
    (this as? IcsTime.DateTime)?.periodSeconds?.takeIf { it > 0L }?.seconds

internal sealed interface IcsZone {
    data object Floating : IcsZone
    data object Utc : IcsZone
    data class Platform(override val id: String, val zone: TimeZone) : IcsZone
    data class Custom(val zone: CustomZone) : IcsZone

    val id: String?
        get() = when (this) {
            Floating, Utc -> null
            is Platform -> id
            is Custom -> zone.id
        }
}

private fun resolveZone(tzid: String?): IcsZone? = when {
    tzid == null -> null
    tzid.equals("UTC", ignoreCase = true) || tzid.equals("GMT", ignoreCase = true) -> IcsZone.Utc
    else -> resolvePlatformZone(tzid)
        ?: IcsTimeZoneCache.customZoneOrNull(tzid)?.let { IcsZone.Custom(it) }
}

/** Platform zone a `TZID` resolves to, or null when only a stored calendar definition knows it. */
internal fun resolveIcsZoneOrNull(tzid: String): TimeZone? =
    when (val zone = resolveZone(tzid)) {
        is IcsZone.Platform -> zone.zone
        IcsZone.Utc -> TimeZone.UTC
        else -> null
    }

/** Absolute instant of a value whose zone may only exist in a calendar-level `VTIMEZONE`. */
internal fun customZoneInstantOrNull(dateTime: LocalDateTime, tzid: String): Instant? =
    resolveZone(tzid)?.toInstant(dateTime)

/** Wall clock of [instant] in the zone [tzid] names, calendar-level `VTIMEZONE` included. */
internal fun zoneDateTimeOrNull(instant: Instant, tzid: String): LocalDateTime? = when (val zone = resolveZone(tzid)) {
    is IcsZone.Platform -> instant.toLocalDateTime(zone.zone)
    is IcsZone.Custom -> zone.zone.toLocalDateTime(instant)
    IcsZone.Utc -> instant.toLocalDateTime(TimeZone.UTC)
    IcsZone.Floating, null -> null
}

/** Whether the platform resolves [tzid] on its own, so no `VTIMEZONE` block needs to be stored. */
internal fun isPlatformTimeZone(tzid: String): Boolean =
    tzid.equals("UTC", ignoreCase = true) || tzid.equals("GMT", ignoreCase = true) ||
        resolvePlatformZone(tzid) != null

internal fun normalizeTimeZoneId(tzid: String): String = tzid.trim().removePrefix("/")

/**
 * Scope of definitions that name no stored calendar: a file being parsed, an import preview. A record
 * only resolves through them while no calendar declares the same `TZID`.
 */
internal const val UnscopedTimeZoneScope = "\u0000unscoped"

/**
 * Adds the calendar-level definitions of unknown zones to the runtime lookup cache. [scope] names the
 * calendar (or preview) the definitions were declared by, so a second calendar that declares the same
 * `TZID` differently does not overwrite the first one's interpretation.
 */
internal fun registerIcsTimeZones(timeZones: Iterable<CalendarTimeZone>, scope: String = UnscopedTimeZoneScope) {
    timeZones.forEach { IcsTimeZoneCache.register(scope, it.tzid, it.definition) }
}

/**
 * Rebuilds the calendar scopes of the lookup cache from the stored state. This is what a deleted
 * subscription or a discarded import preview needs: definitions only ever leave the cache through this
 * call, so a scope no longer in [calendars] stops resolving.
 */
internal fun replaceIcsTimeZones(calendars: List<Calendar>) {
    IcsTimeZoneCache.replaceCalendarScopes(calendars)
}

/** Stored `VTIMEZONE` text of an unknown zone, for an export that only carries [Event] values. */
internal fun icsTimeZoneDefinition(tzid: String, calendarId: String? = null): String? =
    IcsTimeZoneCache.definitionOf(tzid, calendarId)

/**
 * Every `TZID` more than one calendar declares with a different definition. RFC 5545 scopes a `TZID`
 * to its own `VCALENDAR`, so such a name is ambiguous for the record-level projections and only the
 * per-calendar lookup via [icsTimeZoneDefinition] is exact.
 */
internal fun icsTimeZoneConflicts(): Set<String> = IcsTimeZoneCache.conflicts()

/**
 * Process-wide lookup of the calendar-level definitions. [Calendar.timeZones] remains the persisted
 * source of truth; this cache only lets the `Event` display projections resolve unknown zones
 * without threading the whole state through every recurrence query. Parsed definitions are kept so
 * hundreds of values from one file share a single interpretation of their `VTIMEZONE`.
 *
 * Entries live per declaring scope: two calendars naming the same `TZID` keep both definitions, and a
 * record whose calendar is not known resolves through the first scope declaring it — the state order,
 * so the local calendar's copy wins and a later import cannot silently move events that are already
 * stored.
 */
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
private object IcsTimeZoneCache {
    /**
     * scope id -> (`TZID` -> raw `VTIMEZONE` text), in registration order. The map itself is never
     * mutated: writers build a replacement snapshot and swap it through an atomic reference, so a
     * lookup running on another thread (a background import, export or reminder pass) iterates a
     * stable map instead of racing a rebuild.
     */
    private val scopes = AtomicReference<Map<String, Map<String, String>>>(emptyMap())

    /** Parsed zones keyed by the exact text they were read from, so two definitions never share a parse. */
    private val parsed = AtomicReference<Map<Pair<String, String>, CustomZone?>>(emptyMap())

    fun register(scope: String, tzid: String, definition: String) {
        val id = normalizeTimeZoneId(tzid)
        if (id.isEmpty() || definition.isBlank() || isPlatformTimeZone(id)) return
        while (true) {
            val current = scopes.load()
            val entries = current[scope]
            if (entries?.get(id) == definition) return
            val updated = current + (scope to (entries ?: emptyMap()) + (id to definition))
            if (scopes.compareAndSet(current, updated)) return
        }
    }

    fun replaceCalendarScopes(calendars: List<Calendar>) {
        val rebuilt = LinkedHashMap<String, Map<String, String>>()
        calendars.forEach { calendar ->
            val entries = LinkedHashMap<String, String>()
            calendar.timeZones.forEach { zone ->
                val id = normalizeTimeZoneId(zone.tzid)
                if (id.isNotEmpty() && zone.definition.isNotBlank() && !isPlatformTimeZone(id)) {
                    entries[id] = zone.definition
                }
            }
            rebuilt[calendar.id] = entries
        }
        // Registrations that name no calendar (a file being parsed, a preview not applied yet) live only
        // until the next write: the stored calendars are the source of truth, so a definition nobody
        // declares any more — a deleted subscription, a discarded preview — stops resolving here.
        scopes.store(rebuilt)
        val alive = rebuilt.values.flatMapTo(mutableSetOf()) { entries -> entries.map { (id, text) -> id to text } }
        while (true) {
            val current = parsed.load()
            if (current.keys == alive) return
            val kept = current.filterKeys { it in alive }.toMap()
            if (parsed.compareAndSet(current, kept)) return
        }
    }

    fun definitionOf(tzid: String, scope: String?): String? {
        val id = normalizeTimeZoneId(tzid)
        val snapshot = scopes.load()
        scope?.let { named -> snapshot[named]?.get(id) }?.let { return it }
        snapshot.values.forEach { entries -> entries[id]?.let { return it } }
        return null
    }

    fun conflicts(): Set<String> {
        val first = mutableMapOf<String, String>()
        val out = linkedSetOf<String>()
        scopes.load().values.forEach { entries ->
            entries.forEach { (id, definition) ->
                val known = first[id]
                when {
                    known == null -> first[id] = definition
                    known != definition -> out += id
                }
            }
        }
        return out
    }

    fun customZoneOrNull(tzid: String): CustomZone? {
        val definition = definitionOf(tzid, null) ?: return null
        val key = normalizeTimeZoneId(tzid) to definition
        parsed.load().let { if (it.containsKey(key)) return it[key] }
        val zone = customZoneFromText(definition)
        while (true) {
            val current = parsed.load()
            if (current.containsKey(key)) return current[key]
            if (parsed.compareAndSet(current, current + (key to zone))) return zone
        }
    }

    private fun customZoneFromText(text: String): CustomZone? =
        parseComponents(text).firstOrNull { it.name == "VTIMEZONE" }?.let(::parseCustomZone)
}

/** The zone a calendar declares for values without a `TZID`, if any. */
internal fun calendarFloatingZone(
    calendars: List<IcsComponent>,
    zones: Map<String, CustomZone>,
    warnings: MutableSet<IcsWarning>,
): IcsZone? {
    val raw = calendars
        .firstNotNullOfOrNull { it.first("X-WR-TIMEZONE")?.value }
        ?.trim()
        ?.removeSurrounding("\"")
        ?.removePrefix("/")
        ?.takeIf { it.isNotEmpty() }
        ?: return null
    zones[raw]?.let { return IcsZone.Custom(it) }
    if (raw.equals("UTC", ignoreCase = true) || raw.equals("GMT", ignoreCase = true)) return IcsZone.Utc
    return resolvePlatformZone(raw) ?: run {
        warnings += IcsWarning.UnresolvedTimeZone
        null
    }
}

/**
 * Resolves a platform zone for [tzid]. Exchange writes Windows names ("China Standard Time") and
 * some clients prefix the real id with their own domain, e.g.
 * `/mozilla.org/20050126_1/America/New_York`; both have to become a real [TimeZone] or the event
 * would silently fall back to floating time.
 *
 * A prefix is only stripped from an id with more than two path segments: a real IANA id is
 * `Region/City`, so a third segment belongs to the producer. `Custom/UTC` has two and is an unknown
 * zone that merely *ends* in a real name — reading it as the platform's UTC would drop the offsets
 * its own `VTIMEZONE` declares.
 *
 * The same reason keeps the stripped suffix to at least one more segment: after removing a producer's
 * prefix the remainder still has to read as `Region/City`, otherwise
 * `example.org/20050126_1/Custom/UTC` loses everything but its last token and is resolved as the
 * platform's `UTC` — which is the very defect above, only one segment deeper.
 */
internal fun resolvePlatformZone(tzid: String): IcsZone.Platform? {
    val normalized = tzid.trim().removePrefix("/")
    if (normalized.isEmpty()) return null
    val candidates = buildList {
        add(normalized)
        windowsTimeZones[normalized.lowercase()]?.let { add(it) }
        if (normalized.count { it == '/' } >= 2) {
            var slash = normalized.indexOf('/')
            while (slash >= 0 && slash + 1 < normalized.length) {
                val suffix = normalized.substring(slash + 1)
                if (suffix.contains('/')) add(suffix)
                slash = normalized.indexOf('/', slash + 1)
            }
        }
    }
    candidates.forEach { id ->
        runCatching { TimeZone.of(id) }.getOrNull()?.let { return IcsZone.Platform(id, it) }
    }
    return null
}

// ----- custom VTIMEZONE ----------------------------------------------------

/** Generation horizon for open-ended observance rules; no calendar the app schedules reaches it. */
private const val MaxTransitionYear = 2200

internal class CustomZone(val id: String, val observances: List<Observance>) {
    fun toInstant(local: LocalDateTime): Instant = local.toInstant(TimeZone.UTC).minusSeconds(offsetSecondsAt(local))

    /**
     * Wall clock of [instant] in this zone — the reverse of [toInstant]. A local value is the instant
     * plus the offset in effect *at that local value*, so the offset has to be found by refining the
     * guess: one step lands on the wrong side of a transition, the next confirms the one before it.
     * Without this, an instant could only be read back in the device zone, which moved every custom
     * zone's ends, edited times and exclusions by the difference between the two offsets.
     */
    fun toLocalDateTime(instant: Instant): LocalDateTime {
        var local = instant.toLocalDateTime(TimeZone.UTC)
        repeat(3) {
            val next = (instant + offsetSecondsAt(local).seconds).toLocalDateTime(TimeZone.UTC)
            if (next == local) return local
            local = next
        }
        return local
    }

    /**
     * Offset in effect at [local]. The latest transition not after it wins; when the value predates
     * every known transition the earliest one is used, which is what a zone with rules that only
     * start later implies.
     */
    fun offsetSecondsAt(local: LocalDateTime): Int {
        var latestTime: LocalDateTime? = null
        var latestOffset = 0
        var earliestTime: LocalDateTime? = null
        var earliestOffset = 0
        observances.forEach { observance ->
            observance.transitionsThrough(local.year).forEach { transition ->
                val latest = latestTime
                if (transition <= local && (latest == null || latest < transition)) {
                    latestTime = transition
                    latestOffset = observance.offsetToSeconds
                }
                val earliest = earliestTime
                if (earliest == null || earliest > transition) {
                    earliestTime = transition
                    earliestOffset = observance.offsetToSeconds
                }
            }
        }
        return if (latestTime != null) latestOffset else earliestOffset
    }
}

@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
internal class Observance(
    val start: LocalDateTime,
    /** Offset before the transition, in seconds. */
    val offsetFromSeconds: Int,
    /** Offset after the transition, in seconds. */
    val offsetToSeconds: Int,
    val rule: RepeatRule?,
    /** Explicit `RDATE` transitions. */
    val rDates: List<LocalDateTime> = emptyList(),
) {
    /**
     * Transitions generated so far, with the year their generation covers. One imported file
     * resolves hundreds of values against the same zone, and an observance anchored in 1970 would
     * otherwise rebuild every year of its rule for every single value. The list is immutable and
     * published by reference swap, so lookups running on another thread never iterate a list that
     * a concurrent query is still growing.
     */
    private val transitions = AtomicReference(start.year - 1 to (listOf(start) + rDates))

    /** Every transition of this observance up to and including the year [year] + 1. */
    fun transitionsThrough(year: Int): List<LocalDateTime> {
        val repeat = rule ?: return transitions.load().second
        // Rules without UNTIL or COUNT are open ended, so generation stops at a horizon far beyond
        // any calendar the app schedules; later values keep the offset of the last known transition.
        val targetYear = (year + 1).coerceAtMost(MaxTransitionYear)
        while (true) {
            val snapshot = transitions.load()
            if (snapshot.first >= targetYear) return snapshot.second
            val regenerated = generateTransitions(repeat, targetYear)
            if (transitions.compareAndSet(snapshot, targetYear to regenerated)) return regenerated
        }
    }

    /** Rebuilds every transition from the anchor up to [targetYear]; races just redo this work. */
    private fun generateTransitions(repeat: RepeatRule, targetYear: Int): List<LocalDateTime> {
        val anchor = start.date
        val out = ArrayList(listOf(start) + rDates)
        var candidateYear = anchor.year
        while (candidateYear <= targetYear) {
            val candidates = when (repeat.frequency) {
                RepeatFrequency.Yearly -> yearCandidates(repeat, anchor, candidateYear, withSetPos = true)
                RepeatFrequency.Monthly -> (1..12).flatMap { month ->
                    monthCandidates(repeat, anchor, candidateYear, month)
                }
                else -> emptyList()
            }
            out += candidates.filter { date ->
                date >= anchor && withinUntil(date) && withinCount(repeat, anchor, date)
            }.map { LocalDateTime(it, start.time) }
            candidateYear++
        }
        return out
    }

    private fun withinUntil(date: LocalDate): Boolean {
        val until = rule?.until ?: return true
        val local = LocalDateTime(date, start.time)
        return when (until) {
            is IcsTimeValue.Date -> date <= until.date
            is IcsTimeValue.Floating -> local <= until.dateTime
            is IcsTimeValue.Zoned -> local <= until.dateTime
            is IcsTimeValue.Utc ->
                local.toInstant(TimeZone.UTC).minusSeconds(offsetFromSeconds) <= until.instant
        }
    }

    private fun withinCount(repeat: RepeatRule, anchor: LocalDate, date: LocalDate): Boolean {
        val count = repeat.count ?: return true
        val ordinal = occurrenceOrdinal(repeat, anchor, date, count) ?: return false
        return ordinal <= count
    }
}

internal fun parseCustomZone(component: IcsComponent): CustomZone? {
    val id = component.first("TZID")?.value?.trim()?.removePrefix("/") ?: return null
    val observances = component.children.mapNotNull { child ->
        if (child.name != "STANDARD" && child.name != "DAYLIGHT") return@mapNotNull null
        val startLine = child.first("DTSTART") ?: return@mapNotNull null
        val start = parseTime(startLine, emptyMap(), mutableSetOf()) as? IcsTime.DateTime ?: return@mapNotNull null
        val from = child.first("TZOFFSETFROM")?.value?.let(::parseUtcOffset) ?: 0
        val to = child.first("TZOFFSETTO")?.value?.let(::parseUtcOffset) ?: from
        val rule = child.first("RRULE")?.value?.let { parseRule(it, emptyMap(), mutableSetOf()) }
        val rDates = child.all("RDATE").flatMap { line ->
            splitList(line.value).mapNotNull { value ->
                parseTime(
                    ContentLine("RDATE", line.params, value, line.raw),
                    emptyMap(),
                    mutableSetOf(),
                ) as? IcsTime.DateTime
            }
        }.map { it.value }
        Observance(start.value, from, to, rule, rDates)
    }
    if (observances.isEmpty()) return null
    return CustomZone(id, observances)
}

/** `TZOFFSETFROM`/`TZOFFSETTO` in seconds; RFC 5545 also allows an optional seconds part. */
private fun parseUtcOffset(value: String): Int? {
    val text = value.trim()
    if (text.isEmpty()) return null
    val sign = if (text.startsWith('-')) -1 else 1
    val digits = text.removePrefix("+").removePrefix("-").filter { it.isDigit() }
    if (digits.length < 4) return null
    val hours = digits.substring(0, 2).toIntOrNull() ?: return null
    val minutes = digits.substring(2, 4).toIntOrNull() ?: return null
    val seconds = if (digits.length >= 6) digits.substring(4, 6).toIntOrNull() ?: 0 else 0
    if (minutes !in 0..59 || seconds !in 0..59) return null
    return sign * (hours * 3600 + minutes * 60 + seconds)
}
