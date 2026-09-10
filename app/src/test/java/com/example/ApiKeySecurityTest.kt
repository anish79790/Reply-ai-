package com.example

// NOTE: every credential-looking literal in this file is a SYNTHETIC placeholder used only
// to exercise key redaction and storage. No real API key is present or required.

import com.example.ai.ProviderId
import com.example.security.ApiKeyRepository
import com.example.security.SecretRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** API key storage/retrieval and redaction (requirements 15, 27.13, 27.14). */
class ApiKeySecurityTest {

    private val store = InMemorySecretStore()
    private val repo = ApiKeyRepository(store)

    @Test
    fun keyRoundTripsThroughRepository() {
        repo.setKey(ProviderId.GEMINI, "AIzaSyFAKEKEYVALUE_123456789")
        assertEquals("AIzaSyFAKEKEYVALUE_123456789", repo.getKey(ProviderId.GEMINI))
        assertTrue(repo.isConfigured(ProviderId.GEMINI))
    }

    @Test
    fun keysAreNamespacedPerProvider() {
        repo.setKey(ProviderId.GEMINI, "gem-key")
        repo.setKey(ProviderId.GROQ, "groq-key")
        repo.setKey(ProviderId.XKIRO, "xkiro-key")

        assertEquals("gem-key", repo.getKey(ProviderId.GEMINI))
        assertEquals("groq-key", repo.getKey(ProviderId.GROQ))
        assertEquals("xkiro-key", repo.getKey(ProviderId.XKIRO))
    }

    @Test
    fun blankValueClearsTheKey() {
        repo.setKey(ProviderId.GROQ, "gsk_secret")
        repo.setKey(ProviderId.GROQ, "")
        assertNull(repo.getKey(ProviderId.GROQ))
        assertFalse(repo.isConfigured(ProviderId.GROQ))
    }

    @Test
    fun configuredStreamReflectsPresenceOnly() {
        repo.setKey(ProviderId.XKIRO, "xkiro-secret-value")
        val configured = repo.configured.value

        assertTrue(configured.contains(ProviderId.XKIRO))
        assertFalse(configured.contains(ProviderId.GROQ))
        // The stream exposes presence, never the secret itself.
        assertTrue(configured.none { it.toString().contains("xkiro-secret-value") })
    }

    @Test
    fun clearKeyRemovesOnlyThatProvider() {
        repo.setKey(ProviderId.GEMINI, "a")
        repo.setKey(ProviderId.GROQ, "b")
        repo.clearKey(ProviderId.GEMINI)

        assertNull(repo.getKey(ProviderId.GEMINI))
        assertEquals("b", repo.getKey(ProviderId.GROQ))
    }

    @Test
    fun keysAreTrimmed() {
        repo.setKey(ProviderId.GROQ, "  gsk_spaced  ")
        assertEquals("gsk_spaced", repo.getKey(ProviderId.GROQ))
    }

    // ------------------------------------------------------------------ redaction

    @Test
    fun redactsGoogleStyleKeys() {
        val redacted = SecretRedactor.redact("Request failed for AIzaSyD-1234567890abcdefghijklmnop")
        assertFalse(redacted.contains("AIzaSyD-1234567890abcdefghijklmnop"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun redactsGroqStyleKeys() {
        val redacted = SecretRedactor.redact("Groq rejected gsk_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789")
        assertFalse(redacted.contains("gsk_AbCdEfGhIjKlMnOpQrStUvWxYz0123456789"))
    }

    @Test
    fun redactsBearerTokens() {
        val redacted = SecretRedactor.redact("Authorization: Bearer sk-abcdefgh12345678")
        assertFalse(redacted.contains("sk-abcdefgh12345678"))
        assertTrue(redacted.contains("Bearer"))
        assertTrue(redacted.contains("[REDACTED]"))
    }

    @Test
    fun redactsJsonAssignments() {
        val redacted = SecretRedactor.redact("""{"apiKey": "supersecretvalue", "model": "x"}""")
        assertFalse(redacted.contains("supersecretvalue"))
        assertTrue(redacted.contains("\"model\""))
    }

    @Test
    fun redactHandlesNullAndEmpty() {
        assertEquals("", SecretRedactor.redact(null))
        assertEquals("", SecretRedactor.redact(""))
    }

    @Test
    fun fingerprintNeverRevealsTheWholeKey() {
        val fingerprint = SecretRedactor.fingerprint("AIzaSySUPERSECRETVALUE")
        assertFalse(fingerprint.contains("AIzaSySUPERSECRETVALUE"))
        assertTrue(fingerprint.startsWith("present("))
        assertEquals("absent", SecretRedactor.fingerprint(null))
    }

    @Test
    fun truncateCapsRawProviderBodies() {
        val long = "x".repeat(5000)
        val truncated = SecretRedactor.truncate(long, 240)
        assertTrue(truncated.length <= 241)
    }

    @Test
    fun aiErrorToStringNeverCarriesKeyMaterial() {
        val redacted = SecretRedactor.redact("xkiro authentication failed for key sk-livekey123456789")
        assertFalse(redacted.contains("sk-livekey123456789"))
        assertTrue(redacted.contains("xkiro authentication failed"))
    }
}
