package com.crko.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.crko.AndroidAppContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android Keystore AES-GCM cipher around a private `SharedPreferences` blob: the key material never
 * leaves the keystore, and disk only ever sees base64 of IV + ciphertext.
 */
actual fun defaultSecretStore(): SecretStore? {
    val context = AndroidAppContext.getOrNull() ?: return null
    return AndroidSecretStore(context)
}

private class AndroidSecretStore(context: Context) : SecretStore {
    private val prefs = context.getSharedPreferences("crko.secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(Provider).apply { load(null) }
        (keyStore.getKey(Alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, Provider)
        generator.init(
            KeyGenParameterSpec.Builder(
                Alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override suspend fun save(key: String, value: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(Transformation)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        prefs.edit().putString(key, Base64.getEncoder().encodeToString(bytes)).apply()
    }

    override suspend fun load(key: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key, null) ?: return@withContext null
        runCatching {
            val bytes = Base64.getDecoder().decode(stored)
            val cipher = Cipher.getInstance(Transformation)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GcmTagBits, bytes, 0, GcmIvBytes))
            cipher.doFinal(bytes, GcmIvBytes, bytes.size - GcmIvBytes).decodeToString()
        }.getOrNull()
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val Provider = "AndroidKeyStore"
        const val Alias = "crko-secret"
        const val Transformation = "AES/GCM/NoPadding"
        const val GcmIvBytes = 12
        const val GcmTagBits = 128
    }
}
