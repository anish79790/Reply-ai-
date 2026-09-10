package com.example.gemini

import android.util.Log
import com.example.ai.AiError
import com.example.ai.AiErrorCategory
import com.example.ai.AiErrorException
import com.example.ai.ProviderId
import com.example.ai.RateLimitHeaders
import com.example.security.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Text produced by Gemini together with the concrete model that produced it. */
data class GeminiCompletion(val text: String, val model: String)

/**
 * Direct Gemini REST client (generativelanguage.googleapis.com).
 *
 * This is the ONLY path used by the "Gemini" engine - Gemini requests are never routed through
 * Smart AI, Groq, xKiro or the local model, and no API key is compiled into the app.
 *
 * Request construction follows the current Gemini API:
 * - Gemini 3.x no longer accepts the legacy sampling parameters (`temperature`, `topP`, `topK`) and
 *   replaces the numeric `thinkingBudget` with the string `thinkingLevel`, so those are only sent
 *   for 2.x models.
 * - `maxOutputTokens` is sent for every model family.
 */
class GeminiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = BASE_URL
) {
    companion object {
        private const val TAG = "GeminiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        /**
         * Ordered seed preference chain, used only when the caller does not pin a model.
         * The first model that answers successfully wins.
         */
        val MODELS: List<String> = listOf(
            "gemini-3.8-flash",
            "gemini-2.5-flash",
            "gemini-flash-latest"
        )

        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val WRITE_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 25L
        private const val CALL_TIMEOUT_SECONDS = 35L

        /** Errors that will not be fixed by trying another model in the seed chain. */
        private val NON_RETRYABLE_IN_CHAIN = setOf(
            AiErrorCategory.InvalidApiKey,
            AiErrorCategory.PermissionDenied,
            AiErrorCategory.QuotaExceeded,
            AiErrorCategory.InvalidRequest
        )

        /** Gemini 3.x models: legacy sampling params removed, thinking_level is a string enum. */
        fun isGemini3(model: String): Boolean = model.startsWith("gemini-3", ignoreCase = true)
    }

    /** Real, minimal request used by Test Connection. Fails with a structured [AiError] on error. */
    suspend fun testApiKey(apiKey: String): Result<String> {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return Result.failure(
                AiErrorException(
                    AiError(
                        category = AiErrorCategory.MissingApiKey,
                        safeMessage = "GEMINI_ERROR: Gemini API key is empty",
                        provider = ProviderId.GEMINI
                    )
                )
            )
        }
        return generateContent(
            apiKey = trimmed,
            prompt = "Reply with 'OK' only.",
            maxOutputTokens = 16
        ).map { it.text }
    }

    suspend fun generateContent(
        apiKey: String,
        prompt: String,
        systemInstruction: String? = null,
        maxOutputTokens: Int = 320,
        preferredModel: String? = null
    ): Result<GeminiCompletion> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(
                AiErrorException(
                    AiError(
                        category = AiErrorCategory.MissingApiKey,
                        safeMessage = "GEMINI_ERROR: Gemini API key is missing. Add it in Settings.",
                        provider = ProviderId.GEMINI
                    )
                )
            )
        }

        val candidates = buildList {
            preferredModel?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(MODELS.filter { it != preferredModel })
        }

        var lastError: AiError? = null

        for (model in candidates) {
            val url = "$baseUrl/$model:generateContent?key=$trimmedKey"

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", prompt) })
                        })
                    })
                })

                if (!systemInstruction.isNullOrBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", systemInstruction) })
                        })
                    })
                }

                put("generationConfig", buildGenerationConfig(model, maxOutputTokens))
            }

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val startedAt = System.currentTimeMillis()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startedAt
                    val responseString = response.body?.string().orEmpty()

                    // Safe metadata only: model, status, latency. Never the body, never the key.
                    Log.d(TAG, "PROVIDER: gemini | MODEL: $model | HTTP STATUS: ${response.code} | LATENCY: ${latency}ms")

                    if (!response.isSuccessful) {
                        val error = toAiError(response.code, response, responseString)
                        lastError = error
                        Log.w(TAG, "Gemini error: $error")
                        if (error.category in NON_RETRYABLE_IN_CHAIN) {
                            return@withContext Result.failure(AiErrorException(error))
                        }
                        return@use
                    }

                    val replyText = extractText(responseString)
                    if (replyText.isNotBlank()) {
                        return@withContext Result.success(GeminiCompletion(replyText, model))
                    }
                    lastError = AiError(
                        category = AiErrorCategory.UnknownError,
                        safeMessage = "GEMINI_ERROR: empty text returned by $model",
                        httpStatus = 200,
                        provider = ProviderId.GEMINI
                    )
                }
            } catch (e: SocketTimeoutException) {
                lastError = AiError(
                    category = AiErrorCategory.Timeout,
                    safeMessage = "GEMINI_ERROR: timeout calling $model",
                    provider = ProviderId.GEMINI,
                    cause = e
                )
            } catch (e: IOException) {
                lastError = AiError(
                    category = AiErrorCategory.NetworkUnavailable,
                    safeMessage = "GEMINI_ERROR: network failure calling $model",
                    provider = ProviderId.GEMINI,
                    cause = e
                )
            } catch (e: Exception) {
                lastError = AiError(
                    category = AiErrorCategory.UnknownError,
                    safeMessage = "GEMINI_ERROR: ${SecretRedactor.truncate(e.message, 120)}",
                    provider = ProviderId.GEMINI,
                    cause = e
                )
            }
        }

        Result.failure(
            AiErrorException(
                lastError ?: AiError(
                    category = AiErrorCategory.UnknownError,
                    safeMessage = "GEMINI_ERROR: all Gemini models failed",
                    provider = ProviderId.GEMINI
                )
            )
        )
    }

    /**
     * Gemini 3.x dropped `temperature` / `topP` / `topK` and replaced numeric `thinkingBudget`
     * with the string enum `thinkingLevel`. Sending the legacy fields to a 3.x model is rejected,
     * so the generation config is built per model family.
     */
    fun buildGenerationConfig(model: String, maxOutputTokens: Int): JSONObject =
        JSONObject().apply {
            put("maxOutputTokens", maxOutputTokens)
            if (isGemini3(model)) {
                put("thinkingConfig", JSONObject().apply {
                    // Reply suggestions are short and latency sensitive. "minimal" is not supported
                    // on 3.8 Flash, so "low" is the cheapest valid setting.
                    put("thinkingLevel", "low")
                })
            } else {
                put("temperature", 0.7)
                put("topP", 0.9)
                if (model.startsWith("gemini-2.5", ignoreCase = true)) {
                    put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 0) })
                }
            }
        }

    /** Pulls the assistant text out of a Gemini response, skipping reasoning "thought" parts. */
    fun extractText(responseString: String): String {
        if (responseString.isBlank()) return ""
        return runCatching {
            val responseJson = JSONObject(responseString)
            val candidates = responseJson.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val parts = candidates.getJSONObject(0)
                .optJSONObject("content")
                ?.optJSONArray("parts") ?: return ""

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                sb.append(part.optString("text", ""))
            }
            val text = sb.toString()
            if (text.isBlank()) parts.optJSONObject(0)?.optString("text", "").orEmpty() else text
        }.getOrDefault("").trim()
    }

    /** Maps a Gemini HTTP failure onto the shared error model. The body is redacted before use. */
    fun toAiError(status: Int, response: okhttp3.Response, body: String): AiError {
        val serverMessage = runCatching {
            val errJson = JSONObject(body)
            errJson.optJSONObject("error")?.let { err ->
                listOfNotNull(
                    err.optString("code").takeIf { it.isNotBlank() },
                    err.optString("message").takeIf { it.isNotBlank() }
                ).joinToString(" - ")
            } ?: errJson.optString("message")
        }.getOrDefault("").orEmpty()

        return AiError(
            category = when (status) {
                400 -> AiErrorCategory.InvalidRequest
                401 -> AiErrorCategory.InvalidApiKey
                403 -> AiErrorCategory.PermissionDenied
                404 -> AiErrorCategory.ModelUnavailable
                408 -> AiErrorCategory.Timeout
                429 -> AiErrorCategory.RateLimited
                in 500..599 -> AiErrorCategory.ServerError
                else -> AiErrorCategory.UnknownError
            },
            safeMessage = SecretRedactor.truncate("GEMINI_ERROR: HTTP $status $serverMessage", 200),
            httpStatus = status,
            retryAfterMs = RateLimitHeaders.parseRetryAfterMillis(response),
            provider = ProviderId.GEMINI
        )
    }
}
