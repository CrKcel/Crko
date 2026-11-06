package com.crko.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.crko.domain.AppJson
import com.crko.domain.PersistedSettings
import com.crko.state.settings.SettingsController
import com.crko.storage.CloudAccountConfig
import com.crko.storage.CloudAccountStore
import com.crko.storage.CloudCrypto
import com.crko.storage.MemoryCloudAccountStore
import com.crko.storage.OutboxStore
import com.crko.storage.SecretStore
import com.crko.storage.SubscriptionHttp
import com.crko.state.sync.CloudPushService
import com.crko.state.sync.CloudSyncService
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope

/**
 * The cloud half of the state facade, one place for the push and pull engines plus the account
 * service. The holder delegates its public cloud API here; without a host-wired account store,
 * crypto or HTTP bridge everything inside answers inertly, which is what tests run on.
 */
internal class CloudSyncHandle(
    access: StateAccess,
    scope: CoroutineScope,
    outbox: OutboxStore,
    http: SubscriptionHttp?,
    crypto: CloudCrypto?,
    secretStore: SecretStore?,
    clock: Clock,
    idProvider: () -> String,
    accountStore: CloudAccountStore?,
    settingsController: SettingsController,
) {
    /** The injected cloud bookkeeping, or an empty memory stand-in when the host wired none. */
    private val store: CloudAccountStore = accountStore ?: MemoryCloudAccountStore()

    /** Whether local calendars currently fan out into cloud rows; the recorder reads this. */
    val cloudActive: Boolean get() = store.load()?.enabled == true

    private val push = CloudPushService(
        access = access,
        outbox = outbox,
        http = http,
        crypto = crypto,
        accountStore = store,
        secretStore = secretStore,
        clock = clock,
        settingsJson = { AppJson.encodeToString(PersistedSettings.serializer(), settingsController.settings) },
    )

    private val sync = CloudSyncService(
        access = access,
        scope = scope,
        outbox = outbox,
        http = http,
        crypto = crypto,
        accountStore = store,
        secretStore = secretStore,
        clock = clock,
        idProvider = idProvider,
        settingsJson = { AppJson.encodeToString(PersistedSettings.serializer(), settingsController.settings) },
        applySettings = { settingsController.applyCloud(it) },
        push = push,
    )

    private val accounts = CloudAccountService(
        outbox = outbox,
        http = http,
        crypto = crypto,
        accountStore = store,
        secretStore = secretStore,
        sync = { sync.syncNow(notify = true) },
    )

    /**
     * The signed-in cloud account as observable state: the settings page only recomposes when this
     * changes, and a plain store read would leave the switch and the signed-in rows stale.
     */
    private var accountState by mutableStateOf(store.load())
    private fun refreshAccount() {
        accountState = store.load()
        sync.reloadLastSyncMs()
    }

    /** The signed-in cloud account, or null when this device has none. */
    val account: CloudAccountConfig? get() = accountState

    /** Whether a cloud push+pull pass is currently running. */
    val isSyncing: Boolean get() = sync.isSyncing

    /** Epoch millis of the last completed cloud pass; 0 when this device never synced. */
    val lastSyncMs: Long get() = sync.lastSyncMs

    /** The foreground poll's quiet pass: pushes due rows, pulls at most every five minutes. */
    fun syncDue() = sync.syncNow()

    /** One manual push+pull pass; the outcome arrives as a message. */
    fun syncNow() = sync.syncNow(notify = true)

    suspend fun signup(server: String, invite: String, name: String, password: String): CloudAccountResult =
        accounts.signup(server, invite, name, password).also { refreshAccount() }

    suspend fun login(server: String, name: String, password: String): CloudAccountResult =
        accounts.login(server, name, password).also { refreshAccount() }

    /** Signs out; the local calendars and records stay on the device. */
    suspend fun signOut() {
        accounts.signOut()
        refreshAccount()
    }

    fun setEnabled(enabled: Boolean) {
        accounts.setEnabled(enabled)
        refreshAccount()
    }

    /** Removes the account and every encrypted blob on the server after verifying [password]. */
    suspend fun deleteAccount(password: String): CloudAccountResult =
        accounts.deleteAccount(password).also { refreshAccount() }
}
