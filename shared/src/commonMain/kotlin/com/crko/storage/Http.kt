package com.crko.storage

/**
 * The minimal HTTP surface calendar subscriptions need: one request in, one response out. Kept in
 * the storage layer like the other platform bridges so the domain code can substitute fakes in
 * tests.
 */

/** One outbound request. `method` may be any HTTP token — WebDAV `REPORT` included. */
class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/** One inbound response; header keys are lower-cased, the body is the raw bytes. */
class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/** Sends subscription requests through the platform network stack. */
interface SubscriptionHttp {
    suspend fun send(request: HttpRequest): HttpResponse
}

/** Platform-provided sender, wired into the state holder by the app composition root. */
expect fun defaultSubscriptionHttp(): SubscriptionHttp
