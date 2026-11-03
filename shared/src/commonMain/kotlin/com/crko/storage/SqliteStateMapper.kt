package com.crko.storage

import com.crko.domain.AppJson
import com.crko.domain.AppState
import com.crko.domain.BusyInterval
import com.crko.domain.Calendar
import com.crko.domain.CalendarSyncState
import com.crko.domain.CalendarTimeZone
import com.crko.domain.CurrentSchemaVersion
import com.crko.domain.Event
import com.crko.domain.IcsTimeValue
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.domain.instantOrNull
import com.crko.domain.replaceIcsTimeZones
import com.crko.domain.withLocalCalendar
import com.crko.storage.db.Busy_period
import com.crko.storage.db.Calendar as DbCalendar
import com.crko.storage.db.Calendar_timezone
import com.crko.storage.db.CrkoDatabase
import com.crko.storage.db.Record as DbRecord
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

/** Decoded state plus the records that had to be moved into `damaged_record`. */
internal data class SqliteReadResult(
    val state: AppState,
    val droppedRecords: Int,
    val payloadVersion: Int?,
)

/**
 * Reads the whole state through the index rows and the payload columns. Records are decoded
 * independently: one broken payload goes to `damaged_record` (the first copy is kept) instead of
 * taking the rest of the calendar down with it.
 */
internal fun readSqliteState(database: CrkoDatabase, nowMillis: () -> Long): SqliteReadResult {
    val queries = database.crkoQueries
    // One snapshot for all tables: a background read must not see a calendar from before a commit
    // together with records from after it.
    val snapshot = database.transactionWithResult {
        ReadSnapshot(
            calendars = queries.selectCalendars().executeAsList(),
            timeZones = queries.selectTimeZones().executeAsList(),
            records = queries.selectRecords().executeAsList(),
            busy = queries.selectBusyPeriods().executeAsList(),
            payloadVersion = queries.getMeta(PayloadVersionKey).executeAsOneOrNull()?.toIntOrNull(),
        )
    }
    val calendarRows = snapshot.calendars
    val timeZoneRows = snapshot.timeZones
    val recordRows = snapshot.records
    val busyRows = snapshot.busy

    val eventsByCalendar = mutableMapOf<String, MutableList<Positioned<Event>>>()
    val todosByCalendar = mutableMapOf<String, MutableList<Positioned<Todo>>>()
    val journalsByCalendar = mutableMapOf<String, MutableList<Positioned<Journal>>>()
    val damaged = mutableListOf<DamagedRow>()
    var dropped = 0

    recordRows.forEach { row ->
        val decoded: Any? = when (row.kind) {
            "event" -> runCatching {
                AppJson.decodeFromString(Event.serializer(), row.payload).copy(id = row.record_id)
            }.getOrNull()
            "todo" -> runCatching {
                AppJson.decodeFromString(Todo.serializer(), row.payload).copy(id = row.record_id)
            }.getOrNull()
            "journal" -> runCatching {
                AppJson.decodeFromString(Journal.serializer(), row.payload).copy(id = row.record_id)
            }.getOrNull()
            else -> null
        }
        when (decoded) {
            is Event -> eventsByCalendar.getOrPut(row.calendar_id) { mutableListOf() }
                .add(Positioned(row.position.toInt(), decoded))
            is Todo -> todosByCalendar.getOrPut(row.calendar_id) { mutableListOf() }
                .add(Positioned(row.position.toInt(), decoded))
            is Journal -> journalsByCalendar.getOrPut(row.calendar_id) { mutableListOf() }
                .add(Positioned(row.position.toInt(), decoded))
            else -> {
                dropped += 1
                damaged += DamagedRow("record", "${row.calendar_id}/${row.record_id}", row.payload)
            }
        }
    }

    val busy = mutableListOf<Positioned<BusyInterval>>()
    busyRows.forEach { row ->
        val decoded = runCatching {
            AppJson.decodeFromString(BusyInterval.serializer(), row.payload)
        }.getOrNull()
        if (decoded == null) {
            dropped += 1
            damaged += DamagedRow("busy_period", row.busy_period_id.toString(), row.payload)
        } else {
            busy += Positioned(row.position.toInt(), decoded)
        }
    }

    if (damaged.isNotEmpty()) {
        val damagedKeys = damaged.mapTo(mutableSetOf()) { it.sourceTable to it.sourceKey }
        database.transaction {
            val captured = nowMillis()
            damaged.forEach { row ->
                queries.insertDamaged(captured, row.sourceTable, row.sourceKey, row.payload)
            }
            recordRows.forEach { row ->
                if ("record" to "${row.calendar_id}/${row.record_id}" in damagedKeys) {
                    queries.deleteRecord(row.calendar_id, row.record_id)
                }
            }
            busyRows.forEach { row ->
                if ("busy_period" to row.busy_period_id.toString() in damagedKeys) {
                    queries.deleteBusyPeriod(row.busy_period_id)
                }
            }
        }
    }

    val zonesByCalendar = timeZoneRows.groupBy { it.calendar_id }
    val calendars = calendarRows.map { row ->
        Calendar(
            id = row.id,
            name = row.name,
            colorArgb = row.color_argb,
            visible = row.visible != 0L,
            method = row.method,
            refreshMinutes = row.refresh_minutes?.toInt(),
            timeZones = zonesByCalendar[row.id].orEmpty().toCalendarTimeZones(),
            events = eventsByCalendar[row.id].orEmpty().ordered(),
            todos = todosByCalendar[row.id].orEmpty().ordered(),
            journals = journalsByCalendar[row.id].orEmpty().ordered(),
            syncState = row.sync_url?.let { url ->
                CalendarSyncState(
                    url = url,
                    enabled = row.sync_enabled != 0L,
                    refreshMinutes = (row.sync_refresh_minutes ?: 0L).toInt(),
                    lastSync = row.sync_last_sync,
                    etag = row.sync_etag,
                    syncToken = row.sync_token,
                    caldav = row.sync_caldav != 0L,
                )
            },
        )
    }

    val state = AppState(
        calendars = calendars,
        busyPeriods = busy.ordered(),
        schemaVersion = CurrentSchemaVersion,
    ).withLocalCalendar()
    replaceIcsTimeZones(state.calendars)
    return SqliteReadResult(
        state = state,
        droppedRecords = dropped,
        payloadVersion = snapshot.payloadVersion,
    )
}

