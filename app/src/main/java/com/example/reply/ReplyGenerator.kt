package com.example.reply

import com.example.conversation.ExtractedConversation

/**
 * Interface for generating fresh contextual conversation replies via on-device LLM.
 */
interface ReplyGenerator {
    suspend fun generateReplies(
        conversation: ExtractedConversation,
        customPersona: String? = null
    ): Result<ReplyGenerationResult>

    suspend fun generateCompletions(
        draftText: String,
        conversation: ExtractedConversation,
        customPersona: String? = null
    ): Result<ReplyGenerationResult>
}
