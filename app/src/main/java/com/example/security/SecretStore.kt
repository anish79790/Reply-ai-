package com.example.security

/** Minimal contract for encrypted secret storage, so it can be faked in unit tests. */
interface SecretStore {
    fun put(alias: String, value: String)
    fun get(alias: String): String?
    fun remove(alias: String)
    fun contains(alias: String): Boolean
}
