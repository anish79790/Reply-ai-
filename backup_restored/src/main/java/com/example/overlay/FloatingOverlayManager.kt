package com.example.overlay

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.example.MainActivity
import com.example.accessibility.AccessibilityConversationExtractor
import com.example.accessibility.ComposerState
import com.example.accessibility.ConversationExtractor
import com.example.conversation.ExtractedConversation
import com.example.llm.AndroidDeviceCapabilityManager
import com.example.llm.ModelDownloadManager
import com.example.llm.QuantizedLocalLLMEngine
import com.example.prompt.ReplyPromptBuilder
import com.example.reply.LocalReplyGenerator
import com.example.reply.ReplyStyle
import com.example.reply.ReplySuggestion
import com.example.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Zinro-Grade Floating Overlay Architecture:
 * 1. Pinned Right-Edge Tab: Subtle customizable handle that stays on the right edge during active chats.
 * 2. In-Chat Zinro Assistant Side Panel: Slide-over controls for reply mode, autofill strip, memory toggle & balance.
 * 3. Keyboard-Anchored Floating AI Bar: Dynamically adjusts above the active keyboard / composer.
 *    - [ ✨ Suggest Replies ] when composer is empty
 *    - [ ✨ Complete with AI ] when user has draft text
 *    - [ ⚡ Run AI Command ] when user types `ai: ...`
 * 4. Instant Zero-Lag Auto-Dismissal: Disappears smoothly without ghosting when leaving chat or pressing Back.
 */
