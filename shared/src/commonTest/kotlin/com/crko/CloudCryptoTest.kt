package com.crko

import com.crko.storage.CloudKeyBytes
import com.crko.storage.CloudKeys
import com.crko.storage.CloudNonceBytes
import com.crko.storage.PureCloudCrypto
import com.crko.storage.cloudCrypto
import com.crko.storage.deriveCloudKeys
import com.crko.storage.hmacSha256Bytes
import com.crko.storage.openText
import com.crko.storage.pbkdf2HmacSha256
import com.crko.storage.sealText
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NIST/RFC vectors plus round trips for the pure-Kotlin cloud crypto. */
class CloudCryptoTest {

    // Nonces must differ per call, so the fake randomness advances a counter across invocations.
    private var randomCounter = 0
    private val crypto = PureCloudCrypto { count ->
        val start = randomCounter
        randomCounter += count
        ByteArray(count) { ((start + it) * 7 + 3).toByte() }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun sha256MatchesFips180Vectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            com.crko.storage.sha256("abc".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            com.crko.storage.sha256(ByteArray(0)).toHex(),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            com.crko.storage.sha256(
                "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray(),
            ).toHex(),
        )
    }

    @Test
    fun hmacSha256MatchesRfc4231() {
        // RFC 4231 test case 1 and 2.
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            hmacSha256Bytes(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()).toHex(),
        )
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            hmacSha256Bytes("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHex(),
        )
    }

    @Test
    fun pbkdf2MatchesThePublishedVectors() {
        val password = "password".encodeToByteArray()
        val salt = "salt".encodeToByteArray()
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            pbkdf2HmacSha256(password, salt, rounds = 1, keyLengthBytes = 32).toHex(),
        )
        assertEquals(
            "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43",
            pbkdf2HmacSha256(password, salt, rounds = 2, keyLengthBytes = 32).toHex(),
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            pbkdf2HmacSha256(password, salt, rounds = 4096, keyLengthBytes = 32).toHex(),
        )
        // A longer password and output crossing the block boundary exercise the multi-block loop
        // (the widely published extended vector).
        assertEquals(
            "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9",
            pbkdf2HmacSha256(
                "passwordPASSWORDpassword".encodeToByteArray(),
                "saltSALTsaltSALTsaltSALTsaltSALTsalt".encodeToByteArray(),
                rounds = 4096,
                keyLengthBytes = 40,
            ).toHex(),
        )
    }

    @Test
    fun aes256BlockMatchesFips197() {
        // FIPS 197 appendix C.3.
        val key = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val plaintext = hex("00112233445566778899aabbccddeeff")
        val expected = "8ea2b7ca516745bfeafc49904b496089"
        val output = ByteArray(16)
        com.crko.storage.AesEncryptor(key).encryptBlock(plaintext, 0, output, 0)
        assertEquals(expected, output.toHex())
    }

    @Test
    fun gcmMatchesTheSpecificationTestVectors() {
        // GCM spec test case 13: AES-256, zero key, zero IV, empty plaintext.
        val key = ByteArray(32)
        val nonce = ByteArray(12)
        assertContentEquals(hex("530f8afbc74536b9a963b4f1c4cb738b"), com.crko.storage.Gcm.seal(key, nonce, ByteArray(0), ByteArray(0)))

        // Test case 14: one zero block.
        val sealed = com.crko.storage.Gcm.seal(key, nonce, ByteArray(0), ByteArray(16))
        assertEquals(
            "cea7403d4d606b6e074ec5d3baf39d18d0d1c8a799996bf0265b98b5d48ab919",
            sealed.toHex(),
        )
        assertContentEquals(ByteArray(16), com.crko.storage.Gcm.open(key, nonce, ByteArray(0), sealed))
    }

    @Test
    fun sealedTextRoundTripsAndRejectsTampering() {
        val key = ByteArray(CloudKeyBytes) { it.toByte() }
        val sealed = crypto.sealText(key, "hello cloud", "crko/v1|event|local/uid1/")
        assertTrue(sealed.startsWith("v1."))
        assertEquals("hello cloud", crypto.openText(key, sealed, "crko/v1|event|local/uid1/"))

        // A different coordinate never opens the payload: the AAD binds it.
        assertNull(crypto.openText(key, sealed, "crko/v1|event|local/uid2/"))

        // A flipped bit in the ciphertext kills the tag.
        val raw = Base64.decode(sealed.removePrefix("v1."))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(crypto.openText(key, "v1." + Base64.encode(raw), "crko/v1|event|local/uid1/"))

        // An unknown format version answers null instead of garbage.
        assertNull(crypto.openText(key, "v9." + sealed.removePrefix("v1."), "crko/v1|event|local/uid1/"))
    }

    @Test
    fun sealingTwiceNeverRepeatsANonce() {
        val key = ByteArray(CloudKeyBytes) { 1 }
        val first = crypto.sealText(key, "same", "aad")
        val second = crypto.sealText(key, "same", "aad")
        assertNotEquals(first, second)
    }

    @Test
    fun derivedKeysSplitAuthenticationFromEncryption() {
        val keys = deriveCloudKeys(crypto, "correct horse battery staple", ByteArray(CloudNonceBytes) { 5 }, rounds = 2_000)
        assertEquals(CloudKeyBytes, keys.authKey.size)
        assertEquals(CloudKeyBytes, keys.encKey.size)
        // Content comparison: assertNotEquals on ByteArrays would compare references on the JVM.
        assertNotEquals(keys.authKey.toList(), keys.encKey.toList())
        // The same password and salt derive the same keys; a different salt does not.
        val again = deriveCloudKeys(crypto, "correct horse battery staple", ByteArray(CloudNonceBytes) { 5 }, rounds = 2_000)
        assertContentEquals(keys.authKey, again.authKey)
        val other = deriveCloudKeys(crypto, "correct horse battery staple", ByteArray(CloudNonceBytes) { 6 }, rounds = 2_000)
        assertNotEquals(keys.authKey.toList(), other.authKey.toList())
    }

    @Test
    fun platformCryptoIsAvailableAndInteroperates() {
        // The platform actual only supplies randomness; it must interoperate with the fixed-random
        // instance because both run the same common algorithms.
        val platform = assertNotNull(cloudCrypto())
        val keys: CloudKeys = deriveCloudKeys(platform, "password", ByteArray(CloudNonceBytes) { 9 }, rounds = 2_000)
        val sealed = platform.sealText(keys.encKey, "interop", "aad")
        assertEquals("interop", crypto.openText(keys.encKey, sealed, "aad"))
    }
}
