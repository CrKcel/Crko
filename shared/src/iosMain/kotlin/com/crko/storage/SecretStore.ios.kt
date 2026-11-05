@file:OptIn(ExperimentalForeignApi::class)

package com.crko.storage

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS Keychain (generic password) store. The system encrypts the item at rest; the accessibility
 * level matches the database's first-unlock file protection.
 */
actual fun defaultSecretStore(): SecretStore? = KeychainSecretStore

private object KeychainSecretStore : SecretStore {
    override suspend fun save(key: String, value: String) {
        withContext(Dispatchers.Default) {
            delete(key)
            val bytes = value.encodeToByteArray()
            val query = dictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrAccount to cfString(key),
                kSecValueData to memScoped {
                    CFDataCreate(null, bytes.asUByteArray().refTo(0), bytes.size.convert())
                },
                kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlock,
            )
            SecItemAdd(query, null)
        }
    }

    override suspend fun load(key: String): String? = withContext(Dispatchers.Default) {
        memScoped {
            val query = dictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrAccount to cfString(key),
                kSecReturnData to kCFBooleanTrue,
            )
            val result = alloc<CFTypeRefVar>()
            if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
            @Suppress("UNCHECKED_CAST")
            val data = result.value as CFDataRef? ?: return@memScoped null
            val length = CFDataGetLength(data).toInt()
            if (length <= 0) return@memScoped null
            val bytes = CFDataGetBytePtr(data) ?: return@memScoped null
            bytes.readBytes(length).decodeToString()
        }
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.Default) {
            SecItemDelete(dictionary(kSecClass to kSecClassGenericPassword, kSecAttrAccount to cfString(key)))
        }
    }

    /** An owned CFString for a Kotlin [text]; the CF allocator keeps it alive for the call. */
    private fun cfString(text: String): CFStringRef? =
        CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)

    /**
     * An immutable CFDictionary of the given pairs. CF collections retain their entries, so the
     * created string and data handles stay valid for the Security call that consumes the dict.
     */
    private fun dictionary(vararg pairs: Pair<CFTypeRef?, CFTypeRef?>): CFDictionaryRef? {
        val target = CFDictionaryCreateMutable(null, pairs.size.convert(), null, null) ?: return null
        pairs.forEach { (key, value) -> CFDictionarySetValue(target, key, value) }
        return target
    }
}
