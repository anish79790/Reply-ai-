package com.example.llm

enum class ModelTier(val title: String, val minRamGb: Int) {
    TIER_6GB("Standard 6GB RAM", 6),
    TIER_8GB("Enhanced 8GB RAM", 8),
    TIER_12GB_PLUS("Ultra 12GB+ RAM", 12)
}

data class ModelConfig(
    val id: String,
    val name: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val fileSizeBytes: Long,
    val formattedSize: String,
    val parameterCount: String,
    val quantization: String,
    val targetTier: ModelTier,
    val isDefault: Boolean = false
) {
    companion object {
        // Qwen 1.7B / 1.5B 4-bit quantized: default for 6 GB RAM devices (~1.1 GB disk & memory footprint)
        val QWEN_1_7B_INT4 = ModelConfig(
            id = "qwen_1.7b_int4",
            name = "Qwen 1.7B Instruct (4-bit)",
            description = "Optimized for 6 GB RAM devices. Fast on-device inference with low memory and thermal footprint.",
            fileName = "qwen-1.7b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen1.5-1.8B-Chat-GGUF/resolve/main/qwen1_5-1_8b-chat-q4_k_m.gguf",
            fileSizeBytes = 1180000000L, // ~1.1 GB
            formattedSize = "1.1 GB",
            parameterCount = "1.7 Billion",
            quantization = "Q4_K_M (4-bit quantized)",
            targetTier = ModelTier.TIER_6GB,
            isDefault = true
        )

        // Qwen 4B 4-bit quantized: for devices with 8 GB+ RAM (~2.5 GB memory footprint)
        val QWEN_4B_INT4 = ModelConfig(
            id = "qwen_4b_int4",
            name = "Qwen 4B Instruct (4-bit)",
            description = "Higher nuance for devices with 8 GB+ RAM. Enhanced conversational depth.",
            fileName = "qwen-4b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen1.5-4B-Chat-GGUF/resolve/main/qwen1_5-4b-chat-q4_k_m.gguf",
            fileSizeBytes = 2500000000L, // ~2.5 GB
            formattedSize = "2.5 GB",
            parameterCount = "4.0 Billion",
            quantization = "Q4_K_M (4-bit quantized)",
            targetTier = ModelTier.TIER_8GB,
            isDefault = false
        )

        val AVAILABLE_MODELS = listOf(QWEN_1_7B_INT4, QWEN_4B_INT4)
    }
}

sealed interface ModelState {
    data object NotInstalled : ModelState
    data class Downloading(
        val progress: Float, // 0.0 to 1.0
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedMbPerSec: Float,
        val isPaused: Boolean = false
    ) : ModelState
    data object InstalledUnloaded : ModelState
    data class Loading(val stage: String) : ModelState
    data object Ready : ModelState
    data class Generating(val progress: Float) : ModelState
    data class Error(val message: String, val canRetry: Boolean = true) : ModelState
}
