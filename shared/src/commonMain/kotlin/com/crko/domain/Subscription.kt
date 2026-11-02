package com.crko.domain

import kotlinx.serialization.Serializable

/**
 * UI-facing view of a subscribed [Calendar]: the address plus its sync bookkeeping. Persisted data
 * keeps the same values inside [Calendar.syncState], so this type survives for the subscription
 * screen and for reading pre-stage-5 payloads.
 */
@Serializable
data class CalendarSubscription(
    val id: String,
    /** User-visible name; defaults to the host name when the user did not pick one. */
    val name: String,
    /** URL as entered, including a possible `webcal://` scheme. */
    val url: String,
    val enabled: Boolean = true,
    /** Minutes between automatic refreshes; 0 means sync only on request. */
    val refreshMinutes: Int = 0,
    /** ISO instant of the last successful sync. */
    val lastSync: String? = null,
    /** HTTP entity tag of the last fetched feed, echoed back as `If-None-Match`. */
    val etag: String? = null,
    /** CalDAV `sync-token` of the last successful sync, for incremental reports. */
    val syncToken: String? = null,
    /**
     * The server turned out to speak CalDAV, so syncing uses `REPORT` instead of a plain `GET`.
     * Detected from the first response and remembered.
     */
    val caldav: Boolean = false,
)

/** The sync half of a subscribed calendar. */
fun CalendarSubscription.toSyncState(): CalendarSyncState = CalendarSyncState(
    url = url,
    enabled = enabled,
    refreshMinutes = refreshMinutes,
    lastSync = lastSync,
    etag = etag,
    syncToken = syncToken,
    caldav = caldav,
)

/** Creates the calendar container of a subscription, without any records yet. */
fun CalendarSubscription.toCalendar(): Calendar = Calendar(
    id = id,
    name = name,
    refreshMinutes = refreshMinutes.takeIf { it > 0 },
    syncState = toSyncState(),
)

/** The subscription view of a calendar; null for a local calendar. */
val Calendar.subscription: CalendarSubscription?
    get() = syncState?.let { state ->
        CalendarSubscription(
            id = id,
            name = name,
            url = state.url,
            enabled = state.enabled,
            refreshMinutes = state.refreshMinutes,
            lastSync = state.lastSync,
            etag = state.etag,
            syncToken = state.syncToken,
            caldav = state.caldav,
        )
    }
