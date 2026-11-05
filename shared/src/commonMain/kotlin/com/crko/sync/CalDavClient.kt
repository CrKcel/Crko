package com.crko.sync

import com.crko.domain.DiscoveryFailure
import com.crko.domain.MaxSubscriptionBytes
import com.crko.domain.decodeCalendarBytes
import com.crko.domain.resolveSubscriptionUrl
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.SubscriptionHttp
import kotlinx.coroutines.CancellationException

/**
 * The write half of the CalDAV client: account discovery (RFC 6764 style `PROPFIND` walks) plus the
 * `PUT`/`DELETE`/`GET` resource calls the push engine drains the outbox with. All requests carry a
 * Basic `Authorization` header built by [basicAuthHeader].
 */

/** One calendar collection a server exposed during discovery. */
class CalDavCollection(
    /** Absolute member URL of the collection, the value stored as the calendar's sync URL. */
    val href: String,
    /** `displayname`, falling back to the last path segment. */
    val displayName: String,
    /** `calendar-color` as `0xAARRGGBB`; null when the server publishes none. */
    val colorArgb: Long?,
    /** The signed-in user holds a write privilege on the collection. */
    val writable: Boolean,
    /** Component names the collection accepts, e.g. `VEVENT`, `VTODO`, `VJOURNAL`. */
    val components: Set<String>,
)

/** The outcome of one discovery run. */
sealed interface CalDavDiscovery {
    data class Success(val collections: List<CalDavCollection>) : CalDavDiscovery
    data class Failure(val failure: DiscoveryFailure, val status: Int? = null) : CalDavDiscovery
}

/**
 * Walks `current-user-principal` → `calendar-home-set` → the home set's children and returns every
 * calendar collection. The entered [serverUrl] usually serves the principal directly; servers that
 * only publish `/.well-known/caldav` are followed by one manual redirect hop.
 */
suspend fun discoverCalendars(
    serverUrl: String,
    username: String,
    password: String,
    http: SubscriptionHttp,
): CalDavDiscovery {
    val auth = basicAuthHeader(username, password) ?: return CalDavDiscovery.Failure(DiscoveryFailure.Unauthorized)
    val base = resolveDavUrl(serverUrl) ?: return CalDavDiscovery.Failure(DiscoveryFailure.NotCalDav)

    val principal = resolvePrincipal(http, base, auth)
    return when (principal) {
        is DiscoveryStep.Failed -> CalDavDiscovery.Failure(principal.failure, principal.status)
        is DiscoveryStep.Unreachable -> CalDavDiscovery.Failure(DiscoveryFailure.Unreachable)
        is DiscoveryStep.Found -> when (val home = resolveHomeSet(http, principal.url, auth)) {
            is DiscoveryStep.Failed -> CalDavDiscovery.Failure(home.failure, home.status)
            is DiscoveryStep.Unreachable -> CalDavDiscovery.Failure(DiscoveryFailure.Unreachable)
            is DiscoveryStep.Found -> listCollections(http, home.url, auth)
        }
    }
}

/** Internal per-step result: the resolved URL, or why the step did not resolve. */
private sealed interface DiscoveryStep {
    data class Found(val url: String) : DiscoveryStep
    data class Failed(val failure: DiscoveryFailure, val status: Int? = null) : DiscoveryStep
    data object Unreachable : DiscoveryStep
}

/** `current-user-principal` of [base], falling back to `/.well-known/caldav`. */
private suspend fun resolvePrincipal(http: SubscriptionHttp, base: String, auth: String?): DiscoveryStep {
    val body = principalPropfind()
    val direct = propfind(http, base, body, auth, depth = 0)
        ?: return DiscoveryStep.Unreachable
    if (direct.status in 200..299) {
        val text = decodeCalendarBytes(direct.body) ?: direct.body.decodeToString()
        principalHref(text)?.let { href -> absolutize(href, base)?.let { return DiscoveryStep.Found(it) } }
    }
    // A 2xx without a principal and a 404/405 both leave room for the well-known fallback; a real
    // rejection (401/403/5xx) is not something the fallback can fix.
    val retryable = direct.status in 200..299 || direct.status == 404 || direct.status == 405
    if (!retryable) {
        val failure = discoveryFailureFor(direct.status) ?: DiscoveryFailure.NotCalDav
        return DiscoveryStep.Failed(failure, direct.status)
    }

    // The well-known address is the second chance the standard provides, usually answered with a
    // redirect to the real principal.
    val wellKnown = absolutize("/.well-known/caldav", base) ?: return DiscoveryStep.Failed(DiscoveryFailure.NotCalDav)
    val redirected = propfindFollowingRedirect(http, wellKnown, body, auth)
        ?: return DiscoveryStep.Unreachable
    if (redirected.second.status == 401 || redirected.second.status == 403) {
        return DiscoveryStep.Failed(DiscoveryFailure.Unauthorized, redirected.second.status)
    }
    if (redirected.second.status !in 200..299) {
        return DiscoveryStep.Failed(DiscoveryFailure.NotCalDav, redirected.second.status)
    }
    val text = decodeCalendarBytes(redirected.second.body) ?: redirected.second.body.decodeToString()
    val href = principalHref(text) ?: return DiscoveryStep.Failed(DiscoveryFailure.NotCalDav, redirected.second.status)
    val principal = absolutize(href, redirected.first)
        ?: return DiscoveryStep.Failed(DiscoveryFailure.NotCalDav, redirected.second.status)
    return DiscoveryStep.Found(principal)
}

