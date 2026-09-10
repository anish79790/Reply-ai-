package com.example.ai

/**
 * Provider-agnostic error model.
 *
 * Every provider maps its own transport/HTTP failures onto these categories so the router can
 * decide whether to retry, cool down, or fall through to the next candidate, and so the UI can
 * show a human-readable sentence instead of a raw provider error body.
 *
 * [safeMessage] must already be redacted by the provider; it must never contain an API key or
 * an Authorization header value.
 */
enum class AiErrorCategory {
    MissingApiKey,
    InvalidApiKey,
    PermissionDenied,
    QuotaExceeded,
    RateLimited,
    ModelUnavailable,
    NetworkUnavailable,
    Timeout,
    ServerError,
    InvalidRequest,
    NoEligibleModel,
    Cancelled,
    UnknownError
}

data class AiError(
    val category: AiErrorCategory,
    val safeMessage: String = "",
    val httpStatus: Int? = null,
    val retryAfterMs: Long? = null,
    val provider: ProviderId? = null,
    val cause: Throwable? = null
) {
    /** Short, user-presentable sentence. Never contains raw provider payloads. */
    fun toUserMessage(): String = when (category) {
        AiErrorCategory.MissingApiKey ->
            "No API key configured for ${provider?.displayName ?: "this provider"}. Add one in Settings → AI Providers."
        AiErrorCategory.InvalidApiKey ->
            "${provider?.displayName ?: "Provider"} rejected the API key. Check the key in Settings."
        AiErrorCategory.PermissionDenied ->
            "Your ${provider?.displayName ?: "provider"} account cannot use that model. Another model was tried."
        AiErrorCategory.QuotaExceeded ->
            "${provider?.displayName ?: "Provider"} quota or credits exhausted."
        AiErrorCategory.RateLimited ->
            "${provider?.displayName ?: "Provider"} is rate limiting requests. Try again shortly."
        AiErrorCategory.ModelUnavailable ->
            "The selected model is no longer available. Another model was tried."
        AiErrorCategory.NetworkUnavailable ->
            "Network unavailable. Check your connection and try again."
        AiErrorCategory.Timeout ->
            "${provider?.displayName ?: "Provider"} took too long to respond. Another model was tried."
        AiErrorCategory.ServerError ->
            "${provider?.displayName ?: "Provider"} is temporarily unavailable."
        AiErrorCategory.InvalidRequest ->
            "The request was rejected by ${provider?.displayName ?: "the provider"}."
        AiErrorCategory.NoEligibleModel ->
            "No eligible model is currently available for ${provider?.displayName ?: "this provider"}."
        AiErrorCategory.Cancelled -> "Request cancelled."
        AiErrorCategory.UnknownError -> "Something went wrong while contacting ${provider?.displayName ?: "the provider"}."
    }

    /** Log-safe single line. Intentionally excludes the cause stack trace and any key material. */
    override fun toString(): String =
        "AiError(category=$category, http=$httpStatus, retryAfterMs=$retryAfterMs, provider=${provider?.storageKey}, msg=$safeMessage)"

    companion object {
        fun fromThrowable(provider: ProviderId, t: Throwable): AiError {
            val isTimeout = t is java.net.SocketTimeoutException
            val isNetwork = t is java.io.IOException || t is java.net.UnknownHostException
            return AiError(
                category = when {
                    isTimeout -> AiErrorCategory.Timeout
                    isNetwork -> AiErrorCategory.NetworkUnavailable
                    else -> AiErrorCategory.UnknownError
                },
                safeMessage = t.javaClass.simpleName + ": " + (t.message ?: "").let { com.example.security.SecretRedactor.truncate(it, 160) },
                provider = provider,
                cause = t
            )
        }

        /** Maps an HTTP status onto the internal category, honouring provider error semantics. */
        fun fromHttpStatus(provider: ProviderId, status: Int, retryAfterMs: Long? = null, safeMessage: String = ""): AiError =
            AiError(
                category = when (status) {
                    401 -> AiErrorCategory.InvalidApiKey
                    402 -> AiErrorCategory.QuotaExceeded
                    403 -> AiErrorCategory.PermissionDenied
                    404 -> AiErrorCategory.ModelUnavailable
                    408 -> AiErrorCategory.Timeout
                    422 -> AiErrorCategory.InvalidRequest
                    429 -> AiErrorCategory.RateLimited
                    in 500..599 -> AiErrorCategory.ServerError
                    else -> AiErrorCategory.UnknownError
                },
                safeMessage = safeMessage,
                httpStatus = status,
                retryAfterMs = retryAfterMs,
                provider = provider
            )
    }
}
