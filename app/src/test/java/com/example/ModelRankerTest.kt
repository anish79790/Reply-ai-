package com.example

import com.example.ai.AiError
import com.example.ai.AiErrorCategory
import com.example.ai.ModelCandidate
import com.example.ai.ModelRanker
import com.example.ai.ProviderHealthStore
import com.example.ai.ProviderId
import com.example.ai.TaskProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider/model ranking (requirement 7). */
class ModelRankerTest {

    private val health = ProviderHealthStore(clock = { 1_000L })

    private fun candidate(
        id: String,
        provider: ProviderId = ProviderId.XKIRO,
        tier: String? = null,
        context: Int? = 128_000,
        maxOut: Int? = 4096,
        reasoning: Boolean = false,
        price: Double? = null
    ) = ModelCandidate(
        provider = provider,
        modelId = id,
        accessTier = tier,
        contextLength = context,
        maxOutputTokens = maxOut,
        capabilities = buildSet {
            add("chat")
            if (reasoning) add("reasoning")
        },
        outputPricePerToken = price
    )

    private fun rank(candidates: List<ModelCandidate>, profile: TaskProfile) =
        ModelRanker.rank(candidates, profile, health, clock = { 1_000L })

    @Test
    fun freeTierOutranksPaidAndPremium() {
        val ranked = rank(
            listOf(
                candidate("v/premium", tier = "premium"),
                candidate("v/paid", tier = "paid"),
                candidate("v/free", tier = "free")
            ),
            TaskProfile.default()
        )
        assertEquals("v/free", ranked.first().candidate.modelId)
        assertEquals("v/premium", ranked.last().candidate.modelId)
    }

    @Test
    fun cheapestAdequateModelWins_forShortSimpleReplies() {
        // A small/cheap model and a large/expensive one are both feasible; the router must prefer
        // the smaller one rather than the one whose name sounds most powerful.
        val ranked = rank(
            listOf(
                candidate("vendor/huge-405b", tier = "free", price = 2e-5, reasoning = true),
                candidate("vendor/small-8b", tier = "free", price = 0.0)
            ),
            TaskProfile(complexity = 0.1f, 800, 320, wantsReasoning = false, requiresFreeTier = true)
        )
        assertEquals("vendor/small-8b", ranked.first().candidate.modelId)
    }

    @Test
    fun reasoningModelPreferred_forComplexTasks() {
        val ranked = rank(
            listOf(
                candidate("vendor/plain", tier = "free"),
                candidate("vendor/reasoner", tier = "free", reasoning = true, context = 200_000)
            ),
            TaskProfile(complexity = 0.9f, 4000, 320, wantsReasoning = true, requiresFreeTier = true)
        )
        assertEquals("vendor/reasoner", ranked.first().candidate.modelId)
    }

    @Test
    fun insufficientContextWindowIsPenalised() {
        val ranked = rank(
            listOf(
                candidate("vendor/tiny", tier = "free", context = 600),
                candidate("vendor/roomy", tier = "free", context = 200_000)
            ),
            TaskProfile(complexity = 0.9f, 6000, 320, wantsReasoning = true, requiresFreeTier = true)
        )
        assertEquals("vendor/roomy", ranked.first().candidate.modelId)
        assertTrue(ranked.first().score > ranked.last().score)
    }

    @Test
    fun observedLatencyImprovesScore() {
        val fast = candidate("vendor/fast", tier = "free")
        val slow = candidate("vendor/slow", tier = "free")

        repeat(3) { health.recordSuccess(ProviderId.XKIRO, "vendor/fast", 400L) }
        repeat(3) { health.recordSuccess(ProviderId.XKIRO, "vendor/slow", 6000L) }

        val ranked = rank(listOf(slow, fast), TaskProfile.default())
        assertEquals("vendor/fast", ranked.first().candidate.modelId)
    }

    @Test
    fun recentFailuresLowerScore() {
        val flaky = candidate("vendor/flaky", tier = "free")
        val solid = candidate("vendor/solid", tier = "free")

        repeat(3) {
            health.recordFailure(
                ProviderId.XKIRO, "vendor/flaky",
                AiError(AiErrorCategory.ServerError, "boom", httpStatus = 500, provider = ProviderId.XKIRO)
            )
        }
        // A success clears the cooldown but the failure history still counts against the model.
        health.recordSuccess(ProviderId.XKIRO, "vendor/flaky", 500L)

        val ranked = rank(listOf(flaky, solid), TaskProfile.default())
        assertEquals("vendor/solid", ranked.first().candidate.modelId)
    }

    @Test
    fun coolingDownProviderIsExcluded() {
        health.recordFailure(
            ProviderId.GROQ, null,
            AiError(AiErrorCategory.InvalidApiKey, "bad key", httpStatus = 401, provider = ProviderId.GROQ)
        )

        val ranked = rank(
            listOf(
                candidate("groq/any", provider = ProviderId.GROQ, tier = "free"),
                candidate("xkiro/any", provider = ProviderId.XKIRO, tier = "free")
            ),
            TaskProfile.default()
        )

        assertEquals("xkiro/any", ranked.first().candidate.modelId)
        assertTrue(ranked.last().breakdown.containsKey("blockedProvider"))
    }

    @Test
    fun coolingDownSingleModelIsExcludedButProviderSurvives() {
        health.recordFailure(
            ProviderId.XKIRO, "vendor/entitled",
            AiError(AiErrorCategory.PermissionDenied, "403", httpStatus = 403, provider = ProviderId.XKIRO)
        )

        val ranked = rank(
            listOf(
                candidate("vendor/entitled", tier = "free"),
                candidate("vendor/other", tier = "free")
            ),
            TaskProfile.default()
        )

        assertEquals("vendor/other", ranked.first().candidate.modelId)
        assertTrue(ranked.last().breakdown.containsKey("blockedModel"))
    }

    @Test
    fun rankingIsDeterministic() {
        val candidates = listOf(
            candidate("a/one", tier = "free", price = 0.0),
            candidate("b/two", tier = "free", price = 1e-7),
            candidate("c/three", tier = "free", price = 5e-7)
        )
        val first = rank(candidates, TaskProfile.default()).map { it.candidate.modelId }
        val second = rank(candidates, TaskProfile.default()).map { it.candidate.modelId }
        assertEquals(first, second)
    }

    @Test
    fun scoreBreakdownIsDebuggable() {
        val ranked = rank(listOf(candidate("vendor/x", tier = "free")), TaskProfile.default())
        val breakdown = ranked.first().breakdown
        assertTrue(breakdown.containsKey("tier"))
        assertTrue(breakdown.containsKey("contextFit"))
        assertTrue(breakdown.containsKey("latency"))
        assertTrue(breakdown.containsKey("health"))
    }
}
