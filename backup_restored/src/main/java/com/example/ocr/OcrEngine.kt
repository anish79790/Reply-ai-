package com.example.ocr

import android.graphics.Bitmap
import com.example.conversation.ExtractedConversation

/**
 * Pluggable OCR interface for extracting conversation directly from screen bitmaps.
 */
interface OcrEngine {
    suspend fun extractConversationFromBitmap(
        bitmap: Bitmap,
        targetPackage: String? = null
    ): Result<ExtractedConversation>
}
