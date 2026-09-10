package com.example

import com.example.ai.RateLimitHeaders
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Retry-After and Groq rate-limit header parsing (requirements 9, 10). */
class RateLimitHeadersTest {

    private fun response(
        code: Int = 429,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url("https://api.example.com/v1/chat/completions").build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Too Many Requests")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    @Test
    fun parsesRetryAfterInSeconds() {
        val retry = RateLimitHeaders.parseRetryAfterMillis(
            response(headers = mapOf("Retry-After" to "30")),
            nowMs = 0L
        )
        assertEquals(30_000L, retry)
    }

    @Test
    fun parsesRetryAfterAsHttpDate() {
        // HTTP-date form: retry at a known instant.
        val retry = RateLimitHeaders.parseRetryAfterMillis(
            response(headers = mapOf("Retry-After" to "Wed, 21 Oct 2015 07:28:00 GMT")),
            nowMs = 1_445_401_600_000L // 2015-10-21T07:20:00Z
        )
        assertEquals(8 * 60 * 1000L, retry)
    }

    @Test
    fun missingOrGarbageRetryAfterReturnsNull() {
        assertNull(RateLimitHeaders.parseRetryAfterMillis(response(headers = emptyMap())))
        assertNull(RateLimitHeaders.parseRetryAfterMillis(response(headers = mapOf("Retry-After" to "not-a-number"))))
        assertNull(RateLimitHeaders.parseRetryAfterMillis(response(headers = mapOf("Retry-After" to ""))))
    }

    @Test
    fun retryAfterIsCapped() {
        val retry = RateLimitHeaders.parseRetryAfterMillis(
            response(headers = mapOf("Retry-After" to "99999")),
            nowMs = 0L
        )
        assertEquals(5 * 60 * 1000L, retry)
    }

    @Test
    fun readsGroqRateLimitHeaders() {
        val snapshot = RateLimitHeaders.snapshot(
            response(
                headers = mapOf(
                    "x-ratelimit-limit-requests" to "30",
                    "x-ratelimit-remaining-requests" to "0",
                    "x-ratelimit-limit-tokens" to "6000",
                    "x-ratelimit-remaining-tokens" to "5400",
                    "x-ratelimit-reset-requests" to "3m2.4s",
                    "x-ratelimit-reset-tokens" to "1.5s",
                    "Retry-After" to "12"
                )
            ),
            nowMs = 0L
        )

        assertEquals(30L, snapshot.limitRequests)
        assertEquals(0L, snapshot.remainingRequests)
        assertEquals(6000L, snapshot.limitTokens)
        assertEquals(5400L, snapshot.remainingTokens)
        assertEquals(12_000L, snapshot.retryAfterMs)
        assertEquals(182_400L, snapshot.resetRequestsMs)
        assertTrue(snapshot.isExhausted)
    }

    @Test
    fun toleratesUnparseableResetDurations() {
        val snapshot = RateLimitHeaders.snapshot(
            response(headers = mapOf("x-ratelimit-reset-requests" to "soon")),
            nowMs = 0L
        )
        assertNull(snapshot.resetRequestsMs)
    }

    @Test
    fun rateLimitSnapshotIsOnlyAdvisoryNotExhaustedByDefault() {
        val snapshot = RateLimitHeaders.snapshot(response(headers = emptyMap()), nowMs = 0L)
        assertNull(snapshot.remainingRequests)
        assertNull(snapshot.retryAfterMs)
        assertEquals(false, snapshot.isExhausted)
    }
}