/** Inserts the complete state in one transaction, used by first launch and by the migrator. */
internal fun replaceSqliteState(database: CrkoDatabase, state: AppState, nowMillis: Long) {
    database.transaction {
        writeState(database, state, previous = null, nowMillis = nowMillis, replace = true)
    }
}

/**
 * Applies one drain batch. Only rows whose payload or position changed are touched; a burst edit
 * therefore writes the one changed record plus the few calendar rows, not the whole state.
 */
internal fun saveSqliteState(
    database: CrkoDatabase,
    next: AppState,
    previous: AppState?,
    nowMillis: Long,
) {
    database.transaction {
        writeState(database, next, previous, nowMillis, replace = false)
    }
}

private fun writeState(
    database: CrkoDatabase,
    next: AppState,
    previous: AppState?,
    nowMillis: Long,
    replace: Boolean,
) {
    val queries = database.crkoQueries
    if (replace) {
        // Foreign keys cascade, but the explicit order keeps a partially migrated database empty.
        queries.deleteAllRecords()
        queries.deleteAllTimeZones()
        queries.deleteAllCalendars()
        queries.deleteBusyPeriods()
        queries.deleteAllDamaged()
    } else if (previous != null) {
        val removed = previous.calendars.filter { it.id !in next.calendars.mapTo(mutableSetOf()) { cal -> cal.id } }
        removed.forEach {
            // Explicit child deletes make removal correct even if a platform driver opens without
            // `foreign_keys=ON`; with it on these are no-ops before the cascade.
            queries.deleteRecordsForCalendar(it.id)
            queries.deleteTimeZones(it.id)
            queries.deleteCalendar(it.id)
        }
    }

    val previousCalendars = previous?.calendars.orEmpty().associateBy { it.id }
    next.calendars.forEachIndexed { calendarIndex, calendar ->
        queries.insertCalendar(
            id = calendar.id,
            name = calendar.name,
            color_argb = calendar.colorArgb,
            visible = if (calendar.visible) 1L else 0L,
            method = calendar.method,
            refresh_minutes = calendar.refreshMinutes?.toLong(),
            position = calendarIndex.toLong(),
            sync_url = calendar.syncState?.url,
            sync_enabled = calendar.syncState?.let { if (it.enabled) 1L else 0L },
            sync_refresh_minutes = calendar.syncState?.refreshMinutes?.toLong(),
            sync_last_sync = calendar.syncState?.lastSync,
            sync_etag = calendar.syncState?.etag,
            sync_token = calendar.syncState?.syncToken,
            sync_caldav = calendar.syncState?.let { if (it.caldav) 1L else 0L },
        )
        queries.updateCalendar(
            name = calendar.name,
            color_argb = calendar.colorArgb,
            visible = if (calendar.visible) 1L else 0L,
            method = calendar.method,
            refresh_minutes = calendar.refreshMinutes?.toLong(),
            position = calendarIndex.toLong(),
            sync_url = calendar.syncState?.url,
            sync_enabled = calendar.syncState?.let { if (it.enabled) 1L else 0L },
            sync_refresh_minutes = calendar.syncState?.refreshMinutes?.toLong(),
            sync_last_sync = calendar.syncState?.lastSync,
            sync_etag = calendar.syncState?.etag,
            sync_token = calendar.syncState?.syncToken,
            sync_caldav = calendar.syncState?.let { if (it.caldav) 1L else 0L },
            id = calendar.id,
        )
        if (previousCalendars[calendar.id]?.timeZones != calendar.timeZones) {
            queries.deleteTimeZones(calendar.id)
            calendar.timeZones.forEachIndexed { index, zone ->
                queries.insertTimeZone(
                    calendar_id = calendar.id,
                    tzid = zone.tzid,
                    definition = zone.definition,
                    position = index.toLong(),
                )
                queries.updateTimeZone(
                    definition = zone.definition,
                    position = index.toLong(),
                    calendar_id = calendar.id,
                    tzid = zone.tzid,
                )
            }
        }
    }

    val previousRecords = previousRecordMap(previous)
    next.calendars.forEach { calendar ->
        calendar.events.forEachIndexed { position, event ->
            upsertIfChanged(queries, calendar.id, event.id, event, position, previousRecords, nowMillis) { eventProjection(event) }
        }
        calendar.todos.forEachIndexed { position, todo ->
            upsertIfChanged(queries, calendar.id, todo.id, todo, position, previousRecords, nowMillis) { todoProjection(todo) }
        }
        calendar.journals.forEachIndexed { position, journal ->
            upsertIfChanged(queries, calendar.id, journal.id, journal, position, previousRecords, nowMillis) { journalProjection(journal) }
        }
    }

    if (previous != null && previousRecords.isNotEmpty()) {
        val nextKeys = next.calendars.flatMap { calendar ->
            calendar.events.mapNotNull { it.id?.let { id -> RecordKey(calendar.id, id) } } +
                calendar.todos.map { RecordKey(calendar.id, it.id) } +
                calendar.journals.map { RecordKey(calendar.id, it.id) }
        }.toSet()
        val nextCalendars = next.calendars.mapTo(mutableSetOf()) { it.id }
        previousRecords.keys
            .filter { it !in nextKeys && it.calendarId in nextCalendars }
            .forEach { queries.deleteRecord(it.calendarId, it.recordId) }
    }

    if (previous == null || previous.busyPeriods != next.busyPeriods) {
        queries.deleteBusyPeriods()
        next.busyPeriods.forEachIndexed { position, interval ->
            queries.insertBusyPeriod(
                position = position.toLong(),
                start_ms = interval.startMs,
                end_ms = interval.endMs,
                payload = AppJson.encodeToString(BusyInterval.serializer(), interval),
                payload_version = CurrentSchemaVersion.toLong(),
            )
        }
    }

    queries.putMeta(PayloadVersionKey, CurrentSchemaVersion.toString())
}

