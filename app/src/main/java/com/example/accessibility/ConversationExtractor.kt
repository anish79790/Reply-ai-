package com.example.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.example.conversation.ExtractedConversation

data class ComposerState(
    val node: AccessibilityNodeInfo,
    val bounds: Rect,
    val draftText: String,
    val isDraftNotEmpty: Boolean
)

/**
 * Pluggable extractor interface for reading conversation from accessibility nodes.
 */
interface ConversationExtractor {
    fun extractFromNode(rootNode: AccessibilityNodeInfo?, targetPackage: String?, pillYPosition: Int? = null): ExtractedConversation
    fun findComposerNode(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo?
    fun getComposerState(rootNode: AccessibilityNodeInfo?): ComposerState?
    fun isChatConversationScreen(rootNode: AccessibilityNodeInfo?, targetPackage: String?): Boolean
}

