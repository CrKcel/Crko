package com.crko.domain

import kotlin.time.Clock
import kotlin.time.Instant

/**
 * RFC 5546 iCalendar Transport-Independent Interoperability Protocol (iTIP). A calendar carrying a
 * `METHOD` property is not a plain snapshot but a scheduling message: `REQUEST` proposes or updates
 * events, `REPLY` answers on behalf of one attendee, and `CANCEL` withdraws them.
 */
enum class ItipMethod(val wireName: String) {
    Publish("PUBLISH"),
    Request("REQUEST"),
    Reply("REPLY"),
    Cancel("CANCEL"),
    Counter("COUNTER"),
    DeclineCounter("DECLINECOUNTER"),
    Refresh("REFRESH");

    companion object {
        fun parse(text: String?): ItipMethod? =
            text?.trim()?.uppercase()?.let { raw -> entries.firstOrNull { it.wireName == raw } }
    }
}

/** RFC 5545 `PARTSTAT` participation status of an attendee. */
enum class IcsPartStat(val wireName: String) {
    NeedsAction("NEEDS-ACTION"),
    Accepted("ACCEPTED"),
    Declined("DECLINED"),
    Tentative("TENTATIVE"),
    Delegated("DELEGATED"),
    Completed("COMPLETED"),
    InProcess("IN-PROCESS");

    companion object {
        fun parse(value: String?): IcsPartStat? =
            value?.trim()?.uppercase()?.let { raw -> entries.firstOrNull { it.wireName == raw } }
    }
}

/**
 * One `ATTENDEE` of an event, with the scheduling parameters an invitation round trip depends on.
 * Since stage 4 this is the stored type itself ([CalAddress]), so a reply edits a structured
 * `PARTSTAT` instead of rewriting a cached content line.
 */
typealias IcsAttendee = CalAddress

/** Structured attendee list of an event. */
val Event.attendeeEntries: List<IcsAttendee>
    get() = attendees

/**
 * Case-insensitive comparison of two calendar addresses, ignoring the `mailto:` scheme. The scheme
 * is written in any letter case in the wild (`mailto:`, `MAILTO:`, `MailTo:`), so it is stripped
 * without depending on its spelling.
 */
internal fun sameCalendarAddress(a: String, b: String): Boolean {
    fun bare(value: String): String {
        val text = value.trim()
        return if (text.startsWith("mailto:", ignoreCase = true)) text.substring("mailto:".length) else text
    }
    return bare(a).equals(bare(b), ignoreCase = true)
}

/**
 * Quotes a content line parameter value when it contains characters (`;`, `:`, `,`) that would
 * otherwise split the head of the line. Plain values stay unquoted, like every mainstream exporter
 * writes them. A value that carries a double quote has to be quoted *and* have that quote escaped:
 * an unescaped one would close the quoted section early, and the line the reader gets from it is a
 * parameter list whose value separator sits in the wrong place.
 */
internal fun icsParameterValue(value: String): String =
    if (value.none { it == ';' || it == ':' || it == ',' || it == '"' }) value
    else "\"${value.replace("\"", "\\\"")}\""

/**
 * Values of a comma-separated parameter list. The commas that separate the elements are the ones
 * outside the quotes; a comma inside a quoted address, and a `\"` inside a quoted element, belong to
 * the element they sit in.
 */
internal fun splitParameterList(value: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var quoted = false
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        when {
            quoted && ch == '\\' && index + 1 < value.length -> {
                current.append(value[index + 1])
                index += 2
                continue
            }
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> {
                parts += current.toString()
                current.clear()
            }
            else -> current.append(ch)
        }
        index++
    }
    parts += current.toString()
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/** The answer one attendee gave in an imported `METHOD:REPLY` calendar. */
data class ItipReply(
    /** UID of the series the reply refers to. */
    val uid: String,
    /** Original `RECURRENCE-ID` slot for a reply about one occurrence; null for the master. */
    val recurrenceId: IcsTimeValue? = null,
    val organizer: String? = null,
    /** `SEQUENCE` of the copy the reply was produced from; 0 when the sender omitted it. */
    val sequence: Int = 0,
    val attendee: IcsAttendee,
)

/**
 * The attendee's answer carried by [this] import when it is a `REPLY` message; null for any other
 * method or for a reply that names no attendee.
 */
