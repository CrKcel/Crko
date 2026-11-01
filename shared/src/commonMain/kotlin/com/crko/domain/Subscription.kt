package com.crko.domain

import kotlinx.serialization.Serializable

/**
 * A remote calendar the app keeps a local copy of: a plain webcal/ICS feed or a CalDAV collection.
 * The subscription is only the address and its sync bookkeeping — records are merged into the
 * ordinary event list, so they can be read and edited like local ones.
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
