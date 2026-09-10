package com.example.ai

import android.util.Log
import com.example.security.ApiKeyRepository

/**
 * Groq provider (https://api.groq.com/openai/v1).
 *
 * Internal provider for Smart AI only - it is never presented as a user-facing engine.
 *
 * Groq does not publish an `access_tier`, so we never assume a listed model is free for the
 * current account: organisation/project permissions and rate limits are only discoverable by
 * making the request, and 403/404/429 responses are treated as authoritative.
 */
class GroqAiProvider(
    keys: ApiKeyRepository,
    health: ProviderHealthStore,
    catalog: ModelCatalogCache,
    httpClient: okhttp3.OkHttpClient = OpenAiCompatibleProvider.defaultHttpClient(),
    baseUrl: String = BASE_URL
) : OpenAiCompatibleProvider(
    keys = keys,
    health = health,
    catalog = catalog,
    httpClient = httpClient,
    id = ProviderId.GROQ,
    displayName = "Groq",
    baseUrl = baseUrl
) {

    /**
     * Small seed list used ONLY when live discovery fails (cold start, transient outage).
     *
     * These are hints, never an authoritative catalogue and never a dependency: if a seed model
     * disappears or is rejected by the account it is skipped, and the live catalogue always wins
     * as soon as it can be fetched.
     */
    override val seedModelIds: List<String> = listOf(
        "openai/gpt-oss-120b",
        "openai/gpt-oss-20b",
        "qwen/qwen3.6-27b",
        "qwen/qwen3.8-27b",
        "groq/compound",
        "groq/compound-mini"
    )

    override suspend fun fetchCatalog(apiKey: String): List<ModelCandidate> {
        val body = getAuthenticated("/models", apiKey)
        val parsed = GroqModelParser.parse(body)
        Log.d(TAG, "provider=groq catalog models=${parsed.size}")
        return ModelCatalogFilter.distinct(parsed)
    }

    override fun eligible(models: List<ModelCandidate>, profile: TaskProfile): List<ModelCandidate> {
        val minimumContext = profile.estimatedPromptTokens + profile.desiredOutputTokens
        return ModelCatalogFilter.eligible(
            candidates = models,
            // Groq publishes no tier metadata, so this filter cannot and must not imply "free".
            requireFreeTier = false,
            minContextTokens = minimumContext
        )
    }

    companion object {
        private const val TAG = "GroqAiProvider"
        const val BASE_URL = "https://api.groq.com/openai/v1"
    }
}