class FloatingOverlayManager(
    private val context: Context,
    private val conversationExtractor: ConversationExtractor = AccessibilityConversationExtractor(),
    private val onOcrFallbackRequested: (() -> Unit)? = null,
    private val onClearContextRequested: (() -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val repository = AppSettingsRepository.getInstance(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val capabilityManager = AndroidDeviceCapabilityManager(context)
    private val downloadManager = ModelDownloadManager(context, scope)
    private val llmEngine = QuantizedLocalLLMEngine(context, capabilityManager, downloadManager, scope)
    private val promptBuilder = ReplyPromptBuilder()
    private val replyGenerator = LocalReplyGenerator(
        promptBuilder = promptBuilder,
        llmEngine = llmEngine,
        capabilityManager = capabilityManager,
        appSettingsRepository = repository
    )

    // Suggestion Pill Bar (Anchored above keyboard)
    private var pillView: FrameLayout? = null
    private var pillParams: WindowManager.LayoutParams? = null

    // Right-Edge Tab (Handle to open Zinro Assistant)
    private var edgeTabView: FrameLayout? = null
    private var edgeTabParams: WindowManager.LayoutParams? = null

    // Zinro Assistant Side Panel
    private var sidePanelView: FrameLayout? = null
    private var sidePanelParams: WindowManager.LayoutParams? = null

    private var currentActivePackage: String = ""
    private var currentDraftText: String = ""
    private var isDraftNotEmpty: Boolean = false
    private var lastComposerBounds: Rect? = null
    private var isUserDragged: Boolean = false

    private var autoDismissJob: Job? = null
    private var activeGenerationJob: Job? = null

    private enum class OverlayState { COLLAPSED, GENERATING, SUGGESTIONS }
    private var overlayState: OverlayState = OverlayState.COLLAPSED

    val isGenerating: Boolean
        get() = overlayState == OverlayState.GENERATING

    private var currentRootNodeProvider: (() -> AccessibilityNodeInfo?)? = null
    private var currentCachedConversationProvider: (() -> ExtractedConversation?)? = null

    fun setRootNodeProvider(provider: () -> AccessibilityNodeInfo?) {
        currentRootNodeProvider = provider
    }

    fun setCachedConversationProvider(provider: () -> ExtractedConversation?) {
        currentCachedConversationProvider = provider
    }

    fun isAppSupportedAndEnabled(packageName: String): Boolean {
        val settings = repository.settings.value
        return settings.isOverlayEnabled && settings.enabledApps.contains(packageName)
    }

    fun updateActivePackage(packageName: String, isAppActive: Boolean = isAppSupportedAndEnabled(packageName)) {
        currentActivePackage = packageName
        val settings = repository.settings.value
        if (isAppActive && settings.isOverlayEnabled && settings.enabledApps.contains(packageName) && Settings.canDrawOverlays(context)) {
            showEdgeTab()
            if (settings.isAutofillStripEnabled) {
                showPill()
            }
        } else {
            if (!isGenerating) {
                hideAll(force = true)
            }
        }
    }

    private var lastLiveTextUpdateMs = 0L

    private var collapsedTitleView: TextView? = null

    fun updateDraftTextDirectly(text: String) {
        val draftChanged = currentDraftText != text
        currentDraftText = text
        isDraftNotEmpty = text.isNotBlank()
        lastLiveTextUpdateMs = System.currentTimeMillis()
        
        if (overlayState == OverlayState.COLLAPSED && draftChanged) {
            collapsedTitleView?.let { titleTv ->
                val currentTone = repository.settings.value.selectedTone
                val toneIcon = when {
                    currentTone.contains("Casual", true) -> "😎 "
                    currentTone.contains("Pro", true) -> "💼 "
                    currentTone.contains("Funny", true) -> "😂 "
                    currentTone.contains("Flirty", true) -> "🔥 "
                    currentTone.contains("Sarcastic", true) -> "😒 "
                    else -> "🤖 "
                }
                val isAiCommand = currentDraftText.startsWith("ai:", ignoreCase = true) || currentDraftText.startsWith("ai ", ignoreCase = true)
                val newTitle = when {
                    isAiCommand -> "⚡ Run AI Command"
                    isDraftNotEmpty -> {
                        val preview = if (currentDraftText.length > 12) currentDraftText.take(10) + "…" else currentDraftText
                        "✨ Complete: \"$preview\""
                    }
                    else -> "$toneIcon✨ Suggest Replies"
                }
                titleTv.text = newTitle
            } ?: run {
                pillView?.let { renderCollapsedPill(it) }
            }
        }
    }

    /**
     * Updates the overlay with the live composer status (bounds and user-typed draft).
     */
    fun updateComposerState(composerState: ComposerState) {
        val now = System.currentTimeMillis()
        // If we recently received a live keystroke event, trust it over the accessibility tree state
        val trustLiveText = (now - lastLiveTextUpdateMs < 1500) && isDraftNotEmpty

        val newDraftText = if (trustLiveText) currentDraftText else composerState.draftText
        val newIsDraftNotEmpty = if (trustLiveText) isDraftNotEmpty else composerState.isDraftNotEmpty

        val draftChanged = currentDraftText != newDraftText
        currentDraftText = newDraftText
        isDraftNotEmpty = newIsDraftNotEmpty
        lastComposerBounds = composerState.bounds

        val density = context.resources.displayMetrics.density
        val screenHeight = context.resources.displayMetrics.heightPixels
        val barOffsetDp = repository.settings.value.barPositionAboveKeyboardDp

        // Dynamic position adjustment: anchor right above keyboard / composer
        pillParams?.let { params ->
            if (!isUserDragged && composerState.bounds.top > screenHeight * 0.2f) {
                val barHeightPx = (44 * density).toInt()
                val targetY = (composerState.bounds.top - barHeightPx - (barOffsetDp * density).toInt())
                    .coerceIn((50 * density).toInt(), (screenHeight - 80 * density).toInt())
                if (Math.abs(params.y - targetY) > 15) {
                    params.y = targetY
                    pillView?.let { v ->
                        try {
                            windowManager.updateViewLayout(v, params)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        // If collapsed and draft state changed (empty vs typed vs ai command), update label
        if (overlayState == OverlayState.COLLAPSED && draftChanged) {
            collapsedTitleView?.let { titleTv ->
                val currentTone = repository.settings.value.selectedTone
                val toneIcon = when {
                    currentTone.contains("Casual", true) -> "😎 "
                    currentTone.contains("Pro", true) -> "💼 "
                    currentTone.contains("Funny", true) -> "😂 "
                    currentTone.contains("Flirty", true) -> "🔥 "
                    currentTone.contains("Sarcastic", true) -> "😒 "
                    else -> "🤖 "
                }
                val isAiCommand = currentDraftText.startsWith("ai:", ignoreCase = true) || currentDraftText.startsWith("ai ", ignoreCase = true)
                val newTitle = when {
                    isAiCommand -> "⚡ Run AI Command"
                    isDraftNotEmpty -> {
                        val preview = if (currentDraftText.length > 12) currentDraftText.take(10) + "…" else currentDraftText
                        "✨ Complete: \"$preview\""
                    }
                    else -> "$toneIcon✨ Suggest Replies"
                }
                titleTv.text = newTitle
            } ?: run {
                pillView?.let { renderCollapsedPill(it) }
            }
        }
    }

    // ==========================================
    // 1. RIGHT-EDGE FLOATING TAB (ZINRO HANDLE)
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    fun showEdgeTab() {
        if (!Settings.canDrawOverlays(context)) return
        if (edgeTabView != null) {
            edgeTabView?.visibility = View.VISIBLE
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenHeight = displayMetrics.heightPixels
        val settings = repository.settings.value

        val tabWidthPx = (settings.edgeTabWidthDp * density).toInt().coerceAtLeast((5 * density).toInt())
        val tabHeightPx = (settings.edgeTabHeightDp * density).toInt().coerceAtLeast((40 * density).toInt())

        edgeTabParams = WindowManager.LayoutParams(
            tabWidthPx + (10 * density).toInt(), // touch target padding
            tabHeightPx + (10 * density).toInt(),
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = (screenHeight * 0.42f).toInt()
        }

        val container = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val tabIndicator = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(tabWidthPx, tabHeightPx).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            }
            background = GradientDrawable().apply {
                when (settings.edgeTabDesign) {
                    "outline" -> {
                        setColor(0x44261E3D.toInt())
                        setStroke((2 * density).toInt(), settings.edgeTabBorderColor.toInt())
                    }
                    "wide" -> {
                        setColor(settings.edgeTabFillColor.toInt())
                        setStroke((1.5f * density).toInt(), settings.edgeTabBorderColor.toInt())
                    }
                    else -> {
                        setColor(settings.edgeTabFillColor.toInt())
                        setStroke((1 * density).toInt(), settings.edgeTabBorderColor.toInt())
                    }
                }
                cornerRadii = floatArrayOf(
                    14 * density, 14 * density, // top-left
                    0f, 0f,                     // top-right
                    0f, 0f,                     // bottom-right
                    14 * density, 14 * density  // bottom-left
                )
            }
            elevation = 12f
        }

        container.addView(tabIndicator)

        var initialY = 0
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = edgeTabParams?.y ?: 0
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dy) > 12) {
                        isDragging = true
                        val screenH = context.resources.displayMetrics.heightPixels
                        edgeTabParams?.y = (initialY + dy).coerceIn((30 * density).toInt(), screenH - (120 * density).toInt())
                        try {
                            windowManager.updateViewLayout(container, edgeTabParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        toggleSidePanel()
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, edgeTabParams)
            edgeTabView = container
        } catch (_: Exception) {
            edgeTabView = null
        }
    }

    private fun hideEdgeTab() {
        edgeTabView?.let {
            it.visibility = View.GONE
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            edgeTabView = null
        }
    }

    // ==========================================
    // 2. IN-CHAT ZINRO ASSISTANT SIDE PANEL
    // ==========================================
    fun toggleSidePanel() {
        if (sidePanelView != null) {
            hideSidePanel()
        } else {
            showSidePanel()
        }
    }

    @SuppressLint("SetTextI18n")
    fun showSidePanel() {
        if (!Settings.canDrawOverlays(context)) return
        if (sidePanelView != null) {
            sidePanelView?.visibility = View.VISIBLE
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels

        val panelWidth = (screenWidth * 0.88f).toInt().coerceAtMost((360 * density).toInt())

        sidePanelParams = WindowManager.LayoutParams(
            panelWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val container = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        val panelCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt(), (18 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xF8151026.toInt())
                cornerRadius = 24 * density
                setStroke((1.5f * density).toInt(), 0xFF7C3AED.toInt())
            }
            elevation = 30f
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (14 * density).toInt()
            }
        }

        val iconBadge = TextView(context).apply {
            text = "⚡"
            textSize = 16f
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF261947.toInt())
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), 0xFF8B5CF6.toInt())
            }
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (10 * density).toInt()
            }
        }

        val titleText = TextView(context).apply {
            text = "Zinro Assistant"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        }

        val subTitleText = TextView(context).apply {
            text = "Smart Overlay. Smarter Replies."
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11.5f
        }

        titleCol.addView(titleText)
        titleCol.addView(subTitleText)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 16f
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                hideSidePanel()
            }
        }

        header.addView(iconBadge)
        header.addView(titleCol)
        header.addView(closeBtn)
        panelCard.addView(header)

        val settings = repository.settings.value

        // Card 1: Reply Suggestions Mode (Tap - Credit Saver vs Auto Fetch)
        val modeCard = createPanelCard(density).apply {
            orientation = LinearLayout.VERTICAL
        }

        val modeHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val modeTitle = TextView(context).apply {
            text = "Reply Suggestions"
            setTextColor(0xFFF1F5F9.toInt())
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val modeSwitch = Switch(context).apply {
            isChecked = settings.isOverlayEnabled
            setOnCheckedChangeListener { _, isChecked ->
                repository.setOverlayEnabled(isChecked)
                if (isChecked) showPill() else hidePill()
            }
        }

        modeHeader.addView(modeTitle)
        modeHeader.addView(modeSwitch)
        modeCard.addView(modeHeader)

        val modePillRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (8 * density).toInt()
            }
        }

        val tapSaverPill = TextView(context).apply {
            text = "Tap - Credit Saver"
            textSize = 11.5f
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            val active = !repository.settings.value.isAutoFetchEnabled
            setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF94A3B8.toInt())
            background = GradientDrawable().apply {
                setColor(if (active) 0xFF7C3AED.toInt() else 0xFF1F1A33.toInt())
                cornerRadius = 12 * density
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (4 * density).toInt()
            }
            gravity = Gravity.CENTER
            setOnClickListener {
                repository.setAutoFetchEnabled(false)
                hideSidePanel()
                Toast.makeText(context, "Mode: Tap - Credit Saver", Toast.LENGTH_SHORT).show()
            }
        }

        val autoFetchPill = TextView(context).apply {
            text = "Auto Fetch"
            textSize = 11.5f
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            val active = repository.settings.value.isAutoFetchEnabled
            setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF94A3B8.toInt())
            background = GradientDrawable().apply {
                setColor(if (active) 0xFF7C3AED.toInt() else 0xFF1F1A33.toInt())
                cornerRadius = 12 * density
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (4 * density).toInt()
            }
            gravity = Gravity.CENTER
            setOnClickListener {
                repository.setAutoFetchEnabled(true)
                hideSidePanel()
                Toast.makeText(context, "Mode: Auto Fetch", Toast.LENGTH_SHORT).show()
            }
        }

        modePillRow.addView(tapSaverPill)
        modePillRow.addView(autoFetchPill)
        modeCard.addView(modePillRow)
        panelCard.addView(modeCard)

        // Card 2: Autofill Strip
        val autofillCard = createPanelCard(density).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val autofillInfo = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val autofillTitle = TextView(context).apply {
            text = "Autofill Strip"
            setTextColor(0xFFF1F5F9.toInt())
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val autofillSub = TextView(context).apply {
            text = "Show suggestions above chat input"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11.5f
        }

        autofillInfo.addView(autofillTitle)
        autofillInfo.addView(autofillSub)

        val autofillSwitch = Switch(context).apply {
            isChecked = settings.isAutofillStripEnabled
            setOnCheckedChangeListener { _, isChecked ->
                repository.setAutofillStripEnabled(isChecked)
                if (isChecked) showPill() else hidePill()
            }
        }

        autofillCard.addView(autofillInfo)
        autofillCard.addView(autofillSwitch)
        panelCard.addView(autofillCard)

        // Card 3: Balance & Credits
        val creditCard = createPanelCard(density).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val creditCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val creditTitle = TextView(context).apply {
            text = "Suggestions Remaining"
            setTextColor(0xFFF1F5F9.toInt())
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val creditSub = TextView(context).apply {
            text = "Live monthly balance"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11.5f
        }

        creditCol.addView(creditTitle)
        creditCol.addView(creditSub)

        val creditBadge = TextView(context).apply {
            text = "${settings.remainingCredits}"
            setTextColor(0xFF86EFAC.toInt())
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF142E1F.toInt())
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), 0xFF22C55E.toInt())
            }
        }

        creditCard.addView(creditCol)
        creditCard.addView(creditBadge)
        panelCard.addView(creditCard)

        // Card 4: Business Memory Toggle
        val bizCard = createPanelCard(density).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val bizCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val bizTitle = TextView(context).apply {
            text = "Business Memory"
            setTextColor(0xFFF1F5F9.toInt())
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val bizSub = TextView(context).apply {
            text = if (settings.businessName.isNotBlank()) "Store: ${settings.businessName}" else "Configure in settings"
            setTextColor(0xFF94A3B8.toInt())
            textSize = 11.5f
        }

        bizCol.addView(bizTitle)
        bizCol.addView(bizSub)

        val bizSwitch = Switch(context).apply {
            isChecked = settings.isBusinessContextEnabled
            setOnCheckedChangeListener { _, isChecked ->
                repository.setBusinessContextEnabled(isChecked)
                Toast.makeText(context, if (isChecked) "Business Memory Enabled" else "Business Memory Disabled", Toast.LENGTH_SHORT).show()
            }
        }

        bizCard.addView(bizCol)
        bizCard.addView(bizSwitch)
        panelCard.addView(bizCard)

        // Action Buttons: Clear memory & Open Settings
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (10 * density).toInt()
            }
        }

        val clearBtn = TextView(context).apply {
            text = "Clear Context"
            setTextColor(0xFFFCA5A5.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF28141D.toInt())
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), 0xFFEF4444.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = (6 * density).toInt()
            }
            setOnClickListener {
                hideSidePanel()
                onClearContextRequested?.invoke()
                Toast.makeText(context, "✓ Chat memory cleared!", Toast.LENGTH_SHORT).show()
            }
        }

        val settingsBtn = TextView(context).apply {
            text = "Full Settings"
            setTextColor(0xFFC084FC.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF271746.toInt())
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), 0xFF8B5CF6.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = (6 * density).toInt()
            }
            setOnClickListener {
                hideSidePanel()
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }

        actionRow.addView(clearBtn)
        actionRow.addView(settingsBtn)
        panelCard.addView(actionRow)

        container.addView(panelCard)

        try {
            windowManager.addView(container, sidePanelParams)
            sidePanelView = container
        } catch (_: Exception) {
            sidePanelView = null
        }
    }

    private fun createPanelCard(density: Float): LinearLayout {
        return LinearLayout(context).apply {
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF1E1738.toInt())
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), 0xFF35265A.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
    }

    private fun hideSidePanel() {
        sidePanelView?.let {
            it.visibility = View.GONE
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            sidePanelView = null
        }
    }

    // ==========================================
    // 3. KEYBOARD-ANCHORED SUGGESTION PILL BAR
    // ==========================================
    @SuppressLint("ClickableViewAccessibility")
    fun showPill() {
        if (!Settings.canDrawOverlays(context)) return

        pillView?.let {
            it.visibility = View.VISIBLE
            return
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val barOffsetDp = repository.settings.value.barPositionAboveKeyboardDp

        val startY = if (lastComposerBounds != null && lastComposerBounds!!.top > screenHeight * 0.2f) {
            (lastComposerBounds!!.top - (48 * density).toInt() - (barOffsetDp * density).toInt())
                .coerceAtLeast((60 * density).toInt())
        } else {
            (screenHeight * 0.60f).toInt()
        }

        val approxWidthPx = (200 * density).toInt()
        val startX = ((screenWidth - approxWidthPx) / 2).coerceAtLeast((16 * density).toInt())

        pillParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = startX
            y = startY
        }

        val container = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        renderCollapsedPill(container)

        try {
            windowManager.addView(container, pillParams)
            pillView = container
        } catch (_: Exception) {
            pillView = null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderCollapsedPill(container: FrameLayout) {
        overlayState = OverlayState.COLLAPSED
        container.removeAllViews()
        autoDismissJob?.cancel()
        autoDismissJob = null

        val density = context.resources.displayMetrics.density

        val pillLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xEE1E1838.toInt())
                cornerRadius = 22 * density
                setStroke((1.5f * density).toInt(), 0xFF8B5CF6.toInt())
            }
            elevation = 18f
        }

        val dragHandle = TextView(context).apply {
            text = "⋮⋮"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
            setPadding(0, 0, (6 * density).toInt(), 0)
        }

        val currentTone = repository.settings.value.selectedTone
        val toneIcon = when {
            currentTone.contains("Casual", true) -> "😎 "
            currentTone.contains("Pro", true) -> "💼 "
            currentTone.contains("Funny", true) -> "😂 "
            currentTone.contains("Flirty", true) -> "🔥 "
            currentTone.contains("Sarcastic", true) -> "😒 "
            else -> "🤖 "
        }

        val isAiCommand = currentDraftText.startsWith("ai:", ignoreCase = true) || currentDraftText.startsWith("ai ", ignoreCase = true)

        val actionTitle = when {
            isAiCommand -> "⚡ Run AI Command"
            isDraftNotEmpty -> "✨ Complete with AI"
            else -> "$toneIcon✨ Suggest Replies"
        }

        val title = TextView(context).apply {
            text = actionTitle
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((4 * density).toInt(), 0, 0, 0)
        }
        collapsedTitleView = title

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
            setPadding((10 * density).toInt(), 0, (2 * density).toInt(), 0)
            setOnClickListener {
                hidePill()
            }
        }

        pillLayout.addView(dragHandle)
        pillLayout.addView(title)
        pillLayout.addView(closeBtn)
        container.addView(pillLayout)

        var initialX = 0
        var initialYTouch = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        container.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = pillParams?.x ?: 0
                    initialYTouch = pillParams?.y ?: 0
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        isUserDragged = true
                        val metrics = context.resources.displayMetrics
                        pillParams?.x = (initialX + dx).coerceIn(0, metrics.widthPixels - (80 * density).toInt())
                        pillParams?.y = (initialYTouch + dy).coerceIn((30 * density).toInt(), metrics.heightPixels - (100 * density).toInt())
                        try {
                            windowManager.updateViewLayout(container, pillParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onPillActivated()
                    }
                    true
                }
                else -> false
            }
        }

        updateOverlayLayout()
    }

    private fun onPillActivated() {
        val rootNode = currentRootNodeProvider?.invoke()
        
        // Use pill center Y position to target a specific message if user dragged it
        val density = context.resources.displayMetrics.density
        val targetY = if (isUserDragged) (pillParams?.y ?: 0) + (24 * density).toInt() else null
        
        var extracted = conversationExtractor.extractFromNode(rootNode, currentActivePackage, targetY)

        if (extracted.messages.isEmpty()) {
            val cached = currentCachedConversationProvider?.invoke()
            if (cached != null && cached.messages.isNotEmpty()) {
                extracted = cached
            }
        }

        // If no message detected under pill, provide visual feedback and instant 1-tap OCR option
        if (extracted.messages.isEmpty()) {
            val container = pillView ?: return
            renderNoTextDetectedPill(container)
            return
        }

        val isAiCommand = currentDraftText.startsWith("ai:", ignoreCase = true) || currentDraftText.startsWith("ai ", ignoreCase = true)

        if (isAiCommand) {
            val command = currentDraftText.substringAfter(":").ifEmpty { currentDraftText.substringAfter(" ") }.trim()
            startAiCommandExecution(command, extracted)
        } else if (isDraftNotEmpty && currentDraftText.isNotBlank()) {
            startFloatingCompletion(currentDraftText, extracted)
        } else {
            startFloatingReplyGeneration(extracted)
        }
    }

    fun showBottomSheetWithOcrConversation(conversation: ExtractedConversation) {
        startFloatingReplyGeneration(conversation)
    }

    private fun renderNoTextDetectedPill(container: FrameLayout) {
        overlayState = OverlayState.SUGGESTIONS
        container.removeAllViews()
        val density = context.resources.displayMetrics.density

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xEE1E1838.toInt())
                cornerRadius = 22 * density
                setStroke((1.5f * density).toInt(), 0xFF8B5CF6.toInt())
            }
            elevation = 18f
        }

        val hint = TextView(context).apply {
            text = "🔍 Drag over text or"
            setTextColor(0xFFE2E8F0.toInt())
            textSize = 12f
            setPadding(0, 0, (6 * density).toInt(), 0)
        }

        val ocrBtn = TextView(context).apply {
            text = "📷 Scan Screen (OCR)"
            setTextColor(0xFFC084FC.toInt())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF2E244E.toInt())
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), 0xFF8B5CF6.toInt())
            }
            setOnClickListener {
                onOcrFallbackRequested?.invoke()
            }
        }

        val close = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
            setPadding((8 * density).toInt(), 0, (2 * density).toInt(), 0)
            setOnClickListener {
                renderCollapsedPill(container)
            }
        }

        layout.addView(hint)
        layout.addView(ocrBtn)
        layout.addView(close)
        container.addView(layout)
        updateOverlayLayout()

        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(6000L)
            withContext(Dispatchers.Main) {
                if (pillView == container) {
                    renderCollapsedPill(container)
                }
            }
        }
    }

    private fun startFloatingReplyGeneration(conversation: ExtractedConversation) {
        val container = pillView ?: return
        val density = context.resources.displayMetrics.density

        overlayState = OverlayState.GENERATING
        container.setOnTouchListener(null)
        container.removeAllViews()

        val loadingLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xEE1E1838.toInt())
                cornerRadius = 24 * density
                setStroke((2 * density).toInt(), 0xFF8B5CF6.toInt())
            }
            elevation = 18f
        }

        val progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                rightMargin = (8 * density).toInt()
            }
        }

        val targetMsg = conversation.latestIncomingMessage?.text ?: conversation.messages.lastOrNull()?.text ?: ""
        val targetSnippet = if (targetMsg.length > 20) targetMsg.take(20) + "…" else targetMsg

        val loadingText = TextView(context).apply {
            text = if (targetSnippet.isNotBlank()) "✨ Replying: \"$targetSnippet\"" else "✨ Generating replies…"
            setTextColor(0xFFC084FC.toInt())
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val cancelBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
            setPadding((12 * density).toInt(), 0, (2 * density).toInt(), 0)
            setOnClickListener {
                activeGenerationJob?.cancel()
                renderCollapsedPill(container)
            }
        }

        loadingLayout.addView(progressBar)
        loadingLayout.addView(loadingText)
        loadingLayout.addView(cancelBtn)
        container.addView(loadingLayout)

        updateOverlayLayout()

        activeGenerationJob?.cancel()
        activeGenerationJob = scope.launch {
            val customPersona = repository.settings.value.customPersona
            var result = replyGenerator.generateReplies(conversation, customPersona)
            
            // Auto-retry once seamlessly if transient failure
            if (result.isFailure) {
                delay(600L)
                result = replyGenerator.generateReplies(conversation, customPersona)
            }

            withContext(Dispatchers.Main) {
                if (pillView == container) {
                    if (result.isSuccess) {
                        val genResult = result.getOrThrow()
                        renderSuggestionsChips(container, genResult.suggestions, conversation, genResult.engineUsed, genResult.latencyMs)
                    } else {
                        renderErrorPill(container, result.exceptionOrNull()?.localizedMessage ?: "Failed", conversation)
                    }
                }
            }
        }
    }

    private fun startFloatingCompletion(draftText: String, conversation: ExtractedConversation) {
        val container = pillView ?: return
        val density = context.resources.displayMetrics.density

        overlayState = OverlayState.GENERATING
        container.setOnTouchListener(null)
        container.removeAllViews()

        val loadingLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xEE1E1838.toInt())
                cornerRadius = 24 * density
                setStroke((2 * density).toInt(), 0xFF8B5CF6.toInt())
            }
            elevation = 18f
        }

        val progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                rightMargin = (8 * density).toInt()
            }
        }

        val loadingText = TextView(context).apply {
            text = "✨ Completing with AI…"
            setTextColor(0xFFC084FC.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }

        val cancelBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
            setPadding((12 * density).toInt(), 0, (2 * density).toInt(), 0)
            setOnClickListener {
                activeGenerationJob?.cancel()
                renderCollapsedPill(container)
            }
        }

        loadingLayout.addView(progressBar)
        loadingLayout.addView(loadingText)
        loadingLayout.addView(cancelBtn)
        container.addView(loadingLayout)

        updateOverlayLayout()

        activeGenerationJob?.cancel()
        activeGenerationJob = scope.launch {
            val customPersona = repository.settings.value.customPersona
            var result = replyGenerator.generateCompletions(draftText, conversation, customPersona)

            // Auto-retry once seamlessly if transient failure
            if (result.isFailure) {
                delay(600L)
                result = replyGenerator.generateCompletions(draftText, conversation, customPersona)
            }

            withContext(Dispatchers.Main) {
                if (pillView == container) {
                    if (result.isSuccess) {
                        val suggestions = result.getOrThrow().suggestions
                        renderSuggestionsChips(container, suggestions, conversation)
                    } else {
                        val errMsg = result.exceptionOrNull()?.localizedMessage ?: "Could not complete"
                        renderErrorPill(container, errMsg, conversation)
                    }
                }
            }
        }
    }

    private fun startAiCommandExecution(command: String, conversation: ExtractedConversation) {
        val container = pillView ?: return
        val density = context.resources.displayMetrics.density

        overlayState = OverlayState.GENERATING
        container.setOnTouchListener(null)
        container.removeAllViews()

        val loadingLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xEE1E1838.toInt())
                cornerRadius = 24 * density
                setStroke((2 * density).toInt(), 0xFF8B5CF6.toInt())
            }
            elevation = 18f
        }

        val progressBar = ProgressBar(context).apply {
            layoutParams = LinearLayout.LayoutParams((18 * density).toInt(), (18 * density).toInt()).apply {
                rightMargin = (8 * density).toInt()
            }
        }

        val loadingText = TextView(context).apply {
            text = "⚡ Generating command response…"
            setTextColor(0xFFC084FC.toInt())
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }

        loadingLayout.addView(progressBar)
        loadingLayout.addView(loadingText)
        container.addView(loadingLayout)
        updateOverlayLayout()

        activeGenerationJob?.cancel()
        activeGenerationJob = scope.launch {
            val customPersona = repository.settings.value.customPersona
            val result = replyGenerator.executeAiCommand(command, conversation, customPersona)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val reply = result.getOrThrow()
                    onSuggestionSelected(reply)
                } else {
                    val errMsg = result.exceptionOrNull()?.localizedMessage ?: "Command failed"
                    renderErrorPill(container, errMsg, conversation)
                }
            }
        }
    }

    fun executeDirectAiCommand(command: String, targetNode: AccessibilityNodeInfo? = null) {
        scope.launch {
            val root = currentRootNodeProvider?.invoke()
            val conv = conversationExtractor.extractFromNode(root, currentActivePackage)
            val customPersona = repository.settings.value.customPersona
            val result = replyGenerator.executeAiCommand(command, conv, customPersona)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val response = result.getOrThrow()
                    var inserted = false
                    try {
                        val liveRoot = currentRootNodeProvider?.invoke() ?: root
                        val node = conversationExtractor.findComposerNode(liveRoot) ?: targetNode
                        if (node != null) {
                            val args = Bundle().apply {
                                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, response)
                            }
                            inserted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                    } catch (e: Exception) {
                        Log.w("FloatingOverlay", "Could not set text directly on node: ${e.message}")
                    }

                    if (inserted) {
                        Toast.makeText(context, "✨ Generated by Zinro AI!", Toast.LENGTH_SHORT).show()
                    } else {
                        val clip = ClipData.newPlainText("ReplyAI", response)
                        clipboardManager.setPrimaryClip(clip)
                        Toast.makeText(context, "✓ Copied to clipboard!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errMsg = result.exceptionOrNull()?.localizedMessage ?: "Command failed"
                    Toast.makeText(context, "⚠️ $errMsg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun renderSuggestionsChips(
        container: FrameLayout,
        suggestions: List<ReplySuggestion>,
        conversation: ExtractedConversation,
        engineUsed: String = "AI",
        latencyMs: Long = 0L
    ) {
        overlayState = OverlayState.SUGGESTIONS
        container.removeAllViews()
        val density = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels

        pillParams?.let { params ->
            params.x = (12 * density).toInt()
            try {
                windowManager.updateViewLayout(container, params)
            } catch (_: Exception) {}
        }

        val barLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xF218132B.toInt())
                cornerRadius = 24 * density
                setStroke((2 * density).toInt(), 0xFF8B5CF6.toInt())
            }
            elevation = 20f
        }

        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                (screenWidth - (74 * density).toInt()).coerceAtLeast((200 * density).toInt()),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val chipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        // Engine indicator chip
        val engineBadge = TextView(context).apply {
            val engineClean = when {
                engineUsed.contains("local", true) -> "⚡ Local"
                engineUsed.contains("groq", true) -> "⚡ Groq"
                else -> "✨ Gemini"
            }
            text = if (latencyMs > 0) "$engineClean • ${latencyMs}ms" else engineClean
            setTextColor(0xFFA78BFA.toInt())
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF221A3E.toInt())
                cornerRadius = 14 * density
                setStroke((1 * density).toInt(), 0xFF7C3AED.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (6 * density).toInt()
            }
        }
        chipsContainer.addView(engineBadge)

        for (suggestion in suggestions) {
            val chip = TextView(context).apply {
                val icon = when (suggestion.style) {
                    ReplyStyle.NATURAL_SAFE -> "💬"
                    ReplyStyle.CASUAL_FRIENDLY -> "😊"
                    ReplyStyle.PLAYFUL_INTERESTING -> "🔥"
                }
                text = "$icon ${suggestion.text}"
                setTextColor(0xFFF1F5F9.toInt())
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF2B2144.toInt())
                    cornerRadius = 16 * density
                    setStroke((1 * density).toInt(), 0xFF583F7E.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (6 * density).toInt()
                }
                setOnClickListener {
                    autoDismissJob?.cancel()
                    onSuggestionSelected(suggestion.text)
                }
            }
            chipsContainer.addView(chip)
        }

        val regenBtn = TextView(context).apply {
            text = "🔄"
            textSize = 14f
            setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF261E3D.toInt())
                cornerRadius = 16 * density
                setStroke((1 * density).toInt(), 0xFF8B5CF6.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (6 * density).toInt()
            }
            setOnClickListener {
                autoDismissJob?.cancel()
                if (isDraftNotEmpty && currentDraftText.isNotBlank()) {
                    startFloatingCompletion(currentDraftText, conversation)
                } else {
                    startFloatingReplyGeneration(conversation)
                }
            }
        }
        chipsContainer.addView(regenBtn)

        scroll.addView(chipsContainer)
        barLayout.addView(scroll)

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 14f
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                autoDismissJob?.cancel()
                renderCollapsedPill(container)
            }
        }
        barLayout.addView(closeBtn)

        container.addView(barLayout)
        updateOverlayLayout()

        // Auto-dismiss after 10s of inactivity
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(10000L)
            withContext(Dispatchers.Main) {
                if (pillView == container && overlayState == OverlayState.SUGGESTIONS) {
                    renderCollapsedPill(container)
                }
            }
        }
    }

    private fun renderErrorPill(container: FrameLayout, message: String, conversation: ExtractedConversation) {
        container.removeAllViews()
        val density = context.resources.displayMetrics.density

        val errorLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                setColor(0xFF2A1520.toInt())
                cornerRadius = 24 * density
                setStroke((1 * density).toInt(), 0xFFEF4444.toInt())
            }
            elevation = 18f
        }

        val shortMsg = when {
            message.contains("API key", ignoreCase = true) -> "⚠️ API Key missing"
            message.contains("timeout", ignoreCase = true) || message.contains("network", ignoreCase = true) || message.contains("connect", ignoreCase = true) -> "⚠️ Network error"
            else -> "⚠️ Retry"
        }

        val errText = TextView(context).apply {
            text = shortMsg
            setTextColor(0xFFFCA5A5.toInt())
            textSize = 13f
            setOnClickListener {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                if (isDraftNotEmpty && currentDraftText.isNotBlank()) {
                    startFloatingCompletion(currentDraftText, conversation)
                } else {
                    startFloatingReplyGeneration(conversation)
                }
            }
        }

        val closeBtn = TextView(context).apply {
            text = "✕"
            setTextColor(0xFF9CA3AF.toInt())
            textSize = 13f
            setPadding((10 * density).toInt(), 0, (2 * density).toInt(), 0)
            setOnClickListener {
                hidePill()
            }
        }

        errorLayout.addView(errText)
        errorLayout.addView(closeBtn)
        container.addView(errorLayout)

        updateOverlayLayout()

        // Automatically revert to collapsed pill after 4 seconds so user is never stuck
        autoDismissJob?.cancel()
        autoDismissJob = scope.launch {
            delay(4000L)
            withContext(Dispatchers.Main) {
                if (pillView == container) {
                    renderCollapsedPill(container)
                }
            }
        }
    }

    private fun updateOverlayLayout() {
        val container = pillView ?: return
        val params = pillParams ?: return
        try {
            windowManager.updateViewLayout(container, params)
        } catch (_: Exception) {}
    }

    private fun onSuggestionSelected(text: String) {
        val clip = ClipData.newPlainText("ReplyAI", text)
        clipboardManager.setPrimaryClip(clip)

        var inserted = false
        val rootNode = currentRootNodeProvider?.invoke()
        val composerNode = conversationExtractor.findComposerNode(rootNode)
        if (composerNode != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            inserted = composerNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        val feedback = if (inserted) {
            "✓ Typed into chat!"
        } else {
            "✓ Copied to clipboard!"
        }

        Toast.makeText(context, feedback, Toast.LENGTH_SHORT).show()

        pillView?.let {
            renderCollapsedPill(it)
        }
    }

    fun dismissBottomSheet() {
        hideSidePanel()
    }

    fun hidePill() {
        autoDismissJob?.cancel()
        autoDismissJob = null
        activeGenerationJob?.cancel()
        activeGenerationJob = null
        overlayState = OverlayState.COLLAPSED

        pillView?.let {
            it.visibility = View.GONE
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            pillView = null
        }
    }

    fun hideAll(force: Boolean = false) {
        if (isGenerating && !force) return
        hidePill()
        hideEdgeTab()
        hideSidePanel()
    }

    fun onDestroy() {
        hideAll(force = true)
        scope.cancel()
    }
}
