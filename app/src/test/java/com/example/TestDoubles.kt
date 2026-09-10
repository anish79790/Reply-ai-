package com.example

import com.example.security.SecretStore

/**
 * In-memory [SecretStore] used ONLY by unit tests.
 *
 * It is a test double for the storage layer, so production code keeps using the real
 * Android Keystore-backed [com.example.security.SecureSecretStore]. No fake provider is
 * ever introduced into production code.
 */
class InMemorySecretStore : SecretStore {
    private val values = HashMap<String, String>()

    override fun put(alias: String, value: String) {
        if (value.isEmpty()) values.remove(alias) else values[alias] = value
    }

    override fun get(alias: String): String? = values[alias]

    override fun remove(alias: String) {
        values.remove(alias)
    }

    override fun contains(alias: String): Boolean = values.containsKey(alias)
}
