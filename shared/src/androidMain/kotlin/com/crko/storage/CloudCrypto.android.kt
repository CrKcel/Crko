package com.crko.storage

import java.security.SecureRandom

/**
 * Android randomness for the pure-Kotlin cloud crypto; the algorithms themselves are common code,
 * so a sealed payload from Android opens on iOS and vice versa without provider quirks.
 */
actual fun cloudCrypto(): CloudCrypto? = PureCloudCrypto { count ->
    ByteArray(count).also(SecureRandom()::nextBytes)
}
