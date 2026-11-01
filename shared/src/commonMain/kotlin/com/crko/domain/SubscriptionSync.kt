package com.crko.domain

import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.SubscriptionHttp

/**
 * Fetching and incremental merging of subscribed calendars. A `webcal` feed is a plain `GET` with
 * entity-tag validation; a CalDAV collection is queried with WebDAV `REPORT`s and a
 * `sync-token`, so a repeated sync only downloads what actually changed.
 */

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
 * Fetches [subscription]'s upstream calendar. CalDAV collections — detected from a stored token, an
 * XML answer, or a `GET` the server refuses — are queried with `REPORT`s; everything else is a
 * plain `GET` whose body should be ICS text.
 */
suspend fun fetchSubscription(
    subscription: CalendarSubscription,
    http: SubscriptionHttp,
): SubscriptionFetch {
    val url = resolveSubscriptionUrl(subscription.url)
        ?: return SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar)
    if (subscription.caldav) {
        return fetchCalDav(subscription, url, http)
    }
    val response = try {
        http.send(
            HttpRequest(
                method = "GET",
                url = url,
                headers = buildMap {
                    put("Accept", "text/calendar, text/plain;q=0.9, */*;q=0.1")
                    subscription.etag?.let { put("If-None-Match", it) }
                },
            ),
        )
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
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
        response.isXml -> fetchCalDav(subscription.copy(caldav = true), url, http)
        else -> decodeCalendarBytes(response.body)?.let { text ->
            if (text.contains("BEGIN:VEVENT", ignoreCase = true) || text.contains("BEGIN:VCALENDAR", ignoreCase = true)) {
                SubscriptionFetch.Full(text, etag = response.header("etag"))
            } else {
                SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar, response.status)
            }
        } ?: SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar, response.status)
    }
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
): SubscriptionFetch {
    val token = subscription.syncToken
    if (token != null) {
        val report = report(http, url, syncCollectionReport(token)) ?: return SubscriptionFetch.Failed(
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
            )
            report.status == 401 || report.status == 403 ->
                SubscriptionFetch.Failed(SubscriptionFailure.Unauthorized, report.status)
            else -> SubscriptionFetch.Failed(SubscriptionFailure.HttpError, report.status)
        }
    }
    val report = report(http, url, calendarQueryReport()) ?: return SubscriptionFetch.Failed(
        SubscriptionFailure.Unreachable,
    )
    if (report.body.size > MaxSubscriptionBytes) return SubscriptionFetch.Failed(SubscriptionFailure.TooLarge)
    return when {
        report.status in 200..299 -> {
            val multi = parseMultiStatus(decodeCalendarBytes(report.body) ?: report.body.decodeToString())
            when {
                multi.hasDocuments -> SubscriptionFetch.Full(multi.documents.joinToString("\r\n"), syncToken = multi.syncToken)
                // An empty but well-formed multistatus is a real (currently empty) collection:
                // syncing it succeeds and reconciles the local copy down to nothing.
                multi.recognized -> SubscriptionFetch.Full("", syncToken = multi.syncToken)
                else -> SubscriptionFetch.Failed(SubscriptionFailure.NotCalendar, report.status)
            }
        }
        report.status == 401 || report.status == 403 ->
            SubscriptionFetch.Failed(SubscriptionFailure.Unauthorized, report.status)
        else -> SubscriptionFetch.Failed(SubscriptionFailure.HttpError, report.status)
    }
}

