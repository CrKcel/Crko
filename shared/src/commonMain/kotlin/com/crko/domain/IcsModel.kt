package com.crko.domain

/** RFC 5545 weekday codes, shared by the rule reader and writer. */

internal val weekdayCodes = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

enum class IcsWarning {
    UnresolvedTimeZone,
    InvalidValue,
    UnsupportedRecurrence,
    MultipleRrules,

    /** A VEVENT had no usable `DTSTART`, so nothing could be placed on the calendar. */
    MissingStart,

    /** Several exceptions shared one occurrence date, which the single-start model cannot tell apart. */
    AmbiguousRecurrenceId,

    /** An `EXRULE` was found; it is deprecated and its exclusions are not applied. */
    ExruleIgnored,

    /** A component carried child components other than `VALARM`, which are not supported. */
    UnknownSubcomponent,

    /** An `EXDATE` fell on an occurrence date but at a different time of day. */
    ExdateMismatch,

    /**
     * A `RECURRENCE-ID;RANGE=THISANDFUTURE` exception carried no recurrence of its own, so nothing
     * replaces the master's later instances and the exception covers its slot only.
     */
    UnappliedRecurrenceRange,
}

data class IcsImport(
    val events: List<Event> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val journals: List<Journal> = emptyList(),
    val warnings: List<IcsWarning> = emptyList(),
    /** The same warnings attached to the record (and content line) that caused them. */
    val warningDetails: List<IcsWarningDetail> = emptyList(),
    /** UIDs whose newest master copy is `STATUS:CANCELLED`; matching local records are removed. */
    val cancelledUids: Set<String> = emptySet(),
    /**
     * The cancelled series themselves, with title and line, so a per-record preview can offer the
     * removal as a choice instead of applying it behind the user's back.
     */
    val cancelled: List<CancelledIcsRecord> = emptyList(),
    /** iTIP `METHOD` of the calendar; null for a plain snapshot without scheduling semantics. */
    val method: ItipMethod? = null,
    /** `X-WR-CALNAME` of the source calendar, unescaped; null when the file does not name itself. */
    val calendarName: String? = null,
    /**
     * `REFRESH-INTERVAL` (or Google's `X-PUBLISHED-TTL`) in minutes; how often the publisher says a
     * subscribed copy should be refreshed.
     */
    val refreshMinutes: Int? = null,
    /** Raw `VTIMEZONE` definitions for `TZID` values the platform cannot resolve, deduplicated. */
    val timeZones: List<CalendarTimeZone> = emptyList(),
    /** Busy periods of the `VFREEBUSY` components the file carried. */
    val freeBusy: List<BusyInterval> = emptyList(),
    /**
     * Every UID the file still describes, kept rows or not. Reconciliation against this set must
     * not use the kept subset alone, or unchecking a preview row would turn "skip this update"
     * into "the source removed it" and delete the local record.
     */
    val describedUids: Set<String> = emptySet(),
)

/** A series whose newest master copy is `STATUS:CANCELLED`; importing it removes local records. */
data class CancelledIcsRecord(
    val uid: String,
    val summary: String,
    val line: Int? = null,
    /** `SEQUENCE` of the cancelled copy, so a stale cancellation cannot remove a newer event. */
    val sequence: Int = 0,
)

/** One warning occurrence, so the UI can say which record or line was affected. */
data class IcsWarningDetail(
    val warning: IcsWarning,
    val line: Int? = null,
    val record: String? = null,
)

/** Why a calendar file could not be imported; each case has its own user-facing message. */
enum class IcsImportFailure {
    /** The picked file is larger than the platform limit. */
    TooLarge,

    /** The bytes are not valid UTF-8. */
    InvalidEncoding,

    /** The file or its provider could not be read. */
    Unreadable,

    /** The text was read but is not a calendar the parser accepts. */
    InvalidFormat,
}

internal class ContentLine(
    val name: String,
    val params: Map<String, String>,
    val value: String,
    val raw: String,
    val lineNumber: Int = 0,
)

internal class IcsComponent(
    val name: String,
    val lineNumber: Int = 0,
    val properties: MutableList<ContentLine> = mutableListOf(),
    val children: MutableList<IcsComponent> = mutableListOf(),
) {
    fun first(name: String): ContentLine? = properties.firstOrNull { it.name == name }
    fun all(name: String): List<ContentLine> = properties.filter { it.name == name }

    fun toText(): String = buildString {
        append("BEGIN:${name}\r\n")
        properties.forEach { append(it.raw).append("\r\n") }
        children.forEach { append(it.toText()) }
        append("END:${name}\r\n")
    }
}

internal data class UnfoldedLine(val text: String, val lineNumber: Int)
