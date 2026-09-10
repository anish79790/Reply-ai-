package com.example.ai

import okhttp3.Response

/**
 * Reads rate-limit signals from provider responses.
 *
 * Retry-After is the primary source of truth for *when* we may retry. The Groq `x-ratelimit-*`
 * headers are advisory only — they are recorded for diagnostics and health scoring, never used to
 * build a predictive limiter from stale values.
 */
object RateLimitHeaders {

    /**
     * Parses `Retry-After` in either form:
     *  - delta-seconds: "30"
     *  - HTTP-date: "Wed, 21 Oct 2015 07:28:00 GMT"
     */
    fun parseRetryAfterMillis(response: Response, nowMs: Long = System.currentTimeMillis()): Long? {
        val raw = response.header("Retry-After")?.trim() ?: return null
        if (raw.isEmpty()) return null

        raw.toLongOrNull()?.let { seconds ->
            if (seconds >= 0) return (seconds * 1000L).coerceAtMost(MAX_RETRY_AFTER_MS)
        }

        return runCatching {
            val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .apply { isLenient = false }
                .parse(raw)
            if (date == null) null else (date.time - nowMs).coerceIn(0L, MAX_RETRY_AFTER_MS)
        }.getOrNull()
    }

    /** Advisory snapshot of the rate-limit state a provider reported, for health scoring. */
    data class Snapshot(
        val limitRequests: Long? = null,
        val remainingRequests: Long? = null,
        val limitTokens: Long? = null,
        val remainingTokens: Long? = null,
        val resetRequestsMs: Long? = null,
        val resetTokensMs: Long? = null,
        val retryAfterMs: Long? = null
    ) {
        val isExhausted: Boolean
            get() = remainingRequests == 0L || remainingTokens == 0L
    }

    fun snapshot(response: Response, nowMs: Long = System.currentTimeMillis()): Snapshot = Snapshot(
        limitRequests = response.headerLong("x-ratelimit-limit-requests"),
        remainingRequests = response.headerLong("x-ratelimit-remaining-requests"),
        limitTokens = response.headerLong("x-ratelimit-limit-tokens"),
        remainingTokens = response.headerLong("x-ratelimit-remaining-tokens"),
        resetRequestsMs = response.resetDuration("x-ratelimit-reset-requests", nowMs),
        resetTokensMs = response.resetDuration("x-ratelimit-reset-tokens", nowMs),
        retryAfterMs = parseRetryAfterMillis(response, nowMs)
    )

    private fun Response.headerLong(name: String): Long? =
        header(name)?.trim()?.toLongOrNull()

    /**
     * Groq expresses reset windows either as an epoch-seconds timestamp or as an ISO-8601 style
     * duration ("3m2.4s"). Both are tolerated; unparseable values are ignored rather than guessed.
     */
    private fun Response.resetDuration(name: String, nowMs: Long): Long? {
        val raw = header(name)?.trim() ?: return null
        if (raw.isEmpty()) return null

        raw.toDoubleOrNull()?.let { epochSeconds ->
            val targetMs = (epochSeconds * 1000).toLong()
            return (targetMs - nowMs).coerceAtLeast(0L).takeIf { it < MAX_RETRY_AFTER_MS }
        }

        val durationRegex = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?")
        val match = durationRegex.matchEntire(raw) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: 0L
        val seconds = match.groupValues[3].toDoubleOrNull() ?: 0.0
        val totalMs = (hours * 3600_000L) + (minutes * 60_000L) + (seconds * 1000).toLong()
        return totalMs.takeIf { it > 0 }
    }

    private const val MAX_RETRY_AFTER_MS = 5 * 60 * 1000L
}
