package com.crko

import com.crko.sync.CalDavDiscovery
import com.crko.sync.absolutize
import com.crko.sync.basicAuthHeader
import com.crko.sync.discoverCalendars
import com.crko.sync.encodePathSegment
import com.crko.sync.parseCalendarColor
import com.crko.sync.resourceHref
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.SubscriptionHttp
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Fake sender: every request is recorded, and the current handler decides the answer. */
private class DiscoveryFakeHttp(initial: (HttpRequest) -> HttpResponse) : SubscriptionHttp {
    var handler: (HttpRequest) -> HttpResponse = initial
    val calls = mutableListOf<HttpRequest>()

    override suspend fun send(request: HttpRequest): HttpResponse {
        calls += request
        return handler(request)
    }
}

class CalDavClientTest {

    @Test
    fun basicAuthHeaderEncodesUserAndPassword() {
        assertEquals("Basic dXNlcjpzZWNyZXQ=", basicAuthHeader("user", "secret"))
        assertNull(basicAuthHeader(null, "secret"))
        assertNull(basicAuthHeader("user", null))
        assertNull(basicAuthHeader("user", ""))
    }

    @Test
    fun resourceHrefsPercentEncodeTheUid() {
        assertEquals("https://x/cal/abc.ics", resourceHref("https://x/cal/", "abc"))
        assertEquals("https://x/cal/a%20b%2Fc@x.ics", resourceHref("https://x/cal", "a b/c@x"))
    }

    @Test
    fun calendarColorsParseToArgb() {
        // The server spelling is #RRGGBBAA; the app stores 0xAARRGGBB.
        assertEquals(0xFFE4B306uL.toLong(), parseCalendarColor("#E4B306FF"))
        assertEquals(0xFFE4B306uL.toLong(), parseCalendarColor("#E4B306"))
        assertNull(parseCalendarColor("nope"))
        assertNull(parseCalendarColor("#12345"))
    }

    @Test
    fun hrefsAbsolutizeAgainstTheRequestUrl() {
        assertEquals("https://h/cal/p/", absolutize("/cal/p/", "https://h/remote.php/dav/"))
        assertEquals("https://o/x", absolutize("https://o/x", "https://h/"))
        assertNull(absolutize("relative", "https://h/"))
        assertNull(absolutize("  ", "https://h/"))
    }

    @Test
    fun discoveryWalksPrincipalHomeSetAndCollections() = runTest {
        val principal = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/principals/me/</d:href>
                <d:propstat>
                  <d:prop><d:current-user-principal><d:href>/principals/me/</d:href></d:current-user-principal></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val homeSet = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/cal/</d:href>
                <d:propstat>
                  <d:prop><c:calendar-home-set><d:href>/cal/</d:href></c:calendar-home-set></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val collections = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/">
              <d:response>
                <d:href>/cal/personal/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Personal</d:displayname>
                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                    <cs:calendar-color>#E4B306FF</cs:calendar-color>
                    <c:supported-calendar-component-set><c:comp name="VEVENT"/><c:comp name="VTODO"/></c:supported-calendar-component-set>
                    <d:current-user-privilege-set>
                      <d:privilege><d:read/></d:privilege>
                      <d:privilege><d:write-content/></d:privilege>
                    </d:current-user-privilege-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/cal/shared/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Shared</d:displayname>
                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                    <d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/cal/not-a-calendar/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val http = DiscoveryFakeHttp { request ->
            val body = when {
                "current-user-principal" in request.body.decodeToString() -> principal
                "calendar-home-set" in request.body.decodeToString() -> homeSet
                else -> collections
            }
            HttpResponse(207, mapOf("content-type" to "application/xml"), body.encodeToByteArray())
        }
        val result = discoverCalendars("https://example.com", "me", "pw", http)
        val found = result as? CalDavDiscovery.Success
        assertTrue(found != null, "discovery failed: $result")
        assertEquals(2, found.collections.size)
        val personal = found.collections.first { it.displayName == "Personal" }
        assertEquals("https://example.com/cal/personal/", personal.href)
        assertEquals(0xFFE4B306uL.toLong(), personal.colorArgb)
        assertTrue(personal.writable)
        assertEquals(setOf("VEVENT", "VTODO"), personal.components)
        val shared = found.collections.first { it.displayName == "Shared" }
        assertTrue(!shared.writable)
        // Every step authenticates with the same Basic header.
        val auth = "Basic " + Base64.encode("me:pw".encodeToByteArray())
        assertTrue(http.calls.isNotEmpty() && http.calls.all { it.headers["Authorization"] == auth })
    }

    @Test
    fun discoveryReportsRejectedCredentials() = runTest {
        val http = DiscoveryFakeHttp { _ -> HttpResponse(401, emptyMap(), ByteArray(0)) }
        val result = discoverCalendars("https://example.com", "me", "wrong", http)
        assertEquals(com.crko.domain.DiscoveryFailure.Unauthorized, (result as? CalDavDiscovery.Failure)?.failure)
    }

    @Test
    fun discoveryFallsBackToTheWellKnownAddress() = runTest {
        val principal = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:propstat>
                  <d:prop><d:current-user-principal><d:href>/p/</d:href></d:current-user-principal></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val homeSet = """
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:propstat>
                  <d:prop><c:calendar-home-set><d:href>/cal/</d:href></c:calendar-home-set></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val collections = """
            <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
              <d:response>
                <d:href>/cal/p/</d:href>
                <d:propstat>
                  <d:prop>
                    <d:displayname>Only</d:displayname>
                    <d:resourcetype><d:collection/><c:calendar/></d:resourcetype>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        // The entered address refuses the report; the well-known hop answers instead.
        val http = DiscoveryFakeHttp { request ->
            val body = when {
                request.url.contains(".well-known/caldav") && "current-user-principal" in request.body.decodeToString() -> principal
                request.url.endsWith("/p/") -> homeSet
                else -> collections
            }
            HttpResponse(207, mapOf("content-type" to "application/xml"), body.encodeToByteArray())
        }
        val result = discoverCalendars("https://example.com", "me", "pw", http)
        val found = result as? CalDavDiscovery.Success
        assertTrue(found != null, "discovery failed: $result")
        assertEquals(listOf("Only"), found.collections.map { it.displayName })
    }
}
