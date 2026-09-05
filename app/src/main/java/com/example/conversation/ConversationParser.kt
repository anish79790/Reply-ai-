package com.example.conversation

import java.util.Locale

object ConversationParser {

    private val SYSTEM_NOISE_KEYWORDS = setOf(
        "active now", "online", "seen", "delivered", "sent", "typing...", "typing",
        "message...", "message", "type a message", "write a message", "send a message",
        "chat...", "search", "like", "reply", "forward", "copy",
        "delete", "details", "call", "video call", "view profile",
        "audio call", "today", "yesterday", "mon", "tue", "wed", "thu", "fri", "sat", "sun",
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
        "am", "pm", "gif", "sticker", "camera", "gallery", "mic", "voice note",
        "profile picture", "profile photo", "profile picture of", "double tap to like",
        "double tap", "double-tap to like", "tap and hold to react", "see more", "...see more",
        "you replied", "replied to you", "replied to themself", "swipe to reply",
        "view profile photo", "double tap to view profile photo", "watch again",
        "reel by", "post by", "instagram post", "voice message", "audio message",
        "responsive • active now", "responsive", "active today", "end-to-end encrypted",
        "messages and calls are end-to-end encrypted", "disappearing messages",
        "tap for info", "waiting for this message", "this message was deleted",
        "you deleted this message"
    )

    private val HINGLISH_KEYWORDS = setOf(
        "kya", "hai", "haan", "nhi", "nahi", "kaise", "kaisa", "kaha", "kahan",
        "bhai", "yaar", "bro", "kal", "aaj", "chalo", "chalte", "kab", "kar",
        "kare", "karenge", "thik", "theek", "accha", "acha", "sahi", "mast",
        "kuch", "apna", "apni", "ab", "sath", "mujhe", "tum", "tu", "mera",
        "meri", "tera", "teri", "khana", "milte", "dekhte", "baat", "pakka",
        "batana", "bol", "bola", "dekh", "sun", "mat", "lekin", "kyu", "kyun",
        "vahi", "wahi", "sab", "toh", "to", "bhi", "sirf", "jaldi", "aao",
        "rha", "raha", "rhi", "rahi", "hoga", "hogi", "kr", "karunga", "vaale", "wale",
        "hru", "wbu", "gn", "gm", "kal", "sham", "subah"
    )

    fun isNoise(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        if (trimmed.length <= 1 && !trimmed.matches(Regex("[\\p{So}\\p{Sm}\\p{Sc}]"))) return true

        val lower = trimmed.lowercase(Locale.ROOT)
        if (SYSTEM_NOISE_KEYWORDS.contains(lower)) return true

        // Filter system phrases starting with common noise prefixes
        if (lower.startsWith("profile picture") ||
            lower.startsWith("profile photo") ||
            lower.startsWith("double tap") ||
            lower.startsWith("double-tap") ||
            lower.startsWith("tap and hold") ||
            lower.startsWith("seen ") ||
            lower.startsWith("seen yesterday") ||
            lower.startsWith("sent yesterday") ||
            lower.startsWith("delivered ") ||
            lower.startsWith("active ") ||
            lower.startsWith("responsive") ||
            lower.startsWith("reel by") ||
            lower.startsWith("post by") ||
            lower.startsWith("story by") ||
            lower.startsWith("replied to") ||
            lower.startsWith("reacted to") ||
            lower.startsWith("liked a message") ||
            lower.startsWith("shared a ") ||
            lower == "you replied" ||
            lower == "...see more" ||
            lower == "see more" ||
            lower == "seen" ||
            lower == "sent" ||
            lower == "delivered"
        ) {
            return true
        }

        // Check if string is only a timestamp like "10:30 PM", "Yesterday", "14:20"
        if (lower.matches(Regex("^\\d{1,2}:\\d{2}(\\s*(am|pm))?$"))) return true
        if (lower.matches(Regex("^\\d{1,2}:\\d{2}\\s*(am|pm)?\\s*·\\s*.*$"))) return true
        if (lower.matches(Regex("^(today|yesterday|seen yesterday|sent yesterday)(\\s+at\\s+\\d{1,2}:\\d{2}(\\s*(am|pm))?)?$"))) return true

        return false
    }

    fun detectLanguage(messages: List<ChatMessage>): DetectedLanguage {
        if (messages.isEmpty()) return DetectedLanguage.ENGLISH

        val combinedText = messages.joinToString(" ") { it.text }

        // Check for Devanagari Unicode block (0x0900 to 0x097F)
        val devanagariCount = combinedText.count { it.code in 0x0900..0x097F }
        if (devanagariCount > 5) {
            return DetectedLanguage.HINDI
        }

        // Check for Hinglish keywords
        val words = combinedText.lowercase(Locale.ROOT)
            .split(Regex("[\\s.,!?\"'()\\[\\]{}]+"))
            .filter { it.isNotEmpty() }

        var hinglishHits = 0
        for (word in words) {
            if (HINGLISH_KEYWORDS.contains(word)) {
                hinglishHits++
            }
        }

        val totalWords = words.size.coerceAtLeast(1)
        val hinglishRatio = hinglishHits.toFloat() / totalWords.toFloat()

        return when {
            hinglishHits >= 2 || hinglishRatio > 0.15f -> DetectedLanguage.HINGLISH
            else -> DetectedLanguage.ENGLISH
        }
    }

    fun cleanMessageText(rawText: String): String {
        return rawText
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
