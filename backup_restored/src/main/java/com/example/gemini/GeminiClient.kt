package com.example.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GeminiClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "GeminiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        // Latest official Gemini 3.8 Flash as primary, with fallback
        private val MODELS = listOf(
            "gemini-3.8-flash",
            "gemini-2.5-flash",
            "gemini-flash-latest",
            "gemini-3.1-flash-lite-preview"
        )
    }

    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is empty"))
        }
        generateContent(trimmed, "Reply with 'OK' only.")
    }

    suspend fun generateContent(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is missing. Please enter your API key in Settings."))
        }

        var lastError: Exception? = null

        for (model in MODELS) {
            val url = "$BASE_URL/$model:generateContent?key=${apiKey.trim()}"

            try {
                val jsonBody = JSONObject().apply {
                    val contentsArray = JSONArray().apply {
                        val contentObj = JSONObject().apply {
                            val partsArray = JSONArray().apply {
                                val partObj = JSONObject().apply {
                                    put("text", prompt)
                                }
                                put(partObj)
                            }
                            put("parts", partsArray)
                        }
                        put(contentObj)
                    }
                    put("contents", contentsArray)

                    val generationConfig = JSONObject().apply {
                        put("temperature", 0.7)
                        put("topP", 0.9)
                        put("maxOutputTokens", 500)
                        if (model == "gemini-2.5-flash") {
                            put("thinkingConfig", JSONObject().apply {
                                put("thinkingBudget", 0)
                            })
                        }
                    }
                    put("generationConfig", generationConfig)
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseString = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errJson = JSONObject(responseString)
                        errJson.optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: $responseString"
                    }
                    Log.w(TAG, "Model $model returned error ($response.code): $errorMsg, trying fallback...")
                    lastError = IllegalStateException("[$model] $errorMsg")
                    continue
                }

                val responseJson = JSONObject(responseString)
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates == null || candidates.length() == 0) {
                    lastError = IllegalStateException("No response candidate from $model")
                    continue
                }

                val firstCandidate = candidates.getJSONObject(0)
                val content = firstCandidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val sb = java.lang.StringBuilder()
                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        if (part != null && !part.optBoolean("thought", false)) {
                            val textPart = part.optString("text", "")
                            if (textPart.isNotBlank()) sb.append(textPart)
                        }
                    }
                }
                val replyText = if (sb.isNotBlank()) sb.toString().trim() else parts?.optJSONObject(0)?.optString("text")?.trim() ?: ""

                if (replyText.isNotBlank()) {
                    Log.d(TAG, "Successfully generated with $model")
                    return@withContext Result.success(replyText)
                } else {
                    lastError = IllegalStateException("Empty text returned by $model")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini network exception on model $model", e)
                lastError = e
            }
        }

        Result.failure(lastError ?: IllegalStateException("All Gemini models failed"))
    }
}
