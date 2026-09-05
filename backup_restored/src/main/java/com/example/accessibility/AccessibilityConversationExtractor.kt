package com.example.accessibility

import android.content.res.Resources
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.conversation.ChatMessage
import com.example.conversation.ConversationParser
import com.example.conversation.ExtractedConversation
import com.example.conversation.ExtractionSource

class AccessibilityConversationExtractor : ConversationExtractor {

    private val screenWidth: Int = Resources.getSystem().displayMetrics.widthPixels

    override fun extractFromNode(
        rootNode: AccessibilityNodeInfo?,
        targetPackage: String?,
        pillYPosition: Int?
    ): ExtractedConversation {
        if (rootNode == null) {
            return ExtractedConversation.EMPTY
        }

        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val screenWidth = Resources.getSystem().displayMetrics.widthPixels

        // Find composer to delimit the message list area
        val composer = findRealChatComposer(rootNode, screenHeight)
        val composerTop = if (composer != null) {
            val cb = Rect()
            composer.getBoundsInScreen(cb)
            cb.top
        } else {
            (screenHeight * 0.88f).toInt()
        }

        // Define valid message viewport bounds (exclude top action bar and bottom composer/keyboard)
        val minY = (screenHeight * 0.08f).toInt()
        val maxY = composerTop

        val rawItems = mutableListOf<RawNodeItem>()
        traverseNodesInBounds(rootNode, rawItems, minY, maxY)

        // Filter noise, timestamps, action buttons, composer placeholders
        val filteredItems = rawItems.filter { item ->
            !ConversationParser.isNoise(item.text)
        }

        // Sort chronologically from top to bottom
        val sortedItems = filteredItems.sortedBy { it.bounds.top }

        // Deduplicate and classify sender
        var chatMessages = mutableListOf<ChatMessage>()
        var lastText = ""

        for (item in sortedItems) {
            val clean = ConversationParser.cleanMessageText(item.text)
            if (clean.equals(lastText, ignoreCase = true)) continue
            lastText = clean

            // Accurate Sender Classification:
            // Outgoing messages (Me) in Instagram/WhatsApp/Messenger are aligned to the right edge.
            // Incoming messages (Them) are aligned to the left edge.
            val isUser = when {
                item.bounds.right >= (screenWidth * 0.72f) && item.bounds.left > (screenWidth * 0.15f) -> true
                item.bounds.left <= (screenWidth * 0.35f) && item.bounds.right < (screenWidth * 0.85f) -> false
                else -> item.bounds.centerX() > (screenWidth * 0.50f)
            }

            val sender = if (isUser) "Me" else "Them"
            chatMessages.add(
                ChatMessage(
                    sender = sender,
                    text = clean,
                    isUser = isUser,
                    bounds = item.bounds
                )
            )
        }

        // Filter by pillYPosition if provided (target specific message)
        var latestIncoming: ChatMessage? = null
        if (pillYPosition != null && chatMessages.isNotEmpty()) {
            // Find the message closest to the pill's Y coordinate (center of message to top of pill + offset)
            // Pill is usually dragged directly over or slightly above/below the target message.
            val targetMessage = chatMessages.minByOrNull { Math.abs((it.bounds?.centerY() ?: 0) - pillYPosition) }
            
            if (targetMessage != null) {
                // Truncate the conversation so the target message is the LAST message
                val targetIndex = chatMessages.indexOf(targetMessage)
                if (targetIndex != -1) {
                    chatMessages = chatMessages.subList(0, targetIndex + 1).toMutableList()
                }
                latestIncoming = targetMessage
            }
        }
        
        // Fallback to last incoming if not targeted
        if (latestIncoming == null) {
            latestIncoming = chatMessages.findLast { !it.isUser }
        }
        
        val detectedLang = ConversationParser.detectLanguage(chatMessages)

        return ExtractedConversation(
            messages = chatMessages,
            latestIncomingMessage = latestIncoming,
            activePackage = targetPackage ?: rootNode.packageName?.toString() ?: "",
            source = ExtractionSource.ACCESSIBILITY,
            detectedLanguage = detectedLang
        )
    }

