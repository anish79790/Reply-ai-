package com.example.settings

/**
 * Persisted, non-secret user preferences.
 *
 * API keys are deliberately NOT part of this model: they live only in the Keystore-backed
 * [com.example.security.ApiKeyRepository], so they can never leak through this StateFlow into
 * UI state dumps, diagnostics or crash reports.
 */
data class AppSettings(
    val isOverlayEnabled: Boolean = true,
    val isAssistantEnabled: Boolean = true, // Master Kill Switch
    /** One of [ENGINE_GEMINI], [ENGINE_SMART], [ENGINE_LOCAL]. */
    val aiEngine: String = ENGINE_GEMINI,
    val selectedTone: String = "Auto", // e.g. Auto, Casual, Funny, Professional, Flirty, Sarcastic
    val promptStrategy: String = "gemini", // "gemini", "chatgpt", "grok", "custom"
    val customPrompt: String = "",
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
) {
    companion object {
        /** Calls Gemini directly. Never routed through Smart AI. */
        const val ENGINE_GEMINI = "gemini"

        /** Multi-provider router. Internally uses Groq and/or xKiro only. */
        const val ENGINE_SMART = "smart"

        /** On-device GGUF only. Never falls back to a cloud provider. */
        const val ENGINE_LOCAL = "local"

        /** Legacy value from before Groq became an internal Smart AI provider. */
        private const val ENGINE_LEGACY_GROQ = "groq"

        val ENGINES = listOf(ENGINE_GEMINI, ENGINE_SMART, ENGINE_LOCAL)

        /** Maps persisted values (including the legacy "groq" engine) onto a supported engine. */
        fun normalizeEngine(value: String?): String = when (value) {
            ENGINE_GEMINI -> ENGINE_GEMINI
            ENGINE_SMART, ENGINE_LEGACY_GROQ -> ENGINE_SMART
            ENGINE_LOCAL -> ENGINE_LOCAL
            else -> ENGINE_GEMINI
        }
    }
}
