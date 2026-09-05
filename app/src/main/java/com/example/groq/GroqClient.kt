package com.example.groq

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

class GroqClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "GroqClient"
        private const val BASE_URL = "https://api.groq.com/openai/v1/chat/completions"
        private val MODELS = listOf(
            "llama-3.1-8b-instant",
            "llama-3.3-70b-versatile",
            "gemma2-9b-it"
        )
    }

    suspend fun testApiKey(apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Groq API key is empty"))
        }
        generateContent(trimmed, "Respond with only 'OK'")
    }

    suspend fun generateContent(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("GROQ_ERROR: Groq API key is missing. Please enter your API key in Settings."))
        }

        var lastError: Exception? = null

        for (model in MODELS) {
            try {
                Log.d(TAG, "PROVIDER: Groq | MODEL: $model | REQUEST START: $BASE_URL")

                val jsonBody = JSONObject().apply {
                    put("model", model)
                    val messagesArray = JSONArray().apply {
                        val messageObj = JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        }
                        put(messageObj)
                    }
                    put("messages", messagesArray)
                    put("temperature", 0.7)
                    put("max_tokens", 500)
                }

                val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(BASE_URL)
                    .addHeader("Authorization", "Bearer $trimmedKey")
                    .addHeader("User-Agent", "Mozilla/5.0 (Android; InstaReply)")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseString = response.body?.string() ?: ""
                Log.d(TAG, "PROVIDER: Groq | MODEL: $model | HTTP STATUS: ${response.code} | RESPONSE RECEIVED")
                Log.d(TAG, "RAW RESPONSE: $responseString")

                if (!response.isSuccessful) {
                    val errorMsg = try {
                        val errJson = JSONObject(responseString)
                        errJson.optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (_: Exception) {
                        "HTTP ${response.code}: $responseString"
                    }
                    Log.w(TAG, "Groq model $model returned error (${response.code}): $errorMsg")

                    if (response.code == 401) {
                        return@withContext Result.failure(
                            IllegalStateException("GROQ_ERROR: Invalid Groq API Key (HTTP 401). Please check your key in Settings.")
                        )
                    }

                    lastError = IllegalStateException("GROQ_ERROR: HTTP ${response.code} - [Groq $model] $errorMsg")
                    continue
                }

                val responseJson = JSONObject(responseString)
                val choices = responseJson.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    lastError = IllegalStateException("GROQ_ERROR: No response candidate returned by Groq")
                    continue
                }

                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val replyText = message?.optString("content")?.trim() ?: ""

                if (replyText.isNotBlank()) {
                    Log.d(TAG, "Successfully generated with Groq model $model")
                    return@withContext Result.success(replyText)
                } else {
                    lastError = IllegalStateException("GROQ_ERROR: Empty content returned by Groq model $model")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Groq network exception for $model", e)
                lastError = e
            }
        }

        return@withContext Result.failure(lastError ?: IllegalStateException("GROQ_ERROR: Groq inference failed on all models"))
    }
}