/** `calendar-home-set` of the [principal] URL. */
private suspend fun resolveHomeSet(http: SubscriptionHttp, principal: String, auth: String?): DiscoveryStep {
    val response = propfind(http, principal, homeSetPropfind(), auth, depth = 0)
        ?: return DiscoveryStep.Unreachable
    if (response.status == 401 || response.status == 403) {
        return DiscoveryStep.Failed(DiscoveryFailure.Unauthorized, response.status)
    }
    if (response.status !in 200..299) {
        return DiscoveryStep.Failed(DiscoveryFailure.HttpError, response.status)
    }
    val text = decodeCalendarBytes(response.body) ?: response.body.decodeToString()
    val home = findElement(text, 0, "calendar-home-set")?.let { set ->
        findElement(set.inner, 0, "href")?.value?.trim()?.takeIf(String::isNotEmpty)
    } ?: return DiscoveryStep.Failed(DiscoveryFailure.NotCalDav, response.status)
    return DiscoveryStep.Found(absolutize(home, principal) ?: return DiscoveryStep.Failed(DiscoveryFailure.NotCalDav))
}

/** Depth:1 listing of the home set, reduced to the calendar collections it contains. */
private suspend fun listCollections(http: SubscriptionHttp, home: String, auth: String?): CalDavDiscovery {
    val response = propfind(http, home, collectionPropfind(), auth, depth = 1)
        ?: return CalDavDiscovery.Failure(DiscoveryFailure.Unreachable)
    if (response.status == 401 || response.status == 403) {
        return CalDavDiscovery.Failure(DiscoveryFailure.Unauthorized, response.status)
    }
    if (response.status !in 200..299) {
        return CalDavDiscovery.Failure(DiscoveryFailure.HttpError, response.status)
    }
    val text = decodeCalendarBytes(response.body) ?: response.body.decodeToString()
    val multi = parseMultiStatus(text)
    val collections = multi.entries.mapNotNull { entry -> parseCollection(entry, home) }
    return CalDavDiscovery.Success(collections)
}

/** One multistatus response reduced to a collection; null for non-calendar resources. */
private fun parseCollection(entry: MultistatusEntry, requestUrl: String): CalDavCollection? {
    val href = absolutize(entry.href, requestUrl) ?: return null
    val resourceType = findElement(entry.xml, 0, "resourcetype") ?: return null
    if (findElement(resourceType.inner, 0, "calendar") == null) return null
    val displayName = findElement(entry.xml, 0, "displayname")?.value?.trim()?.takeIf(String::isNotEmpty)
        ?: entry.href.trim().trimEnd('/').substringAfterLast('/')
    val color = findElement(entry.xml, 0, "calendar-color")?.value?.trim()?.let(::parseCalendarColor)
    val components = findElement(entry.xml, 0, "supported-calendar-component-set")
        ?.let { set -> findAllElements(set.inner, 0, "comp").mapNotNullTo(mutableSetOf()) { it.attributes["name"] } }
        ?: emptySet()
    val writable = findElement(entry.xml, 0, "current-user-privilege-set")
        ?.let { hasWritePrivilege(it.inner) } ?: false
    return CalDavCollection(href, displayName, color, writable, components)
}

/** Whether the privilege set grants `write` or `write-content` to the current user. */
private fun hasWritePrivilege(privilegeSet: String): Boolean =
    findAllElements(privilegeSet, 0, "privilege").any { privilege ->
        localNames(privilege.inner).any { it == "write" || it == "write-content" }
    }

