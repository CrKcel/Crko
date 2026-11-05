package com.crko.state.sync

import com.crko.domain.Calendar
import com.crko.domain.CalendarComponent
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.RecordSyncMeta
import com.crko.domain.SubscriptionFailure
import com.crko.domain.Todo
import com.crko.domain.isNewer
import com.crko.domain.matchesOwnExport
import com.crko.domain.recurrenceKeyText
import com.crko.domain.toIcsText
import com.crko.domain.uidGroup
import com.crko.domain.unfold
import com.crko.domain.importCalendarLines
import com.crko.i18n.Strings
import com.crko.domain.CalendarSyncState
import com.crko.state.AppMessage
import com.crko.state.ChangeOrigin
import com.crko.state.StateAccess
import com.crko.storage.OutboxEntry
import com.crko.storage.OutboxStore
import com.crko.storage.SecretStore
import com.crko.storage.caldavPasswordKey
import com.crko.storage.SubscriptionHttp
import com.crko.sync.CalDavExchange
import com.crko.sync.basicAuthHeader
import com.crko.sync.deleteCalendarResource
import com.crko.sync.getCalendarResource
import com.crko.sync.putCalendarResource
import com.crko.sync.resourceHref
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Drains the outbox of one writable CalDAV calendar into `PUT`/`DELETE` requests, one resource per
 * UID group. Concurrency is optimistic: an upload carries `If-Match` with the etag the record last
 * carried, and a 412 re-reads the server copy, merges by the usual `SEQUENCE`/`DTSTAMP` rule and
 * either accepts the server content or re-uploads the local one with the fresh etag.
 */