private typealias ProjectionBuilder = () -> RecordProjection

private fun upsertIfChanged(
    queries: com.crko.storage.db.CrkoQueries,
    calendarId: String,
    id: String?,
    current: Any,
    position: Int,
    previous: Map<RecordKey, PreviousRecord>,
    nowMillis: Long,
    build: ProjectionBuilder,
) {
    requireNotNull(id) { "SQLite persistence requires a stable record id (calendar=$calendarId)" }
    val key = RecordKey(calendarId, id)
    val old = previous[key]
    // Untouched records share their data-class reference with the previous batch, so equality exits
    // on the identity check without serializing or walking 20k payloads.
    if (old != null && old.position == position && old.content == current) return
    val projection = build()
    queries.insertRecord(
        calendar_id = calendarId,
        record_id = id,
        kind = projection.kind,
        ics_uid = projection.icsUid,
        recurrence_key = projection.recurrenceKey,
        title = projection.title,
        start_ms = projection.startMs,
        end_ms = projection.endMs,
        due_ms = projection.dueMs,
        completed_ms = projection.completedMs,
        status = projection.status,
        priority = projection.priority,
        payload = projection.payload,
        payload_version = CurrentSchemaVersion.toLong(),
        position = position.toLong(),
        updated_ms = nowMillis,
    )
    queries.updateRecord(
        kind = projection.kind,
        ics_uid = projection.icsUid,
        recurrence_key = projection.recurrenceKey,
        title = projection.title,
        start_ms = projection.startMs,
        end_ms = projection.endMs,
        due_ms = projection.dueMs,
        completed_ms = projection.completedMs,
        status = projection.status,
        priority = projection.priority,
        payload = projection.payload,
        payload_version = CurrentSchemaVersion.toLong(),
        position = position.toLong(),
        updated_ms = nowMillis,
        calendar_id = calendarId,
        record_id = id,
    )
}

private data class RecordKey(val calendarId: String, val recordId: String)

