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
 * [com.crko.domain.Event.attendeeRaw] stays the stored source of truth; this is a read-only view
 * over it, so parameters this app does not model are never dropped by an edit.
 */
data class IcsAttendee(
    /** The address after the colon, e.g. `mailto:jane@example.com`. */
    val calAddress: String,
    /** `CN` parameter. */
    val cn: String? = null,
    /** `ROLE` parameter, e.g. `REQ-PARTICIPANT`. */
    val role: String? = null,
    val partStat: IcsPartStat = IcsPartStat.NeedsAction,
    /** `CUTYPE` parameter; `INDIVIDUAL` by default. */
    val cutype: String? = null,
    /** `RSVP` parameter; null when the line does not say. */
    val rsvp: Boolean? = null,
    /** `DELEGATED-FROM` address. */
    val delegatedFrom: String? = null,
    /** `DELEGATED-TO` address. */
    val delegatedTo: String? = null,
    /** `SENT-BY` address of a secretary acting for the attendee. */
    val sentBy: String? = null,
) {
    /** Name shown to the user: `CN` when present, otherwise the bare address. */
    val displayName: String
        get() = cn?.takeIf { it.isNotBlank() } ?: calAddress.substringAfterLast(':').substringBefore('?')

    companion object {
        /** Parses an original `ATTENDEE` content line; null when it is not a usable attendee. */
        fun parse(raw: String): IcsAttendee? {
            val line = parseContentLine(UnfoldedLine(raw, 0)) ?: return null
            if (line.name != "ATTENDEE" || line.value.isBlank()) return null
            return IcsAttendee(
                calAddress = line.value.trim(),
                cn = line.params["CN"]?.takeIf { it.isNotBlank() },
                role = line.params["ROLE"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
                partStat = IcsPartStat.parse(line.params["PARTSTAT"]) ?: IcsPartStat.NeedsAction,
                cutype = line.params["CUTYPE"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() },
                rsvp = when (line.params["RSVP"]?.trim()?.uppercase()) {
                    "TRUE" -> true
                    "FALSE" -> false
                    else -> null
                },
                delegatedFrom = line.params["DELEGATED-FROM"]?.takeIf { it.isNotBlank() },
                delegatedTo = line.params["DELEGATED-TO"]?.takeIf { it.isNotBlank() },
                sentBy = line.params["SENT-BY"]?.takeIf { it.isNotBlank() },
            )
        }
    }
}

/** Structured view over an event's original `ATTENDEE` lines; falls back to the plain values. */
val Event.attendeeEntries: List<IcsAttendee>
    get() = attendeeRaw.mapNotNull(IcsAttendee::parse).ifEmpty {
        attendees.filter { it.isNotBlank() }.map { IcsAttendee(calAddress = it) }
    }

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
 * The `ATTENDEE` line of [raw] with its `PARTSTAT` parameter set to [partStat]. Every other
 * parameter and the value are preserved verbatim, so a reply round trip never loses `CN`/`ROLE`
 * details the app does not model. Null when [raw] is not an `ATTENDEE` line.
 */
internal fun rewriteAttendeePartStat(raw: String, partStat: IcsPartStat): String? {
    val line = parseContentLine(UnfoldedLine(raw, 0)) ?: return null
    if (line.name != "ATTENDEE" || line.value.isBlank()) return null
    val params = LinkedHashMap(line.params)
    params["PARTSTAT"] = partStat.wireName
    return buildString {
        append("ATTENDEE")
        params.forEach { (name, value) ->
            append(';').append(name).append('=').append(icsParameterValue(value))
        }
        append(':').append(line.value)
    }
}

/** Builds an `ATTENDEE` line for [this] attendee, quoting parameter values that need it. */
fun IcsAttendee.toLine(): String = buildString {
    append("ATTENDEE")
    cn?.let { append(";CN=").append(icsParameterValue(it)) }
    role?.let { append(";ROLE=").append(it) }
    cutype?.let { append(";CUTYPE=").append(it) }
    rsvp?.let { append(";RSVP=").append(if (it) "TRUE" else "FALSE") }
    sentBy?.let { append(";SENT-BY=\"").append(it).append('"') }
    delegatedFrom?.let { append(";DELEGATED-FROM=\"").append(it).append('"') }
    delegatedTo?.let { append(";DELEGATED-TO=\"").append(it).append('"') }
    append(";PARTSTAT=").append(partStat.wireName)
    append(':').append(calAddress)
}

/**
 * Quotes a content line parameter value when it contains characters (`;`, `:`, `,`) that would
 * otherwise split the head of the line. Plain values stay unquoted, like every mainstream exporter
 * writes them.
 */
internal fun icsParameterValue(value: String): String =
    if (value.none { it == ';' || it == ':' || it == ',' }) value else "\"$value\""

/** The answer one attendee gave in an imported `METHOD:REPLY` calendar. */
data class ItipReply(
    /** UID of the series the reply refers to. */
    val uid: String,
    /** Original `RECURRENCE-ID` date for a reply about one occurrence; null for the master. */
    val recurrenceId: String? = null,
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
    val event = events.firstOrNull { it.attendeeRaw.isNotEmpty() } ?: return null
    val uid = event.seriesUid ?: event.icsUid ?: return null
    val attendee = event.attendeeRaw.lastOrNull()?.let(IcsAttendee::parse) ?: return null
    return ItipReply(
        uid = uid,
        recurrenceId = event.recurrenceId,
        organizer = event.organizer,
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
 * Applies [reply] to the stored events: the matching record's attendee line for the replying
 * address gets the replied `PARTSTAT`. An unknown attendee is appended; a reply that finds no
 * matching record leaves the list untouched.
 */
fun List<Event>.applyingReply(reply: ItipReply, now: Instant = Clock.System.now()): ItipReplyResult {
    val index = indexOfFirst { event -> matchesReplyTarget(event, reply) }
    if (index < 0) return ItipReplyResult(this, false)
    val event = this[index]
    val rawIndex = event.attendeeRaw.indexOfFirst { raw ->
        IcsAttendee.parse(raw)?.let { sameCalendarAddress(it.calAddress, reply.attendee.calAddress) } == true
    }
    val updated = when {
        rawIndex >= 0 -> {
            val rewritten = rewriteAttendeePartStat(event.attendeeRaw[rawIndex], reply.attendee.partStat)
                ?: return ItipReplyResult(this, false)
            event.copy(attendeeRaw = event.attendeeRaw.mapIndexed { i, raw -> if (i == rawIndex) rewritten else raw })
        }
        // The reply names an attendee the stored copy does not list (an invitation forwarded to a
        // delegate); keep the answer by adding the attendee line instead of dropping it.
        else -> event.copy(attendeeRaw = event.attendeeRaw + reply.attendee.toLine())
    }
    val list = toMutableList()
    list[index] = updated.copy(lastModified = now.toString())
    return ItipReplyResult(list, true)
}

/**
 * Whether [event] is the record [reply] talks about: the exception for a reply with a
 * `RECURRENCE-ID`, the series master otherwise.
 */
private fun matchesReplyTarget(event: Event, reply: ItipReply): Boolean =
    if (reply.recurrenceId != null) {
        // The reply names one occurrence, so only its override record can carry the answer.
        event.seriesUid == reply.uid && event.recurrenceId == reply.recurrenceId
    } else {
        event.uid == reply.uid && event.recurrenceId == null
    }
