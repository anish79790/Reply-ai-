package com.example.reply

data class ReplySuggestion(
    val id: String = java.util.UUID.randomUUID().toString(),
    val index: Int,
    val style: ReplyStyle,
    val text: String
)

enum class ReplyStyle(val title: String, val badgeColor: Long) {
    NATURAL_SAFE("Natural / Safe", 0xFF10B981),
    CASUAL_FRIENDLY("Casual / Friendly", 0xFF8B5CF6),
    PLAYFUL_INTERESTING("Playful / Witty", 0xFFF59E0B)
}

data class ReplyGenerationResult(
    val suggestions: List<ReplySuggestion>,
    val promptUsed: String,
    val engineUsed: String = "gemini",
    val latencyMs: Long = 0L,
    val generatedAt: Long = System.currentTimeMillis()
)
