package com.crko.state.subscription

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppState
import com.crko.domain.CalDavChange
import com.crko.domain.Calendar
import com.crko.domain.CalendarSubscription
import com.crko.domain.RecordSyncMeta
import com.crko.domain.SubscriptionFailure
import com.crko.domain.SubscriptionFetch
import com.crko.domain.gatedCancellations
import com.crko.domain.importCalendarLines
import com.crko.domain.mergeBusyPeriods
import com.crko.domain.mergeCalendarTimeZones
import com.crko.domain.mergeEvents
import com.crko.domain.mergeJournals
import com.crko.domain.mergeTodos
import com.crko.domain.recurrenceKeyText
import com.crko.domain.registerIcsTimeZones
import com.crko.domain.subscription
import com.crko.domain.toSyncState
import com.crko.domain.unfold
import com.crko.i18n.Strings
import com.crko.state.AppMessage
import com.crko.state.ChangeOrigin
import com.crko.state.ImportOutcome
import com.crko.state.StateAccess
import com.crko.state.`import`.AttachmentMaterializer
import com.crko.state.sync.CalDavPushService
import com.crko.storage.AttachmentRepository
import com.crko.storage.OutboxStore
import com.crko.storage.SecretStore
import com.crko.storage.SubscriptionHttp
import com.crko.storage.caldavPasswordKey
import com.crko.sync.fetchSubscription
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Background synchronisation of subscribed calendars. Fetching and applying one feed is isolated
 * here so the state facade only exposes the public entry points; persistence and messages go
 * through [StateAccess], and [strings] is read lazily so a language switch follows the import
 * placeholder without rebuilding the service.
 *
 * A writable CalDAV calendar first drains its outbox through [CalDavPushService] and only then
 * pulls, so a server-side conflict surfaces inside one pass.
 */
