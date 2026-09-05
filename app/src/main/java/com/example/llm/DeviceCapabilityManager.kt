package com.example.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

interface DeviceCapabilityManager {
    val totalRamGb: Float
    val availableRamGb: Float
    val recommendedTier: ModelTier
    val recommendedModel: ModelConfig
    val maxContextMessages: Int
    val isLowRamDevice: Boolean

    fun isMemorySafeForInference(requiredGb: Float): Boolean
    fun getThermalStatus(): String
}

class AndroidDeviceCapabilityManager(private val context: Context) : DeviceCapabilityManager {

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private fun getMemoryInfo(): ActivityManager.MemoryInfo {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo
    }

    override val totalRamGb: Float
        get() {
            val memInfo = getMemoryInfo()
            return memInfo.totalMem / (1024f * 1024f * 1024f)
        }

    override val availableRamGb: Float
        get() {
            val memInfo = getMemoryInfo()
            return memInfo.availMem / (1024f * 1024f * 1024f)
        }

    override val isLowRamDevice: Boolean
        get() = activityManager.isLowRamDevice || totalRamGb < 5.5f

    override val recommendedTier: ModelTier
        get() {
            val ram = totalRamGb
            return when {
                ram < 7.5f -> ModelTier.TIER_6GB
                ram < 11.5f -> ModelTier.TIER_8GB
                else -> ModelTier.TIER_12GB_PLUS
            }
        }

    override val recommendedModel: ModelConfig
        get() = when (recommendedTier) {
            ModelTier.TIER_6GB -> ModelConfig.QWEN_1_7B_INT4
            ModelTier.TIER_8GB, ModelTier.TIER_12GB_PLUS -> ModelConfig.QWEN_1_7B_INT4 // conservative default, user can upgrade
        }

    override val maxContextMessages: Int
        get() = when (recommendedTier) {
            ModelTier.TIER_6GB -> 15 // Aggressive 6 GB RAM optimization
            ModelTier.TIER_8GB -> 22
            ModelTier.TIER_12GB_PLUS -> 30
        }

    override fun isMemorySafeForInference(requiredGb: Float): Boolean {
        val memInfo = getMemoryInfo()
        if (memInfo.lowMemory) return false
        val freeGb = memInfo.availMem / (1024f * 1024f * 1024f)
        // Ensure at least requiredGb + 800MB buffer for the active messaging app & OS
        return freeGb >= (requiredGb + 0.8f)
    }

    override fun getThermalStatus(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Nominal (Cool)"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light Warmth"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Throttling (Severe)"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Normal"
            }
        } else {
            "Normal"
        }
    }
}
