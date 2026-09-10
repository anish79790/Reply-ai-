package com.example.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AppSettingsRepository private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("replyai_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val overlay = prefs.getBoolean("is_overlay_enabled", true)
        val assistantEnabled = prefs.getBoolean("is_assistant_enabled", true)
        val selectedTone = prefs.getString("selected_tone", "Auto") ?: "Auto"
        val promptStrategy = prefs.getString("prompt_strategy", "gemini") ?: "gemini"
        val customPrompt = prefs.getString("custom_prompt", "") ?: ""
        val defaultApps = AppSettings().enabledApps
        val savedApps = prefs.getStringSet("enabled_apps", defaultApps) ?: defaultApps
        val modelId = prefs.getString("selected_model_id", "qwen_1.7b_int4") ?: "qwen_1.7b_int4"
        val engine = AppSettings.normalizeEngine(prefs.getString("ai_engine", null))
        val persona = prefs.getString("custom_persona", "") ?: ""
        val offset = prefs.getInt("bubble_offset_dp", 88)
        val unloadMins = prefs.getInt("auto_unload_mins", 3)
        val ocrFallback = prefs.getBoolean("enable_ocr_fallback", true)

        val bizName = prefs.getString("biz_name", "") ?: ""
        val bizDesc = prefs.getString("biz_desc", "") ?: ""
        val bizEnabled = prefs.getBoolean("biz_enabled", true)
        val tabDesign = prefs.getString("tab_design", "solid") ?: "solid"
        val tabFill = prefs.getLong("tab_fill", 0xFF8B5CF6)
        val tabBorder = prefs.getLong("tab_border", 0xFFC084FC)
        val tabWidth = prefs.getInt("tab_width", 4)
        val tabHeight = prefs.getInt("tab_height", 68)
        val barPos = prefs.getInt("bar_pos", 88)
        val remCredits = prefs.getInt("rem_credits", 48)
        val usedCreds = prefs.getInt("used_credits", 2)
        val autoFetch = prefs.getBoolean("auto_fetch", false)
        val autofill = prefs.getBoolean("autofill_strip", true)
        val theme = prefs.getString("ui_theme", "bento") ?: "bento"

        return AppSettings(
            isOverlayEnabled = overlay,
            isAssistantEnabled = assistantEnabled,
            selectedTone = selectedTone,
            promptStrategy = promptStrategy,
            customPrompt = customPrompt,
            enabledApps = savedApps,
            selectedModelId = modelId,
            aiEngine = engine,
            customPersona = persona,
            bubbleVerticalOffsetDp = offset,
            autoUnloadMinutes = unloadMins,
            enableOcrFallback = ocrFallback,
            businessName = bizName,
            businessDescription = bizDesc,
            isBusinessContextEnabled = bizEnabled,
            edgeTabDesign = tabDesign,
            edgeTabFillColor = tabFill,
            edgeTabBorderColor = tabBorder,
            edgeTabWidthDp = tabWidth,
            edgeTabHeightDp = tabHeight,
            barPositionAboveKeyboardDp = barPos,
            remainingCredits = remCredits,
            usedCredits = usedCreds,
            isAutoFetchEnabled = autoFetch,
            isAutofillStripEnabled = autofill,
            activeUiTheme = theme
        )
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_overlay_enabled", enabled).apply()
        _settings.value = _settings.value.copy(isOverlayEnabled = enabled)
    }

    fun setAssistantEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("is_assistant_enabled", enabled).apply()
        _settings.value = _settings.value.copy(isAssistantEnabled = enabled)
    }

    fun setSelectedTone(tone: String) {
        prefs.edit().putString("selected_tone", tone).apply()
        _settings.value = _settings.value.copy(selectedTone = tone)
    }

    fun setPromptStrategy(strategy: String) {
        prefs.edit().putString("prompt_strategy", strategy).apply()
        _settings.value = _settings.value.copy(promptStrategy = strategy)
    }

    fun setCustomPrompt(prompt: String) {
        prefs.edit().putString("custom_prompt", prompt).apply()
        _settings.value = _settings.value.copy(customPrompt = prompt)
    }

    fun setBusinessContext(name: String, description: String) {
        prefs.edit()
            .putString("biz_name", name.trim())
            .putString("biz_desc", description.trim())
            .apply()
        _settings.value = _settings.value.copy(
            businessName = name.trim(),
            businessDescription = description.trim()
        )
    }

    fun setBusinessContextEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("biz_enabled", enabled).apply()
        _settings.value = _settings.value.copy(isBusinessContextEnabled = enabled)
    }

    fun setEdgeTabDesign(design: String) {
        prefs.edit().putString("tab_design", design).apply()
        _settings.value = _settings.value.copy(edgeTabDesign = design)
    }

    fun setEdgeTabDimensions(widthDp: Int, heightDp: Int) {
        prefs.edit()
            .putInt("tab_width", widthDp)
            .putInt("tab_height", heightDp)
            .apply()
        _settings.value = _settings.value.copy(edgeTabWidthDp = widthDp, edgeTabHeightDp = heightDp)
    }

    fun setEdgeTabColors(fillColor: Long, borderColor: Long) {
        prefs.edit()
            .putLong("tab_fill", fillColor)
            .putLong("tab_border", borderColor)
            .apply()
        _settings.value = _settings.value.copy(edgeTabFillColor = fillColor, edgeTabBorderColor = borderColor)
    }

    fun setBarPositionAboveKeyboard(dp: Int) {
        prefs.edit().putInt("bar_pos", dp).apply()
        _settings.value = _settings.value.copy(barPositionAboveKeyboardDp = dp)
    }

    fun setAutoFetchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_fetch", enabled).apply()
        _settings.value = _settings.value.copy(isAutoFetchEnabled = enabled)
    }

    fun setAutofillStripEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("autofill_strip", enabled).apply()
        _settings.value = _settings.value.copy(isAutofillStripEnabled = enabled)
    }

    fun setUiTheme(theme: String) {
        prefs.edit().putString("ui_theme", theme).apply()
        _settings.value = _settings.value.copy(activeUiTheme = theme)
    }

    fun decrementCredit() {
        val current = _settings.value.remainingCredits
        if (current > 0) {
            val newRem = current - 1
            val newUsed = _settings.value.usedCredits + 1
            prefs.edit()
                .putInt("rem_credits", newRem)
                .putInt("used_credits", newUsed)
                .apply()
            _settings.value = _settings.value.copy(remainingCredits = newRem, usedCredits = newUsed)
        }
    }

    fun toggleApp(packageName: String, enabled: Boolean) {
        val current = _settings.value.enabledApps.toMutableSet()
        if (enabled) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        prefs.edit().putStringSet("enabled_apps", current).apply()
        _settings.value = _settings.value.copy(enabledApps = current)
    }

    fun setSelectedModelId(modelId: String) {
        prefs.edit().putString("selected_model_id", modelId).apply()
        _settings.value = _settings.value.copy(selectedModelId = modelId)
    }

    fun setAiEngine(engine: String) {
        val normalized = AppSettings.normalizeEngine(engine)
        prefs.edit().putString("ai_engine", normalized).apply()
        _settings.value = _settings.value.copy(aiEngine = normalized)
    }

    fun setCustomPersona(persona: String) {
        prefs.edit().putString("custom_persona", persona).apply()
        _settings.value = _settings.value.copy(customPersona = persona)
    }

    fun setBubbleVerticalOffsetDp(dp: Int) {
        prefs.edit().putInt("bubble_offset_dp", dp).apply()
        _settings.value = _settings.value.copy(bubbleVerticalOffsetDp = dp)
    }

    fun setEnableOcrFallback(enable: Boolean) {
        prefs.edit().putBoolean("enable_ocr_fallback", enable).apply()
        _settings.value = _settings.value.copy(enableOcrFallback = enable)
    }

    companion object {
        @Volatile
        private var INSTANCE: AppSettingsRepository? = null

        fun getInstance(context: Context): AppSettingsRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AppSettingsRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
