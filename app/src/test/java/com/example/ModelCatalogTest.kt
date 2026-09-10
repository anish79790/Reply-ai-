package com.example

import com.example.ai.ModelCandidate
import com.example.ai.ModelCatalogFilter
import com.example.ai.GroqModelParser
import com.example.ai.ProviderId
import com.example.ai.TaskProfile
import com.example.ai.XkiroModelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Model catalogue parsing and eligibility filtering (requirements 1-4). */
class ModelCatalogTest {

    private val xkiroBody = """
        {
          "data": [
            {
              "id": "openai/gpt-5.6-sol",
              "display_name": "GPT-5.6 Sol",
              "owned_by": "openai",
              "access_tier": "free",
              "context_length": 128000,
              "max_output_tokens": 8192,
              "capabilities": ["chat", "reasoning", "tools"],
              "reasoning_efforts": ["low", "medium", "high"],
              "pricing": { "input": 0.0, "output": 0.0 }
            },
            {
              "id": "anthropic/claude-fable-5.1",
              "display_name": "Claude Fable 5.1",
              "owned_by": "anthropic",
              "access_tier": "premium",
              "context_length": 200000,
              "max_output_tokens": 8192,
              "capabilities": { "chat": true, "reasoning": true },
              "pricing": { "input": 3e-6, "output": 1.5e-5 }
            },
            {
              "id": "meta/llama-3.1-8b",
              "access_tier": "paid",
              "context_length": 8192,
              "max_output_tokens": 2048,
              "capabilities": ["chat"],
              "pricing": { "input": 1e-7, "output": 1e-7 }
            },
            {
              "id": "openai/text-embedding-3-large",
              "access_tier": "free",
              "capabilities": ["embedding"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun xkiroParsing_readsAllMetadata() {
        val models = XkiroModelParser.parse(xkiroBody)
        assertEquals(4, models.size)

        val gpt = models.first { it.modelId == "openai/gpt-5.6-sol" }
        assertEquals("GPT-5.6 Sol", gpt.displayName)
        assertEquals("openai", gpt.ownedBy)
        assertEquals("free", gpt.accessTier)
        assertEquals(128000, gpt.contextLength)
        assertEquals(8192, gpt.maxOutputTokens)
        assertTrue(gpt.supportsReasoning)
        assertTrue(gpt.supportsTools)
        assertEquals(listOf("low", "medium", "high"), gpt.reasoningEfforts)
        assertEquals(0.0, gpt.outputPricePerToken!!, 0.0)
    }

    @Test
    fun xkiroParsing_preservesVendorPrefix() {
        val models = XkiroModelParser.parse(xkiroBody)
        val gpt = models.first { it.modelId == "openai/gpt-5.6-sol" }

        assertTrue("vendor prefix must be preserved", gpt.hasVendorPrefix)
        assertEquals("openai", gpt.vendor)
        assertEquals("gpt-5.6-sol", gpt.shortName)
        // The id itself is never rewritten.
        assertEquals("openai/gpt-5.6-sol", gpt.modelId)
    }

    @Test
    fun xkiroParsing_toleratesCapabilitiesAsObject() {
        val claude = XkiroModelParser.parse(xkiroBody).first { it.modelId == "anthropic/claude-fable-5.1" }
        assertTrue(claude.supportsChat)
        assertTrue(claude.supportsReasoning)
        assertFalse(claude.supportsVision)
    }

    @Test
    fun xkiroFreeTierFilter_keepsOnlyFreeChatModels() {
        val profile = TaskProfile(0.3f, 1000, 320, wantsReasoning = false, requiresFreeTier = true)
        val eligible = ModelCatalogFilter.eligible(
            candidates = XkiroModelParser.parse(xkiroBody),
            requireFreeTier = profile.requiresFreeTier,
            minContextTokens = profile.estimatedPromptTokens + profile.desiredOutputTokens
        ).let { ModelCatalogFilter.distinct(it) }

        val ids = eligible.map { it.modelId }
        assertTrue(ids.contains("openai/gpt-5.6-sol"))
        assertFalse("premium must be filtered out of the free path", ids.contains("anthropic/claude-fable-5.1"))
        assertFalse("paid must be filtered out of the free path", ids.contains("meta/llama-3.1-8b"))
        assertFalse("embedding models are not chat models", ids.contains("openai/text-embedding-3-large"))
    }

    @Test
    fun xkiroFilter_unknownTierIsNotTreatedAsPaid() {
        val noTier = listOf(
            ModelCandidate(ProviderId.XKIRO, "vendor/no-tier-model", capabilities = setOf("chat"))
        )
        val eligible = ModelCatalogFilter.eligible(noTier, requireFreeTier = true, minContextTokens = 500)
        assertEquals(1, eligible.size)
    }

    @Test
    fun groqParsing_readsActiveModelsAndSkipsInactive() {
        val body = """
            {
              "object": "list",
              "data": [
                {"id": "openai/gpt-oss-120b", "owned_by": "openai", "active": true, "context_window": 131072, "max_completion_tokens": 32768},
                {"id": "qwen/qwen3.8-27b", "owned_by": "qwen", "active": true, "context_window": 131072, "max_completion_tokens": 40960},
                {"id": "groq/compound-mini", "owned_by": "groq", "active": true, "context_window": 131072, "max_completion_tokens": 8192},
                {"id": "llama2-70b-4096", "owned_by": "meta", "active": false, "context_window": 4096, "max_completion_tokens": 4096}
              ]
            }
        """.trimIndent()

        val models = GroqModelParser.parse(body)
        assertEquals("inactive models must be skipped", 3, models.size)
        assertTrue(models.none { it.modelId == "llama2-70b-4096" })

        val oss = models.first { it.modelId == "openai/gpt-oss-120b" }
        assertEquals(131072, oss.contextLength)
        assertEquals(32768, oss.maxOutputTokens)
        assertEquals("openai", oss.ownedBy)
        assertTrue(oss.supportsChat)
        // Groq publishes no tier; it must not be assumed free.
        assertEquals(null, oss.accessTier)
        assertFalse(oss.isFreeTier)
    }

    @Test
    fun groqFilter_doesNotAssumeFreeAndRespectsContext() {
        val models = listOf(
            ModelCandidate(ProviderId.GROQ, "groq/small", contextLength = 1000, capabilities = setOf("chat")),
            ModelCandidate(ProviderId.GROQ, "groq/big", contextLength = 200000, capabilities = setOf("chat"))
        )
        // requireFreeTier cannot filter Groq (no tier metadata) - only context can.
        val eligible = ModelCatalogFilter.eligible(models, requireFreeTier = true, minContextTokens = 50_000)
        assertEquals(listOf("groq/big"), eligible.map { it.modelId })
    }

    @Test
    fun distinct_removesDuplicateIds() {
        val dupes = listOf(
            ModelCandidate(ProviderId.GROQ, "a/b"),
            ModelCandidate(ProviderId.GROQ, "a/b"),
            ModelCandidate(ProviderId.XKIRO, "a/b")
        )
        assertEquals(2, ModelCatalogFilter.distinct(dupes).size)
    }

    @Test
    fun blockedModels_excludesEmbeddingAndAudio() {
        val models = listOf(
            ModelCandidate(ProviderId.GROQ, "openai/whisper-large-v3"),
            ModelCandidate(ProviderId.GROQ, "openai/text-embedding-3-small"),
            ModelCandidate(ProviderId.GROQ, "openai/gpt-oss-20b")
        )
        val eligible = ModelCatalogFilter.eligible(models, requireFreeTier = false, minContextTokens = 100)
        assertEquals(listOf("openai/gpt-oss-20b"), eligible.map { it.modelId })
    }
}
