package com.example.conversation

import android.graphics.Rect

/**
 * Represents an individual chat message extracted from a messaging screen.
 */
data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String,
    val text: String,
    val isUser: Boolean, // true = sent by user (outgoing), false = received (incoming)
    val timestamp: Long = System.currentTimeMillis(),
    val bounds: Rect? = null,
    val confidence: Float = 1.0f
) {
    val displaySender: String
        get() = if (isUser) "Me" else sender
}

enum class ExtractionSource {
    ACCESSIBILITY,
    OCR,
    SIMULATED
}

enum class DetectedLanguage(val displayName: String, val code: String) {
    ENGLISH("English", "en"),
    HINDI("Hindi (हिंदी)", "hi"),
    HINGLISH("Hinglish", "hi-Latn"),
    MIXED("Mixed Chat", "mixed")
}
