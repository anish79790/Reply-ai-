package com.example.ai

import kotlin.math.roundToInt

/** A candidate together with its computed score and a per-component breakdown for debugging. */
data class ScoredCandidate(
    val candidate: ModelCandidate,
    val score: Double,
    val breakdown: Map<String, Double>
)

/**
 * Scores dynamically discovered candidates for a specific task.
 *
 * Design rules:
 * - Deterministic: same candidates + same profile + same health ⇒ same ordering.
 * - Name-agnostic: nothing here rewards a model because its name sounds powerful. Only published
 *   metadata (tier, context, max output, capabilities, price) and *observed* health are used.
 * - Cost-aware: for short reply suggestions we prefer the smallest / cheapest model that can
 *   reliably do the job, and only reach for a stronger model when the task profile says so.
 */
object ModelRanker {

    fun rank(
        candidates: List<ModelCandidate>,
        profile: TaskProfile,
        health: ProviderHealthStore,
        clock: () -> Long = { System.currentTimeMillis() }
    ): List<ScoredCandidate> {
        val now = clock()

        return candidates.map { candidate ->
            val providerHealth = health.snapshot(candidate.provider)
            val modelHealth = health.snapshot(candidate.provider, candidate.modelId)
            var breakdown = mutableMapOf<String, Double>()

            // Providers cooling down (invalid key, quota) are excluded outright; the router must
            // not sit retrying a provider that cannot possibly succeed right now.
            val hardBlocked = providerHealth.cooldownUntilMs > now
            if (hardBlocked) {
                breakdown["blockedProvider"] = -1000.0
                return@map ScoredCandidate(candidate, -1000.0, breakdown)
            }
            if (modelHealth.cooldownUntilMs > now) {
                breakdown["blockedModel"] = -1000.0
                return@map ScoredCandidate(candidate, -1000.0, breakdown)
            }

            breakdown["tier"] = tierScore(candidate)
            breakdown["contextFit"] = contextFitScore(candidate, profile)
            breakdown["outputAdequacy"] = outputAdequacyScore(candidate, profile)
            breakdown["cost"] = costScore(candidate)
            breakdown["capability"] = capabilityScore(candidate, profile)
            breakdown["latency"] = latencyScore(modelHealth)
            breakdown["health"] = healthScore(modelHealth, providerHealth)
            breakdown["fitPenalty"] = -mismatchPenalty(candidate, profile)

            val total = breakdown.values.sum()
            ScoredCandidate(candidate, total, breakdown.toMap())
        }
            // Stable, deterministic ordering: score desc, then fewer failures, then id asc.
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenBy { health.snapshot(it.candidate.provider, it.candidate.modelId).consecutiveFailures }
                    .thenBy { it.candidate.modelId }
            )
    }

    /** Free tier is strongly preferred; an unknown tier (Groq) sits in the middle, never penalised. */
    private fun tierScore(c: ModelCandidate): Double = when {
        c.isFreeTier -> 25.0
        c.accessTier == null -> 15.0
        c.accessTier.equals(ModelCandidate.PREMIUM, ignoreCase = true) -> 3.0
        c.accessTier.equals(ModelCandidate.PAID, ignoreCase = true) -> 8.0
        else -> 10.0
    }

    /** Rewards models whose context comfortably holds the prompt; unknown context is neutral. */
    private fun contextFitScore(c: ModelCandidate, profile: TaskProfile): Double {
        val context = c.contextLength ?: return 10.0
        val needed = profile.estimatedPromptTokens + profile.desiredOutputTokens
        val ratio = context.toDouble() / needed.toDouble()
        return when {
            ratio >= 4.0 -> 15.0
            ratio >= 2.0 -> 12.0
            ratio >= 1.2 -> 8.0
            ratio >= 1.0 -> 3.0
            else -> 0.0
        }
    }

    private fun outputAdequacyScore(c: ModelCandidate, profile: TaskProfile): Double {
        val maxOut = c.maxOutputTokens ?: return 6.0
        return when {
            maxOut >= profile.desiredOutputTokens * 2 -> 8.0
            maxOut >= profile.desiredOutputTokens -> 6.0
            maxOut >= (profile.desiredOutputTokens * 0.6).roundToInt() -> 3.0
            else -> 0.0
        }
    }

    /** Cheaper output tokens are a proxy for "smaller model"; we prefer it when quality allows. */
    private fun costScore(c: ModelCandidate): Double {
        val price = c.outputPricePerToken ?: return 3.0
        return when {
            price <= 0.0 -> 6.0
            price < 1e-6 -> 6.0
            price < 5e-6 -> 4.0
            price < 2e-5 -> 2.0
            else -> 0.0
        }
    }

    /**
     * Capabilities are only rewarded when the task actually needs them. A reasoning model earns
     * nothing extra on a trivial "ok" reply, and a non-reasoning model is only lightly penalised
     * on complex tasks (quality matters, but so does not over-selecting).
     */
    private fun capabilityScore(c: ModelCandidate, profile: TaskProfile): Double {
        var score = 4.0 // baseline: it is a chat-capable model (guaranteed by the filter)
        if (profile.wantsReasoning) {
            score += if (c.supportsReasoning) 8.0 else 0.0
        } else if (c.supportsReasoning) {
            score += 2.0
        }
        return score
    }

    /** Observed latency only; an unseen model is neutral rather than punished. */
    private fun latencyScore(modelHealth: HealthSnapshot): Double {
        val latency = modelHealth.avgLatencyMs ?: return 12.0
        return when {
            latency <= 800 -> 20.0
            latency <= 1500 -> 16.0
            latency <= 2500 -> 12.0
            latency <= 4000 -> 8.0
            else -> 4.0
        }
    }

    /** Blends per-model and per-provider observed reliability. */
    private fun healthScore(modelHealth: HealthSnapshot, providerHealth: HealthSnapshot): Double {
        val rate = (modelHealth.successRate * 0.7) + (providerHealth.successRate * 0.3)
        val base = rate * 20.0
        val failurePenalty = (modelHealth.consecutiveFailures * 3.0).coerceAtMost(12.0)
        val rateLimitedPenalty = if (providerHealth.rateLimitedUntilMs > 0) 4.0 else 0.0
        return (base - failurePenalty - rateLimitedPenalty).coerceIn(0.0, 20.0)
    }

    /** Guards against over- and under-powered selection. */
    private fun mismatchPenalty(c: ModelCandidate, profile: TaskProfile): Double {
        var penalty = 0.0
        val isPremium = c.accessTier?.equals(ModelCandidate.PREMIUM, ignoreCase = true) == true
        if (profile.complexity < 0.35f && isPremium) penalty += 8.0
        if (profile.complexity > 0.65f) {
            if ((c.contextLength ?: Int.MAX_VALUE) < 8000) penalty += 10.0
            if (!c.supportsReasoning) penalty += 5.0
        }
        return penalty
    }
}
