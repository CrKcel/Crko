package com.crko.state.sync

import com.crko.domain.AppJson
import com.crko.domain.CalendarComponent
import com.crko.domain.Event
import com.crko.domain.Journal
import com.crko.domain.Todo
import com.crko.state.AppMessage
import com.crko.state.StateAccess
import com.crko.storage.CloudAccountStore
import com.crko.storage.CloudCrypto
import com.crko.storage.CloudKeyBytes
import com.crko.storage.OutboxBackend
import com.crko.storage.OutboxEntry
import com.crko.storage.OutboxStore
import com.crko.storage.SecretStore
import com.crko.storage.SubscriptionHttp
import com.crko.storage.cloudEncKeyKey
import com.crko.storage.cloudTokenKey
import com.crko.storage.sealText
import com.crko.sync.CloudOp
import com.crko.sync.CloudOutcome
import com.crko.sync.cloudPush
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Drains the cloud half of the outbox into encrypted `POST /v1/sync` batches. Every pending row
 * becomes an opaque op keyed by `calendar/uid/recurrence`: a create or update seals the record's
 * RFC payload with the account's encryption key, a deletion seals nothing and sends a tombstone
 * that outranks the deleted revision by `SEQUENCE + 1`. The server rejects stale tuples, so a
 * losing push is simply dropped and the next pull reconciles the winner — no conflict dance.
 */
internal class CloudPushService(
    private val access: StateAccess,
    private val outbox: OutboxStore,
    private val http: SubscriptionHttp?,
    private val crypto: CloudCrypto?,
    private val accountStore: CloudAccountStore,
    private val secretStore: SecretStore?,
    private val clock: Clock,
    private val settingsJson: () -> String,
) {
    private val state get() = access.state

    /** Whether a push pass has work: due rows, or preferences that changed since the last push. */
    fun hasDuePush(now: Instant = clock.now()): Boolean {
        if (http == null || crypto == null) return false
        if (accountStore.load()?.enabled != true) return false
        if (settingsDirty()) return true
        return outbox.entriesForBackend(OutboxBackend.Cloud).any { it.isDue(now) }
    }

    /**
     * Pushes every due change in batches. The first transport failure or authorization problem ends
     * the pass — the remaining batches would fail the same way and the retry bookkeeping keeps them.
     * True when at least one batch landed.
     */
    suspend fun drain(): Boolean {
        val ready = context() ?: return false
        val now = clock.now().toEpochMilliseconds()
        val work = mutableListOf<Work>()
        outbox.entriesForBackend(OutboxBackend.Cloud).filter { it.isDue(clock.now()) }.forEach { row ->
            when (val op = buildOp(ready, row, now)) {
                null -> outbox.delete(row.id) // the record vanished locally; nothing left to send
                else -> work += Work(row, op)
            }
        }
        if (settingsDirty()) {
            val json = settingsJson()
            work += Work(
                null,
                CloudOp(
                    kind = CloudKind.Settings,
                    key = CloudSettingsKey,
                    seq = 0,
                    ts = now,
                    deleted = false,
                    payload = ready.crypto.sealText(ready.encKey, json, cloudPayloadAad(CloudKind.Settings, CloudSettingsKey)),
                ),
                settingsJson = json,
            )
        }
        if (work.isEmpty()) return false
        var pushed = false
        work.chunked(CloudPushBatch).forEach { batch ->
            when (val result = cloudPush(ready.server, ready.token, batch.map { it.op }, http!!)) {
                is CloudOutcome.Success -> {
                    batch.forEach { work -> work.row?.let { row -> outbox.delete(row.id) } }
                    batch.firstNotNullOfOrNull { it.settingsJson }?.let { accountStore.savePushedSettings(it) }
                    pushed = true
                }
                is CloudOutcome.Failure -> {
                    recordFailure(batch, "HTTP ${result.status}")
                    if (result.status == 401 || result.status == 403) {
                        access.notify(AppMessage.CloudAuthExpired)
                    }
                    return pushed
                }
                CloudOutcome.Unreachable -> {
                    recordFailure(batch, "unreachable")
                    return pushed
                }
            }
        }
        return pushed
    }

    private class Work(val row: OutboxEntry?, val op: CloudOp, val settingsJson: String? = null)

    private fun settingsDirty(): Boolean = settingsJson() != accountStore.loadPushedSettings()

    /** One pending row → one op, or null when the record has disappeared from the state. */
    private fun buildOp(ready: PushContext, row: OutboxEntry, now: Long): CloudOp? {
        val kind = row.entity
        val key = when (kind) {
            CloudKind.Calendar -> row.uid
            else -> cloudRecordKey(row.calendarId, row.uid, row.recurrenceKey)
        }
        if (row.isDelete) {
            // The tombstone outranks the deleted revision: one above its SEQUENCE, pushed now.
            return CloudOp(kind = kind, key = key, seq = row.sequence + 1, ts = now, deleted = true)
        }
        val payload: String = when (kind) {
            CloudKind.Calendar -> {
                val calendar = state.calendars.firstOrNull { it.id == row.uid } ?: return null
                AppJson.encodeToString(
                    CloudCalendarConfig.serializer(),
                    CloudCalendarConfig(
                        id = calendar.id,
                        name = calendar.name,
                        colorArgb = calendar.colorArgb,
                        visible = calendar.visible,
                        timeZones = calendar.timeZones,
                    ),
                )
            }
            else -> {
                val calendar = state.calendars.firstOrNull { it.id == row.calendarId } ?: return null
                val record = findCloudRecord(calendar, kind, row.uid, row.recurrenceKey) ?: return null
                encodeCloudRecord(kind, record)
            }
        }
        return CloudOp(
            kind = kind,
            key = key,
            seq = row.sequence,
            ts = clock.now().toEpochMilliseconds(),
            deleted = false,
            payload = ready.crypto.sealText(ready.encKey, payload, cloudPayloadAad(kind, key)),
        )
    }

    private fun recordFailure(batch: List<Work>, message: String) {
        val now = clock.now().toEpochMilliseconds()
        batch.forEach { work ->
            val row = work.row ?: return@forEach
            val attempts = row.attempts + 1
            outbox.updateAttempt(row.id, attempts, now + backoffMillis(attempts), message)
        }
    }

    /** 1 min, 5 min, 30 min, then every 2 hours until a fresh edit or a success resets the row. */
    private fun backoffMillis(attempts: Long): Long = when {
        attempts <= 1 -> 60_000L
        attempts == 2L -> 5 * 60_000L
        attempts == 3L -> 30 * 60_000L
        else -> 2 * 60 * 60_000L
    }

    /** The ready-to-push context, or null when anything required is missing. */
    private suspend fun context(): PushContext? {
        val http = http ?: return null
        val crypto = crypto ?: return null
        val account = accountStore.load()?.takeIf { it.enabled } ?: return null
        val token = secretStore?.load(cloudTokenKey()) ?: return null
        val encKey = secretStore.load(cloudEncKeyKey())
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
            ?.takeIf { it.size == CloudKeyBytes }
            ?: return null
        return PushContext(account.server, token, crypto, encKey)
    }

    private class PushContext(val server: String, val token: String, val crypto: CloudCrypto, val encKey: ByteArray)

    private fun OutboxEntry.isDue(now: Instant): Boolean =
        nextAttemptMs == null || nextAttemptMs <= now.toEpochMilliseconds()

    private companion object {
        const val CloudPushBatch = 200
    }
}
