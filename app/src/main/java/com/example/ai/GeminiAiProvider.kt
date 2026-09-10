package com.example.ai

import android.util.Log
import com.example.gemini.GeminiClient
import com.example.security.ApiKeyRepository

/**
 * Gemini provider used exclusively by the "Gemini" engine.
 *
 * Gemini is independent: it is never routed through Smart AI and never falls back to Groq, xKiro
 * or the local model. If the configured key fails, the user sees the Gemini error, not a silent
 * switch to another provider.
 */
class GeminiAiProvider(
    private val keys: ApiKeyRepository,
    private val health: ProviderHealthStore,
    private val client: GeminiClient = GeminiClient()
) : AiProvider {

    override val id: ProviderId = ProviderId.GEMINI
    override val displayName: String = "Gemini"

    override suspend fun isConfigured(): Boolean = keys.getKey(ProviderId.GEMINI) != null

    override fun healthSnapshot(): HealthSnapshot = health.snapshot(ProviderId.GEMINI)

    /**
     * Gemini keeps its own internal model preference chain, so it does not expose a dynamic
     * catalogue. An empty list is correct here - Smart AI must not route to Gemini.
     */
    override suspend fun discoverModels(forceRefresh: Boolean): List<ModelCandidate> = emptyList()

    override suspend fun generate(request: AiTextRequest): Result<AiTextResult> {
        val apiKey = keys.getKey(ProviderId.GEMINI)
            ?: return Result.failure(
                AiErrorException(
                    AiError(
                        category = AiErrorCategory.MissingApiKey,
                        safeMessage = "gemini: no API key configured",
                        provider = ProviderId.GEMINI
                    )
                )
            )

        val startedAt = System.currentTimeMillis()

        val result = client.generateContent(
            apiKey = apiKey,
            prompt = request.userPrompt,
            systemInstruction = request.systemPrompt,
            maxOutputTokens = request.maxOutputTokens,
            preferredModel = request.modelId
        )

        val latency = System.currentTimeMillis() - startedAt

        return result
            .onSuccess { completion ->
                health.recordSuccess(ProviderId.GEMINI, completion.model, latency)
                Log.d(
                    TAG,
                    "PROVIDER: gemini | MODEL: ${completion.model} | LATENCY: ${latency}ms | RESULT: success"
                )
            }
            .onFailure { throwable ->
                val error = throwable.asAiError(ProviderId.GEMINI)
                health.recordFailure(ProviderId.GEMINI, null, error)
                Log.w(TAG, "PROVIDER: gemini | RESULT: failure | ${error}")
            }
            .map { completion -> AiTextResult(completion.text, completion.model, latency) }
    }

    /** Real minimal Gemini request. Never reports success without a genuine round trip. */
    override suspend fun testConnection(): ConnectionState {
        val apiKey = keys.getKey(ProviderId.GEMINI) ?: return ConnectionState.NotConfigured

        val result = client.testApiKey(apiKey)
        val error = result.exceptionOrNull()?.asAiError(ProviderId.GEMINI)

        return when {
            result.isSuccess -> {
                health.reset(ProviderId.GEMINI)
                ConnectionState.Connected("Connected to Gemini.")
            }
            error?.category == AiErrorCategory.InvalidApiKey ->
                ConnectionState.InvalidKey("Gemini rejected the API key. Check it in Settings.")
            error?.category == AiErrorCategory.PermissionDenied ->
                ConnectionState.NoEligibleModel("The Gemini key is valid but not permitted for this model.")
            error?.category == AiErrorCategory.QuotaExceeded ->
                ConnectionState.Unavailable("Gemini quota exhausted.")
            error?.category == AiErrorCategory.RateLimited ->
                ConnectionState.RateLimited("Gemini is rate limiting requests.", error.retryAfterMs)
            error?.category == AiErrorCategory.NetworkUnavailable ||
                error?.category == AiErrorCategory.Timeout ->
                ConnectionState.NetworkUnavailable("Network unavailable. Check your connection.")
            else -> ConnectionState.Failed(error?.toUserMessage() ?: "Gemini is unavailable.")
        }
    }

    companion object {
        private const val TAG = "GeminiAiProvider"
    }
}
