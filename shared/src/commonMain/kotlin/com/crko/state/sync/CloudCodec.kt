package com.crko.state.sync

import com.crko.domain.AppJson
import com.crko.domain.Calendar
import com.crko.domain.CalendarComponent
import com.crko.domain.CalendarTimeZone
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.domain.matchesOwnExport
import com.crko.domain.recurrenceKeyText
import com.crko.storage.CloudAccountStore
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Protocol vocabulary and payload helpers shared by the cloud push and pull engines. */
internal object CloudKind {
    const val Calendar = "calendar"
    const val Event = "event"
    const val Todo = "todo"
    const val Journal = "journal"
    const val Settings = "settings"
}

/** The single settings entity of an account. */
internal const val CloudSettingsKey = "settings"

/** AAD of one payload: the ciphertext is bound to its coordinates, so a replay elsewhere fails. */
internal fun cloudPayloadAad(kind: String, key: String): String = "crko/v1|$kind|$key"

/** Applied-version key of a calendar config: the calendar id is its cloud key. */
internal fun cloudCalendarVersionKey(calendarId: String): String = "calendar:$calendarId"

/**
 * The server logs every pushed op, including the ones it rejects as stale. Settings and calendar
 * configs carry no `SEQUENCE`, so the pull remembers the newest `seq:ts` tuple it applied or pushed
 * per key and ignores older log entries; without it a clock-skewed device would revert them.
 */
internal class CloudVersionBook(private val store: CloudAccountStore) {

    /** True when `seq:ts` outranks what this device already applied or pushed for [key]. */
    fun isNewer(key: String, seq: Long, ts: Long): Boolean {
        val applied = parseCloudVersion(store.loadAppliedVersions()[key]) ?: return true
        return seq > applied.first || (seq == applied.first && ts > applied.second)
    }

    /** Raises the stored tuple after a local push or an applied pull; never lowers it. */
    fun record(key: String, seq: Long, ts: Long) {
        val versions = store.loadAppliedVersions()
        val applied = parseCloudVersion(versions[key])
        if (applied != null && (seq < applied.first || (seq == applied.first && ts <= applied.second))) return
        store.saveAppliedVersions(versions + (key to "$seq:$ts"))
    }
}

/** Parses the stored `seq:ts` text; null when missing or malformed. */
private fun parseCloudVersion(text: String?): Pair<Long, Long>? {
    val parts = text?.split(':') ?: return null
    if (parts.size != 2) return null
    val seq = parts[0].toLongOrNull() ?: return null
    val ts = parts[1].toLongOrNull() ?: return null
    return seq to ts
}

/** The server-side key of one record: `calendarId/uid/recurrenceKey`. */
internal fun cloudRecordKey(calendarId: String, uid: String, recurrenceKey: String): String =
    "$calendarId/$uid/$recurrenceKey"

/** Splits [cloudRecordKey] back into its three coordinates; null on a malformed key. */
internal fun splitCloudRecordKey(key: String): Triple<String, String, String>? {
    val calendarId = key.substringBefore('/', missingDelimiterValue = "")
    if (calendarId.isEmpty()) return null
    val rest = key.removePrefix("$calendarId/")
    val uid = rest.substringBefore('/', missingDelimiterValue = "")
    if (uid.isEmpty()) return null
    val recurrenceKey = if (rest.contains('/')) rest.substringAfter('/') else ""
    return Triple(calendarId, uid, recurrenceKey)
}

/**
 * The calendar container payload synced as its own encrypted entity: everything a second device
 * needs to create or update the calendar row, without the records, which travel separately.
 */
@Serializable
data class CloudCalendarConfig(
    val id: String,
    val name: String,
    val colorArgb: Long? = null,
    val visible: Boolean = true,
    val timeZones: List<CalendarTimeZone> = emptyList(),
)

/**
 * Version tuple of a record for last-writer-wins: the RFC `SEQUENCE` primary, and the push
 * timestamp as tie-breaker, parsed from the record's own `DTSTAMP`/`LAST-MODIFIED` so both sides of
 * a comparison read the same field. Falls back to [fallback] when the record has neither.
 */
internal fun cloudTimestamp(record: CalendarComponent, fallback: Long): Long {
    val stamp = record.dtStamp ?: record.lastModified ?: return fallback
    return runCatching { Instant.parse(stamp).toEpochMilliseconds() }.getOrDefault(fallback)
}

/** The calendar's record for a cloud entity key: UID match, own-export match, same recurrence. */
internal fun findCloudRecord(calendar: Calendar, kind: String, uid: String, recurrenceKey: String): CalendarComponent? {
    fun matches(record: CalendarComponent, id: String?): Boolean =
        (record.icsUid == uid || matchesOwnExport(id, uid)) &&
            recurrenceKeyText(record.recurrenceId) == recurrenceKey
    return when (kind) {
        CloudKind.Event -> calendar.events.firstOrNull { matches(it, it.id) }
        CloudKind.Todo -> calendar.todos.firstOrNull { matches(it, it.id) }
        CloudKind.Journal -> calendar.journals.firstOrNull { matches(it, it.id) }
        else -> null
    }
}

/** Encodes one record's RFC payload for sealing; the shape is whatever the kind carries. */
internal fun encodeCloudRecord(kind: String, record: CalendarComponent): String = when (kind) {
    CloudKind.Event -> AppJson.encodeToString(Event.serializer(), record as Event)
    CloudKind.Todo -> AppJson.encodeToString(Todo.serializer(), record as Todo)
    else -> AppJson.encodeToString(Journal.serializer(), record as Journal)
}

/** Decodes a pulled record payload; null when the kind or the JSON does not match. */
internal fun decodeCloudRecord(kind: String, payload: String): CalendarComponent? = runCatching {
    when (kind) {
        CloudKind.Event -> AppJson.decodeFromString(Event.serializer(), payload)
        CloudKind.Todo -> AppJson.decodeFromString(Todo.serializer(), payload)
        CloudKind.Journal -> AppJson.decodeFromString(Journal.serializer(), payload)
        else -> null
    }
}.getOrNull()
