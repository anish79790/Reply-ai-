package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    val appName = context.getString(R.string.app_name)
    assertEquals("ReplyAI", appName)
  }

  @Test
  fun `test empty conversation returns error`() {
    val promptBuilder = com.example.prompt.ReplyPromptBuilder()
    val capabilityManager = object : com.example.llm.DeviceCapabilityManager {
      override val totalRamGb: Float = 8f
      override val availableRamGb: Float = 4f
      override val recommendedTier = com.example.llm.ModelTier.TIER_8GB
      override val recommendedModel = com.example.llm.ModelConfig.QWEN_1_7B_INT4
      override val maxContextMessages: Int = 10
      override val isLowRamDevice: Boolean = false
      override fun isMemorySafeForInference(requiredGb: Float): Boolean = true
      override fun getThermalStatus(): String = "NORMAL"
    }
    val llmEngine = object : com.example.llm.LocalLLMEngine {
      override val currentConfig = kotlinx.coroutines.flow.MutableStateFlow(com.example.llm.ModelConfig.QWEN_1_7B_INT4)
      override val modelState = kotlinx.coroutines.flow.MutableStateFlow<com.example.llm.ModelState>(com.example.llm.ModelState.NotInstalled)
      override fun isModelReady(): Boolean = false
      override suspend fun loadModel(): Result<Unit> = Result.success(Unit)
      override suspend fun unloadModel() {}
      override suspend fun setModelConfig(config: com.example.llm.ModelConfig) {}
      override suspend fun generate(prompt: String): Result<String> = Result.failure(IllegalStateException("Not ready"))
    }
    val generator = com.example.reply.LocalReplyGenerator(promptBuilder, llmEngine, capabilityManager)

    kotlinx.coroutines.runBlocking {
      val res = generator.generateReplies(com.example.conversation.ExtractedConversation.EMPTY, null)
      org.junit.Assert.assertTrue(res.isFailure)
      org.junit.Assert.assertTrue(res.exceptionOrNull()?.message?.contains("CHAT_EXTRACTION_ERROR") == true)
    }
  }

  @Test
  fun `test 3 replies parsed cleanly from delimiter output`() {
    val promptBuilder = com.example.prompt.ReplyPromptBuilder()
    val capabilityManager = object : com.example.llm.DeviceCapabilityManager {
      override val totalRamGb: Float = 8f
      override val availableRamGb: Float = 4f
      override val recommendedTier = com.example.llm.ModelTier.TIER_8GB
      override val recommendedModel = com.example.llm.ModelConfig.QWEN_1_7B_INT4
      override val maxContextMessages: Int = 10
      override val isLowRamDevice: Boolean = false
      override fun isMemorySafeForInference(requiredGb: Float): Boolean = true
      override fun getThermalStatus(): String = "NORMAL"
    }
    val llmEngine = object : com.example.llm.LocalLLMEngine {
      override val currentConfig = kotlinx.coroutines.flow.MutableStateFlow(com.example.llm.ModelConfig.QWEN_1_7B_INT4)
      override val modelState = kotlinx.coroutines.flow.MutableStateFlow<com.example.llm.ModelState>(com.example.llm.ModelState.Ready)
      override fun isModelReady(): Boolean = true
      override suspend fun loadModel(): Result<Unit> = Result.success(Unit)
      override suspend fun unloadModel() {}
      override suspend fun setModelConfig(config: com.example.llm.ModelConfig) {}
      override suspend fun generate(prompt: String): Result<String> = Result.success("Haan main free hoon|||Bas thodi der mein milte hain|||Nahi aaj thoda busy hoon")
    }

    val settingsRepo = object : com.example.settings.AppSettingsRepository {
      override val settings = kotlinx.coroutines.flow.MutableStateFlow(com.example.settings.AppSettings(aiEngine = "local"))
      override suspend fun updateSettings(transform: (com.example.settings.AppSettings) -> com.example.settings.AppSettings) {}
      override suspend fun decrementCredit(): Boolean = true
      override suspend fun addCredits(amount: Int) {}
      override suspend fun resetCreditsDaily() {}
    }

    val generator = com.example.reply.LocalReplyGenerator(
      promptBuilder = promptBuilder,
      llmEngine = llmEngine,
      capabilityManager = capabilityManager,
      appSettingsRepository = settingsRepo
    )

    val conversation = com.example.conversation.ExtractedConversation(
      messages = listOf(
        com.example.conversation.ChatMessage(sender = "Friend", text = "Hey, kya kar rahe ho?", isIncoming = true)
      ),
      lastIncomingMessage = com.example.conversation.ChatMessage(sender = "Friend", text = "Hey, kya kar rahe ho?", isIncoming = true),
      detectedLanguage = com.example.conversation.DetectedLanguage.HINGLISH
    )

    kotlinx.coroutines.runBlocking {
      val res = generator.generateReplies(conversation, null)
      org.junit.Assert.assertTrue(res.isSuccess)
      val replyResult = res.getOrThrow()
      org.junit.Assert.assertEquals(3, replyResult.suggestions.size)
      org.junit.Assert.assertEquals("Haan main free hoon", replyResult.suggestions[0].text)
      org.junit.Assert.assertEquals("Bas thodi der mein milte hain", replyResult.suggestions[1].text)
      org.junit.Assert.assertEquals("Nahi aaj thoda busy hoon", replyResult.suggestions[2].text)
    }
  }
}


