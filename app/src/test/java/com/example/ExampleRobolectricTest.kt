package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.conversation.ChatMessage
import com.example.conversation.DetectedLanguage
import com.example.conversation.ExtractedConversation
import com.example.conversation.ExtractionSource
import com.example.llm.DeviceCapabilityManager
import com.example.llm.LocalLLMEngine
import com.example.llm.ModelConfig
import com.example.llm.ModelState
import com.example.llm.ModelTier
import com.example.prompt.ReplyPromptBuilder
import com.example.reply.LocalReplyGenerator
import com.example.settings.AppSettings
import com.example.settings.AppSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("ReplyAI", context.getString(R.string.app_name))
    }

    @Test
    fun `engine values are normalised`() {
        assertEquals(AppSettings.ENGINE_GEMINI, AppSettings.normalizeEngine("gemini"))
        assertEquals(AppSettings.ENGINE_LOCAL, AppSettings.normalizeEngine("local"))
        // The legacy "groq" engine became the internal-provider Smart AI engine.
        assertEquals(AppSettings.ENGINE_SMART, AppSettings.normalizeEngine("groq"))
        assertEquals(AppSettings.ENGINE_SMART, AppSettings.normalizeEngine("smart"))
        assertEquals(AppSettings.ENGINE_GEMINI, AppSettings.normalizeEngine(null))
        assertEquals(3, AppSettings.ENGINES.size)
    }

    @Test
    fun `empty conversation returns error`() {
        val generator = LocalReplyGenerator(
            promptBuilder = ReplyPromptBuilder(),
            llmEngine = fakeEngine(""),
            capabilityManager = fakeCapabilities()
        )

        runBlocking {
            val res = generator.generateReplies(ExtractedConversation.EMPTY, null)
            assertTrue(res.isFailure)
            assertTrue(
                res.exceptionOrNull()?.message?.contains("CHAT_EXTRACTION_ERROR") == true
            )
        }
    }

    @Test
    fun `local engine output parses into three replies`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppSettingsRepository.getInstance(context)
        repository.setAiEngine(AppSettings.ENGINE_LOCAL)

        val generator = LocalReplyGenerator(
            promptBuilder = ReplyPromptBuilder(),
            llmEngine = fakeEngine("Haan main free hoon|||Bas thodi der mein milte hain|||Nahi aaj thoda busy hoon"),
            capabilityManager = fakeCapabilities(),
            appSettingsRepository = repository
        )

        val conversation = ExtractedConversation(
            messages = listOf(
                ChatMessage(sender = "Friend", text = "Hey, kya kar rahe ho?", isUser = false)
            ),
            latestIncomingMessage = ChatMessage(sender = "Friend", text = "Hey, kya kar rahe ho?", isUser = false),
            activePackage = "com.whatsapp",
            source = ExtractionSource.ACCESSIBILITY,
            detectedLanguage = DetectedLanguage.HINGLISH
        )

        runBlocking {
            val res = generator.generateReplies(conversation, null)
            assertTrue(res.isSuccess)
            val replyResult = res.getOrThrow()
            assertEquals(3, replyResult.suggestions.size)
            assertEquals("Haan main free hoon", replyResult.suggestions[0].text)
            assertEquals("Bas thodi der mein milte hain", replyResult.suggestions[1].text)
            assertEquals("Nahi aaj thoda busy hoon", replyResult.suggestions[2].text)
        }
    }

    private fun fakeEngine(output: String) = object : LocalLLMEngine {
        override val currentConfig = MutableStateFlow(ModelConfig.QWEN_1_7B_INT4)
        override val modelState = MutableStateFlow<ModelState>(ModelState.Ready)
        override val lastDiagnostic = MutableStateFlow(com.example.llm.DiagnosticReport())
        override suspend fun setModelConfig(config: ModelConfig) {}
        override suspend fun loadModel(): Result<Unit> = Result.success(Unit)
        override suspend fun unloadModel() {}
        override suspend fun generate(prompt: String): Result<String> = Result.success(output)
        override fun isModelReady(): Boolean = true
    }

    private fun fakeCapabilities() = object : DeviceCapabilityManager {
        override val totalRamGb: Float = 8f
        override val availableRamGb: Float = 4f
        override val recommendedTier: ModelTier = ModelTier.TIER_8GB
        override val recommendedModel: ModelConfig = ModelConfig.QWEN_1_7B_INT4
        override val maxContextMessages: Int = 10
        override val isLowRamDevice: Boolean = false
        override fun isMemorySafeForInference(requiredGb: Float): Boolean = true
        override fun getThermalStatus(): String = "NORMAL"
    }
}