fun IcsImport.itipReply(): ItipReply? {
    if (method != ItipMethod.Reply) return null
    val event = events.firstOrNull { it.attendees.isNotEmpty() } ?: return null
    val uid = event.icsUid ?: event.uid ?: return null
    val attendee = event.attendees.last()
    return ItipReply(
        uid = uid,
        recurrenceId = event.recurrenceId,
        organizer = event.organizer?.calAddress,
        sequence = event.sequence,
        attendee = attendee,
    )
}

/** Whether an incoming iTIP copy may replace the stored one. */
sealed interface ItipDecision {
    data object Apply : ItipDecision
    data object Ignore : ItipDecision
    /** [sequence] of the stored copy is newer than the message's, so the message is stale. */
    data object Stale : ItipDecision
}

/**
 * RFC 5546 conflict resolution for one scheduling message against a stored copy of the same UID:
 * `SEQUENCE` is the authoritative counter, and a message older than the stored copy must be
 * ignored. A message that does not carry a sequence is always allowed, because mainstream senders
 * omit it on replies.
 */
fun itipDecision(incomingSequence: Int, existingSequence: Int): ItipDecision = when {
    incomingSequence <= 0 || existingSequence <= 0 -> ItipDecision.Apply
    incomingSequence >= existingSequence -> ItipDecision.Apply
    else -> ItipDecision.Stale
}

/** Result of applying a reply: the updated list and whether anything changed. */
data class ItipReplyResult(val events: List<Event>, val applied: Boolean)

/**
 * Applies [reply] to the stored events: the matching record's attendee for the replying address
 * gets the replied `PARTSTAT`. An unknown attendee is appended; a reply that finds no matching
 * record leaves the list untouched.
 */
fun List<Event>.applyingReply(reply: ItipReply, now: Instant = Clock.System.now()): ItipReplyResult {
    val index = indexOfFirst { event -> event.matchesReplyTarget(reply) }
    if (index < 0) return ItipReplyResult(this, false)
    val event = this[index]
    val attendeeIndex = event.attendees.indexOfFirst { attendee ->
        sameCalendarAddress(attendee.calAddress, reply.attendee.calAddress)
    }
    val updated = if (attendeeIndex >= 0) {
        event.copy(
            attendees = event.attendees.mapIndexed { i, attendee ->
                if (i == attendeeIndex) attendee.copy(partStat = reply.attendee.partStat) else attendee
            },
        )
    } else {
        // The reply names an attendee the stored copy does not list (an invitation forwarded to a
        // delegate); keep the answer by adding the attendee instead of dropping it.
        event.copy(attendees = event.attendees + reply.attendee)
    }
    val list = toMutableList()
    list[index] = updated.copy(lastModified = now.toString())
    return ItipReplyResult(list, true)
}

/**
 * Whether [event] is the record [reply] talks about: the exception for a reply with a
 * `RECURRENCE-ID`, the series master otherwise.
 */
internal fun Event.matchesReplyTarget(reply: ItipReply): Boolean =
    uid == reply.uid && sameRecurrenceSlot(recurrenceId, reply.recurrenceId)

/**
 * Whether the two values name the same occurrence slot. A reply may well write the slot in another
 * RFC value type than the stored exception does — `RECURRENCE-ID:20260305T140000Z` answering a
 * `TZID=America/New_York` series is the common shape — because the reply is built from the
 * organizer's own copy and carries no master to align against. Comparing the values verbatim made
 * such a reply find no record at all, so the attendee's answer was dropped.
 */
internal fun sameRecurrenceSlot(stored: IcsTimeValue?, incoming: IcsTimeValue?): Boolean {
    if (stored == incoming) return true
    if (stored == null || incoming == null) return false
    // A date-only slot only ever names its own day, in any zone.
    if (stored is IcsTimeValue.Date || incoming is IcsTimeValue.Date) return false
    val left = stored.instantBearingValue()
    val right = incoming.instantBearingValue()
    if (left != null && right != null) return left.instant == right.instant
    // Without an instant on one side (floating) the wall clock the two files name is all there is.
    return stored.wallDateTime == incoming.wallDateTime
}

/** The value with its own instant, or null when it names none (`Floating`). */
private fun IcsTimeValue.instantBearingValue(): IcsTimeValue.Utc? = when (this) {
    is IcsTimeValue.Utc -> this
    is IcsTimeValue.Zoned -> zonedInstantOrNull(dateTime, tzid)?.let(IcsTimeValue::Utc)
    else -> null
}
