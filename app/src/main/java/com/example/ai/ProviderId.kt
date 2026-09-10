package com.example.ai

/**
 * Internal provider identifiers.
 *
 * These are *internal* providers, not user-facing engines. The app exposes exactly three
 * user-facing engines (Gemini, Smart AI, Local AI); GROQ and XKIRO are reachable only through
 * Smart AI and are never presented as selectable engines in the UI.
 */
enum class ProviderId(val storageKey: String, val displayName: String) {
    GEMINI("gemini", "Gemini"),
    GROQ("groq", "Groq"),
    XKIRO("xkiro", "xKiro"),
    LOCAL("local", "Local AI");

    companion object {
        /** Providers that Smart AI is allowed to route to, in preference order for tie-breaks. */
        val SMART_AI_PROVIDERS: List<ProviderId> = listOf(ProviderId.XKIRO, ProviderId.GROQ)

        fun fromStorageKey(key: String): ProviderId? =
            entries.firstOrNull { it.storageKey == key }
    }
}
