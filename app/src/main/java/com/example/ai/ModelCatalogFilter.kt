package com.example.ai

/**
 * Shared eligibility rules applied to dynamically discovered catalogues.
 *
 * The rules are deliberately conservative and provider-agnostic: they only use metadata the
 * provider actually published, plus the request profile. Anything that depends on account
 * entitlements is decided later, by the real request.
 */
object ModelCatalogFilter {

    /**
     * @param requireFreeTier when true, only models explicitly published as `access_tier == "free"`
     *                        survive. Used for the free Smart AI path on xKiro. Providers that do
     *                        not publish a tier (Groq) are NOT filtered out by this rule, because
     *                        "unknown tier" must not be confused with "paid tier".
     * @param minContextTokens rejects models whose context window cannot hold the prompt.
     * @param minOutputTokens  rejects models that cannot emit three concise reply suggestions.
     */
    fun eligible(
        candidates: List<ModelCandidate>,
        requireFreeTier: Boolean,
        minContextTokens: Int,
        minOutputTokens: Int = 180
    ): List<ModelCandidate> = candidates.filter { c ->
        if (c.modelId.isBlank()) return@filter false
        if (!c.supportsChat) return@filter false
        if (requireFreeTier && c.accessTier != null && !c.isFreeTier) return@filter false
        if (c.contextLength != null && c.contextLength < minContextTokens) return@filter false
        if (c.maxOutputTokens != null && c.maxOutputTokens < minOutputTokens) return@filter false
        isBlocked(c).not()
    }

    /**
     * Models that are structurally unsuitable for short chat replies regardless of metadata,
     * e.g. embedding models, TTS/ASR, image generation and moderation endpoints.
     */
    fun isBlocked(candidate: ModelCandidate): Boolean {
        val id = candidate.modelId.lowercase()
        val name = (candidate.displayName ?: "").lowercase()
        val haystack = "$id $name"
        val blockedTokens = listOf(
            "embedding", "embed", "whisper", "tts", "speech", "transcribe",
            "moderation", "dall-e", "image", "rerank", "guard", "safety"
        )
        return blockedTokens.any { haystack.contains(it) }
    }

    /** Removes duplicate ids, preserving first-seen order. */
    fun distinct(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val seen = HashSet<String>()
        return candidates.filter { seen.add("${it.provider.storageKey}:${it.modelId}") }
    }
}
