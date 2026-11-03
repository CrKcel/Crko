package com.crko.state.subscription

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppState
import com.crko.domain.Calendar
import com.crko.domain.CalendarSubscription
import com.crko.domain.SubscriptionFailure
import com.crko.domain.SubscriptionFetch
import com.crko.domain.gatedCancellations
import com.crko.domain.importCalendarLines
import com.crko.domain.mergeBusyPeriods
import com.crko.domain.mergeCalendarTimeZones
import com.crko.domain.mergeEvents
import com.crko.domain.mergeJournals
import com.crko.domain.mergeTodos
import com.crko.domain.registerIcsTimeZones
import com.crko.domain.subscription
import com.crko.domain.toSyncState
import com.crko.domain.unfold
import com.crko.i18n.Strings
import com.crko.state.AppMessage
import com.crko.state.ImportOutcome
import com.crko.state.StateAccess
import com.crko.state.`import`.AttachmentMaterializer
import com.crko.storage.AttachmentRepository
import com.crko.storage.SubscriptionHttp
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
 */
internal class SubscriptionSyncService(
    private val access: StateAccess,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val http: SubscriptionHttp?,
    attachmentRepository: AttachmentRepository,
    private val parseDispatcher: CoroutineDispatcher?,
    private val idProvider: () -> String,
    private val strings: () -> Strings,
) {
    private val state: AppState get() = access.state

    private val attachments = AttachmentMaterializer(attachmentRepository)

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
     * started again, and without a bridge the call does nothing.
     */
    fun syncSubscription(id: String) {
        val subscription = state.calendars.firstOrNull { it.id == id }?.subscription ?: return
        val http = http ?: return
        if (syncJobs[id]?.isActive == true) return
        activeSyncIds = activeSyncIds + id
        syncJobs[id] = scope.launch {
            val fetch = runCatching { fetchSubscription(subscription, http) }
                .getOrElse { SubscriptionFetch.Failed(SubscriptionFailure.Unreachable) }
            applySubscriptionFetch(subscription, fetch, http)
        }.also { job ->
            job.invokeOnCompletion { activeSyncIds = activeSyncIds - id }
        }
    }

    /** Syncs every subscription whose refresh interval has elapsed. */
    fun syncDueSubscriptions() {
        subscriptionsDue().forEach { syncSubscription(it.id) }
    }

    /** Whether [id] currently has a sync running. */
    fun isSyncing(id: String): Boolean = id in activeSyncIds

    private fun updateSubscription(id: String, transform: (CalendarSubscription) -> CalendarSubscription) {
        val index = calendarIndexContaining { it.id == id && !it.isLocal } ?: return
        val current = state.calendars[index].subscription ?: return
        val next = transform(current)
        access.persist(
            state.replacingCalendar(index) { calendar ->
                calendar.copy(name = next.name, syncState = next.toSyncState())
            },
        )
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
