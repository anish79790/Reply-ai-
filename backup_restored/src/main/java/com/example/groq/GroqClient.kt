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
        if (!trimmed.startsWith("gsk_")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid format: Groq keys must start with 'gsk_'"))
        }
        if (trimmed.length < 45) {
            return@withContext Result.failure(IllegalArgumentException("Incomplete key (${trimmed.length} chars). A full Groq key is ~56 chars."))
        }
        generateContent(trimmed, "Respond with only 'OK'")
    }

    suspend fun generateContent(apiKey: String, prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Groq API key is missing. Please enter your API key in Settings."))
        }
        if (!trimmedKey.startsWith("gsk_")) {
            return@withContext Result.failure(IllegalArgumentException("Invalid Groq API key format: Groq keys start with 'gsk_'. Please check your key."))
        }
        if (trimmedKey.length < 40) {
            return@withContext Result.failure(IllegalArgumentException("Incomplete Groq key (${trimmedKey.length} characters). A full Groq key is 56 characters. Please copy the entire key from console.groq.com/keys."))
        }

        var lastError: Exception? = null

        for (model in MODELS) {
            try {
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
                            IllegalStateException("Invalid Groq API Key (HTTP 401). Please copy the full ~56-char key from console.groq.com/keys")
                        )
                    }

                    lastError = IllegalStateException("[Groq $model] $errorMsg")
                    continue
                }

                val responseJson = JSONObject(responseString)
                val choices = responseJson.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    lastError = IllegalStateException("No response candidate from Groq")
                    continue
                }

                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val replyText = message?.optString("content")?.trim() ?: ""

                if (replyText.isNotBlank()) {
                    Log.d(TAG, "Successfully generated with Groq model $model")
                    return@withContext Result.success(replyText)
                } else {
                    lastError = IllegalStateException("Empty text returned by Groq")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Groq network exception for $model", e)
                lastError = e
            }
        }

        return@withContext Result.failure(lastError ?: IllegalStateException("Groq inference failed on all models"))
    }
}