/** Local names of every open tag in [xml]. */
private fun localNames(xml: String): Set<String> =
    Regex("<([\\w:.-]+)").findAll(xml).mapTo(mutableSetOf()) { it.groupValues[1].substringAfterLast(':') }

/**
 * `current-user-principal`'s member href; null when the server reports none (or an anonymous one).
 * The element's own text is only a href when it looks like one — with a child element present the
 * reader's `value` is the whole inner XML, so the nested `<d:href>` decides.
 */
private fun principalHref(xml: String): String? {
    val principal = findElement(xml, 0, "current-user-principal") ?: return null
    val direct = principal.value.trim()
    if (direct.startsWith("/") || direct.startsWith("http", ignoreCase = true)) return direct
    return findElement(principal.inner, 0, "href")?.value?.trim()?.takeIf(String::isNotEmpty)
}

/** Result of one resource upload, deletion or fetch. */
sealed interface CalDavExchange {
    /** The server applied the change; [etag] is the fresh entity tag, [body] the fetched text. */
    data class Applied(val etag: String? = null, val body: String? = null) : CalDavExchange

    /** Precondition failed (412/409): the server copy changed under us. */
    data class Conflict(val status: Int) : CalDavExchange

    /** 404: the resource no longer exists — success for a delete, a recreate for an upload. */
    data object Gone : CalDavExchange

    /** 401/403: the credentials were rejected. */
    data object Unauthorized : CalDavExchange

    /** Any other HTTP error. */
    data class HttpError(val status: Int) : CalDavExchange

    /** The transport itself failed. */
    data object Unreachable : CalDavExchange
}

/** `PUT`s [ics] to [href]; [ifMatch] turns the upload into an optimistic-concurrency update. */
suspend fun putCalendarResource(
    http: SubscriptionHttp,
    href: String,
    ics: String,
    auth: String?,
    ifMatch: String? = null,
): CalDavExchange = exchange(
    http = http,
    method = "PUT",
    url = href,
    headers = buildMap {
        put("Content-Type", "text/calendar; charset=utf-8")
        ifMatch?.let { put("If-Match", it) }
        auth?.let { put("Authorization", it) }
    },
    body = ics.encodeToByteArray(),
)

/** `DELETE`s the resource at [href]; [ifMatch] protects a concurrent server-side change. */
suspend fun deleteCalendarResource(
    http: SubscriptionHttp,
    href: String,
    auth: String?,
    ifMatch: String? = null,
): CalDavExchange = exchange(
    http = http,
    method = "DELETE",
    url = href,
    headers = buildMap {
        ifMatch?.let { put("If-Match", it) }
        auth?.let { put("Authorization", it) }
    },
    body = ByteArray(0),
)

/** `GET`s one resource back, used to re-read the server copy after a conflict. */
suspend fun getCalendarResource(http: SubscriptionHttp, href: String, auth: String?): CalDavExchange {
    val response = try {
        http.send(
            HttpRequest(
                method = "GET",
                url = href,
                headers = buildMap {
                    put("Accept", "text/calendar")
                    auth?.let { put("Authorization", it) }
                },
            ),
        )
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        return CalDavExchange.Unreachable
    }
    return exchangeResult(response, wantBody = true)
}

private suspend fun exchange(
    http: SubscriptionHttp,
    method: String,
    url: String,
    headers: Map<String, String>,
    body: ByteArray,
): CalDavExchange = try {
    exchangeResult(http.send(HttpRequest(method, url, headers, body)), wantBody = false)
} catch (error: Exception) {
    if (error is CancellationException) throw error
    CalDavExchange.Unreachable
}

private fun exchangeResult(response: HttpResponse, wantBody: Boolean): CalDavExchange = when {
    response.status in 200..299 -> {
        if (wantBody && response.body.size > MaxSubscriptionBytes) {
            CalDavExchange.HttpError(413)
        } else {
            CalDavExchange.Applied(
                etag = response.header("etag")?.trim()?.takeIf(String::isNotEmpty),
                body = if (wantBody) decodeCalendarBytes(response.body) ?: response.body.decodeToString() else null,
            )
        }
    }
    response.status == 404 -> CalDavExchange.Gone
    response.status == 412 || response.status == 409 -> CalDavExchange.Conflict(response.status)
    response.status == 401 || response.status == 403 -> CalDavExchange.Unauthorized
    else -> CalDavExchange.HttpError(response.status)
}