internal class CalDavPushService(
    private val access: StateAccess,
    private val outbox: OutboxStore,
    private val http: SubscriptionHttp?,
    private val secretStore: SecretStore?,
    private val clock: Clock,
    private val idProvider: () -> String,
    private val parseDispatcher: CoroutineDispatcher?,
    private val strings: () -> Strings,
) {
    private val state get() = access.state

    /** Whether [calendarId] has pending rows whose retry time has come. */
    fun hasDuePush(calendarId: String, now: Instant = clock.now()): Boolean =
        outbox.entriesFor(calendarId).any { entry ->
            entry.nextAttemptMs == null || entry.nextAttemptMs <= now.toEpochMilliseconds()
        }

    /**
     * Drains every due pending change of [calendarId]. Groups go out one by one; the first
     * transport-level failure ends the pass, because every remaining group would fail the same way.
     */
    suspend fun drainCalendar(calendarId: String) {
        val http = http ?: return
        val sync = state.calendars.firstOrNull { it.id == calendarId }?.syncState ?: return
        if (!sync.writable) return
        val now = clock.now()
        val due = outbox.entriesFor(calendarId).filter { entry ->
            entry.nextAttemptMs == null || entry.nextAttemptMs <= now.toEpochMilliseconds()
        }
        if (due.isEmpty()) return
        val auth = basicAuthHeader(sync.username, secretStore?.load(caldavPasswordKey(calendarId)))
        due.groupBy { it.uid }.forEach { (uid, group) ->
            if (uid.isEmpty()) return@forEach
            if (pushGroup(http, calendarId, sync, auth, uid, group) == GroupOutcome.Stop) return
        }
    }

    private sealed interface GroupOutcome {
        data object Pushed : GroupOutcome
        data object Stop : GroupOutcome
    }

    private suspend fun pushGroup(
        http: SubscriptionHttp,
        calendarId: String,
        sync: CalendarSyncState,
        auth: String?,
        uid: String,
        group: List<OutboxEntry>,
    ): GroupOutcome {
        val calendar = currentCalendar(calendarId) ?: run {
            group.forEach { outbox.delete(it.id) }
            return GroupOutcome.Pushed
        }
        val resource = calendar.uidGroup(uid)
        val knownHref = group.firstNotNullOfOrNull { it.href }
            ?: resource?.components?.firstNotNullOfOrNull { it.sync?.href }
        val targetHref = knownHref ?: resourceHref(sync.url, uid)

        if (group.all { it.isDelete }) {
            return finishDelete(http, calendar, targetHref, group)
        }
        if (resource == null) {
            // The queued records are gone locally (an undo raced the drain): drop the queue.
            group.forEach { outbox.delete(it.id) }
            return GroupOutcome.Pushed
        }
        val groupEtag = group.firstNotNullOfOrNull { it.baseEtag }
        val ics = resource.toIcsText(calendar, clock.now())
        return when (val result = putCalendarResource(http, targetHref, ics, auth, ifMatch = groupEtag)) {
            is CalDavExchange.Applied -> {
                finishUpload(calendarId, uid, targetHref, resolveEtag(http, auth, targetHref, result.etag, groupEtag), group)
                GroupOutcome.Pushed
            }
            CalDavExchange.Gone -> retryUpload(http, calendarId, uid, targetHref, ics, auth, group)
            CalDavExchange.Unauthorized -> unauthorized(calendar, group)
            CalDavExchange.Unreachable -> failed(group, "unreachable")
            is CalDavExchange.Conflict -> resolveConflict(http, calendarId, targetHref, auth, uid, group)
            is CalDavExchange.HttpError -> failed(group, "HTTP ${result.status}")
        }
    }

    /** A delete only conflicts when the server copy changed; there the user's intent wins. */
    private suspend fun finishDelete(
        http: SubscriptionHttp,
        calendar: Calendar,
        href: String,
        group: List<OutboxEntry>,
    ): GroupOutcome {
        val auth = currentAuth(calendar.id)
        val groupEtag = group.firstNotNullOfOrNull { it.baseEtag }
        return when (val result = deleteCalendarResource(http, href, auth, ifMatch = groupEtag)) {
            is CalDavExchange.Applied, CalDavExchange.Gone -> {
                group.forEach { outbox.delete(it.id) }
                GroupOutcome.Pushed
            }
            is CalDavExchange.Conflict -> when (val retried = deleteCalendarResource(http, href, auth)) {
                is CalDavExchange.Applied, CalDavExchange.Gone -> {
                    group.forEach { outbox.delete(it.id) }
                    GroupOutcome.Pushed
                }
                else -> failed(group, "delete conflict ${retried.statusOrNull()}")
            }
            CalDavExchange.Unauthorized -> unauthorized(calendar, group)
            CalDavExchange.Unreachable -> failed(group, "unreachable")
            is CalDavExchange.HttpError -> failed(group, "HTTP ${result.status}")
        }
    }

    /** Re-uploads without a precondition after the server lost the resource. */
    private suspend fun retryUpload(
        http: SubscriptionHttp,
        calendarId: String,
        uid: String,
        href: String,
        ics: String,
        auth: String?,
        group: List<OutboxEntry>,
    ): GroupOutcome {
        val calendar = currentCalendar(calendarId) ?: return failed(group, "calendar gone")
        return when (val retried = putCalendarResource(http, href, ics, auth)) {
            is CalDavExchange.Applied -> {
                finishUpload(calendarId, uid, href, retried.etag, group)
                GroupOutcome.Pushed
            }
            CalDavExchange.Unauthorized -> unauthorized(calendar, group)
            CalDavExchange.Unreachable -> failed(group, "unreachable")
            else -> failed(group, "retry ${retried.statusOrNull()}")
        }
    }

    /**
     * The server copy changed under the queued upload. The server content is read and merged
     * record by record: a server record with a newer `SEQUENCE`/`DTSTAMP` replaces the local one,
     * otherwise the local record survives and the merged group is re-uploaded.
     */
    private suspend fun resolveConflict(
        http: SubscriptionHttp,
        calendarId: String,
        href: String,
        auth: String?,
        uid: String,
        group: List<OutboxEntry>,
    ): GroupOutcome {
        val read = getCalendarResource(http, href, auth)
        val calendar = currentCalendar(calendarId) ?: return failed(group, "calendar gone")
        if (read is CalDavExchange.Unauthorized) return unauthorized(calendar, group)
        if (read is CalDavExchange.Unreachable) return failed(group, "unreachable")
        if (read is CalDavExchange.HttpError) return failed(group, "HTTP ${read.status}")
        if (read is CalDavExchange.Conflict || read is CalDavExchange.Gone) {
            // The server lost the resource between the 412 and the read: upload our content plain.
            val resource = calendar.uidGroup(uid) ?: run {
                group.forEach { outbox.delete(it.id) }
                return GroupOutcome.Pushed
            }
            val ics = resource.toIcsText(calendar, clock.now())
            return when (val retried = putCalendarResource(http, href, ics, auth)) {
                is CalDavExchange.Applied -> {
                    finishUpload(calendarId, uid, href, retried.etag, group)
                    GroupOutcome.Pushed
                }
                CalDavExchange.Unauthorized -> unauthorized(calendar, group)
                CalDavExchange.Unreachable -> failed(group, "unreachable")
                else -> failed(group, "conflict retry ${retried.statusOrNull()}")
            }
        }
        val applied = read as CalDavExchange.Applied
        val deleteKeys = group.filter { it.isDelete }.mapTo(mutableSetOf()) { it.recurrenceKey }
        val parsed = withContext(parseDispatcher ?: Dispatchers.Default) {
            importCalendarLines(
                unfold(applied.body.orEmpty()).asSequence(),
                TimeZone.currentSystemDefault(),
                strings().untitledEvent,
            )
        }
        val stamp = RecordSyncMeta(href, applied.etag)
        var events = calendar.events
        var todos = calendar.todos
        var journals = calendar.journals
        var serverWonEverything = true
        parsed.events
            .filter { recurrenceKeyText(it.recurrenceId) !in deleteKeys }
            .map { it.copy(sync = stamp) }
            .forEach { server ->
                val local = events.firstOrNull { it.icsUid == uid && it.recurrenceId == server.recurrenceId }
                when {
                    local == null -> events = events + server.copy(id = idProvider())
                    isNewer(server.sequence, server.dtStamp, server.lastModified, local.sequence, local.dtStamp, local.lastModified) ->
                        events = events.map { if (it.id == local.id) server.copy(id = local.id) else it }
                    else -> serverWonEverything = false
                }
            }
        parsed.todos
            .filter { recurrenceKeyText(it.recurrenceId) !in deleteKeys }
            .map { it.copy(sync = stamp) }
            .forEach { server ->
                val local = todos.firstOrNull { it.icsUid == uid && it.recurrenceId == server.recurrenceId }
                when {
                    local == null -> todos = todos + server.copy(id = idProvider())
                    isNewer(server.sequence, server.dtStamp, server.lastModified, local.sequence, local.dtStamp, local.lastModified) ->
                        todos = todos.map { if (it.id == local.id) server.copy(id = local.id) else it }
                    else -> serverWonEverything = false
                }
            }
        parsed.journals.map { it.copy(sync = stamp) }.forEach { server ->
            val local = journals.firstOrNull { it.icsUid == uid }
            when {
                local == null -> journals = journals + server.copy(id = idProvider())
                isNewer(server.sequence, server.dtStamp, server.lastModified, local.sequence, local.dtStamp, local.lastModified) ->
                    journals = journals.map { if (it.id == local.id) server.copy(id = local.id) else it }
                else -> serverWonEverything = false
            }
        }
        if (serverWonEverything) {
            // Every local record was behind the server: adopt the server content and drop the queue.
            replaceCalendarRecords(calendarId, events, todos, journals)
            group.forEach { outbox.delete(it.id) }
            return GroupOutcome.Pushed
        }
        val merged = currentCalendar(calendarId)?.copy(events = events, todos = todos, journals = journals)
        val resource = merged?.uidGroup(uid) ?: run {
            group.forEach { outbox.delete(it.id) }
            return GroupOutcome.Pushed
        }
        val ics = resource.toIcsText(merged, clock.now())
        return when (val retried = putCalendarResource(http, href, ics, auth, ifMatch = applied.etag)) {
            is CalDavExchange.Applied -> {
                stampRecords(calendarId, uid, RecordSyncMeta(href, retried.etag ?: applied.etag))
                group.forEach { outbox.delete(it.id) }
                GroupOutcome.Pushed
            }
            CalDavExchange.Unauthorized -> unauthorized(calendar, group)
            CalDavExchange.Unreachable -> failed(group, "unreachable")
            else -> failed(group, "conflict retry ${retried.statusOrNull()}")
        }
    }

    /** Fresh etag for an upload whose answer carried none: one read, best effort. */
    private suspend fun resolveEtag(
        http: SubscriptionHttp,
        auth: String?,
        href: String,
        returnedEtag: String?,
        hadEtag: String?,
    ): String? {
        if (returnedEtag != null || hadEtag == null) return returnedEtag
        val read = getCalendarResource(http, href, auth)
        return (read as? CalDavExchange.Applied)?.etag ?: returnedEtag
    }

    private fun finishUpload(calendarId: String, uid: String, href: String, etag: String?, group: List<OutboxEntry>) {
        stampRecords(calendarId, uid, RecordSyncMeta(href, etag))
        group.forEach { outbox.delete(it.id) }
    }

    /** Stamps every record of the UID group with the resource location the server confirmed. */
    private fun stampRecords(calendarId: String, uid: String, stamp: RecordSyncMeta) {
        val index = state.calendars.indexOfFirst { it.id == calendarId }
        if (index < 0) return
        val calendar = state.calendars[index]
        val updated = calendar.copy(
            events = calendar.events.map { if (recordMatchesUid(it.icsUid, it.id, uid)) it.copy(sync = stamp) else it },
            todos = calendar.todos.map { if (recordMatchesUid(it.icsUid, it.id, uid)) it.copy(sync = stamp) else it },
            journals = calendar.journals.map { if (recordMatchesUid(it.icsUid, it.id, uid)) it.copy(sync = stamp) else it },
        )
        access.persist(
            state.copy(calendars = state.calendars.toMutableList().also { it[index] = updated }),
            ChangeOrigin.Sync,
        )
    }

    private fun replaceCalendarRecords(calendarId: String, events: List<Event>, todos: List<Todo>, journals: List<Journal>) {
        val index = state.calendars.indexOfFirst { it.id == calendarId }
        if (index < 0) return
        val updated = state.calendars[index].copy(events = events, todos = todos, journals = journals)
        access.persist(
            state.copy(calendars = state.calendars.toMutableList().also { it[index] = updated }),
            ChangeOrigin.Sync,
        )
    }

    private fun currentCalendar(calendarId: String): Calendar? =
        state.calendars.firstOrNull { it.id == calendarId }

    private suspend fun currentAuth(calendarId: String): String? {
        val sync = currentCalendar(calendarId)?.syncState ?: return null
        return basicAuthHeader(sync.username, secretStore?.load(caldavPasswordKey(calendarId)))
    }

    private fun recordMatchesUid(icsUid: String?, recordId: String?, uid: String): Boolean =
        icsUid == uid || matchesOwnExport(recordId, uid)

    private fun unauthorized(calendar: Calendar, group: List<OutboxEntry>): GroupOutcome {
        recordFailure(group, "unauthorized")
        access.notify(AppMessage.SubscriptionFailed(calendar.name, SubscriptionFailure.Unauthorized))
        return GroupOutcome.Stop
    }

    private fun failed(group: List<OutboxEntry>, message: String): GroupOutcome {
        recordFailure(group, message)
        return GroupOutcome.Stop
    }

    private fun recordFailure(group: List<OutboxEntry>, message: String) {
        val now = clock.now().toEpochMilliseconds()
        group.forEach { row ->
            val attempts = row.attempts + 1
            outbox.updateAttempt(row.id, attempts, now + backoffMillis(attempts), message)
        }
    }

    /** 1 min, 5 min, 30 min, then every 2 hours until a fresh edit or a success resets the row. */
    private fun backoffMillis(attempts: Long): Long = when {
        attempts <= 1 -> 60_000L
        attempts == 2L -> 5 * 60_000L
        attempts == 3L -> 30 * 60_000L
        else -> 2 * 60 * 60_000L
    }
}

private fun CalDavExchange.statusOrNull(): Int? = when (this) {
    is CalDavExchange.HttpError -> status
    is CalDavExchange.Conflict -> status
    else -> null
}
