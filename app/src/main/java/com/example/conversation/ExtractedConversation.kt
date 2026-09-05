package com.example.conversation

/**
 * Container for the full extracted conversation state.
 */
data class ExtractedConversation(
    val messages: List<ChatMessage>,
    val latestIncomingMessage: ChatMessage?,
    val activePackage: String = "",
    val source: ExtractionSource = ExtractionSource.ACCESSIBILITY,
    val detectedLanguage: DetectedLanguage = DetectedLanguage.ENGLISH,
    val draftText: String? = null,
    val extractedAt: Long = System.currentTimeMillis()
) {
    val isEmpty: Boolean
        get() = messages.isEmpty()

    val conversationCount: Int
        get() = messages.size

    fun formatForPrompt(maxMessages: Int = 20): String {
        val window = if (messages.size > maxMessages) {
            messages.takeLast(maxMessages)
        } else {
            messages
        }
        return window.joinToString("\n") { message ->
            "${message.displaySender}: ${message.text}"
        }
    }

    companion object {
        val EMPTY = ExtractedConversation(
            messages = emptyList(),
            latestIncomingMessage = null,
            activePackage = "",
            source = ExtractionSource.ACCESSIBILITY,
            detectedLanguage = DetectedLanguage.ENGLISH
        )
    }
}