    private fun traverseNodesInBounds(
        node: AccessibilityNodeInfo,
        results: MutableList<RawNodeItem>,
        minY: Int,
        maxY: Int
    ) {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Only consider nodes inside the chat message list area
        if (bounds.bottom > minY && bounds.top < maxY && !node.isEditable) {
            val rawText = node.text?.toString()?.trim()
            val contentDesc = node.contentDescription?.toString()?.trim()

            var candidate = when {
                !rawText.isNullOrBlank() -> rawText
                !contentDesc.isNullOrBlank() && isLegitimateMessageDesc(contentDesc) -> contentDesc
                else -> null
            }

            if (!candidate.isNullOrBlank()) {
                // Clean Instagram accessibility prefixes like "Double-tap to like.", "Message from...", "You sent..."
                candidate = candidate.replace(Regex("^(Double-tap to like\\.?|Double tap to like\\.?|Tap and hold to react\\.?)\\s*", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^(You sent\\s*|Sent\\s*)", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^(You replied\\s*|You replied to\\s*)", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("^Message from [^:]+:\\s*", RegexOption.IGNORE_CASE), "")
                    .trim()
            }

            val screenHeight = Resources.getSystem().displayMetrics.heightPixels
            if (!candidate.isNullOrBlank() && bounds.width() > 15 && bounds.height() > 12 && bounds.height() < (screenHeight * 0.35)) {
                // Avoid adding duplicate overlapping text from both container and child
                val alreadyAdded = results.any { 
                    it.text.equals(candidate, ignoreCase = true) && Math.abs(it.bounds.centerY() - bounds.centerY()) < 50 
                }
                if (!alreadyAdded) {
                    results.add(RawNodeItem(candidate, bounds, node.viewIdResourceName))
                }
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            traverseNodesInBounds(child, results, minY, maxY)
        }
    }

    private fun isLegitimateMessageDesc(desc: String): Boolean {
        val lower = desc.lowercase()
        // Reject avatars, icons, profile photos, buttons, etc.
        if (lower.contains("profile") ||
            lower.contains("avatar") ||
            lower.contains("photo") ||
            lower.contains("picture") ||
            lower.contains("like") ||
            lower.contains("react") ||
            lower.contains("button") ||
            lower.contains("icon") ||
            lower.contains("call") ||
            lower.contains("story") ||
            lower.contains("double tap") ||
            lower.contains("double-tap") ||
            lower.contains("close") ||
            lower.contains("send") ||
            lower.contains("camera") ||
            lower.contains("gallery") ||
            lower.contains("mic") ||
            lower.contains("sticker")
        ) {
            return false
        }
        return true
    }

    override fun findComposerNode(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        return findRealChatComposer(rootNode, screenHeight)
    }

    override fun getComposerState(rootNode: AccessibilityNodeInfo?): ComposerState? {
        if (rootNode == null) return null
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val composer = findRealChatComposer(rootNode, screenHeight) ?: return null
        val bounds = Rect()
        composer.getBoundsInScreen(bounds)
        val rawText = composer.text?.toString()?.trim() ?: ""
        
        // Normalize unicode horizontal ellipsis \u2026 to "..." and clean whitespace
        val clean = rawText.replace("\u2026", "...").trim()
        val hintClean = (composer.hintText?.toString() ?: "").replace("\u2026", "...").trim()
        val descClean = (composer.contentDescription?.toString() ?: "").replace("\u2026", "...").trim()

        val isPlaceholder = clean.isBlank() ||
                clean.equals(hintClean, ignoreCase = true) ||
                clean.equals(descClean, ignoreCase = true) ||
                clean.matches(Regex("^(message|type a message|send a message|text message|start a chat|write a message|chat)[.… ]*$", RegexOption.IGNORE_CASE))

        val draft = if (isPlaceholder) "" else clean
        return ComposerState(
            node = composer,
            bounds = bounds,
            draftText = draft,
            isDraftNotEmpty = draft.isNotBlank()
        )
    }

    override fun isChatConversationScreen(rootNode: AccessibilityNodeInfo?, targetPackage: String?): Boolean {
        if (rootNode == null) return false

        val screenHeight = Resources.getSystem().displayMetrics.heightPixels

        // 1. HARD DISQUALIFIERS FOR INSTAGRAM:
        // Exclude Instagram Feed, Reels, DM inbox list, Search/Explore, and Profile
        if (targetPackage?.contains("instagram") == true) {
            val allTexts = mutableListOf<String>()
            val allDescs = mutableListOf<String>()
            collectVisibleTextsAndDescs(rootNode, allTexts, allDescs, depth = 0, maxDepth = 32)

            val lowerTexts = allTexts.map { it.lowercase() }
            val lowerDescs = allDescs.map { it.lowercase() }
            val combined = lowerTexts + lowerDescs

            // Instagram Bottom Navigation Bar items (Home, Reels, Search, Profile)
            val hasReels = combined.any { it == "reels" || it.contains("reels tab") }
            val hasHome = combined.any { it == "home" || it.contains("home tab") }
            val hasSearchExplore = combined.any { it.contains("search and explore") }
            val hasStories = combined.any { it == "your story" || it.contains("stories") }
            val hasProfile = combined.any { it.contains("profile tab") || it.contains("edit profile") }
            val hasFeedSignals = combined.any { it == "like" || it == "comment" || it == "share post" || it.contains("add a comment") }

            // If user is in the main Instagram shell (Feed, Explore, Reels, Comments), it is NOT a chat
            if (hasReels || hasHome || hasSearchExplore || hasStories || hasProfile || hasFeedSignals) {
                return false
            }

            // Instagram DM Inbox Thread List tabs / header
            val hasPrimaryTab = combined.any { it.startsWith("primary") }
            val hasGeneralTab = combined.any { it.startsWith("general") }
            val hasRequestsTab = combined.any { it.startsWith("requests") }
            if (hasPrimaryTab || hasGeneralTab || hasRequestsTab) {
                return false
            }

            // DM inbox header search / notes / channels
            if (combined.any {
                it.contains("search or ask meta ai") ||
                it.contains("ask meta ai") ||
                it.contains("your note") ||
                it.contains("leave a note") ||
                it == "messages" ||
                it == "channels" ||
                it.contains("new message") ||
                it.contains("broadcast channels")
            }) {
                return false
            }
        }

        // 2. HARD DISQUALIFIERS FOR WHATSAPP:
        if (targetPackage?.contains("whatsapp") == true) {
            val allTexts = mutableListOf<String>()
            val allDescs = mutableListOf<String>()
            collectVisibleTextsAndDescs(rootNode, allTexts, allDescs, depth = 0, maxDepth = 32)
            val combined = (allTexts + allDescs).map { it.lowercase() }

            if (combined.contains("chats") && (combined.contains("updates") || combined.contains("calls") || combined.contains("communities"))) {
                return false
            }
        }

        // 3. POSITIVE IDENTIFIER: Must have an editable message composer in the lower half of the screen
        val composer = findRealChatComposer(rootNode, screenHeight) ?: return false

        val bounds = Rect()
        composer.getBoundsInScreen(bounds)

        // Must be in the lower 75% of screen (rules out top search bars)
        if (bounds.top < screenHeight * 0.25f) {
            return false
        }

        val hint = (composer.hintText?.toString() ?: "").lowercase()
        val text = (composer.text?.toString() ?: "").lowercase()
        val desc = (composer.contentDescription?.toString() ?: "").lowercase()
        val id = (composer.viewIdResourceName ?: "").lowercase()

        val isSearchPlaceholder = text == "search" || text == "search..." || text.startsWith("search or ask") || text.startsWith("ask meta ai")
        if (hint.contains("search") || desc.contains("search") || id.contains("search") || isSearchPlaceholder ||
            hint.contains("meta ai") || desc.contains("meta ai") || id.contains("meta ai")) {
            return false
        }

        return true
    }

    private fun findRealChatComposer(node: AccessibilityNodeInfo, screenHeight: Int): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditableNodes(node, candidates)

        if (candidates.isEmpty()) return null

        val bounds = Rect()
        // Filter out search bars at top of screen
        val validCandidates = candidates.filter { cand ->
            cand.getBoundsInScreen(bounds)
            if (bounds.top < (screenHeight * 0.35f) || bounds.height() <= 15 || bounds.width() <= 40) {
                return@filter false
            }
            val hint = (cand.hintText?.toString() ?: "").lowercase()
            val text = (cand.text?.toString() ?: "").lowercase()
            val desc = (cand.contentDescription?.toString() ?: "").lowercase()
            val id = (cand.viewIdResourceName ?: "").lowercase()

            val isSearchPlaceholder = text == "search" || text == "search..." || text.startsWith("search or ask") || text.startsWith("ask meta ai")
            if (hint.contains("search") || desc.contains("search") || id.contains("search") || isSearchPlaceholder ||
                hint.contains("meta ai") || desc.contains("meta ai") || id.contains("meta ai")) {
                return@filter false
            }
            true
        }

        // Return the best match: prioritize explicitly editable nodes, then by lowest on screen
        return validCandidates.maxByOrNull { cand ->
            cand.getBoundsInScreen(bounds)
            val score = bounds.bottom
            if (cand.isEditable || cand.className?.contains("EditText", ignoreCase = true) == true) {
                score + 10000 // Heavily weight actual input fields
            } else {
                score
            }
        }
    }

    private fun isPotentialComposer(node: AccessibilityNodeInfo): Boolean {
        if (node.isEditable) return true
        if (node.className?.contains("EditText", ignoreCase = true) == true) return true

        val id = (node.viewIdResourceName ?: "").lowercase()
        if (id.contains("composer") || id.contains("entry") || id.contains("message_box") || id.contains("input")) {
            return true
        }

        val text = (node.text?.toString() ?: "").trim()
        val hint = (node.hintText?.toString() ?: "").trim()
        val desc = (node.contentDescription?.toString() ?: "").trim()

        val textStarts = text.startsWith("Message", ignoreCase = true) || text.startsWith("Type a message", ignoreCase = true) || text.startsWith("Write a message", ignoreCase = true) || text.startsWith("Start a chat", ignoreCase = true)
        val hintStarts = hint.startsWith("Message", ignoreCase = true) || hint.startsWith("Type a message", ignoreCase = true) || hint.startsWith("Write a message", ignoreCase = true)
        val descStarts = desc.startsWith("Message", ignoreCase = true) || desc.startsWith("Type a message", ignoreCase = true)

        return textStarts || hintStarts || descStarts
    }

    private fun collectEditableNodes(node: AccessibilityNodeInfo, results: MutableList<AccessibilityNodeInfo>) {
        if (isPotentialComposer(node)) {
            results.add(node)
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            collectEditableNodes(child, results)
        }
    }

    private fun collectVisibleTextsAndDescs(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        descs: MutableList<String>,
        depth: Int,
        maxDepth: Int
    ) {
        if (depth > maxDepth) return
        node.text?.toString()?.trim()?.let { if (it.isNotEmpty()) texts.add(it) }
        node.contentDescription?.toString()?.trim()?.let { if (it.isNotEmpty()) descs.add(it) }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            collectVisibleTextsAndDescs(child, texts, descs, depth + 1, maxDepth)
        }
    }

    private data class RawNodeItem(
        val text: String,
        val bounds: Rect,
        val viewId: String?
    )
}
