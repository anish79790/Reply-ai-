package com.example

import com.example.conversation.ChatMessage
import com.example.conversation.ConversationParser
import com.example.conversation.DetectedLanguage
import com.example.conversation.ExtractedConversation
import com.example.conversation.ExtractionSource
import com.example.prompt.ReplyPromptBuilder
import com.example.reply.ReplyStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testLanguageDetection_hinglish() {
    val messages = listOf(
      ChatMessage(sender = "Them", text = "Bhai kal free ho kya? Movie dekhne chalte hain!", isUser = false)
    )
    val lang = ConversationParser.detectLanguage(messages)
    assertEquals(DetectedLanguage.HINGLISH, lang)
  }

  @Test
  fun testLanguageDetection_english() {
    val messages = listOf(
      ChatMessage(sender = "Them", text = "Hey, are you free tomorrow for lunch?", isUser = false)
    )
    val lang = ConversationParser.detectLanguage(messages)
    assertEquals(DetectedLanguage.ENGLISH, lang)
  }

  @Test
  fun testLanguageDetection_hindi() {
    val messages = listOf(
      ChatMessage(sender = "Them", text = "नमस्ते, आप कल फ्री हैं क्या?", isUser = false)
    )
    val lang = ConversationParser.detectLanguage(messages)
    assertEquals(DetectedLanguage.HINDI, lang)
  }

  @Test
  fun testPromptBuilder_containsRulesAndStyles() {
    val promptBuilder = ReplyPromptBuilder()
    val conv = ExtractedConversation(
      messages = listOf(
        ChatMessage(sender = "Them", text = "Kal chalte hain Goa!", isUser = false)
      ),
      latestIncomingMessage = ChatMessage(sender = "Them", text = "Kal chalte hain Goa!", isUser = false),
      activePackage = "com.instagram.android",
      source = ExtractionSource.ACCESSIBILITY,
      detectedLanguage = DetectedLanguage.HINGLISH
    )

    val prompt = promptBuilder.buildPrompt(conv, customPersona = "Humorous college student")
    
    assertTrue(prompt.contains("Humorous college student"))
    assertTrue(prompt.contains("Kal chalte hain Goa!"))
    assertTrue(prompt.contains("Option 1") || prompt.contains("Direct"))
    assertTrue(prompt.contains("Option 2") || prompt.contains("Playful"))
    assertTrue(prompt.contains("Option 3") || prompt.contains("Engaging"))
  }

  @Test
  fun testNoiseFiltering() {
    assertTrue(ConversationParser.isNoise("Active now"))
    assertTrue(ConversationParser.isNoise("10:45 AM"))
    assertTrue(ConversationParser.isNoise("Seen"))
    assertTrue(ConversationParser.isNoise("Typing..."))
    assertFalse(ConversationParser.isNoise("Bhai shaam ko milte hain."))
  }

  @Test
  fun testAiCommandPromptBuilder_includesBusinessContext() {
    val promptBuilder = ReplyPromptBuilder()
    val conv = ExtractedConversation(
      messages = listOf(
        ChatMessage(sender = "Them", text = "Do you have COD available?", isUser = false)
      ),
      latestIncomingMessage = ChatMessage(sender = "Them", text = "Do you have COD available?", isUser = false),
      activePackage = "com.whatsapp",
      source = ExtractionSource.ACCESSIBILITY,
      detectedLanguage = DetectedLanguage.ENGLISH
    )

    val prompt = promptBuilder.buildAiCommandPrompt(
      command = "answer about COD policy",
      conversation = conv,
      customPersona = "Support Executive",
      businessContext = "Store: TrendyStyles. Policy: COD available on orders above 499 only."
    )

    assertTrue(prompt.contains("answer about COD policy"))
    assertTrue(prompt.contains("Support Executive"))
    assertTrue(prompt.contains("TrendyStyles"))
    assertTrue(prompt.contains("COD available on orders above 499 only."))
  }

  @Test
  fun testSearchWordInSentence_isNotNoise() {
    // "search" as standalone keyword is UI noise
    assertTrue(ConversationParser.isNoise("search"))
    // A sentence containing search is a legitimate user message
    assertFalse(ConversationParser.isNoise("Bhai please google pe search karke bata"))
    assertFalse(ConversationParser.isNoise("Did you search for the package tracking?"))
  }

  @Test
  fun testSentenceCompletionPromptBuilder_formatsCorrectly() {
    val promptBuilder = ReplyPromptBuilder()
    val conv = ExtractedConversation(
      messages = listOf(
        ChatMessage(sender = "Them", text = "Hey are we still meeting?", isUser = false)
      ),
      latestIncomingMessage = ChatMessage(sender = "Them", text = "Hey are we still meeting?", isUser = false),
      activePackage = "com.instagram.android",
      source = ExtractionSource.ACCESSIBILITY,
      detectedLanguage = DetectedLanguage.ENGLISH
    )

    val prompt = promptBuilder.buildCompletionPrompt(
      draftText = "Yeah I am",
      conversation = conv,
      customPersona = "Casual Friend"
    )

    assertTrue(prompt.contains("Yeah I am"))
    assertTrue(prompt.contains("Casual Friend"))
    assertTrue(prompt.contains("Hey are we still meeting?"))
  }
}


