package com.example.prompt

import com.example.conversation.ExtractedConversation

/**
 * Interface for building localized on-device LLM prompts.
 */
interface PromptBuilder {
    fun buildPrompt(
        conversation: ExtractedConversation,
        maxContextMessages: Int = 20,
        customPersona: String? = null,
        businessContext: String? = null,
        tone: String = "Auto",
        strategy: String = "gemini",
        customPromptText: String = ""
    ): String

    fun buildAiCommandPrompt(
        command: String,
        conversation: ExtractedConversation,
        customPersona: String? = null,
        businessContext: String? = null
    ): String

    fun buildCompletionPrompt(
        draftText: String,
        conversation: ExtractedConversation,
        customPersona: String? = null,
        businessContext: String? = null
    ): String
}
