package com.example.ai

import com.example.conversation.ExtractedConversation

/**
 * A compact description of *what* the router has to generate.
 *
 * Produced once per request from the extracted conversation, so providers never re-implement
 * conversation analysis and the ranking stays deterministic for the same input.
 */
data class TaskProfile(
    /** 0f = trivial (a single short message), 1f = long, multi-turn, nuanced context. */
    val complexity: Float,
    /** Rough token estimate for the prompt we are about to send. */
    val estimatedPromptTokens: Int,
    /** Tokens we need back: three concise suggestions plus formatting slack. */
    val desiredOutputTokens: Int,
    /** True when the task is long/nuanced enough that a reasoning-capable model helps. */
    val wantsReasoning: Boolean,
    /** True when the Smart AI path should stay on explicitly free-tier models. */
    val requiresFreeTier: Boolean
) {
    companion object {
        /** Conservative default used when no conversation is available (e.g. AI commands). */
        fun default(requiresFreeTier: Boolean = true) = TaskProfile(
            complexity = 0.35f,
            estimatedPromptTokens = 900,
            desiredOutputTokens = 320,
            wantsReasoning = false,
            requiresFreeTier = requiresFreeTier
        )
    }
}

/**
 * Derives a [TaskProfile] from the already-extracted conversation.
 *
 * This intentionally reads only what the extractor produced — it does NOT re-walk the
 * accessibility node tree and does NOT duplicate prompt building.
 */
object TaskProfileAnalyzer {

    fun analyze(
        conversation: ExtractedConversation?,
        prompt: String? = null,
        requiresFreeTier: Boolean = true
    ): TaskProfile {
        val messages = conversation?.messages.orEmpty()
        val characters = (prompt?.length ?: 0).let { promptLength ->
            if (promptLength > 0) promptLength else messages.sumOf { it.text.length }
        }

        val estimatedPromptTokens = (characters / CHARS_PER_TOKEN).coerceAtLeast(MIN_PROMPT_TOKENS)
        val turns = messages.size

        val complexity = listOf(
            // Longer conversations carry more context to reconcile.
            (turns / TURNS_FOR_MAX_COMPLEXITY.toFloat()).coerceAtMost(1f) * 0.40f,
            // Longer text usually means more nuance and more constraints to honour.
            (characters / CHARS_FOR_MAX_COMPLEXITY.toFloat()).coerceAtMost(1f) * 0.35f,
            // An explicit question or a business-context answer needs more care than a "hmm".
            (if (containsQuestion(messages.map { it.text })) 0.15f else 0f),
            // Multi-participant threads are harder to follow.
            (if (messages.map { it.sender }.distinct().size > 2) 0.10f else 0f)
        ).sum().coerceIn(0f, 1f)

        val wantsReasoning = complexity >= REASONING_COMPLEXITY_THRESHOLD

        return TaskProfile(
            complexity = complexity,
            estimatedPromptTokens = estimatedPromptTokens,
            desiredOutputTokens = DESIRED_OUTPUT_TOKENS,
            wantsReasoning = wantsReasoning,
            requiresFreeTier = requiresFreeTier
        )
    }

    private fun containsQuestion(texts: List<String>): Boolean {
        val joined = texts.takeLast(RECENT_MESSAGES_FOR_QUESTION).joinToString(" ")
        return joined.contains('?') || joined.contains("क्या") || joined.contains("kya", ignoreCase = true)
    }

    private const val CHARS_PER_TOKEN = 4
    private const val MIN_PROMPT_TOKENS = 256
    private const val TURNS_FOR_MAX_COMPLEXITY = 18
    private const val CHARS_FOR_MAX_COMPLEXITY = 1600
    private const val REASONING_COMPLEXITY_THRESHOLD = 0.55f
    private const val RECENT_MESSAGES_FOR_QUESTION = 4
    private const val DESIRED_OUTPUT_TOKENS = 320
}
