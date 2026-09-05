package com.example.settings

data class AppSettings(
    val isOverlayEnabled: Boolean = true,
    val isAssistantEnabled: Boolean = true, // Master Kill Switch
    val selectedTone: String = "Auto", // e.g. Auto, Casual, Funny, Professional, Flirty, Sarcastic
    val promptStrategy: String = "gemini", // "gemini", "chatgpt", "grok", "custom"
    val customPrompt: String = "",
    val groqApiKey: String = "",
    val enabledApps: Set<String> = setOf(
        "com.instagram.android",
        "com.instagram.lite",
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.plus",
        "com.facebook.orca",
        "com.discord",
        "com.google.android.apps.messaging"
    ),
    val selectedModelId: String = "qwen_1.7b_int4",
    val aiEngine: String = "gemini", // "gemini" or "local"
    val geminiApiKey: String = "",
    val customPersona: String = "",
    val bubbleVerticalOffsetDp: Int = 88,
    val autoUnloadMinutes: Int = 3,
    val enableOcrFallback: Boolean = true,
    // Zinro Features
    val businessName: String = "",
    val businessDescription: String = "",
    val isBusinessContextEnabled: Boolean = true,
    val edgeTabDesign: String = "solid", // "solid", "wide", "outline"
    val edgeTabFillColor: Long = 0xFF8B5CF6,
    val edgeTabBorderColor: Long = 0xFFC084FC,
    val edgeTabWidthDp: Int = 4,
    val edgeTabHeightDp: Int = 68,
    val barPositionAboveKeyboardDp: Int = 88,
    val remainingCredits: Int = 48,
    val usedCredits: Int = 2,
    val isAutoFetchEnabled: Boolean = false, // false = Tap - Credit Saver, true = Auto Fetch
    val isAutofillStripEnabled: Boolean = true,
    val activeUiTheme: String = "bento" // "bento", "glassmorphism", "flat_neo", "brutalism", "skeuomorphic"
)