/** One WebDAV `PROPFIND`; null when the transport itself failed. */
private suspend fun propfind(http: SubscriptionHttp, url: String, body: String, auth: String?, depth: Int): HttpResponse? =
    try {
        http.send(
            HttpRequest(
                method = "PROPFIND",
                url = url,
                headers = buildMap {
                    put("Depth", depth.toString())
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

/** A `PROPFIND` that follows one manual redirect hop, keeping the final URL for href resolution. */
private suspend fun propfindFollowingRedirect(
    http: SubscriptionHttp,
    url: String,
    body: String,
    auth: String?,
): Pair<String, HttpResponse>? {
    val response = propfind(http, url, body, auth, depth = 0) ?: return null
    if (response.status !in 300..399) return url to response
    val location = response.header("location")?.let { absolutize(it, url) } ?: return url to response
    val followed = propfind(http, location, body, auth, depth = 0) ?: return null
    return location to followed
}

private fun HttpResponse.header(name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

private fun discoveryFailureFor(status: Int): DiscoveryFailure? = when {
    status in 200..299 -> null
    status == 401 || status == 403 -> DiscoveryFailure.Unauthorized
    else -> DiscoveryFailure.HttpError
}

/** `PROPFIND` body asking for the signed-in principal. */
internal fun principalPropfind(): String = """
    <?xml version="1.0" encoding="utf-8" ?>
    <d:propfind xmlns:d="DAV:">
      <d:prop>
        <d:current-user-principal/>
      </d:prop>
    </d:propfind>
""".trimIndent()

/** `PROPFIND` body asking for the principal's calendar home set. */
internal fun homeSetPropfind(): String = """
    <?xml version="1.0" encoding="utf-8" ?>
    <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
      <d:prop>
        <c:calendar-home-set/>
      </d:prop>
    </d:propfind>
""".trimIndent()

/** `PROPFIND` body asking for everything the calendar picker needs per collection. */
internal fun collectionPropfind(): String = """
    <?xml version="1.0" encoding="utf-8" ?>
    <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">
      <d:prop>
        <d:displayname/>
        <d:resourcetype/>
        <d:current-user-privilege-set/>
        <c:supported-calendar-component-set/>
        <cs:calendar-color/>
      </d:prop>
    </d:propfind>
""".trimIndent()

/**
 * Absolute URL of [href] as answered for a request to [requestUrl]: absolute hrefs pass through,
 * root-relative ones join the request origin, anything else is not addressable.
 */
internal fun absolutize(href: String, requestUrl: String): String? {
    val trimmed = href.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    if (!trimmed.startsWith("/")) return null
    val schemeEnd = requestUrl.indexOf("://")
    if (schemeEnd <= 0) return null
    val authorityStart = schemeEnd + 3
    val pathStart = requestUrl.indexOf('/', authorityStart).let { if (it < 0) requestUrl.length else it }
    return requestUrl.substring(0, pathStart) + trimmed
}

/** Accepts the URL forms the add-account dialog allows. */
internal fun resolveDavUrl(raw: String): String? =
    resolveSubscriptionUrl(raw)

/**
 * Member URL a resource with [uid] gets inside [collectionUrl]. The UID is percent-encoded so a
 * `/` or space inside it cannot escape the collection path.
 */
internal fun resourceHref(collectionUrl: String, uid: String): String =
    collectionUrl.trimEnd('/') + "/" + encodePathSegment(uid) + ".ics"

/** Percent-encodes everything outside the unreserved set of RFC 3986. */
internal fun encodePathSegment(value: String): String = buildString(value.length) {
    value.encodeToByteArray().forEach { byte ->
        val b = byte.toInt() and 0xFF
        if (b in 0x41..0x5A || b in 0x61..0x7A || b in 0x30..0x39 ||
            b == 0x2D || b == 0x2E || b == 0x5F || b == 0x7E || b == 0x40
        ) {
            append(b.toChar())
        } else {
            append('%')
            append(b.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

/** `calendar-color` (`#RRGGBBAA` on most servers, `#RRGGBB` elsewhere) as `0xAARRGGBB`. */
internal fun parseCalendarColor(value: String): Long? {
    val hex = value.trim().removePrefix("#").removePrefix("#")
    if (hex.length == 8) {
        val rgba = hex.toLongOrNull(16) ?: return null
        val alpha = rgba and 0xFF
        val rgb = rgba ushr 8
        return (alpha shl 24) or rgb
    }
    if (hex.length == 6) {
        val rgb = hex.toLongOrNull(16) ?: return null
        return (0xFFL shl 24) or rgb
    }
    return null
}
