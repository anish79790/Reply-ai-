package com.example.ai

import android.util.Log
import com.example.security.ApiKeyRepository

/**
 * xKiro provider (https://api.xkiro.com/v1).
 *
 * Internal provider for Smart AI only - it is never presented as a user-facing engine.
 *
 * Notes that shape this implementation:
 * - `GET /v1/models` is PUBLIC and returns the entire active catalogue, so a model appearing there
 *   does NOT mean the user's key may call it. `access_tier` is therefore only a first-pass filter
 *   (we prefer `free`), and a real 403 remains the final source of truth.
 * - Model ids are vendor-prefixed ("openai/gpt-5.6-sol") and the prefix must never be stripped.
 * - A 403 means "this account is not entitled to this model": we cool the model down and move on
 *   to another candidate rather than retrying it.
 */
class XkiroAiProvider(
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
    id = ProviderId.XKIRO,
    displayName = "xKiro",
    baseUrl = baseUrl
) {

    override suspend fun fetchCatalog(apiKey: String): List<ModelCandidate> {
        val body = getAuthenticated("/models", apiKey)
        val parsed = XkiroModelParser.parse(body)
        Log.d(TAG, "provider=xkiro catalog models=${parsed.size} freeModels=${parsed.count { it.isFreeTier }}")
        return ModelCatalogFilter.distinct(parsed)
    }

    override fun eligible(models: List<ModelCandidate>, profile: TaskProfile): List<ModelCandidate> {
        val minimumContext = profile.estimatedPromptTokens + profile.desiredOutputTokens
        return ModelCatalogFilter.eligible(
            candidates = models,
            // Prefer explicitly free models for the free Smart AI path. Models with no published
            // tier are kept: "unknown tier" is not the same as "paid tier".
            requireFreeTier = profile.requiresFreeTier,
            minContextTokens = minimumContext
        )
    }

    /**
     * xKiro's catalogue is public, so "connected" does not prove this account may actually call a
     * model. Test Connection therefore also fires one minimal completion at the best free
     * candidate, which is what distinguishes an invalid key from a model-entitlement failure.
     */
    override suspend fun testConnection(): ConnectionState {
        val base = super.testConnection()
        if (base !is ConnectionState.Connected) return base

        val modelId = runCatching { bestEffortDefaultModel() }.getOrNull()
            ?: return ConnectionState.NoEligibleModel(
                "$displayName connected, but no eligible model matched this account."
            )

        val result = generate(
            AiTextRequest(
                userPrompt = "Reply with OK only.",
                maxOutputTokens = 8,
                modelId = modelId
            )
        )

        if (result.isSuccess) {
            return ConnectionState.Connected("$displayName verified • $modelId responding")
        }

        val error = result.exceptionOrNull()!!.asAiError(ProviderId.XKIRO)
        return when (error.category) {
            AiErrorCategory.PermissionDenied -> ConnectionState.NoEligibleModel(
                "The $displayName key is valid, but this account cannot call $modelId."
            )
            AiErrorCategory.QuotaExceeded -> ConnectionState.Unavailable("$displayName quota exhausted.")
            AiErrorCategory.RateLimited -> ConnectionState.RateLimited(
                "$displayName is rate limiting requests.",
                error.retryAfterMs
            )
            AiErrorCategory.InvalidApiKey -> ConnectionState.InvalidKey(
                "$displayName rejected the API key. Check it in Settings."
            )
            AiErrorCategory.NetworkUnavailable, AiErrorCategory.Timeout ->
                ConnectionState.NetworkUnavailable("Network unavailable. Check your connection.")
            else -> ConnectionState.Failed(error.toUserMessage())
        }
    }

    /**
     * Optional account usage snapshot for diagnostics only.
     * Deliberately not called on the reply path - actual responses and Retry-After are the truth.
     */
    suspend fun fetchUsage(): String? = runCatching {
        val apiKey = keys.getKey(ProviderId.XKIRO) ?: return null
        getAuthenticated("/usage", apiKey)
    }.onFailure {
        Log.w(TAG, "provider=xkiro usage fetch failed: ${it.javaClass.simpleName}")
    }.getOrNull()

    companion object {
        private const val TAG = "XkiroAiProvider"
        const val BASE_URL = "https://api.xkiro.com/v1"
    }
}
