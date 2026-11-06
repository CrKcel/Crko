package com.crko.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.crko.domain.AppJson

/**
 * The cloud sync account plus the device-local sync bookkeeping that must never travel: which
 * account signs in, whether sync is on, the pull cursor and the settings fingerprints the push/pull
 * dedup compares. Lives outside SQLite (the database file is exportable) in the platform
 * preferences, exactly like the small preferences payload.
 */
@Serializable
data class CloudAccountConfig(
    /** Base URL of the sync service, e.g. `https://crko.cloud`. */
    val server: String,
    val name: String,
    val enabled: Boolean = true,
)

/** Device-local cloud sync bookkeeping, phrased for [CloudSyncService]'s use. */
interface CloudAccountStore {
    fun load(): CloudAccountConfig?
    fun save(config: CloudAccountConfig?)

    /** The change-log cursor this device already pulled. */
    fun loadCursor(): Long
    fun saveCursor(cursor: Long)

    /** Whether the initial bootstrap (everything local queued for the cloud) already ran. */
    fun loadBootstrapped(): Boolean
    fun saveBootstrapped(bootstrapped: Boolean)

    /** The settings JSON as last pushed, so unchanged preferences do not re-push. */
    fun loadPushedSettings(): String?
    fun savePushedSettings(json: String?)

    /** The settings JSON as last applied from the cloud, so pulled preferences do not re-apply. */
    fun loadAppliedSettings(): String?
    fun saveAppliedSettings(json: String?)

    /**
     * Newest `seq:ts` tuple this device applied or pushed, per cloud key. Settings and calendar
     * configs carry no revision of their own, so the pull compares against this book to ignore the
     * stale change-log entries the server appends for rejected pushes.
     */
    fun loadAppliedVersions(): Map<String, String>
    fun saveAppliedVersions(versions: Map<String, String>)

    /** Epoch millis of the last completed push+pull pass, for the settings page. */
    fun loadLastSyncMs(): Long
    fun saveLastSyncMs(ms: Long)
}

private const val ACCOUNT_KEY = "cloud.account.v1"
private const val CURSOR_KEY = "cloud.cursor.v1"
private const val BOOTSTRAP_KEY = "cloud.bootstrapped.v1"
private const val PUSHED_SETTINGS_KEY = "cloud.settings.pushed.v1"
private const val APPLIED_SETTINGS_KEY = "cloud.settings.applied.v1"
private const val APPLIED_VERSIONS_KEY = "cloud.versions.applied.v1"
private const val LAST_SYNC_KEY = "cloud.last-sync.v1"
private const val CLEARED = ""

private val accountSerializer = CloudAccountConfig.serializer()
private val versionsSerializer = MapSerializer(String.serializer(), String.serializer())

/** [CloudAccountStore] over the platform preferences; the app uses this. */
class KeyValueCloudAccountStore(private val kv: KeyValueStore) : CloudAccountStore {
    override fun load(): CloudAccountConfig? {
        val raw = kv.getString(ACCOUNT_KEY)?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { AppJson.decodeFromString(accountSerializer, raw) }.getOrNull()
    }

    override fun save(config: CloudAccountConfig?) {
        val raw = config?.let { AppJson.encodeToString(accountSerializer, it) } ?: CLEARED
        kv.putString(ACCOUNT_KEY, raw)
    }

    override fun loadCursor(): Long = kv.getString(CURSOR_KEY)?.toLongOrNull() ?: 0L

    override fun saveCursor(cursor: Long) {
        kv.putString(CURSOR_KEY, cursor.toString())
    }

    override fun loadBootstrapped(): Boolean = kv.getString(BOOTSTRAP_KEY) == "1"

    override fun saveBootstrapped(bootstrapped: Boolean) {
        kv.putString(BOOTSTRAP_KEY, if (bootstrapped) "1" else CLEARED)
    }

    override fun loadPushedSettings(): String? = kv.getString(PUSHED_SETTINGS_KEY)?.takeIf { it.isNotEmpty() }

    override fun savePushedSettings(json: String?) {
        kv.putString(PUSHED_SETTINGS_KEY, json ?: CLEARED)
    }

    override fun loadAppliedSettings(): String? = kv.getString(APPLIED_SETTINGS_KEY)?.takeIf { it.isNotEmpty() }

    override fun saveAppliedSettings(json: String?) {
        kv.putString(APPLIED_SETTINGS_KEY, json ?: CLEARED)
    }

    override fun loadAppliedVersions(): Map<String, String> {
        val raw = kv.getString(APPLIED_VERSIONS_KEY)?.takeIf { it.isNotEmpty() } ?: return emptyMap()
        return runCatching { AppJson.decodeFromString(versionsSerializer, raw) }.getOrDefault(emptyMap())
    }

    override fun saveAppliedVersions(versions: Map<String, String>) {
        kv.putString(APPLIED_VERSIONS_KEY, AppJson.encodeToString(versionsSerializer, versions))
    }

    override fun loadLastSyncMs(): Long = kv.getString(LAST_SYNC_KEY)?.toLongOrNull() ?: 0L

    override fun saveLastSyncMs(ms: Long) {
        kv.putString(LAST_SYNC_KEY, ms.toString())
    }
}

/** In-memory [CloudAccountStore] for the fallback storage and the tests. */
class MemoryCloudAccountStore : CloudAccountStore {
    private var config: CloudAccountConfig? = null
    private var cursor = 0L
    private var bootstrapped = false
    private var pushedSettings: String? = null
    private var appliedSettings: String? = null
    private var appliedVersions: Map<String, String> = emptyMap()
    private var lastSyncMs = 0L

    override fun load(): CloudAccountConfig? = config

    override fun save(config: CloudAccountConfig?) {
        this.config = config
    }

    override fun loadCursor(): Long = cursor

    override fun saveCursor(cursor: Long) {
        this.cursor = cursor
    }

    override fun loadBootstrapped(): Boolean = bootstrapped

    override fun saveBootstrapped(bootstrapped: Boolean) {
        this.bootstrapped = bootstrapped
    }

    override fun loadPushedSettings(): String? = pushedSettings

    override fun savePushedSettings(json: String?) {
        pushedSettings = json
    }

    override fun loadAppliedSettings(): String? = appliedSettings

    override fun saveAppliedSettings(json: String?) {
        appliedSettings = json
    }

    override fun loadAppliedVersions(): Map<String, String> = appliedVersions

    override fun saveAppliedVersions(versions: Map<String, String>) {
        appliedVersions = versions
    }

    override fun loadLastSyncMs(): Long = lastSyncMs

    override fun saveLastSyncMs(ms: Long) {
        lastSyncMs = ms
    }
}

/** Keys under which the cloud secrets live in the [SecretStore]. */
fun cloudTokenKey(): String = "cloud-token"

fun cloudEncKeyKey(): String = "cloud-enc-key"
