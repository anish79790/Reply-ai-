package com.example.ai

import android.content.Context
import com.example.llm.LocalLLMEngine
import com.example.security.ApiKeyRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the provider instances and the Smart AI router.
 *
 * Wiring rules enforced here:
 * - GEMINI is standalone and is only ever used by the "Gemini" engine.
 * - LOCAL is standalone and is only ever used by the "Local AI" engine.
 * - Smart AI may only route between GROQ and XKIRO.
 */
class AiProviderRegistry private constructor(
    context: Context,
    localEngine: LocalLLMEngine?
) {
    val keys: ApiKeyRepository = ApiKeyRepository.getInstance(context)
    val health: ProviderHealthStore = ProviderHealthStore()
    val catalog: ModelCatalogCache = ModelCatalogCache()

    val groq: GroqAiProvider = GroqAiProvider(keys, health, catalog)
    val xkiro: XkiroAiProvider = XkiroAiProvider(keys, health, catalog)
    val gemini: GeminiAiProvider = GeminiAiProvider(keys, health)

    /** Null until the local engine is attached; Local AI stays untouched until then. */
    @Volatile
    var local: LocalAiProvider? = localEngine?.let { LocalAiProvider(it, health) }
        private set

    val smartRouter: SmartModelRouter = SmartModelRouter(
        providers = listOf(xkiro, groq),
        health = health,
        catalog = catalog
    )

    fun attachLocalEngine(engine: LocalLLMEngine) {
        local = LocalAiProvider(engine, health)
    }

    /** Providers Smart AI is allowed to use, in router preference order. */
    fun smartAiProviders(): List<OpenAiCompatibleProvider> = listOf(xkiro, groq)

    /** Resolves a provider by id; used by the Settings key rows. */
    fun providerFor(id: ProviderId): AiProvider? = when (id) {
        ProviderId.GEMINI -> gemini
        ProviderId.GROQ -> groq
        ProviderId.XKIRO -> xkiro
        ProviderId.LOCAL -> local
    }

    fun configuredSmartAiProviders(): List<OpenAiCompatibleProvider> =
        smartAiProviders().filter { keys.getKey(it.id) != null }

    fun hasAnySmartAiProvider(): Boolean = configuredSmartAiProviders().isNotEmpty()

    /** Safe, key-free diagnostic summary. */
    fun diagnosticsSummary(): String = buildString {
        appendLine("Gemini configured: ${yesNo(keys.getKey(ProviderId.GEMINI) != null)}")
        appendLine("Groq configured: ${yesNo(keys.getKey(ProviderId.GROQ) != null)}")
        appendLine("xKiro configured: ${yesNo(keys.getKey(ProviderId.XKIRO) != null)}")
        append("Local model: ${local?.let { "attached" } ?: "not attached"}")
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    companion object {
        @Volatile
        private var INSTANCE: AiProviderRegistry? = null

        fun getInstance(context: Context): AiProviderRegistry =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AiProviderRegistry(context.applicationContext, null).also { INSTANCE = it }
            }
    }
}

/** Router decision stream exposed for diagnostics. */
val AiProviderRegistry.lastRun: StateFlow<SmartModelRouter.RunInfo?>
    get() = smartRouter.lastRun
