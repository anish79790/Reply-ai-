package com.example.ai

import android.util.Log
import com.example.llm.LocalLLMEngine

/**
 * On-device GGUF/llama provider used exclusively by the "Local AI" engine.
 *
 * Strictly local: it delegates to the existing [LocalLLMEngine] and never calls Gemini, Groq or
 * xKiro. There is deliberately no cloud fallback here - if the model is missing or the native
 * runtime fails, the user is told about the local model instead of being silently moved to a
 * cloud provider.
 */
class LocalAiProvider(
    private val engine: LocalLLMEngine,
    private val health: ProviderHealthStore
) : AiProvider {

    override val id: ProviderId = ProviderId.LOCAL
    override val displayName: String = "Local AI"

    /** No key required; readiness depends on the local model being installed and loadable. */
    override suspend fun isConfigured(): Boolean = true

    override fun healthSnapshot(): HealthSnapshot = health.snapshot(ProviderId.LOCAL)

    override suspend fun discoverModels(forceRefresh: Boolean): List<ModelCandidate> =
        listOf(
            ModelCandidate(
                provider = ProviderId.LOCAL,
                modelId = engine.currentConfig.value.fileName,
                displayName = engine.currentConfig.value.name,
                ownedBy = "on-device",
                // Local inference has no metered tier: it is always "free" to call.
                accessTier = ModelCandidate.FREE,
                contextLength = null,
                maxOutputTokens = null,
                capabilities = setOf("chat")
            )
        )

    override suspend fun generate(request: AiTextRequest): Result<AiTextResult> {
        val prompt = buildLocalPrompt(request)
        val startedAt = System.currentTimeMillis()

        val result = engine.generate(prompt)
        val latency = System.currentTimeMillis() - startedAt

        return result
            .onSuccess {
                health.recordSuccess(ProviderId.LOCAL, LOCAL_MODEL_KEY, latency)
                Log.d(TAG, "PROVIDER: local | LATENCY: ${latency}ms | RESULT: success")
            }
            .onFailure { throwable ->
                val error = AiError(
                    category = AiErrorCategory.UnknownError,
                    safeMessage = "LOCAL_LLM_ERROR: ${throwable.message ?: "on-device inference failed"}",
                    provider = ProviderId.LOCAL,
                    cause = throwable
                )
                health.recordFailure(ProviderId.LOCAL, LOCAL_MODEL_KEY, error)
                Log.w(TAG, "PROVIDER: local | RESULT: failure | $error")
            }
            .map { text -> AiTextResult(text, LOCAL_MODEL_KEY, latency) }
    }

    /**
     * Local models have no system/role separation in this engine, so the system instruction is
     * prepended to the user prompt rather than being dropped.
     */
    private fun buildLocalPrompt(request: AiTextRequest): String =
        if (request.systemPrompt.isNullOrBlank()) request.userPrompt
        else "${request.systemPrompt}\n\n${request.userPrompt}"

    override suspend fun testConnection(): ConnectionState {
        if (!engine.isModelReady()) {
            return ConnectionState.NoEligibleModel(
                if (engine.modelState.value is com.example.llm.ModelState.NotInstalled)
                    "No local model installed. Download one on the AI Model tab."
                else
                    "Local model is not loaded yet. Open the AI Model tab to load it."
            )
        }

        val result = engine.generate("Reply with OK only.")
        return if (result.isSuccess && result.getOrThrow().isNotBlank()) {
            health.reset(ProviderId.LOCAL)
            ConnectionState.Connected("Local model is ready and responding on-device.")
        } else {
            ConnectionState.Failed(
                result.exceptionOrNull()?.asAiError(ProviderId.LOCAL)?.toUserMessage()
                    ?: "Local model did not return a response."
            )
        }
    }

    companion object {
        private const val TAG = "LocalAiProvider"
        const val LOCAL_MODEL_KEY = "local:gguf"
    }
}