/** Previous batch record and its list position; content is compared by data-class equality. */
private data class PreviousRecord(val position: Int, val content: Any?)

private fun previousRecordMap(state: AppState?): Map<RecordKey, PreviousRecord> {
    if (state == null) return emptyMap()
    val map = LinkedHashMap<RecordKey, PreviousRecord>()
    state.calendars.forEach { calendar ->
        calendar.events.forEachIndexed { position, event ->
            event.id?.let { map[RecordKey(calendar.id, it)] = PreviousRecord(position, event) }
        }
        calendar.todos.forEachIndexed { position, todo ->
            map[RecordKey(calendar.id, todo.id)] = PreviousRecord(position, todo)
        }
        calendar.journals.forEachIndexed { position, journal ->
            map[RecordKey(calendar.id, journal.id)] = PreviousRecord(position, journal)
        }
    }
    return map
}

private data class RecordProjection(
    val kind: String,
    val icsUid: String?,
    val recurrenceKey: String?,
    val title: String,
    val startMs: Long?,
    val endMs: Long?,
    val dueMs: Long?,
    val completedMs: Long?,
    val status: String?,
    val priority: Long?,
    val payload: String,
)

private fun eventProjection(event: Event): RecordProjection = RecordProjection(
    kind = "event",
    icsUid = event.icsUid,
    recurrenceKey = recurrenceKey(event.recurrenceId),
    title = event.title,
    startMs = event.start.indexMillis(),
    endMs = event.end?.indexMillis(),
    dueMs = null,
    completedMs = null,
    status = event.status.name,
    priority = event.priority?.toLong(),
    payload = AppJson.encodeToString(Event.serializer(), event),
)

private fun todoProjection(todo: Todo): RecordProjection = RecordProjection(
    kind = "todo",
    icsUid = todo.icsUid,
    recurrenceKey = recurrenceKey(todo.recurrenceId),
    title = todo.title,
    startMs = todo.start?.indexMillis(),
    endMs = null,
    dueMs = todo.due?.indexMillis(),
    completedMs = todo.completed?.toEpochMilliseconds(),
    status = todo.status.name,
    priority = todo.priority?.toLong(),
    payload = AppJson.encodeToString(Todo.serializer(), todo),
)

private fun journalProjection(journal: Journal): RecordProjection = RecordProjection(
    kind = "journal",
    icsUid = journal.icsUid,
    recurrenceKey = null,
    title = journal.title,
    startMs = journal.start?.indexMillis(),
    endMs = null,
    dueMs = null,
    completedMs = null,
    status = journal.status?.name,
    priority = null,
    payload = AppJson.encodeToString(Journal.serializer(), journal),
)

/**
 * Index millis follow the ADR: DATE is midnight UTC, floating values are read as UTC wall clock, and
 * a zoned value resolves through its calendar-level definition when the platform cannot know it.
 * A failed zone lookup leaves the column null; queries then fall back to the payload.
 */
private fun IcsTimeValue.indexMillis(): Long? = when (this) {
    is IcsTimeValue.Date -> date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    is IcsTimeValue.Floating -> dateTime.toInstant(TimeZone.UTC).toEpochMilliseconds()
    is IcsTimeValue.Zoned -> instantOrNull(TimeZone.UTC)?.toEpochMilliseconds()
    is IcsTimeValue.Utc -> instant.toEpochMilliseconds()
}

/** Stable text encoding of `RECURRENCE-ID`, independent of the payload serializer. */
private fun recurrenceKey(value: IcsTimeValue?): String? = value?.let {
    when (it) {
        is IcsTimeValue.Date -> "date:${it.date}"
        is IcsTimeValue.Floating -> "floating:${it.dateTime}"
        is IcsTimeValue.Zoned -> "zoned:${it.tzid}:${it.dateTime}"
        is IcsTimeValue.Utc -> "utc:${it.instant}"
    }
}

private inline fun <reified T> List<Positioned<T>>.ordered(): List<T> =
    sortedBy { it.position }.map { it.value }

private fun List<Calendar_timezone>.toCalendarTimeZones(): List<CalendarTimeZone> =
    sortedBy { it.position }.map { CalendarTimeZone(it.tzid, it.definition) }

internal const val PayloadVersionKey = "payloadVersion"

/** Written once on the first launch that seeds the database; guards repeat seeding. */
internal const val FirstInitKey = "initialized"

private data class Positioned<T>(val position: Int, val value: T)
private data class DamagedRow(val sourceTable: String, val sourceKey: String, val payload: String)

private data class ReadSnapshot(
    val calendars: List<DbCalendar>,
    val timeZones: List<Calendar_timezone>,
    val records: List<DbRecord>,
    val busy: List<Busy_period>,
    val payloadVersion: Int?,
)
