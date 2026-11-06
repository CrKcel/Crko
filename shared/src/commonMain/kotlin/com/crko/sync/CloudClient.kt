package com.crko.sync

import com.crko.domain.AppJson
import com.crko.storage.HttpRequest
import com.crko.storage.HttpResponse
import com.crko.storage.SubscriptionHttp
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The crko.cloud wire protocol: plain JSON over HTTPS, one sealed blob per op. Every function
 * answers [CloudOutcome]; `Failure` carries the server's error code (e.g. `invalid_invite`) for the
 * account dialogs, `Unreachable` covers transport failures. Payload bytes are opaque ciphertext —
 * this layer serializes envelopes and never inspects calendar data.
 */

/** Pull responses larger than this are refused; a page should never come near it. */
const val MaxCloudPullBytes = 16_000_000

@Serializable
internal data class CloudSignupRequest(
    val invite: String,
    val name: String,
    @SerialName("kdf_salt") val kdfSalt: String,
    @SerialName("auth_hash") val authHash: String,
)

@Serializable
internal data class CloudPrepareRequest(val name: String)

@Serializable
data class CloudPrepareResponse(@SerialName("kdf_salt") val kdfSalt: String)

@Serializable
internal data class CloudSessionRequest(
    val name: String,
    @SerialName("auth_hash") val authHash: String,
)

@Serializable
data class CloudSessionResponse(
    val token: String,
    @SerialName("expires_ms") val expiresMs: Long,
    @SerialName("kdf_salt") val kdfSalt: String? = null,
)

/** One encrypted change; the tuple fields are the merge keys, [payload] stays opaque. */
@Serializable
data class CloudOp(
    val kind: String,
    val key: String,
    val seq: Long,
    val ts: Long,
    val deleted: Boolean,
    val payload: String? = null,
)

@Serializable
internal data class CloudPushRequest(val ops: List<CloudOp>)

@Serializable
data class CloudPushResponse(
    val applied: Int,
    val stale: Int,
    val cursor: Long,
)

@Serializable
data class CloudChange(
    val kind: String,
    val key: String,
    val seq: Long,
    val ts: Long,
    val deleted: Boolean,
    val payload: String? = null,
)

@Serializable
data class CloudPullResponse(
    val cursor: Long,
    val changes: List<CloudChange>,
)

@Serializable
internal data class CloudDeleteRequest(@SerialName("auth_hash") val authHash: String)

/** Outcome of one cloud protocol call, phrased for the sync engine and the account dialogs. */
sealed interface CloudOutcome<out T> {
    data class Success<T>(val value: T) : CloudOutcome<T>
    data class Failure(val status: Int, val error: String?) : CloudOutcome<Nothing>
    data object Unreachable : CloudOutcome<Nothing>
}

/**
 * Normalizes a user-entered server base URL: trims spaces and trailing slashes, and answers null
 * unless an http(s) URL remains.
 */
fun resolveCloudServerUrl(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = when {
        trimmed.startsWith("https://") || trimmed.startsWith("http://") -> trimmed
        else -> "https://$trimmed"
    }
    val withoutTrailing = withScheme.trimEnd('/')
    return if (withoutTrailing.startsWith("http")) withoutTrailing else null
}

internal fun bearerHeader(token: String): String = "Bearer $token"

/** Normalizes a base URL and joins [path]; the sync engine calls this with a validated account. */
internal fun cloudUrl(server: String, path: String): String =
    server.trimEnd('/') + path

internal suspend fun cloudExchange(http: SubscriptionHttp, request: HttpRequest): CloudExchange {
    val response: HttpResponse = try {
        http.send(request)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        return CloudExchange.Unreachable
    }
    return when {
        response.status in 200..299 -> CloudExchange.Ok(response.body.decodeToString())
        else -> CloudExchange.HttpError(response.status, response.body.decodeToString())
    }
}

private inline fun <T> CloudExchange.into(parse: (String) -> T?): CloudOutcome<T> = when (this) {
    is CloudExchange.Ok -> parse(body)?.let { CloudOutcome.Success(it) }
        ?: CloudOutcome.Failure(0, "invalid_response")
    is CloudExchange.HttpError -> CloudOutcome.Failure(status, errorOrNull(body.orEmpty()))
    CloudExchange.Unreachable -> CloudOutcome.Unreachable
}

