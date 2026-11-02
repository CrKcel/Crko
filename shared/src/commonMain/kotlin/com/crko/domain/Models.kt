package com.crko.domain

import com.crko.storage.AppAttachmentStore
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

enum class RepeatFrequency { Daily, Weekly, Monthly, Yearly }

/** `RECURRENCE-ID;RANGE=…`; only `THISANDFUTURE` belongs to RFC 5545, `THISANDPRIOR` is a legacy extension. */
@Serializable
enum class RecurrenceRange(val wireName: String) {
    ThisAndFuture("THISANDFUTURE"),
    ThisAndPrior("THISANDPRIOR");

    companion object {
        fun parse(value: String?): RecurrenceRange? =
            value?.trim()?.uppercase()?.let { raw -> entries.firstOrNull { it.wireName == raw } }
    }
}

@Serializable
enum class EventStatus { Confirmed, Tentative, Cancelled }

@Serializable
enum class EventTransparency { Opaque, Transparent }

@Serializable
enum class EventClass { Public, Private, Confidential }

/** `BYDAY` value with an ordinal, e.g. `1MO` (first Monday) or `-1FR` (last Friday). */
@Serializable
data class OrdinalWeekday(val ordinal: Int, val weekday: Int)

/** Local storage bookkeeping. It never belongs to the RFC component itself. */
@Serializable
data class LocalRecordMeta(
    /** Name of the file or subscription this record was imported from; local records have none. */
    val importSource: String? = null,
    /** Line of `BEGIN:…` in the imported file, for pointing feedback at the record. */
    val sourceLine: Int = 0,
)

/**
 * Fields an RFC 5545 component shares regardless of its kind. `Event`, `Todo` and `Journal` all
 * implement it, so import and export code can handle `UID`, `DTSTAMP`, text properties, attachments
 * and unknown lines once instead of three times.
 */
interface CalendarComponent {
    val icsUid: String?
    val title: String
    val note: String
    val url: String
    val categories: List<String>
    val attachments: List<Attachment>
    val created: String?
    val lastModified: String?
    val dtStamp: String?
    val sequence: Int
    val extraProperties: List<String>
    val local: LocalRecordMeta

    val importSource: String? get() = local.importSource
    val sourceLine: Int get() = local.sourceLine
}

/**
 * One `ORGANIZER`/`ATTENDEE` cal-address with the scheduling parameters an invitation round trip
 * depends on. Parameters this model does not interpret stay in [parameters], so editing an attendee
 * never drops them.
 */
