package com.example.reply

import android.util.Log
import com.example.ai.AiError
import com.example.ai.AiErrorCategory
import com.example.ai.AiErrorException
import com.example.ai.AiProviderRegistry
import com.example.ai.AiTextRequest
import com.example.ai.AiTextResult
import com.example.ai.LocalAiProvider
import com.example.ai.ProviderHealthStore
import com.example.ai.ReplySuggestionParser
import com.example.ai.TaskProfile
import com.example.ai.TaskProfileAnalyzer
import com.example.conversation.ExtractedConversation
import com.example.llm.DeviceCapabilityManager
import com.example.llm.LocalLLMEngine
import com.example.prompt.PromptBuilder
import com.example.settings.AppSettings
import com.example.settings.AppSettingsRepository

/**
 * Single implementation of reply generation for all engines.
 *
 * Engine routing (exactly three user-facing engines):
 *  - Gemini  -> [AiProviderRegistry.gemini] only. Never Smart AI, Groq, xKiro or local.
 *  - Local AI-> [AiProviderRegistry.local] only. No cloud fallback of any kind.
 *  - Smart AI-> [AiProviderRegistry.smartRouter], which internally routes between Groq and xKiro.
 *
 * Prompt building stays in [PromptBuilder] and reply parsing stays in [ReplySuggestionParser],
 * so providers never duplicate either. Tone is carried entirely by the prompt and is never
 * rewritten here.
 */