/** Pulls `"error": "…"` out of a JSON error body without failing on a plain body. */
private fun errorOrNull(body: String): String? =
    runCatching { AppJson.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()

@Serializable
private data class ErrorBody(val error: String? = null)

private suspend fun post(http: SubscriptionHttp, url: String, body: String, token: String? = null): CloudExchange =
    cloudExchange(
        http,
        HttpRequest(
            method = "POST",
            url = url,
            headers = buildMap {
                put("Content-Type", "application/json")
                token?.let { put("Authorization", bearerHeader(it)) }
            },
            body = body.encodeToByteArray(),
        ),
    )

/** POST /v1/account — creates the account behind [invite] and returns the first session. */
suspend fun cloudSignup(
    server: String,
    invite: String,
    name: String,
    saltBase64: String,
    authHashBase64: String,
    http: SubscriptionHttp,
): CloudOutcome<CloudSessionResponse> = post(
    http,
    cloudUrl(server, "/v1/account"),
    AppJson.encodeToString(
        CloudSignupRequest.serializer(),
        CloudSignupRequest(invite.trim(), name.trim().lowercase(), saltBase64, authHashBase64),
    ),
).into { body ->
    runCatching { AppJson.decodeFromString(CloudSessionResponse.serializer(), body) }.getOrNull()
}

/** POST /v1/session/prepare — fetches the KDF salt so the keys can be derived before login. */
suspend fun cloudSessionPrepare(
    server: String,
    name: String,
    http: SubscriptionHttp,
): CloudOutcome<CloudPrepareResponse> = post(
    http,
    cloudUrl(server, "/v1/session/prepare"),
    AppJson.encodeToString(CloudPrepareRequest.serializer(), CloudPrepareRequest(name.trim().lowercase())),
).into { body ->
    runCatching { AppJson.decodeFromString(CloudPrepareResponse.serializer(), body) }.getOrNull()
}

/** POST /v1/session — verifies the auth key and returns a fresh token. */
suspend fun cloudLogin(
    server: String,
    name: String,
    authHashBase64: String,
    http: SubscriptionHttp,
): CloudOutcome<CloudSessionResponse> = post(
    http,
    cloudUrl(server, "/v1/session"),
    AppJson.encodeToString(CloudSessionRequest.serializer(), CloudSessionRequest(name.trim().lowercase(), authHashBase64)),
).into { body ->
    runCatching { AppJson.decodeFromString(CloudSessionResponse.serializer(), body) }.getOrNull()
}

/** POST /v1/sync — pushes a batch of encrypted ops; the server answers with applied/stale counts. */
suspend fun cloudPush(
    server: String,
    token: String,
    ops: List<CloudOp>,
    http: SubscriptionHttp,
): CloudOutcome<CloudPushResponse> = post(
    http,
    cloudUrl(server, "/v1/sync"),
    AppJson.encodeToString(CloudPushRequest.serializer(), CloudPushRequest(ops)),
    token = token,
).into { body ->
    runCatching { AppJson.decodeFromString(CloudPushResponse.serializer(), body) }.getOrNull()
}

/** GET /v1/sync?cursor= — full snapshot at 0, change-log delta otherwise. */
suspend fun cloudPull(
    server: String,
    token: String,
    cursor: Long,
    limit: Int = 200,
    http: SubscriptionHttp,
): CloudOutcome<CloudPullResponse> {
    val exchange = cloudExchange(
        http,
        HttpRequest(
            method = "GET",
            url = cloudUrl(server, "/v1/sync?cursor=$cursor&limit=$limit"),
            headers = mapOf("Authorization" to bearerHeader(token)),
        ),
    )
    return exchange.into { body ->
        if (body.length > MaxCloudPullBytes) return@into null
        runCatching { AppJson.decodeFromString(CloudPullResponse.serializer(), body) }.getOrNull()
    }
}

/** POST /v1/account/delete — removes the account and every stored blob after re-verification. */
suspend fun cloudDeleteAccount(
    server: String,
    token: String,
    authHashBase64: String,
    http: SubscriptionHttp,
): CloudOutcome<Unit> = post(
    http,
    cloudUrl(server, "/v1/account/delete"),
    AppJson.encodeToString(CloudDeleteRequest.serializer(), CloudDeleteRequest(authHashBase64)),
    token = token,
).into { Unit }

/** Transport-level result of one HTTP exchange, before response parsing. */
sealed interface CloudExchange {
    data class Ok(val body: String) : CloudExchange
    data class HttpError(val status: Int, val body: String?) : CloudExchange
    data object Unreachable : CloudExchange
}
