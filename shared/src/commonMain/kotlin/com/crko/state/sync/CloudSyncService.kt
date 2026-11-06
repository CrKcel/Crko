package com.crko.state.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppJson
import com.crko.domain.Calendar
import com.crko.domain.CalendarComponent
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.PersistedSettings
import com.crko.domain.Todo
import com.crko.domain.isNewer
import com.crko.domain.recurrenceKeyText
import com.crko.domain.withAssignedUid
import com.crko.state.AppMessage
import com.crko.state.ChangeOrigin
import com.crko.state.StateAccess
import com.crko.storage.CloudAccountStore
import com.crko.storage.CloudCrypto
import com.crko.storage.CloudKeyBytes
import com.crko.storage.OutboxBackend
import com.crko.storage.OutboxEntry
import com.crko.storage.OutboxStore
import com.crko.storage.SecretStore
import com.crko.storage.SubscriptionHttp
import com.crko.storage.cloudEncKeyKey
import com.crko.storage.cloudTokenKey
import com.crko.storage.openText
import com.crko.sync.CloudChange
import com.crko.sync.CloudOutcome
import com.crko.sync.cloudPull
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The cloud sync pass: bootstrap, push, pull. Enabled once, the service queues everything local
 * into the outbox ([bootstrap]), pushes it encrypted, and then keeps pulling the server's change
 * log, applying each change through the same last-writer-wins rule the CalDAV merge uses. One pass
 * at a time; the UI watches [isSyncing].
 */
