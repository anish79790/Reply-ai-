package com.example.ai

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multi-provider router behind the "Smart AI" engine.
 *
 * The user picks Smart AI once; this class decides which internal provider (Groq / xKiro) and which
 * dynamically discovered model should answer. Groq and xKiro are never surfaced as engines.
 *
 * Pipeline:
 *   task profile -> discover eligible models -> filter -> score -> attempt in score order
 *   -> validate -> fall back on failure
 *
 * Retrying is strictly bounded: [maxAttempts] caps total network attempts per request, and the
 * per-category rules below decide whether a failure means "retry this model", "skip this model" or
 * "abandon this provider". There is no unbounded loop.
 */
class SmartModelRouter(
    private val providers: List<OpenAiCompatibleProvider>,
    private val health: ProviderHealthStore,
    private val catalog: ModelCatalogCache,
    private val maxAttempts: Int = MAX_ATTEMPTS
) {

    /** Log-safe summary of the last routing decision, for diagnostics. */
    data class RunInfo(
        val provider: ProviderId?,
        val model: String?,
        val latencyMs: Long?,
        val result: String,
        val errorCategory: AiErrorCategory?,
        val attempts: Int,
        val candidateCount: Int
    )

    private val _lastRun = MutableStateFlow<RunInfo?>(null)
    val lastRun: StateFlow<RunInfo?> = _lastRun.asStateFlow()

    /**
     * Discovers, filters and ranks candidates across every configured internal provider.
     * Public so diagnostics can show what Smart AI is currently considering.
     */
    suspend fun selectCandidates(
        profile: TaskProfile,
        forceRefresh: Boolean = false
    ): List<ModelCandidate> = coroutineScope {
        val configured = providers.filter { it.isConfigured() }

        val perProvider = configured.map { provider ->
            async {
                runCatching {
                    val models = provider.discoverModels(forceRefresh)
                        .takeIf { it.isNotEmpty() }
                        ?: provider.seedCandidates()
                    provider.eligible(models, profile)
                }.getOrDefault(emptyList())
            }
        }

        val all = perProvider.flatMap { it.await() }
        val distinct = ModelCatalogFilter.distinct(all)

        ModelRanker.rank(distinct, profile, health)
            .filter { it.score > BLOCKED_SCORE }
            .map { it.candidate }
    }

    suspend fun generate(request: AiTextRequest, profile: TaskProfile): Result<AiTextResult> {
        val configured = providers.filter { it.isConfigured() }

        if (configured.isEmpty()) {
            val error = AiError(
                category = AiErrorCategory.NoEligibleModel,
                safeMessage = "Smart AI has no configured provider. Add a Groq or xKiro key in Settings.",
                provider = null
            )
            _lastRun.value = RunInfo(null, null, null, "no-provider", error.category, 0, 0)
            return Result.failure(AiErrorException(error))
        }

        val candidates = selectCandidates(profile)
        if (candidates.isEmpty()) {
            val error = AiError(
                category = AiErrorCategory.NoEligibleModel,
                safeMessage = "Smart AI found no eligible model on the configured providers.",
                provider = null
            )
            _lastRun.value = RunInfo(null, null, null, "no-eligible-model", error.category, 0, 0)
            return Result.failure(AiErrorException(error))
        }

        var attempts = 0
        var lastError: AiError? = null
        var shortestRetryAfterMs: Long? = null
        val attemptsPerModel = HashMap<String, Int>()

        for (candidate in candidates) {
            if (attempts >= maxAttempts) break
            val provider = configured.firstOrNull { it.id == candidate.provider } ?: continue

            while (attempts < maxAttempts) {
                val used = attemptsPerModel[candidate.modelId] ?: 0
                if (used >= MAX_ATTEMPTS_PER_MODEL) break

                attemptsPerModel[candidate.modelId] = used + 1
                attempts++

                val result = provider.generate(request.copy(modelId = candidate.modelId))
                if (result.isSuccess) {
                    val aiResult = result.getOrThrow()
                    Log.d(
                        TAG,
                        "provider=${candidate.provider.storageKey} model=${candidate.modelId} " +
                            "latency=${aiResult.latencyMs}ms result=success"
                    )
                    _lastRun.value = RunInfo(
                        provider = candidate.provider,
                        model = candidate.modelId,
                        latencyMs = aiResult.latencyMs,
                        result = "success",
                        errorCategory = null,
                        attempts = attempts,
                        candidateCount = candidates.size
                    )
                    return Result.success(aiResult)
                }

                val error = result.exceptionOrNull()!!.asAiError(candidate.provider)
                lastError = error
                Log.w(
                    TAG,
                    "provider=${candidate.provider.storageKey} model=${candidate.modelId} " +
                        "result=failure category=${error.category} http=${error.httpStatus}"
                )

                when (error.category) {
                    // Key/credit problems affect the whole provider: never retry, never loop.
                    AiErrorCategory.MissingApiKey,
                    AiErrorCategory.InvalidApiKey,
                    AiErrorCategory.QuotaExceeded,
                    AiErrorCategory.InvalidRequest -> break

                    // Entitlement or a retired model: cool this model down and try the next one.
                    AiErrorCategory.PermissionDenied,
                    AiErrorCategory.ModelUnavailable -> break

                    // Rate limited: remember the shortest requested delay, then fail over.
                    AiErrorCategory.RateLimited -> {
                        error.retryAfterMs?.let { retry ->
                            shortestRetryAfterMs = shortestRetryAfterMs?.let { minOf(it, retry) } ?: retry
                        }
                        break
                    }

                    // Transient: one conservative retry on the same model, then move on.
                    AiErrorCategory.ServerError,
                    AiErrorCategory.Timeout,
                    AiErrorCategory.NetworkUnavailable -> {
                        if (used + 1 >= MAX_TRANSIENT_RETRIES) break
                        delay(DEFAULT_RETRY_DELAY_MS)
                    }

                    else -> break
                }
            }
        }

        // Every candidate failed with a rate limit and the requested wait is short: honour it once
        // rather than hammering the provider, and only if we still have attempt budget.
        val waitMs = shortestRetryAfterMs
        if (waitMs != null && waitMs in 1..MAX_AUTO_WAIT_MS && attempts < maxAttempts && lastError != null) {
            val candidate = candidates.first()
            val provider = configured.firstOrNull { it.id == candidate.provider }
            if (provider != null && !health.isCoolingDown(candidate.provider, candidate.modelId)) {
                delay(waitMs)
                attempts++
                val result = provider.generate(request.copy(modelId = candidate.modelId))
                if (result.isSuccess) {
                    val aiResult = result.getOrThrow()
                    _lastRun.value = RunInfo(
                        provider = candidate.provider,
                        model = candidate.modelId,
                        latencyMs = aiResult.latencyMs,
                        result = "success-after-retry-after",
                        errorCategory = null,
                        attempts = attempts,
                        candidateCount = candidates.size
                    )
                    return Result.success(aiResult)
                }
                lastError = result.exceptionOrNull()?.asAiError(candidate.provider) ?: lastError
            }
        }

        val finalError = lastError ?: AiError(
            category = AiErrorCategory.UnknownError,
            safeMessage = "Smart AI could not complete the request."
        )
        _lastRun.value = RunInfo(null, null, null, "failure", finalError.category, attempts, candidates.size)
        return Result.failure(AiErrorException(finalError))
    }

    companion object {
        private const val TAG = "SmartModelRouter"

        /** Hard ceiling on network attempts for one user-visible request. */
        const val MAX_ATTEMPTS = 5
        const val MAX_ATTEMPTS_PER_MODEL = 2
        const val MAX_TRANSIENT_RETRIES = 2
        const val DEFAULT_RETRY_DELAY_MS = 350L

        /** Longest delay Smart AI will silently wait for a Retry-After before giving up. */
        const val MAX_AUTO_WAIT_MS = 2_000L

        /** Ranker's sentinel score for hard-blocked (cooling down) candidates. */
        const val BLOCKED_SCORE = -999.0
    }
}
