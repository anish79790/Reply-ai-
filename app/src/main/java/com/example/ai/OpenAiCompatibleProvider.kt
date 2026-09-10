package com.example.ai

import android.util.Log
import com.example.security.ApiKeyRepository
import com.example.security.SecretRedactor
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Shared implementation for OpenAI-compatible chat providers (Groq and xKiro).
 *
 * Both use the same `POST /chat/completions` wire format, so the request construction, response
 * parsing, error mapping, health tracking and retry/cooldown rules live here once instead of being
 * duplicated. Subclasses only supply:
 *  - the base URL,
 *  - how to fetch and filter their catalogue,
 *  - a small optional seed list used purely for startup/failure recovery.
 *
 * Only fields Groq and xKiro actually support are sent: `model`, `messages`, `temperature` and
 * `max_tokens`. No speculative OpenAI parameters are copied in.
 */
abstract class OpenAiCompatibleProvider(
    protected val keys: ApiKeyRepository,
    protected val health: ProviderHealthStore,
    protected val catalog: ModelCatalogCache,
    protected val httpClient: OkHttpClient = OpenAiCompatibleProvider.defaultHttpClient(),
    override val id: ProviderId,
    override val displayName: String,
    protected val baseUrl: String
) : AiProvider {

    /** Guards catalogue refresh so concurrent requests trigger a single discovery call. */
    private val refreshMutex = Mutex()

    /** Small, non-authoritative fallback hints used only when discovery fails. */
    protected open val seedModelIds: List<String> = emptyList()

    protected abstract suspend fun fetchCatalog(apiKey: String): List<ModelCandidate>

    /** Public: the router ranks candidates across providers, so it must call this. */
    abstract fun eligible(
        models: List<ModelCandidate>,
        profile: TaskProfile
    ): List<ModelCandidate>

    override suspend fun isConfigured(): Boolean = keys.getKey(id) != null

    override fun healthSnapshot(): HealthSnapshot = health.snapshot(id)

    override suspend fun discoverModels(forceRefresh: Boolean): List<ModelCandidate> {
        if (!forceRefresh && !catalog.isStale(id)) {
            return catalog.get(id).orEmpty()
        }
        val apiKey = keys.getKey(id) ?: return catalog.get(id).orEmpty()

        return refreshMutex.withLock {
            // Re-check inside the lock: another coroutine may have refreshed while we waited.
            if (!forceRefresh && !catalog.isStale(id)) {
                return@withLock catalog.get(id).orEmpty()
            }
            runCatching { fetchCatalog(apiKey) }
                .onFailure { Log.w(TAG, "provider=${id.storageKey} catalog refresh failed: ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
                .also { models ->
                    if (models.isNotEmpty()) catalog.put(id, models)
                }
        }
    }

    override suspend fun generate(request: AiTextRequest): Result<AiTextResult> {
        val apiKey = keys.getKey(id)
            ?: return Result.failure(
                AiErrorException(
                    AiError(
                        category = AiErrorCategory.MissingApiKey,
                        safeMessage = "${id.storageKey}: no API key configured",
                        provider = id
                    )
                )
            )

        if (health.isCoolingDown(id)) {
            val snapshot = health.snapshot(id)
            return Result.failure(
                AiErrorException(
                    AiError(
                        category = snapshot.lastErrorCategory ?: AiErrorCategory.RateLimited,
                        safeMessage = "${id.storageKey}: cooling down for ${health.cooldownRemainingMs(id)}ms",
                        retryAfterMs = health.cooldownRemainingMs(id),
                        provider = id
                    )
                )
            )
        }

        val modelId = request.modelId?.takeIf { it.isNotBlank() }
            ?: bestEffortDefaultModel()
            ?: return Result.failure(
                AiErrorException(
                    AiError(
                        category = AiErrorCategory.NoEligibleModel,
                        safeMessage = "${id.storageKey}: no eligible model available",
                        provider = id
                    )
                )
            )

        val startedAt = System.currentTimeMillis()

        val payload = JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply {
                request.systemPrompt?.takeIf { it.isNotBlank() }?.let { system ->
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", system)
                    })
                }
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", request.userPrompt)
                })
            })
            // temperature is supported by both providers; omitted entirely when null.
            request.temperature?.let { put("temperature", it) }
            put("max_tokens", request.maxOutputTokens)
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return try {
            httpClient.newCall(httpRequest).await().use { response ->
                val latency = System.currentTimeMillis() - startedAt
                val body = response.body?.string().orEmpty()

                // Safe metadata only. Never the response body (it can echo prompts) and never the key.
                Log.d(
                    TAG,
                    "PROVIDER: ${id.storageKey} | MODEL: $modelId | HTTP STATUS: ${response.code} | LATENCY: ${latency}ms"
                )

                if (!response.isSuccessful) {
                    val error = mapHttpError(response.code, response, body, modelId)
                    health.recordFailure(id, modelId, error)
                    if (error.category == AiErrorCategory.ModelUnavailable) {
                        health.retireModel(id, modelId)
                        catalog.invalidate(id)
                    }
                    return Result.failure(AiErrorException(error))
                }

                val text = extractContent(body)
                if (text.isBlank()) {
                    val error = AiError(
                        category = AiErrorCategory.UnknownError,
                        safeMessage = "${id.storageKey}: empty content from $modelId",
                        provider = id
                    )
                    health.recordFailure(id, modelId, error)
                    return Result.failure(AiErrorException(error))
                }

                health.recordSuccess(id, modelId, latency)
                Result.success(AiTextResult(text = text, modelId = modelId, latencyMs = latency))
            }
        } catch (e: SocketTimeoutException) {
            val error = AiError(
                category = AiErrorCategory.Timeout,
                safeMessage = "${id.storageKey}: timeout calling $modelId",
                provider = id,
                cause = e
            )
            health.recordFailure(id, modelId, error)
            Result.failure(AiErrorException(error))
        } catch (e: IOException) {
            val error = AiError(
                category = AiErrorCategory.NetworkUnavailable,
                safeMessage = "${id.storageKey}: network failure calling $modelId",
                provider = id,
                cause = e
            )
            health.recordFailure(id, modelId, error)
            Result.failure(AiErrorException(error))
        } catch (e: Exception) {
            if (e is AiErrorException) return Result.failure(e)
            val error = AiError(
                category = AiErrorCategory.UnknownError,
                safeMessage = "${id.storageKey}: ${SecretRedactor.truncate(e.message, 120)}",
                provider = id,
                cause = e
            )
            health.recordFailure(id, modelId, error)
            Result.failure(AiErrorException(error))
        }
    }

    /**
     * Real reachability check. Fetches the live catalogue and reports precisely what went wrong.
     * Nothing here fakes success: an unusable key or an empty eligible set is reported as such.
     */
    override suspend fun testConnection(): ConnectionState {
        val apiKey = keys.getKey(id) ?: return ConnectionState.NotConfigured

        val models = try {
            fetchCatalog(apiKey)
        } catch (e: AiErrorException) {
            // Distinguish an unusable key from an entitlement problem, without ever surfacing
            // the raw provider error body.
            return when (e.error.category) {
                AiErrorCategory.InvalidApiKey -> ConnectionState.InvalidKey(
                    "$displayName rejected the API key. Check it in Settings."
                )
                AiErrorCategory.RateLimited -> ConnectionState.RateLimited(
                    "$displayName is rate limiting requests. Try again shortly.",
                    e.error.retryAfterMs
                )
                AiErrorCategory.PermissionDenied -> ConnectionState.NoEligibleModel(
                    "The $displayName key is valid but not entitled to any model."
                )
                AiErrorCategory.QuotaExceeded -> ConnectionState.Unavailable(
                    "$displayName quota or credits exhausted."
                )
                AiErrorCategory.ServerError -> ConnectionState.Unavailable(
                    "$displayName is temporarily unavailable."
                )
                AiErrorCategory.NetworkUnavailable, AiErrorCategory.Timeout ->
                    ConnectionState.NetworkUnavailable("Network unavailable. Check your connection.")
                else -> ConnectionState.Failed(e.error.toUserMessage())
            }
        } catch (e: SocketTimeoutException) {
            return ConnectionState.NetworkUnavailable("Timed out while contacting $displayName.")
        } catch (e: IOException) {
            return ConnectionState.NetworkUnavailable("Network unavailable. Check your connection.")
        } catch (e: Exception) {
            return ConnectionState.Failed(e.asAiError(id).toUserMessage())
        }

        if (models.isEmpty()) {
            return ConnectionState.NoEligibleModel(
                "$displayName returned no usable models for this account."
            )
        }

        val eligible = eligible(models, TaskProfile.default())
        catalog.put(id, models)
        health.reset(id)

        return if (eligible.isEmpty()) {
            ConnectionState.NoEligibleModel(
                "$displayName connected, but no eligible model matched this account."
            )
        } else {
            ConnectionState.Connected(
                "Connected to $displayName • ${eligible.size} eligible model(s) • e.g. ${eligible.first().modelId}"
            )
        }
    }

    /**
     * Seed hints as [ModelCandidate]s, used only when live discovery returned nothing.
     * They are never treated as authoritative and are skipped the moment a real catalogue
     * (or a 403/404 on one of them) says otherwise.
     */
    fun seedCandidates(): List<ModelCandidate> =
        seedModelIds.map { ModelCandidate(provider = id, modelId = it) }

    /** Picks a sensible model when the router has not already chosen one. */
    protected suspend fun bestEffortDefaultModel(): String? {
        val models = discoverModels().takeIf { it.isNotEmpty() }
            ?: seedModelIds.map { ModelCandidate(provider = id, modelId = it) }

        return ModelRanker
            .rank(eligible(models, TaskProfile.default()), TaskProfile.default(), health)
            .firstOrNull()
            ?.candidate
            ?.modelId
    }

    protected fun mapHttpError(
        status: Int,
        response: okhttp3.Response,
        body: String,
        modelId: String
    ): AiError {
        val retryAfterMs = RateLimitHeaders.parseRetryAfterMillis(response)
        val serverMessage = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message")
        }.getOrDefault("").orEmpty()

        val category = when (status) {
            401 -> AiErrorCategory.InvalidApiKey
            402 -> AiErrorCategory.QuotaExceeded
            403 -> AiErrorCategory.PermissionDenied
            404 -> AiErrorCategory.ModelUnavailable
            408 -> AiErrorCategory.Timeout
            422 -> AiErrorCategory.InvalidRequest
            429 -> AiErrorCategory.RateLimited
            in 500..599 -> AiErrorCategory.ServerError
            else -> AiErrorCategory.UnknownError
        }

        return AiError(
            category = category,
            safeMessage = SecretRedactor.truncate(
                "${id.storageKey}: HTTP $status on model=$modelId ${serverMessage.orEmpty()}",
                200
            ),
            httpStatus = status,
            retryAfterMs = retryAfterMs,
            provider = id
        )
    }

    /** Extracts the assistant message text from a chat-completions response. */
    fun extractContent(body: String): String {
        if (body.isBlank()) return ""
        return runCatching {
            val json = JSONObject(body)
            val choices = json.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            choices.getJSONObject(0)
                .optJSONObject("message")
                ?.optString("content")
                .orEmpty()
        }.getOrDefault("").trim()
    }

    /**
     * Authenticated GET used for catalogue (and optionally usage) endpoints.
     * Throws [AiErrorException] on any non-2xx status so callers can branch on the category.
     */
    protected suspend fun getAuthenticated(path: String, apiKey: String): String {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        return try {
            httpClient.newCall(request).await().use { response ->
                val body = response.body?.string().orEmpty()
                Log.d(TAG, "PROVIDER: ${id.storageKey} | GET $path | HTTP STATUS: ${response.code}")
                if (!response.isSuccessful) {
                    throw AiErrorException(mapHttpError(response.code, response, body, path))
                }
                body
            }
        } catch (e: AiErrorException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw AiErrorException(
                AiError(AiErrorCategory.Timeout, "${id.storageKey}: timeout on GET $path", provider = id, cause = e)
            )
        } catch (e: IOException) {
            throw AiErrorException(
                AiError(AiErrorCategory.NetworkUnavailable, "${id.storageKey}: network failure on GET $path", provider = id, cause = e)
            )
        }
    }

    companion object {
        private const val TAG = "AiProvider"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }
}
