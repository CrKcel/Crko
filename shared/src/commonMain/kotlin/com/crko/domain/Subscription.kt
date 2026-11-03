package com.crko.domain

import kotlinx.serialization.Serializable

/** Why a subscription fetch failed; each case has its own user-facing message. */
enum class SubscriptionFailure {
    /** The server could not be reached at all. */
    Unreachable,

    /** The server demanded credentials the subscription does not have. */
    Unauthorized,

    /** The server answered with an unexpected HTTP status. */
    HttpError,

    /** The response is not a calendar and not a CalDAV collection. */
    NotCalendar,

    /** The response exceeded the size limit. */
    TooLarge,
}

/** What one subscription fetch produced. */
sealed interface SubscriptionFetch {
    /**
     * The full upstream content; every record the source still carries, so records that vanished
     * from it can be removed locally.
     */
    data class Full(
        val text: String,
        val etag: String? = null,
        val syncToken: String? = null,
    ) : SubscriptionFetch

    /**
     * An incremental CalDAV report: only the records that changed since the last sync. [deletions]
     * says the source also reported removals this content cannot name, so a full refetch follows.
     */
    data class Changes(
        val text: String,
        val syncToken: String? = null,
        val deletions: Boolean = false,
    ) : SubscriptionFetch

    /** The source answered 304: nothing changed since the stored entity tag. */
    data object Unchanged : SubscriptionFetch

    data class Failed(val failure: SubscriptionFailure, val status: Int? = null) : SubscriptionFetch
}

/** Upper bound for one response; a calendar beyond it would freeze the device for seconds. */
internal const val MaxSubscriptionBytes = 8L * 1024 * 1024

/**
 * Absolute https address of [raw]. `webcal://` and `webcals://` are the user-facing spellings of an
 * HTTP(S) calendar URL, so they are rewritten; an explicit `http://` is kept as typed. Anything
 * without a known scheme is not a URL the app can fetch.
 */
fun resolveSubscriptionUrl(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val schemeEnd = text.indexOf("://")
    if (schemeEnd <= 0) return null
    val rest = text.substring(schemeEnd + 3)
    return when (text.substring(0, schemeEnd).lowercase()) {
        "webcal", "webcals" -> "https://$rest"
        "http" -> "http://$rest"
        "https" -> "https://$rest"
        else -> null
    }
}

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
