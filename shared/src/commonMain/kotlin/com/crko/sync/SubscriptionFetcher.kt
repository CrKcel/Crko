package com.crko.sync

import com.crko.domain.CalDavChange
import com.crko.domain.CalendarSubscription
import com.crko.domain.MaxSubscriptionBytes
import com.crko.domain.SubscriptionFailure
import com.crko.domain.SubscriptionFetch
import com.crko.domain.decodeCalendarBytes
import com.crko.domain.resolveSubscriptionUrl
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.SubscriptionHttp
import kotlinx.coroutines.CancellationException

/**
 * Fetching of subscribed calendars. A `webcal` feed is a plain `GET` with entity-tag validation;
 * a CalDAV collection is queried with WebDAV `REPORT`s and a `sync-token`, so a repeated sync only
 * downloads what actually changed.
 */

/**
 * Fetches [subscription]'s upstream calendar. CalDAV collections — detected from a stored token, an
 * XML answer, or a `GET` the server refuses — are queried with `REPORT`s; everything else is a
 * plain `GET` whose body should be ICS text. [password] comes from the platform secret store; with
 * a username and a password every request carries a Basic `Authorization` header.
 */
suspend fun fetchSubscription(
    subscription: CalendarSubscription,
    http: SubscriptionHttp,
    password: String? = null,
): SubscriptionFetch {
    val url = resolveSubscriptionUrl(subscription.url)
        ?: return SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar)
    val auth = basicAuthHeader(subscription.username, password)
    if (subscription.caldav) {
        return fetchCalDav(subscription, url, http, auth)
    }
    val response = try {
        http.send(
            HttpRequest(
                method = "GET",
                url = url,
                headers = buildMap {
                    put("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.1")
                    subscription.etag?.let { put("If-None-Match", it) }
                    auth?.let { put("Authorization", it) }
                },
            ),
        )
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        return SubscriptionFetch.Failed(SubscriptionFailure.Unreachable)
    }
    return when {
        response.status == 304 -> SubscriptionFetch.Unchanged
        response.status == 401 || response.status == 403 ->
            SubscriptionFetch.Failed(SubscriptionFailure.Unauthorized, response.status)
        response.status !in 200..299 ->
            SubscriptionFetch.Failed(SubscriptionFailure.HttpError, response.status)
        response.body.size > MaxSubscriptionBytes ->
            SubscriptionFetch.Failed(SubscriptionFailure.TooLarge)
        // Some servers answer an XML collection listing instead of the calendar; that is the sign
        // this URL is a CalDAV collection, so the next attempt goes through REPORT.
        response.isXml -> fetchCalDav(subscription.copy(caldav = true), url, http, auth)
        else -> decodeCalendarBytes(response.body)?.let { text ->
            if (text.contains("BEGIN:VEVENT", ignoreCase = true) || text.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
                SubscriptionFetch.Full(text, etag = response.header("etag"))
            } else {
                SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar, response.status)
            }
        } ?: SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar, response.status)
    }
}

/** `Authorization: Basic …` for [username] and [password]; null when either is missing. */
internal fun basicAuthHeader(username: String?, password: String?): String? {
    if (username.isNullOrBlank() || password.isNullOrEmpty()) return null
    val token = "$username:$password".encodeToByteArray()
    return "Basic " + kotlin.io.encoding.Base64.Default.encode(token)
}

private val HttpResponse.isXml: Boolean
    get() = header("content-type")?.contains("xml", ignoreCase = true) == true

private fun HttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

/**
 * Queries a CalDAV collection. With a stored `sync-token` the report only names what changed; when
 * the server rejects incremental reports (or none is stored yet), the full calendar is requested.
 */
private suspend fun fetchCalDav(
    subscription: CalendarSubscription,
    url: String,
    http: SubscriptionHttp,
    auth: String?,
): SubscriptionFetch {
    val token = subscription.syncToken
    if (token != null) {
        val report = report(http, url, syncCollectionReport(token), auth) ?: return SubscriptionFetch.Failed(
            SubscriptionFailure.Unreachable,
        )
        if (report.body.size > MaxSubscriptionBytes) return SubscriptionFetch.Failed(SubscriptionFailure.TooLarge)
        return when {
            report.status in 200..299 -> {
                val multi = parseMultiStatus(decodeCalendarBytes(report.body) ?: report.body.decodeToString())
                if (!multi.recognized) {
                    // A 2xx answer that is not a multistatus is not a calendar collection.
                    SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar, report.status)
                } else {
                    val nextToken = multi.syncToken ?: token
                    if (!multi.hasDocuments && !multi.deletions) {
                        SubscriptionFetch.Unchanged
                    } else {
                        SubscriptionFetch.Changes(
                            text = multi.documents.joinToString("\r\n"),
                            syncToken = nextToken,
                            deletions = multi.deletions || multi.documents.isEmpty(),
                            entries = multi.entries.map { it.toChange(url) },
                        )
                    }
                }
            }
            // A server that does not know sync-collection reports rejects the request; fall back to
            // the full query once instead of failing the whole sync.
            report.status in setOf(400, 403, 415, 501) -> fetchCalDav(
                subscription.copy(syncToken = null),
                url,
                http,
                auth,
            )
            report.status == 401 || report.status == 403 ->
                SubscriptionFetch.Failed(SubscriptionFailure.Unauthorized, report.status)
            else -> SubscriptionFetch.Failed(SubscriptionFailure.HttpError, report.status)
        }
    }
    val report = report(http, url, calendarQueryReport(), auth) ?: return SubscriptionFetch.Failed(
        SubscriptionFailure.Unreachable,
    )
    if (report.body.size > MaxSubscriptionBytes) return SubscriptionFetch.Failed(SubscriptionFailure.TooLarge)
    return when {
        report.status in 200..299 -> {
            val multi = parseMultiStatus(decodeCalendarBytes(report.body) ?: report.body.decodeToString())
            val changes = multi.entries.map { it.toChange(url) }
            when {
                multi.hasDocuments -> SubscriptionFetch.Full(
                    multi.documents.joinToString("\r\n"),
                    syncToken = multi.syncToken,
                    entries = changes,
                )
                // An empty but well-formed multistatus is a real (currently empty) collection:
                // syncing it succeeds and reconciles the local copy down to nothing.
                multi.recognized -> SubscriptionFetch.Full("", syncToken = multi.syncToken, entries = changes)
                else -> SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar, report.status)
            }
        }
        report.status == 401 || report.status == 403 ->
            SubscriptionFetch.Failed(SubscriptionFailure.Unauthorized, report.status)
        else -> SubscriptionFetch.Failed(SubscriptionFailure.HttpError, report.status)
    }
}

