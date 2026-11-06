package com.crko.state

import com.crko.storage.CloudAccountConfig
import com.crko.storage.CloudAccountStore
import com.crko.storage.CloudCrypto
import com.crko.storage.CloudKeys
import com.crko.storage.CloudSaltBytes
import com.crko.storage.OutboxBackend
import com.crko.storage.OutboxStore
import com.crko.storage.SecretStore
import com.crko.storage.SubscriptionHttp
import com.crko.storage.cloudEncKeyKey
import com.crko.storage.cloudTokenKey
import com.crko.storage.deriveCloudKeys
import com.crko.storage.toAuthHash
import com.crko.storage.toEncKeySecret
import com.crko.sync.CloudOutcome
import com.crko.sync.CloudSessionResponse
import com.crko.sync.cloudDeleteAccount
import com.crko.sync.cloudLogin
import com.crko.sync.cloudSessionPrepare
import com.crko.sync.cloudSignup
import com.crko.sync.resolveCloudServerUrl
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of one cloud account action, phrased for the account dialogs. */
sealed interface CloudAccountResult {
    data object SignedIn : CloudAccountResult
    data class Failed(val status: Int, val error: String?) : CloudAccountResult
    data object Unreachable : CloudAccountResult
}

/**
 * Signs accounts up, in and out of crko.cloud. The password only derives keys: PBKDF2 with the
 * server's salt produces a master, two HMAC domains split it into the auth key (presented to the
 * server, stored hashed) and the enc key (kept in the platform keystore and used for every
 * payload). Signing in stores the account config and resets the device bookkeeping so the next
 * sync pass bootstraps the whole local state into the account.
 */
