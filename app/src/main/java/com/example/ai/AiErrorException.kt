package com.example.ai

/**
 * Wraps an [AiError] so it can travel through [Result.failure]/[kotlin.Result] channels without
 * losing its structured category.
 *
 * The message is always the already-redacted [AiError.safeMessage], so a stack trace printed for
 * this exception can never contain an API key.
 */
class AiErrorException(val error: AiError) : RuntimeException(error.toString(), error.cause) {
    override val message: String
        get() = error.toString()
}

/** Normalises any throwable coming out of a provider into a structured [AiError]. */
fun Throwable.asAiError(fallbackProvider: ProviderId? = null): AiError =
    (this as? AiErrorException)?.error ?: AiError.fromThrowable(fallbackProvider ?: ProviderId.GEMINI, this)

fun Result<*>.aiErrorOrNull(): AiError? =
    exceptionOrNull()?.asAiError()