internal class CloudSyncService(
    private val access: StateAccess,
    private val scope: CoroutineScope,
    private val outbox: OutboxStore,
    private val http: SubscriptionHttp?,
    private val crypto: CloudCrypto?,
    private val accountStore: CloudAccountStore,
    private val secretStore: SecretStore?,
    private val clock: Clock,
    private val idProvider: () -> String,
    private val settingsJson: () -> String,
    private val applySettings: (PersistedSettings) -> Unit,
    private val push: CloudPushService,
    /** Where payload work runs; tests pin it to null to keep every step on the test scheduler. */
    private val workDispatcher: CoroutineDispatcher? = Dispatchers.Default,
) {
    var isSyncing by mutableStateOf(false)
        private set

    /** Epoch millis of the last completed pass, exposed as state so the settings page refreshes. */
    var lastSyncMs by mutableStateOf(accountStore.loadLastSyncMs())
        private set

    /** Re-reads the persisted pass timestamp after sign-in/out reset the bookkeeping. */
    fun reloadLastSyncMs() {
        lastSyncMs = accountStore.loadLastSyncMs()
    }

    private val versions = CloudVersionBook(accountStore)

    private var job: Job? = null

    /** Epoch millis of the last pull, so the foreground poll does not hammer the server. */
    private var lastPullMs = 0L

    /**
     * Runs one push+pull pass unless one is already running. [notify] reports the outcome as a
     * message (manual syncs); pulls otherwise wait for [CloudPullIntervalMs] since the last one —
     * a push with work always pulls, so an edit lands and its confirmation comes back in one pass.
     */
    fun syncNow(notify: Boolean = false) {
        if (job?.isActive == true) return
        if (http == null || crypto == null) return
        if (accountStore.load()?.enabled != true) return
        job = scope.launch {
            isSyncing = true
            try {
                // Sealing and decoding payloads is CPU work; keep it off the caller's frame.
                if (workDispatcher != null) {
                    withContext(workDispatcher) { runPass(notify) }
                } else {
                    runPass(notify)
                }
            } finally {
                isSyncing = false
                reloadLastSyncMs()
            }
        }
    }

    private suspend fun runPass(notify: Boolean) {
        val account = accountStore.load()?.takeIf { it.enabled } ?: return
        if (!accountStore.loadBootstrapped()) bootstrap()
        val pushed = push.drain()
        val now = clock.now().toEpochMilliseconds()
        val stats = if (notify || pushed || now - lastPullMs >= CloudPullIntervalMs) {
            lastPullMs = now
            pull(account.server)
        } else {
            PullStats()
        }
        accountStore.saveLastSyncMs(clock.now().toEpochMilliseconds())
        if (notify) {
            when {
                // The pull already reported the expired token; do not stack a generic failure on it.
                stats.authExpired -> Unit
                stats.failed -> access.notify(AppMessage.CloudFailed)
                stats.isEmpty -> access.notify(AppMessage.CloudUnchanged)
                else -> access.notify(AppMessage.CloudSynced(stats.events, stats.todos, stats.journals, stats.removed))
            }
        }
    }

    /**
     * Queues every local calendar and record of a fresh account, so the server learns the whole
     * local state. Records without a UID gain their publish UID here; rows already pending win, so
     * an edit made between enabling and the first pass is never overwritten by the bootstrap.
     */
    private fun bootstrap() {
        var next = access.state
        var changed = false
        next.calendars.forEachIndexed { calendarIndex, calendar ->
            if (!calendar.isLocal) return@forEachIndexed
            enqueueRow(
                calendarId = calendar.id,
                entity = CloudKind.Calendar,
                uid = calendar.id,
                recurrenceKey = "",
                operation = OutboxEntry.Operation.Create,
                sequence = 0,
            )
            val events = calendar.events.map { enqueueBootstrapRecord(calendar.id, CloudKind.Event, it, it.id) as? Event ?: it }
            val todos = calendar.todos.map { enqueueBootstrapRecord(calendar.id, CloudKind.Todo, it, it.id) as? Todo ?: it }
            val journals = calendar.journals.map { enqueueBootstrapRecord(calendar.id, CloudKind.Journal, it, it.id) as? Journal ?: it }
            if (events !== calendar.events || todos !== calendar.todos || journals !== calendar.journals) {
                changed = true
                next = next.copy(
                    calendars = next.calendars.toMutableList().also {
                        it[calendarIndex] = calendar.copy(events = events, todos = todos, journals = journals)
                    },
                )
            }
        }
        if (changed) access.persist(next, ChangeOrigin.Sync)
        accountStore.saveBootstrapped(true)
    }

    /**
     * Queues one record for the initial push, assigning its publish UID when missing. Returns the
     * record unchanged when it cannot be keyed (no id) — such a record stays local-only.
     */
    private fun enqueueBootstrapRecord(calendarId: String, kind: String, record: CalendarComponent, id: String?): CalendarComponent {
        val prepared = record.withAssignedUid(id)
        val uid = prepared.icsUid ?: return record
        val key = recurrenceKeyText(prepared.recurrenceId)
        enqueueRow(calendarId, kind, uid, key, OutboxEntry.Operation.Create, prepared.sequence.toLong())
        return prepared
    }

    /**
     * Queues one bootstrap row unless the same entity already waits in the outbox. The pending row
     * wins: it carries the edit made between enabling and the first pass, and inserting a duplicate
     * also violates the outbox's unique key.
     */
    private fun enqueueRow(
        calendarId: String,
        entity: String,
        uid: String,
        recurrenceKey: String,
        operation: String,
        sequence: Long,
    ) {
        if (outbox.entry(OutboxBackend.Cloud, calendarId, entity, uid, recurrenceKey) != null) return
        val now = clock.now().toEpochMilliseconds()
        outbox.upsert(
            OutboxEntry(
                calendarId = calendarId,
                entity = entity,
                uid = uid,
                recurrenceKey = recurrenceKey,
                operation = operation,
                sequence = sequence,
                backend = OutboxBackend.Cloud,
                createdMs = now,
                updatedMs = now,
            ),
        )
    }

    private class PullStats {
        var events = 0
        var todos = 0
        var journals = 0
        var removed = 0
        var failed = false
        var authExpired = false

        val isEmpty: Boolean get() = events == 0 && todos == 0 && journals == 0 && removed == 0

        fun count(kind: String) = when (kind) {
            CloudKind.Event -> events += 1
            CloudKind.Todo -> todos += 1
            else -> journals += 1
        }
    }

    /**
     * Pulls the change log from the stored cursor until a page comes back short, applying every
     * change to the local state. Calendar configs apply before records, so a freshly created
     * calendar receives its records in the same page.
     */
    private suspend fun pull(server: String): PullStats {
        val stats = PullStats()
        val ready = pullContext(server) ?: run {
            stats.failed = true
            return stats
        }
        var cursor = accountStore.loadCursor()
        while (true) {
            when (val outcome = cloudPull(ready.server, ready.token, cursor, CloudPullPage, http!!)) {
                is CloudOutcome.Success -> {
                    val applied = applyChanges(outcome.value.changes, ready)
                    stats.events += applied.events
                    stats.todos += applied.todos
                    stats.journals += applied.journals
                    stats.removed += applied.removed
                    val next = outcome.value.cursor
                    accountStore.saveCursor(next)
                    // A page that brings no cursor progress would make the loop spin forever.
                    val exhausted = outcome.value.changes.size < CloudPullPage || next <= cursor
                    cursor = next
                    if (exhausted) break
                }
                is CloudOutcome.Failure -> {
                    stats.failed = true
                    if (outcome.status == 401 || outcome.status == 403) {
                        stats.authExpired = true
                        access.notify(AppMessage.CloudAuthExpired)
                    }
                    break
                }
                CloudOutcome.Unreachable -> {
                    stats.failed = true
                    break
                }
            }
        }
        return stats
    }

    private class PullContext(val server: String, val token: String, val crypto: CloudCrypto, val encKey: ByteArray)

    private suspend fun pullContext(server: String): PullContext? {
        val token = secretStore?.load(cloudTokenKey()) ?: return null
        val encKey = secretStore.load(cloudEncKeyKey())
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
            ?.takeIf { it.size == CloudKeyBytes }
            ?: return null
        return PullContext(server, token, crypto ?: return null, encKey)
    }

    /** Applies one page: settings first, then calendar configs, then records — two ordered passes. */
    private fun applyChanges(changes: List<CloudChange>, ready: PullContext): PullStats {
        val stats = PullStats()
        changes.forEach { change ->
            if (change.kind == CloudKind.Settings) applySettingsChange(change, ready)
        }
        var calendars = access.state.calendars
        var calendarsChanged = false
        changes.forEach { change ->
            if (change.kind != CloudKind.Calendar) return@forEach
            val calendarId = change.key
            val versionKey = cloudCalendarVersionKey(calendarId)
            // Same rule as records: a pending local row carries the newest local intent, so a
            // fetched config must not overwrite the edit the push is about to send.
            if (outbox.entry(OutboxBackend.Cloud, calendarId, CloudKind.Calendar, calendarId, "") != null) return@forEach
            if (!versions.isNewer(versionKey, change.seq, change.ts)) return@forEach
            if (change.deleted) {
                val before = calendars.size
                calendars = calendars.filterNot { it.id == calendarId && it.isLocal }
                if (calendars.size != before) {
                    calendarsChanged = true
                    stats.removed += 1
                }
                versions.record(versionKey, change.seq, change.ts)
                return@forEach
            }
            val config = decryptConfig(change, ready) ?: return@forEach
            val existing = calendars.firstOrNull { it.id == calendarId }
            when {
                existing == null -> {
                    calendars = calendars + Calendar(
                        id = config.id,
                        name = config.name,
                        colorArgb = config.colorArgb,
                        visible = config.visible,
                        timeZones = config.timeZones,
                    )
                    calendarsChanged = true
                }
                existing.isLocal -> {
                    calendars = calendars.map {
                        if (it.id == calendarId) {
                            it.copy(name = config.name, colorArgb = config.colorArgb, visible = config.visible, timeZones = config.timeZones)
                        } else {
                            it
                        }
                    }
                    calendarsChanged = true
                }
                // A CalDAV calendar is owned by its server; the cloud row loses, but the tuple is
                // still consumed so older log entries cannot churn the pull.
            }
            versions.record(versionKey, change.seq, change.ts)
        }
        changes.forEach { change ->
            val kind = change.kind
            if (kind != CloudKind.Event && kind != CloudKind.Todo && kind != CloudKind.Journal) return@forEach
            val (calendarId, uid, recurrenceKey) = splitCloudRecordKey(change.key) ?: return@forEach
            // A record with a pending cloud row is never touched: the pending push wins, and the
            // pull returns to this key after the push landed.
            if (outbox.entry(OutboxBackend.Cloud, calendarId, kind, uid, recurrenceKey) != null) return@forEach
            val index = calendars.indexOfFirst { it.id == calendarId }
            if (index < 0) return@forEach
            val local = findCloudRecord(calendars[index], kind, uid, recurrenceKey)
            when {
                local == null && change.deleted -> Unit
                local == null -> {
                    val remote = decodeRecord(change, ready) ?: return@forEach
                    calendars = insertRecord(calendars, index, kind, remote)
                    calendarsChanged = true
                    stats.count(kind)
                }
                change.deleted -> {
                    // The tombstone wins only when it outranks the local revision outright; a tie
                    // keeps the local edit, so a concurrent change is never lost to a deletion.
                    if (change.seq > local.sequence) {
                        calendars = removeRecord(calendars, index, kind, local)
                        calendarsChanged = true
                        stats.removed += 1
                    }
                }
                else -> {
                    val remote = decodeRecord(change, ready) ?: return@forEach
                    if (!isNewer(remote.sequence, remote.dtStamp, remote.lastModified, local.sequence, local.dtStamp, local.lastModified)) {
                        return@forEach
                    }
                    if (sameContent(local, remote)) return@forEach
                    calendars = replaceRecord(calendars, index, kind, local, remote)
                    calendarsChanged = true
                    stats.count(kind)
                }
            }
        }
        if (calendarsChanged) access.persist(access.state.copy(calendars = calendars), ChangeOrigin.Sync)
        return stats
    }

    private fun applySettingsChange(change: CloudChange, ready: PullContext) {
        if (change.deleted) return
        // Settings carry no SEQUENCE; the change log still holds ops the server rejected as stale.
        if (!versions.isNewer(CloudSettingsKey, change.seq, change.ts)) return
        val payload = change.payload ?: return
        val json = ready.crypto.openText(ready.encKey, payload, cloudPayloadAad(CloudKind.Settings, CloudSettingsKey)) ?: return
        if (json == accountStore.loadAppliedSettings()) {
            versions.record(CloudSettingsKey, change.seq, change.ts)
            return
        }
        val settings = runCatching { AppJson.decodeFromString(PersistedSettings.serializer(), json) }.getOrNull() ?: return
        applySettings(settings)
        // The remote version now IS the local one; both fingerprints advance so neither side re-pushes.
        accountStore.saveAppliedSettings(json)
        accountStore.savePushedSettings(json)
        versions.record(CloudSettingsKey, change.seq, change.ts)
    }

    private fun decryptConfig(change: CloudChange, ready: PullContext): CloudCalendarConfig? {
        if (change.deleted) return null
        val payload = change.payload ?: return null
        val json = ready.crypto.openText(ready.encKey, payload, cloudPayloadAad(CloudKind.Calendar, change.key)) ?: return null
        return runCatching { AppJson.decodeFromString(CloudCalendarConfig.serializer(), json) }.getOrNull()
    }

    /** Decrypts and decodes one record payload; null on any failure along the way. */
    private fun decodeRecord(change: CloudChange, ready: PullContext): CalendarComponent? {
        val payload = change.payload ?: return null
        val json = ready.crypto.openText(ready.encKey, payload, cloudPayloadAad(change.kind, change.key)) ?: return null
        return decodeCloudRecord(change.kind, json)
    }

    /** Inserts a record the calendar has never seen, keeping the payload's own id when present. */
    private fun insertRecord(calendars: List<Calendar>, index: Int, kind: String, record: CalendarComponent): List<Calendar> {
        val calendar = calendars[index]
        return calendars.toMutableList().also { list ->
            list[index] = when (kind) {
                CloudKind.Event -> {
                    val typed = record as Event
                    calendar.copy(events = calendar.events + typed.copy(id = typed.id ?: idProvider()))
                }
                CloudKind.Todo -> {
                    val typed = record as Todo
                    calendar.copy(todos = calendar.todos + typed.copy(id = typed.id ?: idProvider()))
                }
                else -> {
                    val typed = record as Journal
                    calendar.copy(journals = calendar.journals + typed.copy(id = typed.id ?: idProvider()))
                }
            }
        }
    }

    private fun removeRecord(calendars: List<Calendar>, index: Int, kind: String, record: CalendarComponent): List<Calendar> {
        val calendar = calendars[index]
        return calendars.toMutableList().also { list ->
            list[index] = when (kind) {
                CloudKind.Event -> calendar.copy(events = calendar.events.filterNot { it.id == (record as Event).id })
                CloudKind.Todo -> calendar.copy(todos = calendar.todos.filterNot { it.id == (record as Todo).id })
                else -> calendar.copy(journals = calendar.journals.filterNot { it.id == (record as Journal).id })
            }
        }
    }

    /** Replaces the local record's content with the newer remote one, keeping the local id. */
    private fun replaceRecord(
        calendars: List<Calendar>,
        index: Int,
        kind: String,
        local: CalendarComponent,
        remote: CalendarComponent,
    ): List<Calendar> {
        val calendar = calendars[index]
        return calendars.toMutableList().also { list ->
            list[index] = when (kind) {
                CloudKind.Event -> {
                    val typed = remote as Event
                    val kept = (local as Event).id
                    calendar.copy(events = calendar.events.map { if (it.id == kept) typed.copy(id = it.id) else it })
                }
                CloudKind.Todo -> {
                    val typed = remote as Todo
                    val kept = (local as Todo).id
                    calendar.copy(todos = calendar.todos.map { if (it.id == kept) typed.copy(id = it.id) else it })
                }
                else -> {
                    val typed = remote as Journal
                    val kept = (local as Journal).id
                    calendar.copy(journals = calendar.journals.map { if (it.id == kept) typed.copy(id = it.id) else it })
                }
            }
        }
    }

    /** Content equality that ignores the local id: same payload means no update is due. */
    private fun sameContent(local: CalendarComponent, remote: CalendarComponent): Boolean = when {
        local is Event && remote is Event -> local.copy(id = remote.id) == remote
        local is Todo && remote is Todo -> local.copy(id = remote.id) == remote
        local is Journal && remote is Journal -> local.copy(id = remote.id) == remote
        else -> false
    }

    private companion object {
        const val CloudPullPage = 200

        /** The foreground poll runs every minute; a pull at most every five. */
        const val CloudPullIntervalMs = 5 * 60_000L
    }
}
