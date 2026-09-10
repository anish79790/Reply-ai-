package com.example.ai

import kotlin.math.pow
import kotlin.random.Random

/** Immutable, log-safe view of a provider or provider+model health record. */
data class HealthSnapshot(
    val key: String,
    val lastSuccessAtMs: Long?,
    val lastFailureAtMs: Long?,
    val consecutiveFailures: Int,
    val cooldownUntilMs: Long,
    val rateLimitedUntilMs: Long,
    val avgLatencyMs: Long?,
    val lastErrorCategory: AiErrorCategory?,
    val totalSuccesses: Int,
    val totalFailures: Int
) {
    val successRate: Double
        get() {
            val total = totalSuccesses + totalFailures
            return if (total == 0) 1.0 else totalSuccesses.toDouble() / total.toDouble()
        }
}

/**
 * Lightweight, in-memory provider/model health.
 *
 * Nothing here is persisted and no API key is ever stored in this system — only timing,
 * counters and error *categories*. This makes it safe to surface in diagnostics.
 *
 * Thread-safe: the accessibility service, the overlay and the UI may touch it concurrently.
 */
class ProviderHealthStore(private val clock: () -> Long = { System.currentTimeMillis() }) {

    private class Record {
        var lastSuccessAtMs: Long? = null
        var lastFailureAtMs: Long? = null
        var consecutiveFailures: Int = 0
        var cooldownUntilMs: Long = 0L
        var rateLimitedUntilMs: Long = 0L
        var avgLatencyMs: Long? = null
        var lastErrorCategory: AiErrorCategory? = null
        var totalSuccesses: Int = 0
        var totalFailures: Int = 0
    }

    private val lock = Any()
    private val records = HashMap<String, Record>()

    fun recordSuccess(provider: ProviderId, modelKey: String?, latencyMs: Long) {
        synchronized(lock) {
            listOfNotNull(providerKey(provider), modelKey).forEach { key ->
                val record = records.getOrPut(key) { Record() }
                record.lastSuccessAtMs = clock()
                record.consecutiveFailures = 0
                record.totalSuccesses++
                record.lastErrorCategory = null
                // Exponential moving average keeps recent latency dominant without unbounded memory.
                record.avgLatencyMs = record.avgLatencyMs?.let { prev -> (prev * 0.7 + latencyMs * 0.3).toLong() }
                    ?: latencyMs
                // A success clears any accumulated cooldown for this entity.
                record.cooldownUntilMs = 0L
                record.rateLimitedUntilMs = 0L
            }
        }
    }

    fun recordFailure(provider: ProviderId, modelKey: String?, error: AiError) {
        synchronized(lock) {
            val now = clock()
            val providerOnly = providerKey(provider)
            val keys = listOfNotNull(providerOnly, modelKey)

            for (key in keys) {
                val record = records.getOrPut(key) { Record() }
                record.lastFailureAtMs = now
                record.consecutiveFailures++
                record.totalFailures++
                record.lastErrorCategory = error.category

                val cooldown = cooldownFor(error, record.consecutiveFailures, now)
                record.cooldownUntilMs = maxOf(record.cooldownUntilMs, cooldown)

                if (error.category == AiErrorCategory.RateLimited) {
                    record.rateLimitedUntilMs = maxOf(
                        record.rateLimitedUntilMs,
                        error.retryAfterMs?.let { now + it } ?: cooldown
                    )
                }
            }

            // Key-level and quota problems apply to the whole provider, not just one model:
            // retrying another model with the same invalid key would be pointless.
            if (error.category == AiErrorCategory.InvalidApiKey ||
                error.category == AiErrorCategory.QuotaExceeded ||
                error.category == AiErrorCategory.MissingApiKey
            ) {
                val providerRecord = records.getOrPut(providerOnly) { Record() }
                providerRecord.cooldownUntilMs = maxOf(
                    providerRecord.cooldownUntilMs,
                    now + PROVIDER_LEVEL_COOLDOWN_MS
                )
            }
        }
    }

    /** Marks a model as permanently-ish unusable for this session (404 / model retired). */
    fun retireModel(provider: ProviderId, modelId: String) {
        synchronized(lock) {
            val key = modelKey(provider, modelId)
            val record = records.getOrPut(key) { Record() }
            record.cooldownUntilMs = clock() + RETIRED_MODEL_COOLDOWN_MS
            record.lastErrorCategory = AiErrorCategory.ModelUnavailable
        }
    }

    fun snapshot(provider: ProviderId, modelId: String? = null): HealthSnapshot {
        synchronized(lock) {
            val key = modelId?.let { modelKey(provider, it) } ?: providerKey(provider)
            return records[key]?.toSnapshot(key) ?: HealthSnapshot(
                key = key,
                lastSuccessAtMs = null,
                lastFailureAtMs = null,
                consecutiveFailures = 0,
                cooldownUntilMs = 0L,
                rateLimitedUntilMs = 0L,
                avgLatencyMs = null,
                lastErrorCategory = null,
                totalSuccesses = 0,
                totalFailures = 0
            )
        }
    }

