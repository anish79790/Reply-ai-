package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.example.capture.ScreenCaptureActivity
import com.example.capture.ScreenCaptureManager
import com.example.conversation.ExtractedConversation
import com.example.ocr.MlKitOcrEngine
import com.example.overlay.FloatingOverlayManager
import com.example.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReplyAIAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayManager: FloatingOverlayManager? = null
    private val ocrEngine = MlKitOcrEngine()
    private val captureManager by lazy { ScreenCaptureManager(this) }
    private val conversationExtractor = AccessibilityConversationExtractor()

    private var currentPackageName: String = ""
    private var pendingHideJob: Job? = null
    private var pendingAiCommandJob: Job? = null
    private var cachedConversation: ExtractedConversation? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isServiceRunning.value = true
        instance = this

        overlayManager = FloatingOverlayManager(
            context = this,
            conversationExtractor = conversationExtractor,
            onOcrFallbackRequested = {
                initiateOcrFallback()
            },
            onClearContextRequested = {
                cachedConversation = null
            }
        ).apply {
            setRootNodeProvider {
                getTargetAppRootNode(currentPackageName) ?: rootInActiveWindow
            }
            setCachedConversationProvider {
                cachedConversation
            }
        }

        // Listen for screen capture results from user consent activity
        serviceScope.launch {
            ScreenCaptureActivity.captureResults.collect { captureData ->
                if (captureData != null) {
                    processScreenCaptureForOcr(captureData.resultCode, captureData.data)
                }
            }
        }
    }

    fun getTargetAppRootNode(targetPackage: String): AccessibilityNodeInfo? {
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root
                    if (root != null && (targetPackage.isEmpty() || root.packageName?.toString() == targetPackage)) {
                        return root
                    }
                }
            }
        } catch (_: Exception) {}

        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() != packageName) {
            return active
        }
        return null
    }

    private fun initiateOcrFallback() {
        val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun processScreenCaptureForOcr(resultCode: Int, data: Intent) {
        serviceScope.launch {
            val bitmap = captureManager.captureSingleFrame(resultCode, data)
            if (bitmap != null) {
                val ocrResult = ocrEngine.extractConversationFromBitmap(bitmap, currentPackageName)
                if (ocrResult.isSuccess) {
                    val conversation = ocrResult.getOrThrow()
                    cachedConversation = conversation
                    overlayManager?.showBottomSheetWithOcrConversation(conversation)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 🛑 MASTER KILL SWITCH CHECK
        if (!AppSettingsRepository.getInstance(this).settings.value.isAssistantEnabled) {
            // Service is disabled via master switch, stay in sleep mode
            return
        }

        val pkgName = event.packageName?.toString() ?: return

        // Ignore events from our own app, system UI, or keyboards to prevent flicker loops
        if (shouldIgnorePackage(pkgName)) {
            return
        }

        handlePackageOrContentChanged(pkgName, event)
    }

    private fun shouldIgnorePackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == packageName) return true
        if (pkg == "android" || pkg == "com.android.systemui") return true
        if (pkg.contains("ScreenCaptureActivity", ignoreCase = true)) return true

        val lower = pkg.lowercase()
        // Ignore screen recorders, smart capture, screenshot tools, and system edge panels
        if (lower.contains("screenrecorder") ||
            lower.contains("screen_recorder") ||
            lower.contains("smartcapture") ||
            lower.contains("screencapture") ||
            lower.contains("recorder") ||
            lower.contains("cocktailbarservice") ||
            lower.contains("screenshot") ||
            lower.contains("systemui") ||
            lower.contains("floating") ||
            lower.contains("quickconnect")) {
            return true
        }

        // Filter out keyboard / IME packages so opening the keyboard in chat doesn't dismiss or flicker
        return isKeyboardPackage(pkg)
    }

    private fun isKeyboardPackage(pkg: String): Boolean {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            val enabledImePackages = imm?.enabledInputMethodList?.map { it.packageName }
            if (enabledImePackages?.contains(pkg) == true) return true
        } catch (_: Exception) {}

        val lower = pkg.lowercase()
        return lower.contains("inputmethod") ||
                lower.contains("honeyboard") ||
                lower.contains("swiftkey") ||
                lower.contains("keyboard") ||
                lower.contains("gboard")
    }

    private fun isSystemOrImePackage(pkg: String): Boolean {
        if (pkg.isBlank()) return true
        if (pkg == "android" || pkg == "com.android.systemui" || pkg == packageName) return true
        return shouldIgnorePackage(pkg) || isKeyboardPackage(pkg)
    }

    private var lastScanTimeMs = 0L

    private fun handlePackageOrContentChanged(pkgName: String, event: AccessibilityEvent) {
        val manager = overlayManager ?: return

        // Always check foreground window package to avoid false switching caused by system overlays
        val activeWindowPkg = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }
        val targetPkg = if (manager.isAppSupportedAndEnabled(activeWindowPkg)) {
            activeWindowPkg
        } else {
            pkgName
        }
        val isTargetApp = manager.isAppSupportedAndEnabled(targetPkg)

        val isSystemOrIme = isSystemOrImePackage(targetPkg) || isSystemOrImePackage(pkgName)

        // Instant dismiss if user switched away to launcher/home/another app on window state change
        val isWindowStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                                 event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (!isTargetApp) {
            // NEVER hide or cancel if overlay is actively generating or user is in keyboard/system UI
            if (manager.isGenerating || isSystemOrIme) {
                return
            }

            if (isWindowStateChange) {
                pendingHideJob?.cancel()
                pendingHideJob = serviceScope.launch {
                    delay(350)
                    val currentActive = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                    if (currentActive != null && !manager.isAppSupportedAndEnabled(currentActive) && !isSystemOrImePackage(currentActive)) {
                        if (!manager.isGenerating) {
                            cachedConversation = null
                            manager.hideAll(force = false)
                        }
                    }
                }
            } else if (!manager.isGenerating) {
                pendingHideJob?.cancel()
                pendingHideJob = serviceScope.launch {
                    delay(250)
                    val currentActive = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                    if (currentActive != null && !manager.isAppSupportedAndEnabled(currentActive) && !isSystemOrImePackage(currentActive)) {
                        if (!manager.isGenerating) {
                            cachedConversation = null
                            manager.hideAll(force = false)
                        }
                    }
                }
            }
            return
        }

        currentPackageName = targetPkg
        val root = getTargetAppRootNode(targetPkg) ?: event.source ?: rootInActiveWindow
        
        // DEBOUNCE: Don't traverse the massive Instagram tree on every single millisecond pixel change
        val now = System.currentTimeMillis()
        if (now - lastScanTimeMs < 400 && event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            return
        }
        lastScanTimeMs = now

        // STRICT FILTER: Only show floating pill if actively inside a chat conversation!
        // If user is in Reels, Feed, Profile, or Explore, the overlay is hidden.
        val inChat = conversationExtractor.isChatConversationScreen(root, targetPkg)

        if (inChat) {
            pendingHideJob?.cancel()
            pendingHideJob = null

            // In-Chat ai: command trigger and live typing
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                var textStr = event.text.joinToString("").trim()
                if (event.source?.className?.contains("EditText") == true || event.source?.isEditable == true) {
                    textStr = event.source?.text?.toString()?.trim() ?: textStr
                }
                
                // Immediately push to UI for Smart Typing Assistant
                manager.updateDraftTextDirectly(textStr)

                if (textStr.startsWith("ai:", ignoreCase = true) || textStr.startsWith("ai ", ignoreCase = true)) {
                    val cmd = textStr.substringAfter(":").ifEmpty { textStr.substringAfter(" ") }.trim()
                    if (cmd.length >= 3) {
                        pendingAiCommandJob?.cancel()
                        pendingAiCommandJob = serviceScope.launch {
                            delay(700L) // Wait for user pause in typing
                            manager.executeDirectAiCommand(cmd)
                        }
                    }
                }
            }

            // Pre-extract and cache conversation continuously
            if (root != null) {
                val conv = conversationExtractor.extractFromNode(root, targetPkg)
                if (conv.messages.isNotEmpty()) {
                    cachedConversation = conv
                }
            }

            manager.updateActivePackage(targetPkg, isAppActive = true)

            // Pass live composer bounds and typing state
            val composerState = conversationExtractor.getComposerState(root)
            if (composerState != null) {
                manager.updateComposerState(composerState)
            }
        } else {
            // Outside chat (e.g. user pressed Back to Inbox list, Reels, Feed, Profile)
            pendingHideJob?.cancel()
            pendingHideJob = null
            cachedConversation = null
            if (!manager.isGenerating) {
                manager.hideAll(force = true)
            }
        }
    }

    override fun onInterrupt() {
        overlayManager?.hideAll()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        _isServiceRunning.value = false
        instance = null
        pendingHideJob?.cancel()
        pendingAiCommandJob?.cancel()
        overlayManager?.onDestroy()
        overlayManager = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isServiceRunning.value = false
        instance = null
        pendingHideJob?.cancel()
        pendingAiCommandJob?.cancel()
        overlayManager?.onDestroy()
        overlayManager = null
        serviceScope.cancel()
    }

    companion object {
        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        var instance: ReplyAIAccessibilityService? = null
            private set
    }
}

