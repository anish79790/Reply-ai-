package com.example

import com.example.ai.AiError
import com.example.ai.AiErrorCategory
import com.example.ai.ProviderHealthStore
import com.example.ai.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider/model health, cooldowns and backoff (requirements 8, 9, 10, 19). */
class ProviderHealthTest {

    private var now = 10_000L
    private val health = ProviderHealthStore(clock = { now })

    private fun fail(category: AiErrorCategory, retryAfterMs: Long? = null, http: Int? = null) =
        AiError(category, "safe", httpStatus = http, retryAfterMs = retryAfterMs, provider = ProviderId.XKIRO)

    @Test
    fun http401_marksWholeProviderUnavailable() {
        health.recordFailure(ProviderId.XKIRO, "vendor/a", fail(AiErrorCategory.InvalidApiKey, http = 401))

        assertTrue(health.isCoolingDown(ProviderId.XKIRO))
        // Another model on the same provider must not be retried while the key is invalid.
        assertTrue(health.isCoolingDown(ProviderId.XKIRO, "vendor/b"))
        assertEquals(AiErrorCategory.InvalidApiKey, health.snapshot(ProviderId.XKIRO).lastErrorCategory)
    }

    @Test
    fun http402_quotaCooldownAppliesToProvider() {
        health.recordFailure(ProviderId.XKIRO, "vendor/paid", fail(AiErrorCategory.QuotaExceeded, http = 402))
        assertTrue(health.isCoolingDown(ProviderId.XKIRO))
        assertEquals(AiErrorCategory.QuotaExceeded, health.snapshot(ProviderId.XKIRO).lastErrorCategory)
    }

    @Test
    fun http403_coolsDownOnlyThatModel() {
        health.recordFailure(ProviderId.XKIRO, "vendor/locked", fail(AiErrorCategory.PermissionDenied, http = 403))

        assertTrue(health.isCoolingDown(ProviderId.XKIRO, "vendor/locked"))
        assertFalse("other models stay usable after a 403", health.isCoolingDown(ProviderId.XKIRO, "vendor/other"))
        assertFalse("the provider itself is still healthy", health.isCoolingDown(ProviderId.XKIRO))
    }

    @Test
    fun http404_retiresTheModel() {
        health.recordFailure(ProviderId.XKIRO, "vendor/gone", fail(AiErrorCategory.ModelUnavailable, http = 404))
        assertTrue(health.isCoolingDown(ProviderId.XKIRO, "vendor/gone"))
        assertTrue(health.cooldownRemainingMs(ProviderId.XKIRO, "vendor/gone") >= ProviderHealthStore.RETIRED_MODEL_COOLDOWN_MS)

        health.retireModel(ProviderId.XKIRO, "vendor/also-gone")
        assertTrue(health.isCoolingDown(ProviderId.XKIRO, "vendor/also-gone"))
    }

    @Test
    fun http429_honoursRetryAfterExactly() {
        health.recordFailure(ProviderId.XKIRO, "vendor/x", fail(AiErrorCategory.RateLimited, retryAfterMs = 30_000, http = 429))

        val snapshot = health.snapshot(ProviderId.XKIRO, "vendor/x")
        assertEquals(now + 30_000L, snapshot.rateLimitedUntilMs)
        assertEquals(30_000L, health.cooldownRemainingMs(ProviderId.XKIRO, "vendor/x"))

        // Before the delay elapses the model must stay cooled down...
        assertTrue(health.isCoolingDown(ProviderId.XKIRO, "vendor/x"))
        // ...and exactly when the delay expires it becomes eligible again.
        now += 30_000L
        assertFalse(health.isCoolingDown(ProviderId.XKIRO, "vendor/x"))
    }

    @Test
    fun http429_withoutRetryAfter_usesBoundedBackoff() {
        repeat(12) {
            health.recordFailure(ProviderId.XKIRO, "vendor/x", fail(AiErrorCategory.RateLimited, retryAfterMs = null, http = 429))
        }
        val remaining = health.cooldownRemainingMs(ProviderId.XKIRO, "vendor/x")
        assertTrue(
            "backoff must stay bounded (was ${remaining}ms)",
            remaining <= ProviderHealthStore.MAX_BACKOFF_MS * 2
        )
    }

    @Test
    fun backoffIsExponentialButCapped() {
        val first = health.backoffMs(1)
        val third = health.backoffMs(3)
        val huge = health.backoffMs(50)

        assertTrue(third > first)
        assertTrue("cap must hold, got $huge", huge <= ProviderHealthStore.MAX_BACKOFF_MS * 2)
    }

    @Test
    fun successClearsCooldownAndRestoresScore() {
        health.recordFailure(ProviderId.XKIRO, "vendor/x", fail(AiErrorCategory.ServerError, http = 500))
        assertTrue(health.snapshot(ProviderId.XKIRO, "vendor/x").consecutiveFailures > 0)

        health.recordSuccess(ProviderId.XKIRO, "vendor/x", 700L)

        val snapshot = health.snapshot(ProviderId.XKIRO, "vendor/x")
        assertEquals(0, snapshot.consecutiveFailures)
        assertEquals(700L, snapshot.avgLatencyMs)
        assertFalse(health.isCoolingDown(ProviderId.XKIRO, "vendor/x"))
    }

    @Test
    fun timeoutsAndNetworkFailuresUseShortBackoff() {
        health.recordFailure(ProviderId.XKIRO, "vendor/x", fail(AiErrorCategory.Timeout, http = 408))
        val remaining = health.cooldownRemainingMs(ProviderId.XKIRO, "vendor/x")
        assertTrue(remaining > 0)
        assertTrue(remaining < ProviderHealthStore.PROVIDER_LEVEL_COOLDOWN_MS)
    }

    @Test
    fun healthNeverStoresModelIdsWithKeysAndIsResettable() {
        health.recordSuccess(ProviderId.GROQ, "groq/a", 100L)
        health.reset(ProviderId.GROQ)
        assertEquals(0, health.snapshot(ProviderId.GROQ, "groq/a").totalSuccesses)
    }

    @Test
    fun cooldownRemainingNeverNegative() {
        health.recordFailure(ProviderId.XKIRO, "vendor/x", fail(AiErrorCategory.ServerError, http = 500))
        now += 60 * 60 * 1000L
        assertEquals(0L, health.cooldownRemainingMs(ProviderId.XKIRO, "vendor/x"))
    }
}