internal class CloudAccountService(
    private val outbox: OutboxStore,
    private val http: SubscriptionHttp?,
    private val crypto: CloudCrypto?,
    private val accountStore: CloudAccountStore,
    private val secretStore: SecretStore?,
    private val sync: () -> Unit,
) {
    /** Creates the account behind [invite] and signs in. */
    suspend fun signup(serverInput: String, invite: String, name: String, password: String): CloudAccountResult {
        val server = resolveCloudServerUrl(serverInput) ?: return CloudAccountResult.Failed(0, "invalid_server")
        val crypto = crypto ?: return CloudAccountResult.Failed(0, "unsupported")
        val http = http ?: return CloudAccountResult.Failed(0, "unsupported")
        val salt = crypto.randomBytes(CloudSaltBytes)
        val keys = deriveKeys(crypto, password, salt) ?: return CloudAccountResult.Unreachable
        return when (val outcome = cloudSignup(server, invite, name, Base64.encode(salt), keys.toAuthHash(), http)) {
            is CloudOutcome.Success -> storeSession(server, name, outcome.value, keys)
            is CloudOutcome.Failure -> CloudAccountResult.Failed(outcome.status, outcome.error)
            CloudOutcome.Unreachable -> CloudAccountResult.Unreachable
        }
    }

    /** Fetches the salt, derives the keys and verifies them against the server. */
    suspend fun login(serverInput: String, name: String, password: String): CloudAccountResult {
        val server = resolveCloudServerUrl(serverInput) ?: return CloudAccountResult.Failed(0, "invalid_server")
        val crypto = crypto ?: return CloudAccountResult.Failed(0, "unsupported")
        val http = http ?: return CloudAccountResult.Failed(0, "unsupported")
        val salt = when (val prepare = cloudSessionPrepare(server, name, http)) {
            is CloudOutcome.Success -> runCatching { Base64.decode(prepare.value.kdfSalt) }.getOrNull()
                ?: return CloudAccountResult.Failed(0, "invalid_salt")
            is CloudOutcome.Failure -> return CloudAccountResult.Failed(prepare.status, prepare.error)
            CloudOutcome.Unreachable -> return CloudAccountResult.Unreachable
        }
        val keys = deriveKeys(crypto, password, salt) ?: return CloudAccountResult.Unreachable
        return when (val outcome = cloudLogin(server, name, keys.toAuthHash(), http)) {
            is CloudOutcome.Success -> storeSession(server, name, outcome.value, keys)
            is CloudOutcome.Failure -> return CloudAccountResult.Failed(outcome.status, outcome.error)
            CloudOutcome.Unreachable -> CloudAccountResult.Unreachable
        }
    }

    /** Clears the account, the device bookkeeping and every pending cloud row. Local data stays. */
    suspend fun signOut() {
        secretStore?.delete(cloudTokenKey())
        secretStore?.delete(cloudEncKeyKey())
        accountStore.save(null)
        accountStore.saveCursor(0)
        accountStore.saveBootstrapped(false)
        accountStore.savePushedSettings(null)
        accountStore.saveAppliedSettings(null)
        accountStore.saveLastSyncMs(0)
        outbox.deleteForBackend(OutboxBackend.Cloud)
    }

    /** Pausing stops the recorder fan-out and drops the queue; enabling re-bootstraps. */
    fun setEnabled(enabled: Boolean) {
        val current = accountStore.load() ?: return
        if (current.enabled == enabled) return
        accountStore.save(current.copy(enabled = enabled))
        if (enabled) {
            accountStore.saveBootstrapped(false)
            sync()
        } else {
            outbox.deleteForBackend(OutboxBackend.Cloud)
        }
    }

    /** Removes the account and every stored blob on the server, then signs out locally. */
    suspend fun deleteAccount(password: String): CloudAccountResult {
        val account = accountStore.load() ?: return CloudAccountResult.Failed(0, "signed_out")
        val crypto = crypto ?: return CloudAccountResult.Failed(0, "unsupported")
        val http = http ?: return CloudAccountResult.Failed(0, "unsupported")
        val token = secretStore?.load(cloudTokenKey()) ?: return CloudAccountResult.Failed(0, "signed_out")
        // Re-derive from the stored salt so the request proves the password again.
        val salt = when (val prepare = cloudSessionPrepare(account.server, account.name, http)) {
            is CloudOutcome.Success -> runCatching { Base64.decode(prepare.value.kdfSalt) }.getOrNull()
                ?: return CloudAccountResult.Failed(0, "invalid_salt")
            is CloudOutcome.Failure -> return CloudAccountResult.Failed(prepare.status, prepare.error)
            CloudOutcome.Unreachable -> return CloudAccountResult.Unreachable
        }
        val keys = deriveKeys(crypto, password, salt) ?: return CloudAccountResult.Unreachable
        return when (val outcome = cloudDeleteAccount(account.server, token, keys.toAuthHash(), http)) {
            is CloudOutcome.Success -> {
                signOut()
                CloudAccountResult.SignedIn
            }
            is CloudOutcome.Failure -> CloudAccountResult.Failed(outcome.status, outcome.error)
            CloudOutcome.Unreachable -> CloudAccountResult.Unreachable
        }
    }

    private suspend fun storeSession(server: String, name: String, session: CloudSessionResponse, keys: CloudKeys): CloudAccountResult {
        secretStore?.save(cloudTokenKey(), session.token)
        secretStore?.save(cloudEncKeyKey(), keys.toEncKeySecret())
        accountStore.save(CloudAccountConfig(server = server, name = name, enabled = true))
        accountStore.saveCursor(0)
        accountStore.saveBootstrapped(false)
        accountStore.savePushedSettings(null)
        accountStore.saveAppliedSettings(null)
        accountStore.saveLastSyncMs(0)
        outbox.deleteForBackend(OutboxBackend.Cloud)
        sync()
        return CloudAccountResult.SignedIn
    }

    /** PBKDF2 burns hundreds of thousands of rounds; keep them off the caller's thread. */
    private suspend fun deriveKeys(crypto: CloudCrypto, password: String, salt: ByteArray): CloudKeys? =
        withContext(Dispatchers.Default) {
            runCatching { deriveCloudKeys(crypto, password, salt) }.getOrNull()
        }
}
