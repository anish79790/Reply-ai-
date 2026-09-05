package com.example.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.conversation.ChatMessage
import com.example.conversation.ConversationParser
import com.example.conversation.ExtractedConversation
import com.example.conversation.ExtractionSource
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MlKitOcrEngine : OcrEngine {

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override suspend fun extractConversationFromBitmap(
        bitmap: Bitmap,
        targetPackage: String?
    ): Result<ExtractedConversation> {
        return try {
            val width = bitmap.width
            val height = bitmap.height

            val image = InputImage.fromBitmap(bitmap, 0)

            val visionText = suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { text ->
                        if (continuation.isActive) continuation.resume(text)
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                    }
            }

            // Immediately recycle bitmap after processing to minimize memory footprint
            try {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (_: Exception) {}

            val topCutoff = (height * 0.08f).toInt() // Skip top system status bar
            val bottomCutoff = (height * 0.88f).toInt() // Skip bottom keyboard/dock area

            val extractedItems = mutableListOf<OcrLineItem>()

            for (block in visionText.textBlocks) {
                for (line in block.lines) {
                    val box = line.boundingBox ?: continue
                    if (box.top < topCutoff || box.bottom > bottomCutoff) continue

                    val text = line.text.trim()
                    if (ConversationParser.isNoise(text)) continue

                    extractedItems.add(OcrLineItem(text, box))
                }
            }

            // Sort chronologically by top Y position
            val sortedItems = extractedItems.sortedBy { it.box.top }

            val messages = mutableListOf<ChatMessage>()
            var lastText = ""

            for (item in sortedItems) {
                val clean = ConversationParser.cleanMessageText(item.text)
                if (clean.equals(lastText, ignoreCase = true)) continue
                lastText = clean

                // Horizontal center alignment: right side = Me (user), left side = Them
                val isUser = item.box.centerX() > (width * 0.52f)
                val sender = if (isUser) "Me" else "Them"

                messages.add(
                    ChatMessage(
                        sender = sender,
                        text = clean,
                        isUser = isUser,
                        bounds = item.box
                    )
                )
            }

            val latestIncoming = messages.findLast { !it.isUser }
            val detectedLang = ConversationParser.detectLanguage(messages)

            Result.success(
                ExtractedConversation(
                    messages = messages,
                    latestIncomingMessage = latestIncoming,
                    activePackage = targetPackage ?: "",
                    source = ExtractionSource.OCR,
                    detectedLanguage = detectedLang
                )
            )
        } catch (e: Exception) {
            // Ensure bitmap is cleaned up even on failure
            try {
                if (!bitmap.isRecycled) bitmap.recycle()
            } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    private data class OcrLineItem(val text: String, val box: Rect)
}