/** One WebDAV `REPORT`; null when the transport itself failed. */
private suspend fun report(http: SubscriptionHttp, url: String, body: String, auth: String?): HttpResponse? = try {
    http.send(
        HttpRequest(
            method = "REPORT",
            url = url,
            headers = buildMap {
                put("Depth", "1")
                put("Content-Type", "application/xml; charset=utf-8")
                put("Accept", "application/xml, text/xml")
                auth?.let { put("Authorization", it) }
            },
            body = body.encodeToByteArray(),
        ),
    )
} catch (error: Exception) {
    if (error is CancellationException) throw error
    null
}

/** `sync-collection` report body asking for the changes since [token] plus the next token. */
internal fun syncCollectionReport(token: String): String = """
    <?xml version="1.0" encoding="utf-8" ?>
    <d:sync-collection xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
      <d:sync-token>${xmlEscape(token)}</d:sync-token>
      <d:sync-level>1</d:sync-level>
      <d:prop>
        <d:getetag/>
        <c:calendar-data/>
      </d:prop>
    </d:sync-collection>
""".trimIndent()

/** `calendar-query` report body asking for every VEVENT of the collection with its calendar data. */
internal fun calendarQueryReport(): String = """
    <?xml version="1.0" encoding="utf-8" ?>
    <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
      <d:prop>
        <d:getetag/>
        <c:calendar-data/>
      </d:prop>
      <d:filter>
        <c:comp-filter name="VCALENDAR">
          <c:comp-filter name="VEVENT"/>
        </c:comp-filter>
      </d:filter>
    </c:calendar-query>
""".trimIndent()

/** One `<d:response>` of a multistatus: where the resource lives and what it carries. */
internal class MultistatusEntry(
    /** Server href of the resource, e.g. `/calendars/personal/abc.ics`. */
    val href: String,
    /** Resource entity tag, echoed back as `If-Match` on the next upload. */
    val etag: String?,
    /** The `calendar-data` text, or null when the resource carries none (or is gone). */
    val calendarData: String?,
    /** Whether the server reported this resource as 404, i.e. it vanished upstream. */
    val vanished: Boolean,
    /** Raw inner XML of the response, for readers that need properties beyond the ones above. */
    val xml: String = "",
)

/** Maps one multistatus response onto the domain change type, with an absolute href. */
private fun MultistatusEntry.toChange(requestUrl: String): CalDavChange = CalDavChange(
    href = absolutize(href, requestUrl) ?: href,
    etag = etag,
    text = calendarData,
    vanished = vanished,
)

/** One `DAV:multistatus` answer, reduced to what a sync needs. */
internal class MultiStatus(
    /** `calendar-data` texts, one per resource that still exists. */
    val documents: List<String>,
    /** Whether any listed resource answered 404 and therefore vanished upstream. */
    val deletions: Boolean,
    /** The `sync-token` to send with the next incremental report. */
    val syncToken: String? = null,
    /** Whether the body carried a `multistatus` root at all, as opposed to some other answer. */
    val recognized: Boolean = false,
    /** The per-resource view of the answer, used by the two-way sync. */
    val entries: List<MultistatusEntry> = emptyList(),
) {
    val hasDocuments: Boolean get() = documents.isNotEmpty()
}

/**
 * Reader for a WebDAV multistatus body; the per-resource parsing lives in [com.crko.sync.findElement]
 * and friends, which the CalDAV client shares.
 */
internal fun parseMultiStatus(xml: String): MultiStatus {
    val entries = mutableListOf<MultistatusEntry>()
    var index = 0
    while (true) {
        val response = findElement(xml, index, "response") ?: break
        val inner = response.inner
        val href = findElement(inner, 0, "href")?.value.orEmpty()
        val etag = findElement(inner, 0, "getetag")?.value?.trim()?.takeIf(String::isNotEmpty)
        val calendarData = findElement(inner, 0, "calendar-data")?.value?.takeIf(String::isNotBlank)
        val status = findElement(inner, 0, "status")?.value?.uppercase()
        entries += MultistatusEntry(
            href = href,
            etag = etag,
            calendarData = calendarData,
            vanished = calendarData == null && status?.contains("404") == true,
            xml = inner,
        )
        index = response.end
    }
    val syncToken = findElement(xml, 0, "sync-token")?.value?.trim()?.takeIf(String::isNotEmpty)
    return MultiStatus(
        documents = entries.mapNotNull { it.calendarData },
        deletions = entries.any { it.vanished },
        syncToken = syncToken,
        recognized = findElement(xml, 0, "multistatus") != null,
        entries = entries,
    )
}
