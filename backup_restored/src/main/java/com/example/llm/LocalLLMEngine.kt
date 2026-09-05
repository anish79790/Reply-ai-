package com.example.llm

import kotlinx.coroutines.flow.StateFlow

/**
 * High-level abstraction for the on-device quantized LLM engine.
 */
interface LocalLLMEngine {
    val modelState: StateFlow<ModelState>
    val currentConfig: StateFlow<ModelConfig>
    
    suspend fun setModelConfig(config: ModelConfig)
    suspend fun loadModel(): Result<Unit>
    suspend fun unloadModel()
    suspend fun generate(prompt: String): Result<String>
    fun isModelReady(): Boolean
}
