package com.example

// NOTE: every credential-looking literal in this file is a SYNTHETIC placeholder used only
// to exercise key redaction and storage. No real API key is present or required.

import com.example.ai.AiErrorCategory
import com.example.ai.ConnectionState
import com.example.ai.AiTextRequest
import com.example.ai.GeminiAiProvider
import com.example.ai.ProviderHealthStore
import com.example.ai.ProviderId
import com.example.ai.asAiError
import com.example.gemini.GeminiClient
import com.example.security.ApiKeyRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gemini request construction follows the current Gemini API, and Gemini stays independent
 * (requirements 1, 30).
 */
class GeminiCompatibilityTest {

    private val client = GeminiClient()

    @Test
    fun gemini3_dropsLegacySamplingParamsAndUsesThinkingLevel() {
        val json = client.buildGenerationConfig("gemini-3.8-flash", 320)

        assertTrue(json.has("maxOutputTokens"))
        assertFalse("temperature was removed in Gemini 3.x", json.has("temperature"))
        assertFalse("topP was removed in Gemini 3.x", json.has("topP"))
        assertFalse("topK was removed in Gemini 3.x", json.has("topK"))

        val thinking = json.getJSONObject("thinkingConfig")
        assertEquals("low", thinking.getString("thinkingLevel"))
        assertFalse("thinkingBudget is replaced by thinkingLevel", thinking.has("thinkingBudget"))
    }

    @Test
    fun gemini25_keepsLegacySamplingParams() {
        val json = client.buildGenerationConfig("gemini-2.5-flash", 320)

        assertEquals(0.7, json.getDouble("temperature"), 0.001)
        assertEquals(0.9, json.getDouble("topP"), 0.001)
        assertEquals(0, json.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
    }

    @Test
    fun modelFamilyDetection() {
        assertTrue(GeminiClient.isGemini3("gemini-3.8-flash"))
        assertTrue(GeminiClient.isGemini3("gemini-3.1-flash-lite-preview"))
        assertFalse(GeminiClient.isGemini3("gemini-2.5-flash"))
    }

    @Test
    fun extractTextSkipsThoughtParts() {
        val body = """
            {"candidates":[{"content":{"parts":[
              {"text":"internal reasoning","thought":true},
              {"text":"Haan bhai chalte hain"}
            ]}}]}
        """.trimIndent()

        assertEquals("Haan bhai chalte hain", client.extractText(body))
    }

    @Test
    fun extractTextHandlesEmptyAndMalformed() {
        assertEquals("", client.extractText(""))
        assertEquals("", client.extractText("not json"))
        assertEquals("", client.extractText("""{"candidates":[]}"""))
    }

    @Test
    fun geminiHttpErrorsMapToSharedCategories() {
        assertEquals(
            AiErrorCategory.InvalidApiKey,
            client.toAiError(401, response(401), """{"error":{"message":"bad key"}}""").category
        )
        assertEquals(
            AiErrorCategory.PermissionDenied,
            client.toAiError(403, response(403), """{"error":{"code":"permission_denied"}}""").category
        )
        assertEquals(
            AiErrorCategory.ModelUnavailable,
            client.toAiError(404, response(404), """{"error":{"message":"not found"}}""").category
        )
        assertEquals(
            AiErrorCategory.RateLimited,
            client.toAiError(429, response(429), """{"error":{"message":"quota"}}""").category
        )
        assertEquals(
            AiErrorCategory.ServerError,
            client.toAiError(503, response(503), """{"error":{"message":"down"}}""").category
        )
    }

    @Test
    fun geminiErrorMessagesAreRedacted() {
        val error = client.toAiError(
            401,
            response(401),
            """{"error":{"message":"invalid key AIzaSySUPERSECRETKEYVALUE123"}}"""
        )
        assertFalse(error.safeMessage.contains("AIzaSySUPERSECRETKEYVALUE123"))
        assertFalse(error.toString().contains("AIzaSySUPERSECRETKEYVALUE123"))
    }

    @Test
    fun geminiProviderWithoutKeyReportsMissingKeyAndSendsNothing() = runBlocking {
        val keys = ApiKeyRepository(InMemorySecretStore())
        val provider = GeminiAiProvider(keys, ProviderHealthStore())

        val result = provider.generate(AiTextRequest(userPrompt = "hello"))
        assertEquals(AiErrorCategory.MissingApiKey, result.exceptionOrNull()!!.asAiError().category)
        assertEquals(ProviderId.GEMINI, provider.id)
        assertEquals(ConnectionState.NotConfigured, provider.testConnection())
    }

    private fun response(code: Int): Response = Response.Builder()
        .request(Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/x:generateContent").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("error")
        .build()
}
