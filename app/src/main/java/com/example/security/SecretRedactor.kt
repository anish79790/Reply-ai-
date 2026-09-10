package com.example.security

/**
 * Removes anything that looks like a credential from a string before it is logged, shown in
 * diagnostics, or attached to a crash report.
 *
 * API keys must NEVER reach logs, diagnostics, UI state dumps or analytics, so every provider
 * error path funnels provider-supplied text through [redact] first.
 */
object SecretRedactor {

    private const val REDACTED = "[REDACTED]"

    /**
     * Patterns that match the shapes of credentials used by this app and by common LLM providers.
     * Matching is deliberately broad: over-redacting a log line is always preferable to leaking a
     * key.
     */
    private val PATTERNS: List<Regex> = listOf(
        // Explicit bearer / api-key / token assignments: "Bearer sk-abc", "api_key=xyz", "\"apiKey\": \"abc\""
        Regex(
            "(?i)(bearer\\s+)[A-Za-z0-9._\\-]{6,}",
        ),
        Regex(
            "(?i)((?:api[_-]?key|apikey|authorization|access[_-]?token|secret|password|key)\\s*[\"']?\\s*[:=]\\s*[\"']?)[A-Za-z0-9._\\-]{6,}[\"']?",
        ),
        // Well known provider key prefixes.
        Regex("AIza[0-9A-Za-z_\\-]{10,}"),          // Google AI Studio
        Regex("gsk_[A-Za-z0-9]{8,}"),               // Groq
        Regex("sk-[A-Za-z0-9._\\-]{8,}"),           // OpenAI / xKiro style
        Regex("xkiro[-_][A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE),
    )

    /** Replaces credential-shaped substrings with [REDACTED], preserving the surrounding label. */
    fun redact(input: String?): String {
        if (input.isNullOrEmpty()) return input ?: ""
        var out = input
        for (pattern in PATTERNS) {
            out = when {
                pattern.pattern.startsWith("(?i)((?:api") -> out.replace(pattern) { m ->
                    "${m.groupValues[1]}$REDACTED"
                }
                pattern.pattern.startsWith("(?i)(bearer") -> out.replace(pattern) { m ->
                    "${m.groupValues[1]}$REDACTED"
                }
                else -> out.replace(pattern, REDACTED)
            }
        }
        return out
    }

    /** Never returns the key itself; only whether one is present and a safe fingerprint. */
    fun fingerprint(key: String?): String {
        if (key.isNullOrBlank()) return "absent"
        val tail = if (key.length >= 4) key.takeLast(4) else "****"
        return "present(len=${key.length}, tail=…$tail)"
    }

    /** Truncates arbitrary provider payloads so raw error bodies are never surfaced verbatim. */
    fun truncate(input: String?, maxLength: Int = 240): String {
        val clean = redact(input)
        return if (clean.length <= maxLength) clean else clean.take(maxLength) + "…"
    }
}
