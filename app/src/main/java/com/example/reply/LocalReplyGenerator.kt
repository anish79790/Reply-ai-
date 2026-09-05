package com.example.reply

import android.util.Log
import com.example.conversation.DetectedLanguage
import com.example.conversation.ExtractedConversation
import com.example.gemini.GeminiClient
import com.example.groq.GroqClient
import com.example.llm.DeviceCapabilityManager
import com.example.llm.LocalLLMEngine
import com.example.prompt.PromptBuilder
import com.example.settings.AppSettingsRepository

class LocalReplyGenerator(
    private val promptBuilder: PromptBuilder,
    val llmEngine: LocalLLMEngine,
    private val capabilityManager: DeviceCapabilityManager,
    private val appSettingsRepository: AppSettingsRepository? = null,
    private val geminiClient: GeminiClient = GeminiClient(),
    private val groqClient: GroqClient = GroqClient()
) : ReplyGenerator {

    companion object {
        private const val TAG = "LocalReplyGenerator"
    }

    override suspend fun generateReplies(
        conversation: ExtractedConversation,
        customPersona: String?
    ): Result<ReplyGenerationResult> {
        if (conversation.isEmpty && conversation.draftText.isNullOrBlank()) {
            Log.e(TAG, "Chat extraction failed or empty conversation")
            return Result.failure(IllegalStateException("CHAT_EXTRACTION_ERROR: No conversation messages or draft text detected."))
        }

        val maxContext = capabilityManager.maxContextMessages
        val settings = appSettingsRepository?.settings?.value
        val businessContext = if (settings?.isBusinessContextEnabled == true && !settings.businessDescription.isNullOrBlank()) {
            "Business/Owner: ${settings.businessName}\nKnowledge & Policies: ${settings.businessDescription}"
        } else null

        val tone = settings?.selectedTone ?: "Auto"
        val strategy = settings?.promptStrategy ?: "gemini"
        val customPromptText = settings?.customPrompt ?: ""

        val prompt = promptBuilder.buildPrompt(conversation, maxContext, customPersona, businessContext, tone, strategy, customPromptText)

        val effectiveApiKey = if (!settings?.geminiApiKey.isNullOrBlank()) {
            settings!!.geminiApiKey
        } else {
            com.example.BuildConfig.GEMINI_API_KEY
        }
        val aiEngine = settings?.aiEngine ?: "gemini"

        Log.d(TAG, "generateReplies started | Provider: $aiEngine | Tone: $tone | MessagesCount: ${conversation.messages.size}")

        val startTime = System.currentTimeMillis()
        val actualEngineUsed: String

        val rawOutput = when (aiEngine) {
            "groq" -> {
                val groqKey = settings?.groqApiKey?.trim() ?: ""
                if (groqKey.isBlank()) {
                    return Result.failure(
                        IllegalStateException("GROQ_ERROR: Groq API key is missing. Please enter your API key in Settings.")
                    )
                }
                Log.d(TAG, "Calling Groq provider...")
                val groqResult = groqClient.generateContent(groqKey, prompt)
                if (groqResult.isSuccess) {
                    actualEngineUsed = "Groq API (Llama)"
                    groqResult.getOrThrow()
                } else {
                    val ex = groqResult.exceptionOrNull()
                    Log.e(TAG, "Groq provider execution failed: ${ex?.message}")
                    return Result.failure(ex ?: IllegalStateException("GROQ_ERROR: Unknown failure"))
                }
            }
            "local" -> {
                Log.d(TAG, "Calling Local LLM engine...")
                val localResult = llmEngine.generate(prompt)
                if (localResult.isSuccess) {
                    actualEngineUsed = "Local LLM (GGUF)"
                    localResult.getOrThrow()
                } else {
                    val ex = localResult.exceptionOrNull()
                    Log.e(TAG, "Local LLM provider execution failed: ${ex?.message}")
                    return Result.failure(ex ?: IllegalStateException("LOCAL_LLM_ERROR: Local inference failed"))
                }
            }
            else -> { // "gemini"
                if (effectiveApiKey.isBlank()) {
                    return Result.failure(
                        IllegalStateException("GEMINI_ERROR: Gemini API key is required. Open Settings to enter your key.")
                    )
                }
                Log.d(TAG, "Calling Gemini provider...")
                val geminiResult = geminiClient.generateContent(effectiveApiKey, prompt)
                if (geminiResult.isSuccess) {
                    actualEngineUsed = "Gemini API"
                    geminiResult.getOrThrow()
                } else {
                    val ex = geminiResult.exceptionOrNull()
                    Log.e(TAG, "Gemini provider execution failed: ${ex?.message}")
                    return Result.failure(ex ?: IllegalStateException("GEMINI_ERROR: Unknown failure"))
                }
            }
        }
        val latencyMs = System.currentTimeMillis() - startTime

        val parsedList = parseOutputToSuggestions(rawOutput, conversation.detectedLanguage)
        if (parsedList.isEmpty()) {
            return Result.failure(IllegalStateException("INVALID_MODEL_OUTPUT: Provider returned non-parsable or empty suggestions."))
        }

        appSettingsRepository?.decrementCredit()

        return Result.success(
            ReplyGenerationResult(
                suggestions = parsedList,
                promptUsed = prompt,
                engineUsed = actualEngineUsed,
                latencyMs = latencyMs
            )
        )
    }

    suspend fun executeAiCommand(
        commandInstruction: String,
        conversation: ExtractedConversation,
        customPersona: String?
    ): Result<String> {
        val effectiveCommand = if (commandInstruction.isBlank()) {
            "Generate a natural, helpful reply to the latest message in this conversation."
        } else {
            commandInstruction.trim()
        }

        val settings = appSettingsRepository?.settings?.value
        val businessContext = if (settings?.isBusinessContextEnabled == true && !settings.businessDescription.isNullOrBlank()) {
            "Business/Owner: ${settings.businessName}\nKnowledge & Policies: ${settings.businessDescription}"
        } else null

        val prompt = promptBuilder.buildAiCommandPrompt(effectiveCommand, conversation, customPersona, businessContext)

        val effectiveApiKey = if (!settings?.geminiApiKey.isNullOrBlank()) {
            settings!!.geminiApiKey
        } else {
            com.example.BuildConfig.GEMINI_API_KEY
        }
        val aiEngine = settings?.aiEngine ?: "gemini"

        val rawOutput = when (aiEngine) {
            "groq" -> {
                val groqKey = settings?.groqApiKey?.trim() ?: ""
                if (groqKey.isBlank()) {
                    return Result.failure(IllegalStateException("GROQ_ERROR: Groq API key is missing. Add it in Settings."))
                }
                val groqResult = groqClient.generateContent(groqKey, prompt)
                if (groqResult.isSuccess) {
                    groqResult.getOrThrow()
                } else {
                    return Result.failure(groqResult.exceptionOrNull() ?: IllegalStateException("GROQ_ERROR: Command execution failed"))
                }
            }
            "local" -> {
                val localRes = llmEngine.generate(prompt)
                if (localRes.isSuccess) {
                    localRes.getOrThrow()
                } else {
                    return Result.failure(localRes.exceptionOrNull() ?: IllegalStateException("LOCAL_LLM_ERROR: Local command execution failed"))
                }
            }
            else -> { // "gemini"
                if (effectiveApiKey.isBlank()) {
                    return Result.failure(IllegalStateException("GEMINI_ERROR: Gemini API key is missing. Add it in Settings."))
                }
                val geminiResult = geminiClient.generateContent(effectiveApiKey, prompt)
                if (geminiResult.isSuccess) {
                    geminiResult.getOrThrow()
                } else {
                    return Result.failure(geminiResult.exceptionOrNull() ?: IllegalStateException("GEMINI_ERROR: Command execution failed"))
                }
            }
        }

        val cleaned = rawOutput
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
        val effectiveApiKey = if (!settings?.geminiApiKey.isNullOrBlank()) {
            settings!!.geminiApiKey
        } else {
            com.example.BuildConfig.GEMINI_API_KEY
        }
        val aiEngine = settings?.aiEngine ?: "gemini"

        val businessSnippet = if (settings?.isBusinessContextEnabled == true && !settings.businessDescription.isNullOrBlank()) {
            "Store/Owner: ${settings.businessName}\n${settings.businessDescription}"
        } else null

        val completionPrompt = promptBuilder.buildCompletionPrompt(
            draftText = draftText,
            conversation = conversation,
            customPersona = customPersona,
            businessContext = businessSnippet
        )

        val startTime = System.currentTimeMillis()
        val rawOutput = when (aiEngine) {
            "groq" -> {
                val groqKey = settings?.groqApiKey?.trim() ?: ""
                if (groqKey.isBlank()) {
                    return Result.failure(IllegalStateException("GROQ_ERROR: Groq API key is missing. Please add your Groq key in Settings."))
                }
                val groqResult = groqClient.generateContent(groqKey, completionPrompt)
                if (groqResult.isSuccess) {
                    groqResult.getOrThrow()
                } else {
                    return Result.failure(groqResult.exceptionOrNull() ?: IllegalStateException("GROQ_ERROR: Completion failed"))
                }
            }
            "local" -> {
                val localRes = llmEngine.generate(completionPrompt)
                if (localRes.isSuccess) {
                    localRes.getOrThrow()
                } else {
                    return Result.failure(localRes.exceptionOrNull() ?: IllegalStateException("LOCAL_LLM_ERROR: Local completion failed"))
                }
            }
            else -> { // "gemini"
                if (effectiveApiKey.isBlank()) {
                    return Result.failure(IllegalStateException("GEMINI_ERROR: Gemini API key is missing. Please check your configuration in Settings."))
                }
                val geminiResult = geminiClient.generateContent(effectiveApiKey, completionPrompt)
                if (geminiResult.isSuccess) {
                    geminiResult.getOrThrow()
                } else {
                    return Result.failure(geminiResult.exceptionOrNull() ?: IllegalStateException("GEMINI_ERROR: Completion failed"))
                }
            }
        }
        val latencyMs = System.currentTimeMillis() - startTime

        val parsedList = parseOutputToSuggestions(rawOutput, conversation.detectedLanguage)
        if (parsedList.isEmpty()) {
            return Result.failure(IllegalStateException("INVALID_MODEL_OUTPUT: No completions produced by AI"))
        }

        appSettingsRepository?.decrementCredit()

        return Result.success(
            ReplyGenerationResult(
                suggestions = parsedList,
                promptUsed = draftText,
                engineUsed = aiEngine,
                latencyMs = latencyMs
            )
        )
    }

    private fun parseOutputToSuggestions(
        rawOutput: String,
        language: DetectedLanguage
    ): List<ReplySuggestion> {
        var cleanedOutput = rawOutput
            .replace(Regex("Here are.*?:", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Sure,.*?:", RegexOption.IGNORE_CASE), "")
            .trim()

        val delimiterSplit = cleanedOutput.split("|||")
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }

        val lines = if (delimiterSplit.size >= 2) {
            delimiterSplit
        } else {
            cleanedOutput.lines()
                .map { cleanLine(it) }
                .filter { it.isNotBlank() && it.length > 2 }
        }

        if (lines.isEmpty()) {
            return emptyList()
        }

        val suggestions = mutableListOf<ReplySuggestion>()
        val styles = listOf(
            ReplyStyle.NATURAL_SAFE,
            ReplyStyle.CASUAL_FRIENDLY,
            ReplyStyle.PLAYFUL_INTERESTING
        )

        for (i in 0 until minOf(3, lines.size)) {
            val text = lines[i]
            suggestions.add(
                ReplySuggestion(
                    index = i + 1,
                    style = styles.getOrElse(i) { ReplyStyle.NATURAL_SAFE },
                    text = text
                )
            )
        }

        return suggestions
    }

    private fun cleanLine(line: String): String {
        return line
            .replace(Regex("^(\\d+[.)\\]]|[-*•])\\s*"), "")
            .replace(Regex("^\"|\"$"), "")
            .replace(Regex("^'|'$"), "")
            .replace(Regex("^`|`$"), "")
            .trim()
    }
}