internal class SubscriptionSyncService(
    private val access: StateAccess,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val http: SubscriptionHttp?,
    attachmentRepository: AttachmentRepository,
    private val parseDispatcher: CoroutineDispatcher?,
    private val idProvider: () -> String,
    private val outbox: OutboxStore,
    private val secretStore: SecretStore?,
    private val strings: () -> Strings,
) {
    private val state: AppState get() = access.state

    private val attachments = AttachmentMaterializer(attachmentRepository)

    private val push = CalDavPushService(
        access = access,
        outbox = outbox,
        http = http,
        secretStore = secretStore,
        clock = clock,
        idProvider = idProvider,
        parseDispatcher = parseDispatcher,
        strings = strings,
    )

    /** Index of the calendar that owns the record selected by [contains]. */
    private fun calendarIndexContaining(contains: (Calendar) -> Boolean): Int? =
        state.calendars.indexOfFirst(contains).takeIf { it >= 0 }

    /** Replaces one calendar, leaving every other calendar's records untouched. */
    private fun AppState.replacingCalendar(index: Int, transform: (Calendar) -> Calendar): AppState {
        if (index !in calendars.indices) return this
        val updated = calendars.toMutableList()
        updated[index] = transform(updated[index])
        return copy(calendars = updated)
    }

    /** Subscriptions whose automatic refresh interval has elapsed at [now]. */
    fun subscriptionsDue(now: Instant = clock.now()): List<CalendarSubscription> =
        state.subscriptions.filter { subscription ->
            if (!subscription.enabled || subscription.refreshMinutes <= 0) return@filter false
            val last = subscription.lastSync?.let(::parseInstantOrNull)?.epochSeconds ?: return@filter true
            now.epochSeconds - last >= subscription.refreshMinutes * 60L
        }

    private val syncJobs = mutableMapOf<String, Job>()

    /** Ids with a sync currently running. Compose state, so the UI follows start and finish. */
    private var activeSyncIds by mutableStateOf(emptySet<String>())

    /**
     * Syncs one subscription through the platform HTTP bridge. A sync that is still running is not
     * started again, and without a bridge the call does nothing. A writable calendar pushes its
     * pending local changes before it pulls.
     */
    fun syncSubscription(id: String) {
        val subscription = state.calendars.firstOrNull { it.id == id }?.subscription ?: return
        val http = http ?: return
        if (syncJobs[id]?.isActive == true) return
        activeSyncIds = activeSyncIds + id
        syncJobs[id] = scope.launch {
            val fetch = runCatching {
                if (subscription.writable) push.drainCalendar(id)
                fetchSubscription(subscription, http, password = passwordFor(id))
            }.getOrElse { SubscriptionFetch.Failed(SubscriptionFailure.Unreachable) }
            if (subscription.writable) {
                applyWritableFetch(subscription, fetch, http)
            } else {
                applySubscriptionFetch(subscription, fetch, http)
            }
        }.also { job ->
            job.invokeOnCompletion { activeSyncIds = activeSyncIds - id }
        }
    }

    /** Syncs every subscription whose refresh interval has elapsed. */
    fun syncDueSubscriptions() {
        subscriptionsDue().forEach { syncSubscription(it.id) }
        // A failed push keeps retrying on the polling cadence even when the pull is not due yet.
        state.calendars
            .filter { it.syncState?.writable == true && push.hasDuePush(it.id) }
            .forEach { syncSubscription(it.id) }
    }

    /** Whether [id] currently has a sync running. */
    fun isSyncing(id: String): Boolean = id in activeSyncIds

    /** The stored password of a calendar's account; null keeps the request anonymous. */
    private suspend fun passwordFor(calendarId: String): String? {
        val username = state.calendars.firstOrNull { it.id == calendarId }?.syncState?.username ?: return null
        if (username.isBlank()) return null
        return secretStore?.load(caldavPasswordKey(calendarId))
    }

    private fun updateSubscription(id: String, transform: (CalendarSubscription) -> CalendarSubscription) {
        val index = calendarIndexContaining { it.id == id && !it.isLocal } ?: return
        val current = state.calendars[index].subscription ?: return
        val next = transform(current)
        access.persist(
            state.replacingCalendar(index) { calendar ->
                calendar.copy(name = next.name, syncState = next.toSyncState())
            },
            ChangeOrigin.Sync,
        )
    }

    /**
     * The two-way apply path of a writable calendar: every entry is stamped with the resource
     * location the server reported and merged into the calendar, so records carry the href/etag of
     * the next upload. Pulled records persist with the Sync origin and never enqueue pushes.
     */
    private suspend fun applyWritableFetch(
        subscription: CalendarSubscription,
        fetch: SubscriptionFetch,
        http: SubscriptionHttp,
    ) {
        when (fetch) {
            is SubscriptionFetch.Failed ->
                access.notify(AppMessage.SubscriptionFailed(subscription.name, fetch.failure))
            SubscriptionFetch.Unchanged ->
                updateSubscription(subscription.id) { it.copy(lastSync = clock.now().toString()) }
            is SubscriptionFetch.Full -> {
                if (fetch.entries.isEmpty() && fetch.text.isNotBlank()) {
                    // A plain GET answered with a feed: merge its records, but never reconcile
                    // removals — a writable calendar's removals always arrive through the REPORT
                    // entries, so a text answer must not be allowed to empty the calendar.
                    applySubscriptionContent(subscription, fetch.text, removeVanished = false)
                } else {
                    applyWritableContent(subscription, fetch.entries, removeVanished = true)
                }
                updateSubscription(subscription.id) {
                    it.copy(
                        lastSync = clock.now().toString(),
                        syncToken = fetch.syncToken ?: it.syncToken,
                        caldav = true,
                    )
                }
            }
            is SubscriptionFetch.Changes -> {
                applyWritableContent(subscription, fetch.entries, removeVanished = false)
                if (fetch.deletions) {
                    // An incremental report cannot name what vanished upstream; one full fetch
                    // reconciles the local copy before the sync is called done. The token only
                    // advances once that reconciliation succeeded.
                    val full = runCatching {
                        fetchSubscription(
                            subscription.copy(caldav = true, syncToken = null),
                            http,
                            password = passwordFor(subscription.id),
                        )
                    }.getOrElse { SubscriptionFetch.Failed(SubscriptionFailure.Unreachable) }
                    if (full is SubscriptionFetch.Full) {
                        applyWritableContent(subscription, full.entries, removeVanished = true)
                        updateSubscription(subscription.id) {
                            it.copy(syncToken = full.syncToken ?: it.syncToken, lastSync = clock.now().toString())
                        }
                    } else {
                        // The next sync falls back to a full query, which still names the vanished
                        // records this sync could not apply.
                        updateSubscription(subscription.id) {
                            it.copy(syncToken = null, lastSync = clock.now().toString())
                        }
                    }
                } else {
                    updateSubscription(subscription.id) {
                        it.copy(lastSync = clock.now().toString(), syncToken = fetch.syncToken ?: it.syncToken)
                    }
                }
            }
        }
    }

    /** UIDs with a pending upload: the pull must not remove the records they belong to. */
    private fun protectedUids(calendarId: String): Set<String> =
        outbox.entriesFor(calendarId).mapTo(mutableSetOf()) { it.uid }

    /**
     * Merges the fetched entries into the stored calendar. With [removeVanished] the records the
     * server no longer describes are removed — except the ones with a pending upload.
     */
    private suspend fun applyWritableContent(
        subscription: CalendarSubscription,
        entries: List<CalDavChange>,
        removeVanished: Boolean,
    ): ImportOutcome {
        val index = calendarIndexContaining { it.id == subscription.id && !it.isLocal }
            ?: return ImportOutcome()
        val base = state.calendars[index]
        var events = base.events
        var todos = base.todos
        var journals = base.journals
        var timeZones = base.timeZones
        val serverUids = mutableSetOf<String>()
        val serverSequences = mutableMapOf<String, Int>()
        val protected = protectedUids(subscription.id)
        entries.forEach { change ->
            val text = change.text
            if (change.vanished || text.isNullOrBlank()) return@forEach
            val parsed = withContext(parseDispatcher ?: Dispatchers.Default) {
                importCalendarLines(unfold(text).asSequence(), TimeZone.currentSystemDefault(), strings().untitledEvent)
            }
            // The resource's own zones belong to this calendar, like a feed's do.
            registerIcsTimeZones(parsed.timeZones, subscription.id)
            timeZones = mergeCalendarTimeZones(timeZones, parsed.timeZones)
            val stamp = RecordSyncMeta(change.href, change.etag)
            parsed.events.forEach {
                it.icsUid?.let(serverUids::add)
                it.icsUid?.let { uid -> serverSequences[uid + "|" + recurrenceKeyText(it.recurrenceId)] = it.sequence }
            }
            parsed.todos.forEach {
                it.icsUid?.let(serverUids::add)
                it.icsUid?.let { uid -> serverSequences[uid + "|" + recurrenceKeyText(it.recurrenceId)] = it.sequence }
            }
            parsed.journals.forEach {
                it.icsUid?.let(serverUids::add)
                it.icsUid?.let { uid -> serverSequences[uid + "|"] = it.sequence }
            }
            events = mergeEvents(
                base = events,
                incoming = parsed.events.map { it.copy(sync = stamp) },
                cancelledUids = gatedCancellations(parsed, events),
                source = null,
                removeVanished = false,
                idProvider = idProvider,
                protectedUids = protected,
            ).items
            todos = mergeTodos(
                base = todos,
                incoming = parsed.todos.map { it.copy(sync = stamp) },
                source = null,
                removeVanished = false,
                idProvider = idProvider,
                protectedUids = protected,
            ).items
            journals = mergeJournals(
                base = journals,
                incoming = parsed.journals.map { it.copy(sync = stamp) },
                source = null,
                removeVanished = false,
                idProvider = idProvider,
                protectedUids = protected,
            ).items
        }
        if (removeVanished) {
            // A record without a UID never reached the server and has nothing to vanish from.
            events = events.filter { it.icsUid == null || it.icsUid in serverUids || it.icsUid in protected }
            todos = todos.filter { it.icsUid == null || it.icsUid in serverUids || it.icsUid in protected }
            journals = journals.filter { it.icsUid == null || it.icsUid in serverUids || it.icsUid in protected }
        }
        access.persist(
            state.replacingCalendar(index) { current ->
                current.copy(timeZones = timeZones, events = events, todos = todos, journals = journals)
            },
            ChangeOrigin.Sync,
        )
        dropSupersededRows(subscription.id, serverSequences)
        return ImportOutcome()
    }

    /**
     * A record the server replaced is no longer locally ahead; keeping its pending upload would
     * revert the server copy on the next push, so the row is dropped instead.
     */
    private fun dropSupersededRows(calendarId: String, serverSequences: Map<String, Int>) {
        if (serverSequences.isEmpty()) return
        val calendar = state.calendars.firstOrNull { it.id == calendarId } ?: return
        outbox.entriesFor(calendarId).forEach { row ->
            val serverSequence = serverSequences[row.uid + "|" + row.recurrenceKey] ?: return@forEach
            val record = when (row.entity) {
                "event" -> calendar.events.firstOrNull {
                    it.icsUid == row.uid && recurrenceKeyText(it.recurrenceId) == row.recurrenceKey
                }
                "todo" -> calendar.todos.firstOrNull {
                    it.icsUid == row.uid && recurrenceKeyText(it.recurrenceId) == row.recurrenceKey
                }
                "journal" -> calendar.journals.firstOrNull { it.icsUid == row.uid }
                else -> null
            } ?: return@forEach
            if (serverSequence > record.sequence) outbox.delete(row.id)
        }
    }

    private suspend fun applySubscriptionFetch(
        subscription: CalendarSubscription,
        fetch: SubscriptionFetch,
        http: SubscriptionHttp,
    ) {
        when (fetch) {
            is SubscriptionFetch.Failed ->
                access.notify(AppMessage.SubscriptionFailed(subscription.name, fetch.failure))
            SubscriptionFetch.Unchanged -> {
                updateSubscription(subscription.id) { it.copy(lastSync = clock.now().toString()) }
                access.notify(AppMessage.SubscriptionUnchanged(subscription.name))
            }
            is SubscriptionFetch.Full -> {
                val applied = applySubscriptionContent(subscription, fetch.text, removeVanished = true)
                updateSubscription(subscription.id) {
                    it.copy(
                        lastSync = clock.now().toString(),
                        etag = fetch.etag ?: it.etag,
                        syncToken = fetch.syncToken ?: it.syncToken,
                        caldav = it.caldav || fetch.syncToken != null,
                        // A feed that publishes REFRESH-INTERVAL seeds its own auto-refresh.
                        refreshMinutes = it.refreshMinutes.takeIf { minutes -> minutes > 0 }
                            ?: applied.refreshMinutes
                            ?: it.refreshMinutes,
                    )
                }
                access.notify(
                    AppMessage.SubscriptionSynced(
                        subscription.name,
                        applied.outcome.events,
                        applied.outcome.todos,
                        applied.outcome.removed,
                    ),
                )
            }
            is SubscriptionFetch.Changes -> {
                val applied = applySubscriptionContent(subscription, fetch.text, removeVanished = false)
                val outcome = ImportOutcome(
                    events = applied.outcome.events,
                    todos = applied.outcome.todos,
                    journals = applied.outcome.journals,
                    removed = applied.outcome.removed,
                )
                if (fetch.deletions) {
                    // An incremental report cannot name what vanished upstream; one full fetch
                    // reconciles the local copy before the sync is called done. The token only
                    // advances once that reconciliation succeeded: moving it first would put the
                    // deletions behind the token, so a failed full fetch could never learn them.
                    val full = runCatching {
                        fetchSubscription(subscription.copy(caldav = true, syncToken = null), http)
                    }.getOrElse { SubscriptionFetch.Failed(SubscriptionFailure.Unreachable) }
                    if (full is SubscriptionFetch.Full) {
                        val reconciled = applySubscriptionContent(subscription, full.text, removeVanished = true)
                        updateSubscription(subscription.id) {
                            it.copy(syncToken = full.syncToken ?: it.syncToken, lastSync = clock.now().toString())
                        }
                        access.notify(
                            AppMessage.SubscriptionSynced(
                                subscription.name,
                                outcome.events + reconciled.outcome.events,
                                outcome.todos + reconciled.outcome.todos,
                                outcome.removed + reconciled.outcome.removed,
                            ),
                        )
                    } else {
                        // The next sync falls back to a full query, which still names the vanished
                        // records this sync could not apply.
                        updateSubscription(subscription.id) {
                            it.copy(syncToken = null, lastSync = clock.now().toString())
                        }
                        access.notify(
                            AppMessage.SubscriptionSynced(
                                subscription.name,
                                outcome.events,
                                outcome.todos,
                                outcome.removed,
                            ),
                        )
                    }
                } else {
                    updateSubscription(subscription.id) {
                        it.copy(lastSync = clock.now().toString(), syncToken = fetch.syncToken ?: it.syncToken)
                    }
                    access.notify(
                        AppMessage.SubscriptionSynced(
                            subscription.name,
                            outcome.events,
                            outcome.todos,
                            outcome.removed,
                        ),
                    )
                }
            }
        }
    }

    /** What applying one fetched feed changed, plus the refresh interval the feed publishes. */
    private class SubscriptionApply(val outcome: ImportOutcome, val refreshMinutes: Int?)

    /**
     * Merges the records of one fetched feed into the stored calendar. A full feed may remove
     * records that vanished upstream; an incremental one must not, because it only lists changes.
     * Local edits survive: a record is replaced only when the feed carries a newer
     * `SEQUENCE`/`DTSTAMP`.
     */
    private suspend fun applySubscriptionContent(
        subscription: CalendarSubscription,
        text: String,
        removeVanished: Boolean,
    ): SubscriptionApply {
        // A blank incremental report is a no-op, but a blank full fetch (an upstream collection that
        // is now empty) still reconciles: every record this source brought in has vanished.
        if (text.isBlank() && !removeVanished) return SubscriptionApply(ImportOutcome(), null)
        val parsed = withContext(parseDispatcher ?: Dispatchers.Default) {
            importCalendarLines(
                unfold(text).asSequence(),
                TimeZone.currentSystemDefault(),
                strings().untitledEvent,
            )
        }
        val index = calendarIndexContaining { it.id == subscription.id && !it.isLocal }
            ?: return SubscriptionApply(ImportOutcome(), null)
        // The feed's own zones belong to its calendar: registering them globally would let one feed
        // redefine a same-named TZID another calendar declares differently.
        registerIcsTimeZones(parsed.timeZones, subscription.id)
        val baseCalendar = state.calendars[index]
        // A subscription owns its whole calendar, so no per-record provenance marker is needed;
        // merges and vanished-record detection are scoped to this calendar alone.
        val events = mergeEvents(
            base = baseCalendar.events,
            incoming = parsed.events.map(attachments::materialize),
            cancelledUids = gatedCancellations(parsed, baseCalendar.events),
            source = null,
            removeVanished = removeVanished,
            idProvider = idProvider,
        )
        val todos = mergeTodos(
            base = baseCalendar.todos,
            incoming = parsed.todos.map(attachments::materialize),
            source = null,
            removeVanished = removeVanished,
            idProvider = idProvider,
        )
        val journals = mergeJournals(
            base = baseCalendar.journals,
            incoming = parsed.journals.map(attachments::materialize),
            source = null,
            removeVanished = removeVanished,
            idProvider = idProvider,
        )
        val updatedCalendar = baseCalendar.copy(
            refreshMinutes = parsed.refreshMinutes ?: baseCalendar.refreshMinutes,
            timeZones = mergeCalendarTimeZones(baseCalendar.timeZones, parsed.timeZones),
            events = events.items,
            todos = todos.items,
            journals = journals.items,
        )
        access.persist(
            state.replacingCalendar(index) { updatedCalendar }.copy(
                busyPeriods = mergeBusyPeriods(state.busyPeriods, parsed.freeBusy, clock.now()),
            ),
            ChangeOrigin.Sync,
        )
        return SubscriptionApply(
            outcome = ImportOutcome(
                events = events.changed,
                todos = todos.changed,
                journals = journals.changed,
                removed = events.removed + todos.removed + journals.removed,
            ),
            refreshMinutes = parsed.refreshMinutes,
        )
    }
}

private fun parseInstantOrNull(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }
