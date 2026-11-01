package com.crko.storage

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.getBytes
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** `NSURLSession` sender; the completion handler resumes the caller directly. */
actual fun defaultSubscriptionHttp(): SubscriptionHttp = object : SubscriptionHttp {
    @OptIn(ExperimentalForeignApi::class)
    override suspend fun send(request: HttpRequest): HttpResponse = suspendCancellableCoroutine { continuation ->
        val url = NSURL.URLWithString(request.url)
            ?: throw IllegalArgumentException("Invalid subscription URL: ${request.url}")
        val nsRequest = NSMutableURLRequest.requestWithURL(url)
        nsRequest.setHTTPMethod(request.method)
        request.headers.forEach { (name, value) -> nsRequest.setValue(value, forHTTPHeaderField = name) }
        if (request.body.isNotEmpty()) {
            nsRequest.setValue(request.body.toNSData(), forKey = "HTTPBody")
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(nsRequest) { data, response, error ->
            if (error != null) {
                // The failure surfaces as an exception; the holder maps any of them to "unreachable".
                continuation.resumeWithException(IllegalStateException(error.localizedDescription))
                return@dataTaskWithRequest
            }
            val http = response as? NSHTTPURLResponse
            val headers = http?.allHeaderFields?.entries.orEmpty()
                .associate { (name, value) -> name.toString().lowercase() to value.toString() }
            continuation.resume(
                HttpResponse(http?.statusCode?.toInt() ?: 200, headers, data?.toByteArray() ?: ByteArray(0)),
            )
        }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }
}

/** Copies these bytes into an [NSData] request body. */
@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) {
        NSData.create(bytes = null, length = 0u)
    } else {
        usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val bytes = ByteArray(size)
    bytes.usePinned { pinned -> getBytes(pinned.addressOf(0), length = length) }
    return bytes
}
