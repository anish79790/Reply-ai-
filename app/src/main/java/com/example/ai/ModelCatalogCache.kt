package com.example.ai

/**
 * Short-lived, in-memory cache for provider model catalogues.
 *
 * Discovery requests are comparatively expensive and the catalogue changes slowly, so we refresh
 * on a TTL, after a "model not found" style failure, and on explicit request (Test Connection).
 * Nothing is persisted to disk and nothing sensitive is retained — only public model metadata.
 */
class ModelCatalogCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private class Entry(val models: List<ModelCandidate>, val fetchedAtMs: Long)

    private val lock = Any()
    private val entries = HashMap<ProviderId, Entry>()

    fun get(provider: ProviderId): List<ModelCandidate>? = synchronized(lock) { entries[provider]?.models }

    fun fetchedAt(provider: ProviderId): Long? = synchronized(lock) { entries[provider]?.fetchedAtMs }

    fun isStale(provider: ProviderId): Boolean = synchronized(lock) {
        val entry = entries[provider] ?: return true
        clock() - entry.fetchedAtMs >= ttlMs
    }

    fun put(provider: ProviderId, models: List<ModelCandidate>) {
        synchronized(lock) { entries[provider] = Entry(models, clock()) }
    }

    fun invalidate(provider: ProviderId) {
        synchronized(lock) { entries.remove(provider) }
    }

    fun invalidateAll() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        const val DEFAULT_TTL_MS = 15 * 60 * 1000L
    }
}
