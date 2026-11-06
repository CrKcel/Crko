@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.crko.storage

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * iOS randomness (SecRandomCopyBytes) for the pure-Kotlin cloud crypto; the algorithms themselves
 * are common code, so a sealed payload from iOS opens on Android and vice versa.
 */
actual fun cloudCrypto(): CloudCrypto? = PureCloudCrypto(::secureRandomBytes)

private fun secureRandomBytes(count: Int): ByteArray {
    val bytes = ByteArray(count)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
        check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    }
    return bytes
}