class LocalReplyGenerator(
    private val promptBuilder: PromptBuilder,
    val llmEngine: LocalLLMEngine,
    private val capabilityManager: DeviceCapabilityManager,
    private val appSettingsRepository: AppSettingsRepository? = null,
    private val registry: AiProviderRegistry? = null
) : ReplyGenerator {

    override suspend fun generateReplies(
        conversation: ExtractedConversation,
        customPersona: String?
    ): Result<ReplyGenerationResult> {
        if (conversation.isEmpty && conversation.draftText.isNullOrBlank()) {
            return Result.failure(
                AiErrorException(
                    AiError(
                        category = AiErrorCategory.InvalidRequest,
                        safeMessage = "CHAT_EXTRACTION_ERROR: No conversation messages or draft text detected."
                    )
                )
            )
        }

        val settings = appSettingsRepository?.settings?.value
        val prompt = buildReplyPrompt(conversation, customPersona, settings)

        val raw = generateRaw(
            prompt = prompt,
            conversation = conversation,
            settings = settings,
            systemPrompt = toneSystemInstruction(settings, customPersona)
        )

        return raw.map { result -> finish(result, prompt) }
    }

    suspend fun executeAiCommand(
        commandInstruction: String,
        conversation: ExtractedConversation,
        customPersona: String?
    ): Result<String> {
        val settings = appSettingsRepository?.settings?.value
        val businessContext = businessContextOf(settings)
        val effectiveCommand = if (commandInstruction.isBlank()) {
            "Generate a natural, helpful reply to the latest message in this conversation."
        } else {
            commandInstruction.trim()
        }

        val prompt = promptBuilder.buildAiCommandPrompt(
            effectiveCommand,
            conversation,
            customPersona,
            businessContext
        )

        val raw = generateRaw(
            prompt = prompt,
            conversation = conversation,
            settings = settings,
            systemPrompt = toneSystemInstruction(settings, customPersona)
        )

        val result = raw.getOrNull() ?: return Result.failure(raw.exceptionOrNull()!!)

        val cleaned = result.text
            .replace(Regex("^\"|\"$"), "")
            .replace(Regex("^ai:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()

        if (cleaned.isNotBlank()) {
            appSettingsRepository?.decrementCredit()
        }
        return Result.success(cleaned)
    }

    override suspend fun generateCompletions(
        draftText: String,
        conversation: ExtractedConversation,
        customPersona: String?
    ): Result<ReplyGenerationResult> {
        val settings = appSettingsRepository?.settings?.value
        val businessSnippet = businessContextOf(settings)

        val prompt = promptBuilder.buildCompletionPrompt(
            draftText = draftText,
            conversation = conversation,
            customPersona = customPersona,
            businessContext = businessSnippet
        )

        val raw = generateRaw(
            prompt = prompt,
            conversation = conversation,
            settings = settings,
            systemPrompt = toneSystemInstruction(settings, customPersona)
        )

        return raw.map { result -> finish(result, draftText) }
    }

    // -----------------------------------------------------------------------------------------
    // Engine routing
    // -----------------------------------------------------------------------------------------

    private suspend fun generateRaw(
        prompt: String,
        conversation: ExtractedConversation,
        settings: AppSettings?,
        systemPrompt: String
    ): Result<AiTextResult> {
        val engine = AppSettings.normalizeEngine(settings?.aiEngine)
        val profile = TaskProfileAnalyzer.analyze(
            conversation = conversation,
            prompt = prompt,
            requiresFreeTier = true
        )

        val request = AiTextRequest(
            systemPrompt = systemPrompt,
            userPrompt = prompt,
            maxOutputTokens = profile.desiredOutputTokens,
            temperature = 0.7
        )

        Log.d(TAG, "generateReplies | engine=$engine | messages=${conversation.messages.size}")

        return when (engine) {
            AppSettings.ENGINE_GEMINI -> {
                // Direct Gemini call. Deliberately NOT routed through Smart AI.
                val provider = registry?.gemini
                if (provider == null) {
                    Result.failure(
                        AiErrorException(
                            AiError(
                                category = AiErrorCategory.MissingApiKey,
                                safeMessage = "GEMINI_ERROR: Gemini provider unavailable."
                            )
                        )
                    )
                } else {
                    provider.generate(request)
                }
            }

            AppSettings.ENGINE_LOCAL -> {
                // Local only: no Gemini, no Groq, no xKiro, no silent cloud fallback.
                val provider = registry?.local ?: LocalAiProvider(llmEngine, ProviderHealthStore())
                provider.generate(request)
            }

            AppSettings.ENGINE_SMART -> {
                val router = registry?.smartRouter
                when {
                    router == null -> Result.failure(
                        AiErrorException(
                            AiError(
                                category = AiErrorCategory.NoEligibleModel,
                                safeMessage = "SMART_AI_ERROR: Smart AI is unavailable."
                            )
                        )
                    )
                    // Do not silently fall back to Gemini or Local AI.
                    !registry.hasAnySmartAiProvider() -> Result.failure(
                        AiErrorException(
                            AiError(
                                category = AiErrorCategory.NoEligibleModel,
                                safeMessage = "SMART_AI_ERROR: Configure at least one Smart AI provider (Groq or xKiro) in Settings → AI Providers."
                            )
                        )
                    )
                    else -> router.generate(request, profile)
                }
            }

            else -> Result.failure(
                AiErrorException(
                    AiError(
                        category = AiErrorCategory.UnknownError,
                        safeMessage = "Unknown AI engine: $engine"
                    )
                )
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Shared prompt plumbing (unchanged behaviour)
    // -----------------------------------------------------------------------------------------

    private fun buildReplyPrompt(
        conversation: ExtractedConversation,
        customPersona: String?,
        settings: AppSettings?
    ): String {
        val maxContext = capabilityManager.maxContextMessages
        val businessContext = businessContextOf(settings)
        return promptBuilder.buildPrompt(
            conversation,
            maxContext,
            customPersona,
            businessContext,
            settings?.selectedTone ?: "Auto",
            settings?.promptStrategy ?: "gemini",
            settings?.customPrompt ?: ""
        )
    }

    private fun businessContextOf(settings: AppSettings?): String? =
        if (settings?.isBusinessContextEnabled == true && !settings.businessDescription.isNullOrBlank()) {
            "Business/Owner: ${settings.businessName}\nKnowledge & Policies: ${settings.businessDescription}"
        } else null

    /**
     * Reinforces the tone rules that already live in the prompt.
     *
     * This is additive only: it never rewrites, neutralises or "sanitises" wording, and it
     * explicitly forbids flattening romantic, flirty, playful or Hinglish conversation into
     * generic casual English.
     */
    private fun toneSystemInstruction(settings: AppSettings?, customPersona: String?): String {
        val tone = settings?.selectedTone?.takeIf { it.isNotBlank() } ?: "Auto"
        return buildString {
            append("You generate chat reply suggestions for a messaging app. ")
            append("Match the tone, register and language of the conversation exactly. ")
            append("If the conversation is romantic, flirty, playful, funny, professional, casual, ")
            append("Hindi or Hinglish, keep that tone - do not neutralise it into generic casual English. ")
            append("Never add preambles, explanations or numbering. ")
            if (tone != "Auto") append("Requested style: $tone. ")
            if (!customPersona.isNullOrBlank()) append("Persona: $customPersona. ")
            append("Output exactly three suggestions separated by |||.")
        }
    }

    // -----------------------------------------------------------------------------------------
    // Shared finishing (three-suggestion behaviour)
    // -----------------------------------------------------------------------------------------

    private fun finish(
        result: AiTextResult,
        promptUsed: String
    ): ReplyGenerationResult {
        val suggestions = ReplySuggestionParser.parse(result.text)
        if (suggestions.isEmpty()) {
            throw AiErrorException(
                AiError(
                    category = AiErrorCategory.InvalidRequest,
                    safeMessage = "INVALID_MODEL_OUTPUT: Provider returned non-parsable or empty suggestions."
                )
            )
        }
        appSettingsRepository?.decrementCredit()
        return ReplyGenerationResult(
            suggestions = suggestions,
            promptUsed = promptUsed,
            engineUsed = result.modelId,
            latencyMs = result.latencyMs
        )
    }

    /** Exposed so diagnostics can show the router's last decision. */
    fun lastRouterProfile(): TaskProfile = TaskProfile.default()

    companion object {
        private const val TAG = "LocalReplyGenerator"
    }
}
