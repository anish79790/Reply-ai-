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
    private val llmEngine: LocalLLMEngine,
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
        val isGeminiSelected = aiEngine == "gemini" && effectiveApiKey.isNotBlank()
        val isGroqSelected = aiEngine == "groq" && !settings?.groqApiKey.isNullOrBlank()

        val startTime = System.currentTimeMillis()
        var actualEngineUsed = aiEngine

        val rawOutput = when (aiEngine) {
            "groq" -> {
                val groqKey = settings?.groqApiKey?.trim() ?: ""
                if (groqKey.isBlank()) {
                    return Result.failure(
                        IllegalStateException("Groq API key missing. Open app Settings -> AI Engine -> paste your Groq key.")
                    )
                }
                val groqResult = groqClient.generateContent(groqKey, prompt)
                if (groqResult.isSuccess) {
                    actualEngineUsed = "groq (llama 3.1)"
                    groqResult.getOrThrow()
                } else {
                    return Result.failure(
                        groqResult.exceptionOrNull() ?: IllegalStateException("Groq API request failed. Please verify your key in Settings.")
                    )
                }
            }
            "local" -> {
                val localResult = llmEngine.generate(prompt)
                if (localResult.isSuccess) {
                    actualEngineUsed = "local gguf"
                    localResult.getOrThrow()
                } else {
                    if (effectiveApiKey.isNotBlank()) {
                        val geminiResult = geminiClient.generateContent(effectiveApiKey, prompt)
                        if (geminiResult.isSuccess) {
                            actualEngineUsed = "gemini (cloud fallback)"
                            geminiResult.getOrThrow()
                        } else {
                            return Result.failure(
                                localResult.exceptionOrNull() ?: IllegalStateException("Local AI engine not ready.")
                            )
                        }
                    } else {
                        return Result.failure(
                            localResult.exceptionOrNull() ?: IllegalStateException("Local AI engine not ready. Please select Gemini or Groq in Settings.")
                        )
                    }
                }
            }
            else -> { // "gemini" or default
                if (effectiveApiKey.isBlank()) {
                    return Result.failure(
                        IllegalStateException("Gemini API key is required. Open Settings -> Gemini API Key to enter your key.")
                    )
                }
                val geminiResult = geminiClient.generateContent(effectiveApiKey, prompt)
                if (geminiResult.isSuccess) {
                    actualEngineUsed = "gemini 3.8 flash"
                    geminiResult.getOrThrow()
                } else {
                    return Result.failure(
                        geminiResult.exceptionOrNull() ?: IllegalStateException("Gemini API request failed. Check internet or key in Settings.")
                    )
                }
            }
        }
        val latencyMs = System.currentTimeMillis() - startTime

        val parsedList = parseOutputToSuggestions(rawOutput, conversation.detectedLanguage)
        if (parsedList.isEmpty()) {
            return Result.failure(IllegalStateException("AI produced empty suggestions"))
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

    private fun generateOfflineFallbackSuggestions(
        conversation: ExtractedConversation,
        tone: String,
        customPersona: String?
    ): String {
        val target = conversation.latestIncomingMessage?.text?.trim()
            ?: conversation.messages.lastOrNull()?.text?.trim()
            ?: "Hello"
        
        val isHindi = conversation.detectedLanguage == DetectedLanguage.HINDI ||
                      conversation.detectedLanguage == DetectedLanguage.HINGLISH ||
                      conversation.detectedLanguage == DetectedLanguage.MIXED ||
                      target.contains(Regex("[\\u0900-\\u097F]")) ||
                      target.contains(Regex("(?i)\\b(kya|kaise|haan|nhi|nahi|bhai|chal|karo|theek|kaha|kab|aaj|kal)\\b"))

        return if (isHindi) {
            when {
                target.contains("?") || target.contains(Regex("(?i)(kab|kaha|kaisa|kaise|kyu|kya)")) -> {
                    "1. Haan bilkul, chalte hain!\n2. Abhi thoda busy hoon, thodi der mein batata hoon.\n3. Scene bana lo, main ready hoon."
                }
                target.contains(Regex("(?i)(hi|hello|hey|kaise)")) -> {
                    "1. Badhiya bhai! Tu bata kaisa hai?\n2. All good bro! Kya chal raha hai?\n3. Sab mast! Tu suna."
                }
                else -> {
                    "1. Theek hai bhai, done.\n2. Sahi hai, baat karte hain.\n3. Okay, let me know the plan."
                }
            }
        } else {
            when {
                target.contains("?") -> {
                    "1. Sounds good, let's do it!\n2. I'm slightly tied up right now, will update in a bit.\n3. Count me in!"
                }
                target.contains(Regex("(?i)(hi|hello|hey)")) -> {
                    "1. Hey! How's everything going?\n2. Doing great! What's up?\n3. Hey there! Good to hear from you."
                }
                else -> {
                    "1. Sounds like a plan!\n2. Got it, let's connect shortly.\n3. Perfect, sounds great."
                }
            }
        }
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
        val isGeminiSelected = aiEngine == "gemini" && effectiveApiKey.isNotBlank()
        val isGroqSelected = aiEngine == "groq" && !settings?.groqApiKey.isNullOrBlank()

        val rawOutput = when (aiEngine) {
            "groq" -> {
                val groqKey = settings?.groqApiKey?.trim() ?: ""
                if (groqKey.isBlank()) {
                    return Result.failure(IllegalStateException("Groq API key is missing. Add it in Settings."))
                }
                val groqResult = groqClient.generateContent(groqKey, prompt)
                if (groqResult.isSuccess) {
                    groqResult.getOrThrow()
                } else {
                    return Result.failure(groqResult.exceptionOrNull() ?: IllegalStateException("Groq command failed"))
                }
            }
            "local" -> {
                val localRes = llmEngine.generate(prompt)
                if (localRes.isSuccess) {
                    localRes.getOrThrow()
                } else {
                    if (effectiveApiKey.isNotBlank()) {
                        val geminiResult = geminiClient.generateContent(effectiveApiKey, prompt)
                        if (geminiResult.isSuccess) geminiResult.getOrThrow() else return Result.failure(geminiResult.exceptionOrNull() ?: IllegalStateException("Command failed"))
                    } else {
                        return Result.failure(localRes.exceptionOrNull() ?: IllegalStateException("Local AI not ready"))
                    }
                }
            }
            else -> { // "gemini" or default
                if (effectiveApiKey.isBlank()) {
                    return Result.failure(IllegalStateException("Gemini API key is missing. Add it in Settings."))
                }
                val geminiResult = geminiClient.generateContent(effectiveApiKey, prompt)
                if (geminiResult.isSuccess) {
                    geminiResult.getOrThrow()
                } else {
                    return Result.failure(geminiResult.exceptionOrNull() ?: IllegalStateException("Gemini command failed"))
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
        val latestMsg = conversation.latestIncomingMessage?.text ?: ""
        val langHint = when (conversation.detectedLanguage) {
            DetectedLanguage.HINGLISH -> "Hinglish (Hindi in Roman/Latin script, casual and conversational)"
            DetectedLanguage.HINDI -> "Hindi"
            else -> "English"
        }

        val settings = appSettingsRepository?.settings?.value
        val effectiveApiKey = if (!settings?.geminiApiKey.isNullOrBlank()) {
            settings!!.geminiApiKey
        } else {
            com.example.BuildConfig.GEMINI_API_KEY
        }
        val aiEngine = settings?.aiEngine ?: "gemini"
        val isGeminiSelected = aiEngine == "gemini" && effectiveApiKey.isNotBlank()
        val isGroqSelected = aiEngine == "groq" && !settings?.groqApiKey.isNullOrBlank()

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
                    return Result.failure(IllegalStateException("Groq API key is missing. Please add your Groq key in Settings."))
                }
                val groqResult = groqClient.generateContent(groqKey, completionPrompt)
                if (groqResult.isSuccess) {
                    groqResult.getOrThrow()
                } else {
                    return Result.failure(groqResult.exceptionOrNull() ?: IllegalStateException("Groq completion failed"))
                }
            }
            "local" -> {
                val localRes = llmEngine.generate(completionPrompt)
                if (localRes.isSuccess) {
                    localRes.getOrThrow()
                } else {
                    return Result.failure(localRes.exceptionOrNull() ?: IllegalStateException("Local AI completion failed"))
                }
            }
            else -> { // "gemini" or default
                if (effectiveApiKey.isBlank()) {
                    return Result.failure(IllegalStateException("Gemini API key is missing. Please check your configuration in Settings."))
                }
                val geminiResult = geminiClient.generateContent(effectiveApiKey, completionPrompt)
                if (geminiResult.isSuccess) {
                    geminiResult.getOrThrow()
                } else {
                    return Result.failure(geminiResult.exceptionOrNull() ?: IllegalStateException("Gemini completion failed"))
                }
            }
        }
        val latencyMs = System.currentTimeMillis() - startTime

        val parsedList = parseOutputToSuggestions(rawOutput, conversation.detectedLanguage)
        if (parsedList.isEmpty()) {
            return Result.failure(IllegalStateException("No completions produced by AI"))
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
        // Many LLMs add conversational padding like "Here are your options:"
        // We need to aggressively filter and parse
        var cleanedOutput = rawOutput
            .replace(Regex("Here are.*?:", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Sure,.*?:", RegexOption.IGNORE_CASE), "")
            .trim()

        // First try splitting by the new delimiter |||
        val delimiterSplit = cleanedOutput.split("|||")
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }

        val lines = if (delimiterSplit.size >= 2) {
            delimiterSplit
        } else {
            // Fallback for numbered format or newlines
            cleanedOutput.lines()
                .map { cleanLine(it) }
                .filter { it.isNotBlank() && it.length > 2 } // ignore empty lines or single chars
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
            .replace(Regex("^(\\d+[.)\\]]|[-*•])\\s*"), "") // remove "1. ", "1)", "1]", "- ", "* "
            .replace(Regex("^\"|\"$"), "") // remove exact start/end quotes
            .replace(Regex("^'|'$"), "")
            .replace(Regex("^`|`$"), "")
            .trim()
            .trim()
    }
}
