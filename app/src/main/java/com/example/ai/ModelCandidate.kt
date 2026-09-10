package com.example.ai

/**
 * A single model exposed by a provider's dynamic model catalogue.
 *
 * Populated from live provider responses (GET /v1/models for xKiro, GET /models for Groq).
 * Nothing here is hardcoded: the catalogues change over time and the live response is always the
 * source of truth for what exists and what the account may call.
 */
data class ModelCandidate(
    val provider: ProviderId,
    /** Full provider-native id. For xKiro this MUST keep its vendor prefix, e.g. "openai/gpt-5.6-sol". */
    val modelId: String,
    val displayName: String? = null,
    val ownedBy: String? = null,
    /** xKiro tier metadata: "free" / "paid" / "premium". Null when the provider does not expose it. */
    val accessTier: String? = null,
    val contextLength: Int? = null,
    val maxOutputTokens: Int? = null,
    /** Raw capability names as published by the provider (lower-cased for matching). */
    val capabilities: Set<String> = emptySet(),
    val reasoningEfforts: List<String> = emptyList(),
    val inputPricePerToken: Double? = null,
    val outputPricePerToken: Double? = null
) {

    val isFreeTier: Boolean
        get() = accessTier?.equals(FREE, ignoreCase = true) == true

    /** True when the id still carries a "vendor/" prefix. */
    val hasVendorPrefix: Boolean
        get() = modelId.contains('/')

    val vendor: String?
        get() = if (hasVendorPrefix) modelId.substringBefore('/') else null

    /** Model name without the vendor prefix, for display and heuristic matching only. */
    val shortName: String
        get() = if (hasVendorPrefix) modelId.substringAfter('/') else modelId

    val supportsChat: Boolean
        get() = capabilities.isEmpty() ||
                capabilities.any { it.contains("chat") || it.contains("text") || it.contains("completion") }

    val supportsReasoning: Boolean
        get() = capabilities.any { it.contains("reason") }

    val supportsVision: Boolean
        get() = capabilities.any { it.contains("vision") || it.contains("image") }

    val supportsTools: Boolean
        get() = capabilities.any { it.contains("tool") || it.contains("function") }

    /** Stable key used for per-model health tracking. */
    val healthKey: String
        get() = "${provider.storageKey}:$modelId"

    companion object {
        const val FREE = "free"
        const val PAID = "paid"
        const val PREMIUM = "premium"
    }
}