    fun isCoolingDown(provider: ProviderId, modelId: String? = null): Boolean {
        val now = clock()
        return if (modelId == null) {
            snapshot(provider).cooldownUntilMs > now
        } else {
            snapshot(provider, modelId).cooldownUntilMs > now || snapshot(provider).cooldownUntilMs > now
        }
    }

    fun cooldownRemainingMs(provider: ProviderId, modelId: String? = null): Long {
        val now = clock()
        return maxOf(0L, snapshot(provider, modelId).cooldownUntilMs - now, snapshot(provider).cooldownUntilMs - now)
    }

    fun reset(provider: ProviderId? = null) {
        synchronized(lock) {
            if (provider == null) {
                records.clear()
            } else {
                val iterator = records.entries.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next().key
                    if (key == provider.storageKey || key.startsWith("${provider.storageKey}:")) {
                        iterator.remove()
                    }
                }
            }
        }
    }

    /**
     * Per-category cooldown policy.
     *
     * - 401/402/408/422: provider-level problem or a request we must not repeat → long cooldown.
     * - 403: entitlement problem for THIS model → long model-level cooldown, other models stay usable.
     * - 404: model unknown → retired for the session.
     * - 429: honour Retry-After exactly; otherwise bounded exponential backoff.
     * - 5xx/timeouts: bounded exponential backoff with jitter.
     */
    internal fun cooldownFor(error: AiError, consecutiveFailures: Int, now: Long): Long = when (error.category) {
        AiErrorCategory.InvalidApiKey,
        AiErrorCategory.MissingApiKey,
        AiErrorCategory.QuotaExceeded,
        AiErrorCategory.InvalidRequest -> now + PROVIDER_LEVEL_COOLDOWN_MS

        AiErrorCategory.PermissionDenied -> now + ENTITLEMENT_COOLDOWN_MS

        AiErrorCategory.ModelUnavailable -> now + RETIRED_MODEL_COOLDOWN_MS

        AiErrorCategory.RateLimited -> {
            val retryAfter = error.retryAfterMs
            if (retryAfter != null && retryAfter > 0) now + retryAfter.coerceAtMost(MAX_RETRY_AFTER_MS)
            else now + backoffMs(consecutiveFailures)
        }

        AiErrorCategory.ServerError,
        AiErrorCategory.Timeout,
        AiErrorCategory.NetworkUnavailable -> now + backoffMs(consecutiveFailures)

        AiErrorCategory.NoEligibleModel,
        AiErrorCategory.Cancelled,
        AiErrorCategory.UnknownError -> now + backoffMs(consecutiveFailures)
    }

    /** Exponential backoff with jitter, capped so a bad provider can never stall the UI forever. */
    fun backoffMs(consecutiveFailures: Int): Long {
        val attempt = consecutiveFailures.coerceAtLeast(1).coerceAtMost(MAX_BACKOFF_EXPONENT)
        val base = (BACKOFF_BASE_MS * 2.0.pow(attempt - 1)).toLong().coerceAtMost(MAX_BACKOFF_MS)
        val jitter = (base * JITTER_FRACTION * Random.nextDouble()).toLong()
        return base + jitter
    }

    private fun Record.toSnapshot(key: String) = HealthSnapshot(
        key = key,
        lastSuccessAtMs = lastSuccessAtMs,
        lastFailureAtMs = lastFailureAtMs,
        consecutiveFailures = consecutiveFailures,
        cooldownUntilMs = cooldownUntilMs,
        rateLimitedUntilMs = rateLimitedUntilMs,
        avgLatencyMs = avgLatencyMs,
        lastErrorCategory = lastErrorCategory,
        totalSuccesses = totalSuccesses,
        totalFailures = totalFailures
    )

    companion object {
        fun providerKey(provider: ProviderId): String = provider.storageKey
        fun modelKey(provider: ProviderId, modelId: String): String = "${provider.storageKey}:$modelId"

        const val PROVIDER_LEVEL_COOLDOWN_MS = 10 * 60 * 1000L   // invalid key / quota / bad request
        const val ENTITLEMENT_COOLDOWN_MS = 30 * 60 * 1000L      // 403 on a specific model
        const val RETIRED_MODEL_COOLDOWN_MS = 6 * 60 * 60 * 1000L // 404 model unknown
        const val MAX_RETRY_AFTER_MS = 5 * 60 * 1000L
        const val BACKOFF_BASE_MS = 500L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_EXPONENT = 7
        const val JITTER_FRACTION = 0.20
    }
}
