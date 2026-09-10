package com.example.ai

/**
 * Common request/response abstraction shared by every provider.
 *
 * Reply generation is implemented once, on top of this interface. Providers only translate a
 * [AiTextRequest] into their own wire format; they never build prompts, parse reply suggestions
 * or touch conversation extraction.
 */
data class AiTextRequest(
    /** Optional system instruction. Gemini receives it as a systemInstruction, others as a system message. */
    val systemPrompt: String? = null,
    val userPrompt: String,
    val maxOutputTokens: Int = 320,
    val temperature: Double? = 0.7,
    /** Model chosen by the router. Gemini keeps its own internal fallback chain when null. */
    val modelId: String? = null
)

data class AiTextResult(
    val text: String,
    /** The concrete model that produced the text, safe to log and show in diagnostics. */
    val modelId: String,
    val latencyMs: Long
)

/** Result of a real, lightweight provider reachability check. */
sealed class ConnectionState {
    data object Idle : ConnectionState()
    data object Testing : ConnectionState()
    data object NotConfigured : ConnectionState()
    data class Connected(val detail: String) : ConnectionState()
    data class InvalidKey(val message: String) : ConnectionState()
    data class NoEligibleModel(val message: String) : ConnectionState()
    data class RateLimited(val message: String, val retryAfterMs: Long? = null) : ConnectionState()
    data class NetworkUnavailable(val message: String) : ConnectionState()
    data class Unavailable(val message: String) : ConnectionState()
    data class Failed(val message: String) : ConnectionState()

    val isSuccess: Boolean get() = this is Connected

    fun asMessage(): String = when (this) {
        Idle -> ""
        Testing -> "Testing connection…"
        NotConfigured -> "No API key configured."
        is Connected -> detail
        is InvalidKey -> message
        is NoEligibleModel -> message
        is RateLimited -> message
        is NetworkUnavailable -> message
        is Unavailable -> message
        is Failed -> message
    }
}

interface AiProvider {

    val id: ProviderId

    val displayName: String

    /** True when this provider has everything it needs (an API key, or a local model) to run. */
    suspend fun isConfigured(): Boolean

    /**
     * Performs one completion attempt. Returns [Result.failure] carrying an [AiError] as the
     * throwable so callers can branch on [AiError.category] without string matching.
     */
    suspend fun generate(request: AiTextRequest): Result<AiTextResult>

    /**
     * Performs a REAL lightweight request against the provider. Never fakes success:
     * a key that cannot authenticate must report [ConnectionState.InvalidKey].
     */
    suspend fun testConnection(): ConnectionState

    /** Dynamically discovered model catalogue. Returns an empty list when unavailable. */
    suspend fun discoverModels(forceRefresh: Boolean = false): List<ModelCandidate>

    /** Log-safe health snapshot for diagnostics. Contains no key material. */
    fun healthSnapshot(): HealthSnapshot
}
