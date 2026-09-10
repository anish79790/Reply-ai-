package com.example.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed encrypted storage for API keys.
 *
 * Values are encrypted with AES-256/GCM using a key that lives in the Android Keystore and is
 * therefore never extractable from the process. Only the ciphertext (Base64) ever reaches
 * SharedPreferences, so API keys are never persisted in plaintext.
 *
 * Encryption is per-value: a fresh 12 byte IV is generated for every write and stored alongside
 * the ciphertext.
 */
class SecureSecretStore(context: Context) : SecretStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val cachedKey: SecretKey by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { getOrCreateSecretKey() }

    fun put(alias: String, value: String) {
        if (value.isEmpty()) {
            remove(alias)
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { SECURE_RANDOM.nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, cachedKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertext.size).apply {
            System.arraycopy(iv, 0, this, 0, iv.size)
            System.arraycopy(ciphertext, 0, this, iv.size, ciphertext.size)
        }
        prefs.edit().putString(alias, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun get(alias: String): String? {
        val encoded = prefs.getString(alias, null) ?: return null
        return runCatching {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            if (payload.size <= GCM_IV_LENGTH_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, cachedKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    fun remove(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    fun contains(alias: String): Boolean = prefs.contains(alias)

    private fun getOrCreateSecretKey(): SecretKey {
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS_NAME = "replyai_secure_secrets"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "replyai_secret_store_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_SIZE_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private val SECURE_RANDOM = SecureRandom()
    }
}