@Serializable
data class CalAddress(
    /** The address after the colon, e.g. `mailto:jane@example.com`. */
    val calAddress: String = "",
    /** `CN` parameter. */
    val cn: String? = null,
    /** `ROLE` parameter, e.g. `REQ-PARTICIPANT`. */
    val role: String? = null,
    /** `PARTSTAT` parameter. */
    val partStat: IcsPartStat? = null,
    /** `CUTYPE` parameter; `INDIVIDUAL` by default. */
    val cutype: String? = null,
    /** `RSVP` parameter; null when the line does not say. */
    val rsvp: Boolean? = null,
    /** `DELEGATED-FROM` addresses. */
    val delegatedFrom: List<String> = emptyList(),
    /** `DELEGATED-TO` addresses. */
    val delegatedTo: List<String> = emptyList(),
    /** `SENT-BY` address of a secretary acting for the attendee. */
    val sentBy: String? = null,
    /** Parameters other than the interpreted ones, kept verbatim. */
    val parameters: Map<String, String> = emptyMap(),
) {
    /** Name shown to the user: `CN` when present, otherwise the bare address. */
    val displayName: String
        get() = cn?.takeIf { it.isNotBlank() } ?: calAddress.substringAfterLast(':').substringBefore('?')

    /** Builds the content line for this address, quoting parameter values that need it. */
    fun toLine(name: String = "ATTENDEE"): String = buildString {
        append(name)
        cn?.takeIf { it.isNotBlank() }?.let { append(";CN=").append(icsParameterValue(it)) }
        role?.takeIf { it.isNotBlank() }?.let { append(";ROLE=").append(it) }
        cutype?.takeIf { it.isNotBlank() }?.let { append(";CUTYPE=").append(it) }
        partStat?.let { append(";PARTSTAT=").append(it.wireName) }
        rsvp?.let { append(";RSVP=").append(if (it) "TRUE" else "FALSE") }
        sentBy?.let { append(";SENT-BY=\"").append(it).append('"') }
        delegatedFrom.forEach { append(";DELEGATED-FROM=\"").append(it).append('"') }
        delegatedTo.forEach { append(";DELEGATED-TO=\"").append(it).append('"') }
        parameters.forEach { (parameter, value) ->
            if (parameter.trim().uppercase() !in KnownCalAddressParameters) {
                append(';').append(parameter).append('=').append(icsParameterValue(value))
            }
        }
        append(':').append(calAddress)
    }

    companion object {
        /** Parses an original `ORGANIZER`/`ATTENDEE` content line; null when it is not usable. */
        fun parse(raw: String, name: String = "ATTENDEE"): CalAddress? {
            val line = parseContentLine(UnfoldedLine(raw, 0)) ?: return null
            if (line.name != name || line.value.isBlank()) return null
            return fromContentLine(line)
        }

        internal fun fromContentLine(line: ContentLine): CalAddress = CalAddress(
            calAddress = line.value.trim(),
            cn = line.params["CN"]?.takeIf { it.isNotBlank() },
            role = line.params["ROLE"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            partStat = IcsPartStat.parse(line.params["PARTSTAT"]),
            cutype = line.params["CUTYPE"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
            rsvp = when (line.params["RSVP"]?.trim()?.uppercase()) {
                "TRUE" -> true
                "FALSE" -> false
                else -> null
            },
            delegatedFrom = addressList(line.params["DELEGATED-FROM"]),
            delegatedTo = addressList(line.params["DELEGATED-TO"]),
            sentBy = line.params["SENT-BY"]?.takeIf { it.isNotBlank() },
            parameters = line.params.filterKeys { it !in KnownCalAddressParameters },
        )

        private fun addressList(value: String?): List<String> =
            value.orEmpty().split(',').map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
    }
}

private val KnownCalAddressParameters = setOf(
    "CN", "ROLE", "PARTSTAT", "CUTYPE", "RSVP", "DELEGATED-FROM", "DELEGATED-TO", "SENT-BY",
)

/** Relation a `RELATED-TO` property names; RFC 5545 calls the default `PARENT`. */
@Serializable
data class RelatedTo(
    val uid: String,
    /** `RELTYPE`, uppercase; unknown values survive a round trip. */
    val reltype: String = "PARENT",
) {
    val isParent: Boolean get() = reltype.equals("PARENT", ignoreCase = true)

    companion object {
        fun parent(uid: String): RelatedTo = RelatedTo(uid, "PARENT")
    }
}

/** `RELATED` parameter of a relative `VALARM` trigger. */
@Serializable
enum class AlarmRelated(val wireName: String) {
    Start("START"),
    End("END");

    companion object {
        fun parse(value: String?): AlarmRelated =
            if (value.equals("END", ignoreCase = true)) End else Start
    }
}

/** RFC 5545 `TRIGGER`: a duration relative to the component, or an absolute date-time. */
@Serializable
sealed interface AlarmTrigger {
    @Serializable
    @SerialName("relative")
    data class Relative(
        /** Negative values fire before the related time, as RFC 5545 writes them. */
        @Serializable(with = DurationSecondsSerializer::class)
        val duration: Duration,
        val related: AlarmRelated = AlarmRelated.Start,
    ) : AlarmTrigger

    @Serializable
    @SerialName("absolute")
    data class Absolute(val value: IcsTimeValue) : AlarmTrigger
}

@Serializable
data class Alarm(
    /** `DISPLAY`, `AUDIO` or `EMAIL`; kept as text so unknown actions survive a round trip. */
    val action: String = "DISPLAY",
    val trigger: AlarmTrigger = AlarmTrigger.Relative((-15).minutes),
    /** `REPEAT`: how many extra times the alarm fires. */
    val repeat: Int? = null,
    /** `DURATION`: the spacing between repeated firings. */
    @Serializable(with = DurationSecondsSerializer::class)
    val duration: Duration? = null,
    val description: String = "",
    /** Unknown VALARM content lines kept verbatim. */
    val extraProperties: List<String> = emptyList(),
)

/** Minutes of a relative trigger with the pre-stage-4 sign: positive means before. */
val Alarm.triggerMinutes: Int?
    get() = (trigger as? AlarmTrigger.Relative)?.duration?.let { (-it).inWholeMinutes.toInt() }

val Alarm.relatedToEnd: Boolean
    get() = (trigger as? AlarmTrigger.Relative)?.related == AlarmRelated.End

val Alarm.absoluteTrigger: IcsTimeValue?
    get() = (trigger as? AlarmTrigger.Absolute)?.value

/**
 * An `ATTACH` value: the property value plus its parameters. URIs can be opened from the editor;
 * binary payloads are kept in the attachment store and referenced by [storedFile], so the
 * serialized calendar never carries megabytes of base64 text that every import would accumulate.
 */
@Serializable
data class Attachment(
    /** Property value: the URI, or the inline base64 payload of a binary attachment. */
    val value: String = "",
    /** Every parameter of the content line, including unknown ones. */
    val parameters: Map<String, String> = emptyMap(),
    /** `ENCODING=BASE64` payload; it has no openable URI. */
    val binary: Boolean = false,
    /** Name of the file in the attachment store holding the payload of a binary attachment. */
    val storedFile: String? = null,
) {
    val uri: String get() = if (binary) "" else value

    /** Inline base64 payload; empty once [storedFile] takes over. */
    val data: String get() = if (binary && storedFile == null) value else ""

    /** `FMTTYPE` parameter. */
    val mimeType: String get() = parameter("FMTTYPE").orEmpty()

    /** `FILENAME` parameter or the last URI segment. */
    val name: String
        get() = parameter("FILENAME")?.takeIf { it.isNotBlank() }
            ?: uri.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            .orEmpty()

    fun parameter(name: String): String? = parameters[name.uppercase()]?.takeIf { it.isNotBlank() }
}

/**
 * Decoded bytes of an embedded `ATTACH`. Exporters disagree about padding and about the alphabet,
 * so both are repaired before decoding instead of leaving the payload unreadable.
 */
@OptIn(ExperimentalEncodingApi::class)
fun Attachment.decodedBytes(): ByteArray? {
    val text = data.filterNot { it.isWhitespace() }.replace('-', '+').replace('_', '/')
    val padded = when (text.length % 4) {
        0 -> text
        2, 3 -> text + "=".repeat(4 - text.length % 4)
        else -> return null
    }
    return runCatching { Base64.decode(padded) }.getOrNull()
}

/**
 * Bytes of [this] attachment wherever they live: the attachment-store file for an imported payload,
 * the inline base64 for one that has not been materialized yet. Null when neither is readable.
 */
fun Attachment.attachmentBytes(): ByteArray? {
    val file = storedFile?.takeIf { it.isNotBlank() }
    if (file != null && data.isBlank()) return AppAttachmentStore.read(file)
    if (data.isNotBlank()) return decodedBytes()
    return null
}

/** Characters a file name must never contain when it is handed to the platform file picker. */
private const val ReservedFileNameCharacters = "\\/:*?\"<>|"

/** Longest attachment name offered to the picker, so a pathological `FILENAME` stays usable. */
private const val MaxAttachmentNameChars = 80

/**
 * Safe destination name for [this] attachment. `FILENAME` and URI segments come straight out of an
 * imported file, so they have to lose path separators, parent references and control characters
 * before they may be joined to a directory the app does not control.
 */
fun safeAttachmentFileName(attachment: Attachment): String {
    val candidate = listOf(attachment.name, attachment.uri)
        .asSequence()
        .map { it.replace('\\', '/').substringAfterLast('/').substringBefore('?').substringBefore('#') }
        .map(::cleanFileNamePart)
        .firstOrNull { it.isNotEmpty() }
        ?: ""
    if (candidate.isEmpty()) return "attachment${attachmentExtension(attachment)}"
    return if ('.' in candidate) candidate else candidate + attachmentExtension(attachment)
}

/**
 * One path segment without separators, control characters or leading dots, so neither `../` nor an
 * absolute `FILENAME` can escape the folder the user picked.
 */
private fun cleanFileNamePart(value: String): String {
    val replaced = buildString(value.length) {
        value.forEach { ch ->
            val reserved = ch in ReservedFileNameCharacters || ch.code < 0x20 || ch.code == 0x7F
            append(if (reserved) ' ' else ch)
        }
    }
    return replaced
        .split(' ')
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .trimStart('.')
        .take(MaxAttachmentNameChars)
        .trimStart('.', ' ')
}

private fun attachmentExtension(attachment: Attachment): String = mimeTypeExtension(
    attachment.mimeType.substringBefore(';').trim().lowercase(),
    fallback = if (attachment.binary) ".bin" else "",
)

/** Extension for a MIME type, or [fallback] when one is not known. */
private fun mimeTypeExtension(type: String, fallback: String): String = when (type) {
    "image/png" -> ".png"
    "image/jpeg", "image/jpg" -> ".jpg"
    "image/gif" -> ".gif"
    "image/webp" -> ".webp"
    "image/heic" -> ".heic"
    "application/pdf" -> ".pdf"
    "text/plain", "text/csv" -> ".txt"
    "application/json" -> ".json"
    "audio/mpeg" -> ".mp3"
    "video/mp4" -> ".mp4"
    else -> fallback
}

private val extensionMimeTypes = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "heic" to "image/heic",
    "pdf" to "application/pdf",
    "txt" to "text/plain",
    "csv" to "text/csv",
    "json" to "application/json",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
    "ics" to "text/calendar",
)

/**
 * MIME type for saving [this] attachment: `FMTTYPE` when the file declares one, else the type its
 * file name extension implies, else a generic binary type. The platform picker builds its document
 * from this value, so a payload should not be forced to `application/octet-stream` when its type is
 * known.
 */
fun attachmentMimeType(attachment: Attachment): String {
    val declared = attachment.mimeType.substringBefore(';').trim().lowercase()
    if (declared.isNotEmpty()) return declared
    val name = safeAttachmentFileName(attachment)
    val extension = name.substringAfterLast('.', "").lowercase()
    return extensionMimeTypes[extension] ?: "application/octet-stream"
}

/**
 * RFC 5545 `RRULE`. `RDATE`/`EXDATE` belong to [Recurrence], so this type describes the rule alone.
 * [raw] keeps the original property text when a part of it cannot be represented exactly (an
 * unsupported `FREQ`, an ordinal `BYDAY` on a daily/weekly rule, an unknown part, ...), so opening
 * and saving the event does not silently drop what the importer could not model.
 */
@Serializable
data class RepeatRule(
    val frequency: RepeatFrequency,
    val interval: Int = 1,
    /** Number of occurrences including the first one. */
    val count: Int? = null,
    /** Inclusive last occurrence, in the value type the rule declares. */
    val until: IcsTimeValue? = null,
    /** `BYDAY` values; an ordinal of 0 is a plain weekday, e.g. `MO`. */
    val byDay: List<OrdinalWeekday> = emptyList(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val bySetPos: List<Int> = emptyList(),
    val byYearDay: List<Int> = emptyList(),
    val byWeekNo: List<Int> = emptyList(),
    /** Monday-first weekday index of `WKST`. */
    val weekStart: Int? = null,
    /** `BYHOUR`, in hours of the day. */
    val byHour: List<Int> = emptyList(),
    /** `BYMINUTE`, in minutes of the hour. */
    val byMinute: List<Int> = emptyList(),
    /** `BYSECOND`, in seconds of the minute. Kept for round trips; occurrences are day-granular. */
    val bySecond: List<Int> = emptyList(),
    /** Original `RRULE` text when [frequency]-agnostic parts of it could not be represented. */
    val raw: String? = null,
)

/** One `RDATE` value. A period keeps the length of that particular instance. */
@Serializable
data class RecurrenceDate(
    val value: IcsTimeValue,
    @Serializable(with = DurationSecondsSerializer::class)
    val duration: Duration? = null,
)

/**
 * The recurrence set of a component: the `RRULE`, the additional `RDATE` instances and the excluded
 * `EXDATE` instances. A component without a rule can still add or exclude single instances.
 */
@Serializable
data class Recurrence(
    val rule: RepeatRule? = null,
    val rdates: List<RecurrenceDate> = emptyList(),
    val exdates: List<IcsTimeValue> = emptyList(),
    /** Original `RRULE` text when even the frequency could not be represented. */
    val rawRule: String? = null,
)

@Serializable
data class Event(
    /** Null for events that have not been saved yet. */
    val id: String?,
    override val title: String,
    /** RFC 5545 `DTSTART`; the date of the first occurrence and the anchor of a repeating event. */
    val start: IcsTimeValue,
    /** RFC 5545 `DTEND`, exclusive. Null means a zero-length timed event. */
    val end: IcsTimeValue? = null,
    override val note: String = "",
    val location: String = "",
    override val categories: List<String> = emptyList(),
    override val url: String = "",
    /** Raw `GEO` value, e.g. `37.386013;-122.082932`. */
    val geo: String? = null,
    override val attachments: List<Attachment> = emptyList(),
    val status: EventStatus = EventStatus.Confirmed,
    val transparency: EventTransparency = EventTransparency.Opaque,
    val eventClass: EventClass? = null,
    /** `PRIORITY`, 0 (undefined) through 9 (lowest). */
    val priority: Int? = null,
    val organizer: CalAddress? = null,
    val attendees: List<CalAddress> = emptyList(),
    val alarms: List<Alarm> = emptyList(),
    /** Null for a one-off event; carries `RRULE`, `RDATE` and `EXDATE` together. */
    val recurrence: Recurrence? = null,
    /**
     * UID from an imported ICS file. The master and its exceptions share it; keeps re-importing the
     * same file from duplicating events.
     */
    override val icsUid: String? = null,
    /** Original occurrence slot of an exception (`RECURRENCE-ID`), in the master's value type. */
    val recurrenceId: IcsTimeValue? = null,
    /** `RANGE` parameter of [recurrenceId]; null for an ordinary single-instance exception. */
    val recurrenceRange: RecurrenceRange? = null,
    /** ISO-8601 instant. */
    override val created: String? = null,
    /** ISO-8601 instant. */
    override val lastModified: String? = null,
    /** Original `DTSTAMP`; picks the newest copy when the same UID is imported again. */
    override val dtStamp: String? = null,
    override val sequence: Int = 0,
    /** Unknown content lines kept verbatim so a round trip does not drop them. */
    override val extraProperties: List<String> = emptyList(),
    /** Storage provenance, separate from the RFC properties. */
    override val local: LocalRecordMeta = LocalRecordMeta(),
) : CalendarComponent {
    /** Display start date of the anchor occurrence in the device zone. */
    val day: LocalDate get() = start.displayDate(TimeZone.currentSystemDefault())

    /** Display date the event ends on, matching the pre-migration inclusive end-date semantics. */
    val endDay: LocalDate get() = displayEndDay(TimeZone.currentSystemDefault())

    /** Display date of the last day on which the event actually occupies time. */
    val lastOccupiedDay: LocalDate get() = displayLastOccupiedDay(TimeZone.currentSystemDefault())

    /** Wall-clock start date in the event's own zone, the anchor the recurrence rule expands. */
    val wallDay: LocalDate get() = start.wallDate

    /** Stable UID used for series/exception relationships and exports. */
    val uid: String? get() = icsUid?.takeIf { it.isNotBlank() } ?: id?.let { "$it@crko" }

    /** `RRULE` of the recurrence set, for call sites that do not need the added/excluded instances. */
    val repeat: RepeatRule? get() = recurrence?.rule

    /** Display dates the series skips, projected from the typed `EXDATE` values. */
    val excludedDates: List<String>
        get() = recurrence?.exdates
            ?.map { it.displayDate(TimeZone.currentSystemDefault()).toString() }
            .orEmpty()

    /** The exception was imported from `RECURRENCE-ID;RANGE=THISANDFUTURE`. */
    val thisAndFuture: Boolean get() = recurrenceRange == RecurrenceRange.ThisAndFuture

    /*
     * Read-only views of the pre-migration fields. Nothing here is stored: each value is projected
     * from [start]/[end] on demand, so display code keeps working while the RFC values stay the
     * single source of truth.
     */

    val allDay: Boolean get() = start is IcsTimeValue.Date

    val date: String get() = day.toString()

    val endDate: String? get() = endDay.takeIf { it != day }?.toString()

    val startMinutes: Int
        get() = if (allDay) 0 else localStart().time.let { it.hour * 60 + it.minute }

    val endMinutes: Int
        get() {
            if (allDay) return 0
            val local = localEnd() ?: return startMinutes
            return local.time.hour * 60 + local.time.minute
        }

    val startSeconds: Int get() = if (allDay) 0 else localStart().second

    val endSeconds: Int get() = if (allDay) 0 else localEnd()?.second ?: startSeconds

    /** `TZID` of the start, `UTC` for a UTC value, null for floating/date values. */
    val timeZoneId: String?
        get() = when (val value = start) {
            is IcsTimeValue.Zoned -> value.tzid
            is IcsTimeValue.Utc -> "UTC"
            else -> null
        }

    /** Original ICS local start text (compact form, `Z` for UTC), for legacy call sites. */
    val sourceStart: String? get() = legacySourceText(start)

    /** Original ICS local end text (compact form, `Z` for UTC), for legacy call sites. */
    val sourceEnd: String? get() = legacySourceText(end)

    /**
     * Rule-generated occurrence days of a `COUNT` series, cached with the rule instance they were
     * built from, so a cell query answers by lookup instead of walking the periods from the anchor.
     * Null when the rule has no indexable count.
     */
    internal val countOccurrenceIndex: Pair<RepeatRule, LongArray>? by lazy(LazyThreadSafetyMode.NONE) {
        val rule = repeat ?: return@lazy null
        val index = buildCountIndex(rule, wallDay) ?: return@lazy null
        rule to index
    }

    /**
     * Pre-migration constructor kept for fixtures and the legacy decoder. It converts the former
     * device-local fields into an RFC time value; new code must pass [start]/[end] instead.
     */
    constructor(
        id: String?,
        title: String,
        date: String,
        startMinutes: Int,
        endMinutes: Int,
        startSeconds: Int = 0,
        endSeconds: Int = 0,
        note: String = "",
        endDate: String? = null,
        allDay: Boolean = false,
        location: String = "",
        categories: List<String> = emptyList(),
        url: String = "",
        geo: String? = null,
        attachments: List<Attachment> = emptyList(),
        status: EventStatus = EventStatus.Confirmed,
        transparency: EventTransparency = EventTransparency.Opaque,
        eventClass: EventClass? = null,
        priority: Int? = null,
        organizer: String? = null,
        attendees: List<String> = emptyList(),
        alarms: List<Alarm> = emptyList(),
        repeat: RepeatRule? = null,
        excludedDates: List<String> = emptyList(),
        icsUid: String? = null,
        seriesUid: String? = null,
        recurrenceId: String? = null,
        thisAndFuture: Boolean = false,
        sourceStart: String? = null,
        sourceEnd: String? = null,
        timeZoneId: String? = null,
        created: String? = null,
        lastModified: String? = null,
        dtStamp: String? = null,
        sequence: Int = 0,
        extraProperties: List<String> = emptyList(),
        organizerRaw: String? = null,
        attendeeRaw: List<String> = emptyList(),
        importSource: String? = null,
        sourceLine: Int = 0,
    ) : this(
        id = id,
        title = title,
        start = legacyIcsTimeValue(
            date = date,
            minutes = startMinutes,
            seconds = startSeconds,
            allDay = allDay,
            source = sourceStart,
            tzid = timeZoneId,
            definition = null,
        ),
        end = legacyEndValue(
            date = date,
            endDate = endDate,
            startMinutes = startMinutes,
            endMinutes = endMinutes,
            startSeconds = startSeconds,
            endSeconds = endSeconds,
            allDay = allDay,
            startSource = sourceStart,
            endSource = sourceEnd,
            tzid = timeZoneId,
            definition = null,
        ),
        note = note,
        location = location,
        categories = categories,
        url = url,
        geo = geo,
        attachments = attachments,
        status = status,
        transparency = transparency,
        eventClass = eventClass,
        priority = priority,
        organizer = legacyCalAddress(organizerRaw, organizer),
        attendees = legacyCalAddresses(attendeeRaw, attendees),
        alarms = alarms,
        recurrence = legacyRecurrence(repeat, excludedDates),
        // An old exception stored its series UID separately; the RFC identity is the shared UID.
        icsUid = seriesUid ?: stripSyntheticSeriesUid(icsUid),
        recurrenceId = recurrenceId?.let { text ->
            parseStoredDate(text)?.let(IcsTimeValue::Date)
        },
        recurrenceRange = if (thisAndFuture) RecurrenceRange.ThisAndFuture else null,
        created = created,
        lastModified = lastModified,
        dtStamp = dtStamp,
        sequence = sequence,
        extraProperties = extraProperties,
        local = LocalRecordMeta(importSource, sourceLine),
    )
}

/** Old payloads stored an organizer as a bare value and, separately, its original line. */
internal fun legacyCalAddress(raw: String?, value: String?): CalAddress? =
    raw?.takeIf { it.isNotBlank() }?.let { CalAddress.parse(it, "ORGANIZER") }
        ?: value?.takeIf { it.isNotBlank() }?.let(::CalAddress)

/** Old payloads stored attendees as bare values and, separately, their original lines. */
internal fun legacyCalAddresses(raw: List<String>, values: List<String>): List<CalAddress> =
    raw.mapNotNull { CalAddress.parse(it, "ATTENDEE") }.ifEmpty {
        values.filter { it.isNotBlank() }.map(::CalAddress)
    }

/** RFC 5545: a timed event without `DTEND`/`DURATION` has zero length rather than one hour. */
val Event.isZeroDuration: Boolean
    get() = end == null

/**
 * Latest minute of day an event may end at (23:59). Midnight endings are represented by the next
 * day at 00:00 so the editor's LocalTime picker never sees an hour of 24.
 */
const val MaxEndMinutes = 23 * 60 + 59

/** End of the day, used when clipping multi-day records to one day. */
const val EndOfDayMinutes = 24 * 60

enum class TodoPriority { High, Normal, Low }

@Serializable
enum class TodoStatus { NeedsAction, InProcess, Completed, Cancelled }

/**
 * RFC 5545 `VTODO`. [done] is derived from [status], which is the single source of truth for
 * completion; [priority] keeps the original `PRIORITY` integer (0–9).
 */
@Serializable
data class Todo(
    val id: String,
    override val title: String,
    /** RFC 5545 `DTSTART`. */
    val start: IcsTimeValue? = null,
    /** RFC 5545 `DUE`. */
    val due: IcsTimeValue? = null,
    /** RFC 5545 `COMPLETED`, a UTC instant. */
    @Serializable(with = InstantIso8601Serializer::class)
    val completed: Instant? = null,
    /** `PRIORITY`, 0 (undefined) through 9 (lowest); null when the source omitted it. */
    val priority: Int? = null,
    /** Creation time in epoch milliseconds; used only for ordering. */
    val createdAt: Long = 0L,
    override val note: String = "",
    val location: String = "",
    override val categories: List<String> = emptyList(),
    override val url: String = "",
    override val attachments: List<Attachment> = emptyList(),
    /** `RELATED-TO` links, including their `RELTYPE`. */
    val relatedTo: List<RelatedTo> = emptyList(),
    override val icsUid: String? = null,
    /** RFC 5545 `CREATED`. */
    override val created: String? = null,
    override val lastModified: String? = null,
    override val dtStamp: String? = null,
    override val sequence: Int = 0,
    override val extraProperties: List<String> = emptyList(),
    /** `STATUS`; older saved data defaults to [TodoStatus.NeedsAction]. */
    val status: TodoStatus = TodoStatus.NeedsAction,
    /** `PERCENT-COMPLETE`, 0..100. */
    val percentComplete: Int = 0,
    val alarms: List<Alarm> = emptyList(),
    /** Recurrence of the series an imported `RRULE` describes; null for a one-off todo. */
    val repeat: RepeatRule? = null,
    /** Additional `RDATE` instances of the series, keeping their own time and length. */
    val rDates: List<RecurrenceDate> = emptyList(),
    /** Occurrence dates the series skips, from `EXDATE`. */
    val excludedDates: List<String> = emptyList(),
    /** Storage provenance, separate from the RFC properties. */
    override val local: LocalRecordMeta = LocalRecordMeta(),
) : CalendarComponent {
    /** Completion state derived from [status]; a cancelled todo counts as done like before. */
    val done: Boolean
        get() = status == TodoStatus.Completed || status == TodoStatus.Cancelled

    /** Display date of [completed] in the device zone, for the completion statistics. */
    val completedAt: String?
        get() = completed?.toLocalDateTime(TimeZone.currentSystemDefault())?.date?.toString()

    val startDay: LocalDate?
        get() = start?.displayDate(TimeZone.currentSystemDefault())

    val dueDay: LocalDate?
        get() = due?.displayDate(TimeZone.currentSystemDefault())

    /** UI grouping of the original `PRIORITY` integer. */
    val priorityLevel: TodoPriority
        get() = when (priority) {
            in 1..4 -> TodoPriority.High
            in 8..9 -> TodoPriority.Low
            else -> TodoPriority.Normal
        }
}

/** `PRIORITY` value an editor's coarse priority choice writes. */
fun TodoPriority.toIcsPriority(): Int = when (this) {
    TodoPriority.High -> 1
    TodoPriority.Normal -> 5
    TodoPriority.Low -> 9
}

/** Sort rank of a raw `PRIORITY`: lower numbers mean more urgent; unset sorts last. */
fun todoPriorityRank(priority: Int?): Int = when (priority) {
    null, 0 -> 3
    in 1..4 -> 0
    in 5..7 -> 1
    else -> 2
}

@Serializable
enum class JournalStatus(val wireName: String) {
    Draft("DRAFT"),
    Final("FINAL"),
    Cancelled("CANCELLED");

    companion object {
        fun parse(value: String?): JournalStatus? =
            value?.trim()?.uppercase()?.let { raw -> entries.firstOrNull { it.wireName == raw } }
    }
}

/** A `VJOURNAL` entry. Parsed and exported so a calendar round trip does not drop journals. */
@Serializable
data class Journal(
    val id: String,
    override val title: String = "",
    override val note: String = "",
    /** RFC 5545 `DTSTART`; a date-only value is the common case. */
    val start: IcsTimeValue? = null,
    override val url: String = "",
    override val categories: List<String> = emptyList(),
    override val attachments: List<Attachment> = emptyList(),
    override val created: String? = null,
    override val lastModified: String? = null,
    override val dtStamp: String? = null,
    override val sequence: Int = 0,
    override val icsUid: String? = null,
    override val extraProperties: List<String> = emptyList(),
    /** `RRULE`, `RDATE` and `EXDATE` of a repeating journal. */
    val recurrence: Recurrence? = null,
    val status: JournalStatus? = null,
    val eventClass: EventClass? = null,
    /** Storage provenance, separate from the RFC properties. */
    override val local: LocalRecordMeta = LocalRecordMeta(),
) : CalendarComponent {
    /** Display date of [start] in the device zone; null for an undated journal. */
    val date: String? get() = start?.displayDate(TimeZone.currentSystemDefault())?.toString()

    /** ISO-8601 instant of a timed [start], for display code that expects one. */
    val startTime: String?
        get() = start?.instantOrNull(TimeZone.currentSystemDefault())?.toString()
}

/** Current persisted layout; stage 4 moved component metadata, participants, alarms and attachments. */
const val CurrentSchemaVersion = 5

/**
 * A raw `VTIMEZONE` definition for a `TZID` the platform does not know. Known IANA zones are stored
 * only as the `TZID` inside [IcsTimeValue.Zoned], so this list stays small and deduplicated.
 */
@Serializable
data class CalendarTimeZone(
    val tzid: String,
    val definition: String,
)

/** Properties of the calendar container, independent of any one event or todo. */
@Serializable
data class CalendarMetadata(
    /** `X-WR-CALNAME`. */
    val name: String? = null,
    /** `REFRESH-INTERVAL` / `X-PUBLISHED-TTL`, in minutes. */
    val refreshMinutes: Int? = null,
    /** iTIP `METHOD` of the last imported message, as its wire name. */
    val method: String? = null,
    /** Raw definitions for `TZID` values the platform cannot resolve. */
    val timeZones: List<CalendarTimeZone> = emptyList(),
)

@Serializable
data class AppState(
    val events: List<Event> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val journals: List<Journal> = emptyList(),
    /** Subscribed remote calendars; a sync merges their records into the lists above. */
    val subscriptions: List<CalendarSubscription> = emptyList(),
    /** Busy periods imported from `VFREEBUSY` components, kept for free/busy queries. */
    val busyPeriods: List<BusyInterval> = emptyList(),
    /** Calendar-level properties and unknown `VTIMEZONE` definitions. */
    val calendar: CalendarMetadata = CalendarMetadata(),
    /** Layout version of this payload; older payloads are migrated on read. */
    val schemaVersion: Int = CurrentSchemaVersion,
)

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class PersistedSettings(
    val language: String = "",
    val darkMode: String = "",
    val palette: String = "",
    val keyColor: Long = 0xFF3B6CF5,
    val agendaView: String = "",
    val agendaScrollHour: Int = -1,
)

/** Whether this event happens on [date] in [zone]; repeating events cover every matching date. */
fun Event.occursOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean {
    // A cancelled exception is stored so its SEQUENCE survives, but it occupies no time.
    if (status == EventStatus.Cancelled) return false
    val recurrence = recurrence
    if (recurrence == null || (recurrence.rule == null && recurrence.rdates.isEmpty())) {
        return date >= day && date <= lastOccupiedDay
    }
    return occurrenceStartOn(date, zone) != null
}

/** Display date of the occurrence whose span covers [date], if any. */
fun Event.occurrenceStartOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): LocalDate? {
    if (status == EventStatus.Cancelled) return null
    val anchor = day
    val recurrence = recurrence
    if (recurrence == null || (recurrence.rule == null && recurrence.rdates.isEmpty())) {
        return if (date >= anchor && date <= lastOccupiedDay) anchor else null
    }
    val span = anchor.daysTo(lastOccupiedDay).coerceAtLeast(0)
    // A zone projection can move an occurrence by one day, so the window has one extra day of
    // slack; an RDATE may also name an instance before the anchor.
    for (offset in 0..span + 1) {
        val candidate = date.plusDays(-offset)
        val wall = wallDateFor(candidate, zone)
        if (!isOccurrenceStart(wall, recurrence.rule)) continue
        if (date <= displayLastOccupiedDayOf(candidate, wall, zone)) return candidate
    }
    return null
}

/** Display date on which the occurrence starting on [wallStart] stops occupying time. */
internal fun Event.displayLastOccupiedDayOf(
    displayStart: LocalDate,
    wallStart: LocalDate,
    zone: TimeZone,
): LocalDate {
    if (allDay) return displayStart.plusDays(wallDay.daysTo(wallLastOccupiedDay()))
    val startInstant = occurrenceStartInstant(wallStart, wallStartTime(), zone)
    val endInstant = startInstant + occurrenceDurationOn(wallStart)
    val startLocal = startInstant.toLocalDateTime(zone)
    val endLocal = endInstant.toLocalDateTime(zone)
    val last = if (endLocal.date > startLocal.date && endLocal.time == LocalTime(0, 0)) {
        endLocal.date.plusDays(-1)
    } else {
        endLocal.date
    }
    return if (last > displayStart) last else displayStart
}

/** Events happening on [date], all-day ones first and then ordered by start time. */
fun List<Event>.occurringOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): List<Event> = filter { it.occursOn(date, zone) }
    .sortedWith(
        compareBy<Event> { !it.allDay }
            .thenBy { it.localStart(zone).time.let { time -> time.hour * 60 + time.minute } },
    )

