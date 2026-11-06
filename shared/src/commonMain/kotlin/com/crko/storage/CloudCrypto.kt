package com.crko.storage

import kotlin.io.encoding.Base64

/**
 * The cryptography the cloud sync runs. Every algorithm is pure common code ([PureCloudCrypto]) so
 * both platforms produce byte-identical ciphertexts; only randomness is a platform concern, which
 * is what the [expect fun][cloudCrypto] bridge provides. Null on a platform that cannot supply it:
 * the cloud account features simply stay unavailable.
 */
interface CloudCrypto {
    /** PBKDF2-HMAC-SHA256 (RFC 8018). */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, rounds: Int, keyLengthBytes: Int): ByteArray

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** AES-256-GCM one-shot; returns `ciphertext ‖ tag(16)`. */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray

    /** AES-256-GCM open; null when the tag does not verify or the input is malformed. */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray?

    fun randomBytes(count: Int): ByteArray
}

/** Platform-provided randomness; wired into the state holder by the app composition root. */
expect fun cloudCrypto(): CloudCrypto?

/** The only AES-GCM/SHA-256 crypto implementation: pure common code with injected randomness. */
class PureCloudCrypto(private val random: (Int) -> ByteArray) : CloudCrypto {
    override fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, rounds: Int, keyLengthBytes: Int): ByteArray =
        pbkdf2HmacSha256(password, salt, rounds, keyLengthBytes)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray = hmacSha256Bytes(key, data)

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray =
        Gcm.seal(key, nonce, aad, plaintext)

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? =
        Gcm.open(key, nonce, aad, sealed)

    override fun randomBytes(count: Int): ByteArray = random(count)
}

/** Key sizes and iteration counts of the cloud key derivation. */
const val CloudPbkdf2Rounds = 600_000
const val CloudKeyBytes = 32
const val CloudSaltBytes = 16
const val CloudNonceBytes = 12

/** Derived cloud keys. Both are HMAC masks of the PBKDF2 master: one authenticates, one encrypts. */
class CloudKeys(
    /** Proves knowledge of the password to the server; the server stores only its SHA-256. */
    val authKey: ByteArray,
    /** Encrypts every payload; never leaves the device, lives in the platform keystore/keychain. */
    val encKey: ByteArray,
)

/**
 * Password → keys. PBKDF2-HMAC-SHA256 stretches the password into a master key with the
 * server-provided salt, and two HMAC domains (`crko/auth`, `crko/enc`) split it into independent
 * keys: the authentication oracle never sees the encryption key material. [rounds] defaults to the
 * production cost and is lowered only by tests.
 */
fun deriveCloudKeys(crypto: CloudCrypto, password: String, salt: ByteArray, rounds: Int = CloudPbkdf2Rounds): CloudKeys {
    val master = crypto.pbkdf2Sha256(password.encodeToByteArray(), salt, rounds, CloudKeyBytes)
    return CloudKeys(
        authKey = crypto.hmacSha256(master, "crko/auth".encodeToByteArray()),
        encKey = crypto.hmacSha256(master, "crko/enc".encodeToByteArray()),
    )
}

/** What the client presents to the server for authentication. */
fun CloudKeys.toAuthHash(): String = Base64.encode(authKey)

/** What the client stores in the platform keystore so the next launch can encrypt without a prompt. */
fun CloudKeys.toEncKeySecret(): String = Base64.encode(encKey)

/** Version prefix of the payload ciphertext format; a mismatch answers null instead of garbage. */
private const val SealedVersionPrefix = "v1."

/**
 * Encrypts [plaintext] into `v1.` + base64(nonce ‖ ciphertext ‖ tag). The AAD binds the ciphertext
 * to its entity coordinates, so a payload cannot be replayed under a different `kind`/`key`.
 */
fun CloudCrypto.sealText(key: ByteArray, plaintext: String, aad: String): String {
    val nonce = randomBytes(CloudNonceBytes)
    val sealed = aesGcmSeal(key, nonce, plaintext.encodeToByteArray(), aad.encodeToByteArray())
    val packed = ByteArray(nonce.size + sealed.size)
    nonce.copyInto(packed)
    sealed.copyInto(packed, nonce.size)
    return SealedVersionPrefix + Base64.encode(packed)
}

/** Inverse of [sealText]; null on format mismatch or authentication failure. */
fun CloudCrypto.openText(key: ByteArray, sealedText: String, aad: String): String? {
    if (!sealedText.startsWith(SealedVersionPrefix)) return null
    val packed = runCatching { Base64.decode(sealedText.removePrefix(SealedVersionPrefix)) }.getOrNull() ?: return null
    if (packed.size <= CloudNonceBytes) return null
    val nonce = packed.copyOfRange(0, CloudNonceBytes)
    val sealed = packed.copyOfRange(CloudNonceBytes, packed.size)
    val plaintext = aesGcmOpen(key, nonce, sealed, aad.encodeToByteArray()) ?: return null
    return plaintext.decodeToString()
}
