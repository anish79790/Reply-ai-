package com.example.security

import com.example.ai.ProviderId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single entry point for reading and writing provider API keys.
 *
 * Keys are only ever held encrypted (Android Keystore-backed [SecureSecretStore]) and are never
 * copied into [com.example.settings.AppSettings], so they cannot leak through UI state dumps,
 * diagnostics, screenshots or Crashlytics-style state capture.
 *
 * Only *presence* is observable outside this class.
 */
class ApiKeyRepository(private val store: SecretStore) {

    private val _configured = MutableStateFlow(readConfigured())

    /** Providers that currently have a non-empty key. Never contains the keys themselves. */
    val configured: StateFlow<Set<ProviderId>> = _configured.asStateFlow()

    /** Returns the decrypted key, or null. Callers must not log or persist the result. */
    fun getKey(provider: ProviderId): String? = store.get(provider.storageKey)?.takeIf { it.isNotBlank() }

    fun setKey(provider: ProviderId, value: String) {
        val trimmed = value.trim()
        val changed = if (trimmed.isEmpty()) {
            val existed = store.contains(provider.storageKey)
            store.remove(provider.storageKey)
            existed
        } else {
            store.put(provider.storageKey, trimmed)
            true
        }
        if (changed) _configured.value = readConfigured()
    }

    fun clearKey(provider: ProviderId) {
        if (store.contains(provider.storageKey)) {
            store.remove(provider.storageKey)
            _configured.value = readConfigured()
        }
    }

    fun isConfigured(provider: ProviderId): Boolean = getKey(provider) != null

    fun clearAll() {
        ProviderId.entries.forEach { store.remove(it.storageKey) }
        _configured.value = readConfigured()
    }

    private fun readConfigured(): Set<ProviderId> =
        ProviderId.entries.filter { store.get(it.storageKey).isNullOrBlank().not() }.toSet()

    companion object {
        @Volatile
        private var INSTANCE: ApiKeyRepository? = null

        fun getInstance(context: android.content.Context): ApiKeyRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ApiKeyRepository(SecureSecretStore(context)).also { INSTANCE = it }
            }
    }
}