/** The slice of [event] that falls on [date] in [zone], clipped to the day. */
fun Event.timeRangeOn(
    date: LocalDate,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Pair<Int, Int> {
    if (allDay) return 0 to EndOfDayMinutes
    // Slicing past the occurrence (or an uncovered date) has no meaningful clip, so the raw range is
    // returned for callers that bypass occursOn.
    val displayStart = occurrenceStartOn(date, zone) ?: return startMinutes to endMinutes
    val wallStart = wallDateFor(displayStart, zone)
    val startInstant = occurrenceStartInstant(wallStart, wallStartTime(), zone)
    val endInstant = startInstant + occurrenceDurationOn(wallStart)
    val startLocal = startInstant.toLocalDateTime(zone)
    val endLocal = endInstant.toLocalDateTime(zone)
    // An end at exactly midnight occupies the previous day through 24:00.
    val lastDisplay = if (endLocal.date > startLocal.date && endLocal.time == LocalTime(0, 0)) {
        endLocal.date.plusDays(-1)
    } else {
        endLocal.date
    }
    if (date > lastDisplay) return startMinutes to endMinutes
    val start = if (date == startLocal.date) startLocal.hour * 60 + startLocal.minute else 0
    val end = when {
        date < lastDisplay -> EndOfDayMinutes
        endLocal.time == LocalTime(0, 0) && lastDisplay != endLocal.date -> EndOfDayMinutes
        else -> endLocal.hour * 60 + endLocal.minute
    }
    return start to end
}

/** Whether the event spans more than one display day (a midnight end counts as spanning). */
fun Event.isMultiDay(): Boolean = endDay > day