/** One WebDAV `REPORT`; null when the transport itself failed. */
private suspend fun report(http: SubscriptionHttp, url: String, body: String): HttpResponse? = try {
    http.send(
        HttpRequest(
            method = "REPORT",
            url = url,
            headers = mapOf(
                "Depth" to "1",
                "Content-Type" to "application/xml; charset=utf-8",
                "Accept" to "application/xml, text/xml",
            ),
            body = body.encodeToByteArray(),
        ),
    )
} catch (error: Exception) {
    if (error is kotlinx.coroutines.CancellationException) throw error
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
      <c:filter>
        <c:comp-filter name="VCALENDAR">
          <c:comp-filter name="VEVENT"/>
        </c:comp-filter>
      </c:filter>
    </c:calendar-query>
""".trimIndent()

internal fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}

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
) {
    val hasDocuments: Boolean get() = documents.isNotEmpty()
}

/**
 * Minimal namespace-agnostic reader for a WebDAV multistatus body. CalDAV servers answer with
 * arbitrary prefixes, so elements are matched by local name; `calendar-data` payloads are kept
 * verbatim (CDATA and character references resolved).
 */
internal fun parseMultiStatus(xml: String): MultiStatus {
    val documents = mutableListOf<String>()
    var deletions = false
    var syncToken: String? = null
    var index = 0
    while (true) {
        val response = findElement(xml, index, "response") ?: break
        val inner = response.inner
        val calendarData = findElement(inner, 0, "calendar-data")
        val status = findElement(inner, 0, "status")?.value?.uppercase()
        if (calendarData != null && calendarData.value.isNotBlank()) {
            documents += calendarData.value
        } else if (status?.contains("404") == true) {
            deletions = true
        }
        index = response.end
    }
    findElement(xml, 0, "sync-token")?.let { syncToken = it.value.trim().takeIf(String::isNotEmpty) }
    return MultiStatus(documents, deletions, syncToken, findElement(xml, 0, "multistatus") != null)
}

/** An element found by [findElement]: decoded text, raw inner content, and the index after it. */
internal class FoundElement(val value: String, val inner: String, val end: Int)

/**
 * First element whose local name is [name] at or after [from]. Returns its inner text (character
 * references and CDATA resolved) and the offset just past its closing tag. Attributes and the
 * namespace prefix are ignored, matching how CalDAV clients must read server answers.
 */
internal fun findElement(xml: String, from: Int, name: String): FoundElement? {
    var index = from
    while (index < xml.length) {
        val open = xml.indexOf('<', index)
        if (open < 0) return null
        val close = xml.indexOf('>', open)
        if (close < 0) return null
        val tag = xml.substring(open + 1, close)
        when {
            tag.startsWith('?') || tag.startsWith('!') -> index = close + 1
            tag.startsWith("/") -> index = close + 1
            else -> {
                val localName = tag.substringBefore(' ').substringBefore('/').substringAfterLast(':')
                if (localName == name) {
                    // A self-closing element (`<d:sync-token/>`) carries no content but still means
                    // "present and empty"; skipping it would make every sync fall back to full.
                    if (tag.endsWith("/")) return FoundElement("", "", close + 1)
                    val innerEnd = findCloseTag(xml, close + 1, name) ?: return null
                    val raw = xml.substring(close + 1, innerEnd.first)
                    return FoundElement(decodeXmlText(raw), raw, innerEnd.second)
                }
                index = close + 1
            }
        }
    }
    return null
}

/** Close tag of the element opened before [from]; returns its position and the offset past it. */
private fun findCloseTag(xml: String, from: Int, name: String): Pair<Int, Int>? {
    var index = from
    var depth = 1
    while (index < xml.length) {
        val open = xml.indexOf('<', index)
        if (open < 0) return null
        val close = xml.indexOf('>', open)
        if (close < 0) return null
        val tag = xml.substring(open + 1, close)
        when {
            tag.startsWith('/') -> {
                if (tag.substring(1).trim().substringAfterLast(':') == name) {
                    depth--
                    if (depth == 0) return open to close + 1
                }
            }
            !tag.endsWith("/") -> {
                val localName = tag.substringBefore(' ').substringAfterLast(':')
                if (localName == name) depth++
            }
        }
        index = close + 1
    }
    return null
}

/** Resolves character references and CDATA sections of an XML text node, keeping the layout. */
internal fun decodeXmlText(raw: String): String {
    if (!raw.contains('&') && !raw.contains("<![CDATA[")) {
        return raw
    }
    var text = raw
    // `RegexOption.DOT_MATCHES_ALL` is JVM-only; the inline (?s) flag is its cross-platform form.
    val cdata = Regex("(?s)<!\\[CDATA\\[(.*?)]]>")
    text = cdata.replace(text) { match -> xmlEscape(match.groupValues[1]) }
    return text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(numericReference) { match -> numericReferenceText(match.groupValues[1]) ?: match.value }
        .replace("&amp;", "&")
}

/** Decimal (`&#233;`) and hexadecimal (`&#xE9;`) character references in one pattern. */
private val numericReference = Regex("&#(x[0-9A-Fa-f]+|[0-9]+);")

/** Text of one numeric character reference; null (kept verbatim) when it names no character. */
private fun numericReferenceText(token: String): String? {
    val code = if (token.startsWith('x')) {
        token.substring(1).toLongOrNull(16)?.takeIf { it in 0..0x10FFFF.toLong() }
    } else {
        token.toLongOrNull()?.takeIf { it in 0..0x10FFFF.toLong() }
    } ?: return null
    return if (code < 0x10000) {
        Char(code.toInt()).toString()
    } else {
        // Outside the basic multilingual plane the value is a code point, not a code unit.
        val offset = code.toInt() - 0x10000
        charArrayOf(Char(0xD800 + (offset shr 10)), Char(0xDC00 + (offset and 0x3FF))).concatToString()
    }
}
