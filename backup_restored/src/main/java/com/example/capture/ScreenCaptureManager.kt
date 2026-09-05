package com.example.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles explicit user-consented screen capture via MediaProjection for OCR fallback.
 * Strictly adheres to privacy: captures only 1 frame, never saves to storage, and cleans up immediately.
 */
class ScreenCaptureManager(private val context: Context) {

    private val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    fun createCaptureIntent(): Intent {
        return projectionManager.createScreenCaptureIntent()
    }

    suspend fun captureSingleFrame(resultCode: Int, data: Intent): Bitmap? = withContext(Dispatchers.Default) {
        var mediaProjection: MediaProjection? = null
        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null

        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) return@withContext null

            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ReplyAI_ScreenCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                Handler(Looper.getMainLooper())
            )

            // Allow short frame settling time (100ms)
            kotlinx.coroutines.delay(100)

            var image: Image? = null
            var bitmap: Bitmap? = null

            for (attempt in 0 until 5) {
                image = imageReader.acquireLatestImage()
                if (image != null) break
                kotlinx.coroutines.delay(50)
            }

            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                val tempBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                tempBitmap.copyPixelsFromBuffer(buffer)

                // Crop exact screen dimensions if rowPadding existed
                bitmap = if (rowPadding == 0) {
                    tempBitmap
                } else {
                    val cropped = Bitmap.createBitmap(tempBitmap, 0, 0, width, height)
                    tempBitmap.recycle()
                    cropped
                }
                image.close()
            }

            bitmap
        } catch (e: Exception) {
            null
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        }
    }
}
