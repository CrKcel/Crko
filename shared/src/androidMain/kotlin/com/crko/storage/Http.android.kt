package com.crko.storage

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * `HttpURLConnection` sender. It accepts every HTTP method token, so WebDAV `REPORT` requests go
 * through the same code path as a plain `GET`.
 */
actual fun defaultSubscriptionHttp(): SubscriptionHttp = object : SubscriptionHttp {
    override suspend fun send(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (request.body.isNotEmpty()) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(request.body.size)
                connection.outputStream.use { it.write(request.body) }
            }
            val status = connection.responseCode
            val headers = mutableMapOf<String, String>()
            connection.headerFields.forEach { (name, values) ->
                if (name != null && !values.isNullOrEmpty()) headers[name.lowercase()] = values.first()
            }
            // Error responses carry the CalDAV multistatus the caller may still need to parse.
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            HttpResponse(status, headers, body)
        } finally {
            connection.disconnect()
        }
    }
}
