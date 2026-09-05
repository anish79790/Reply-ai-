package com.example.llm

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

class ModelDownloadManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val _downloadState = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    val downloadState: StateFlow<ModelState> = _downloadState.asStateFlow()

    private var currentDownloadJob: Job? = null
    private var isPaused = false
    private var explicitlyImportedFile: File? = null

    fun findExistingModelFile(config: ModelConfig): File? {
        if (explicitlyImportedFile?.exists() == true && (explicitlyImportedFile?.length() ?: 0L) > 1000000L) {
            return explicitlyImportedFile
        }

        // 1. Check internal app models dir
        val internal = File(modelsDir, config.fileName)
        if (internal.exists() && internal.length() > 1000000L) {
            return internal
        }

        // Check if any other .gguf or .bin file exists in internal modelsDir
        try {
            val internalMatch = modelsDir.listFiles()?.firstOrNull { f ->
                (f.name.endsWith(".gguf", ignoreCase = true) || f.name.endsWith(".bin", ignoreCase = true)) &&
                f.length() > 50_000_000L
            }
            if (internalMatch != null) return internalMatch
        } catch (_: Exception) {}

        // 2. Check public Downloads folder and external storage
        val candidateDirs = listOfNotNull(
            try { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null },
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download"),
            context.getExternalFilesDir(null),
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        )

        for (dir in candidateDirs) {
            if (dir != null && dir.exists() && dir.isDirectory) {
                // Exact file name match
                val exact = File(dir, config.fileName)
                if (exact.exists() && exact.length() > 1000000L) {
                    return exact
                }
                // Any matching GGUF or model file > 50MB
                try {
                    val match = dir.listFiles()?.firstOrNull { f ->
                        (f.name.endsWith(".gguf", ignoreCase = true) || f.name.endsWith(".bin", ignoreCase = true)) &&
                        f.length() > 50_000_000L
                    }
                    if (match != null) {
                        return match
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    fun getModelFile(config: ModelConfig): File {
        return findExistingModelFile(config) ?: File(modelsDir, config.fileName)
    }

    fun isModelInstalled(config: ModelConfig): Boolean {
        val file = findExistingModelFile(config) ?: File(modelsDir, config.fileName)
        return file.exists() && file.length() > 1000000L // at least 1MB
    }

    fun scanDownloadsFolder(): File? {
        val candidateDirs = listOfNotNull(
            try { android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) } catch (_: Exception) { null },
            File("/storage/emulated/0/Download"),
            File("/sdcard/Download"),
            context.getExternalFilesDir(null)
        )
        for (dir in candidateDirs) {
            if (dir != null && dir.exists() && dir.isDirectory) {
                try {
                    val match = dir.listFiles()?.firstOrNull { f ->
                        (f.name.endsWith(".gguf", ignoreCase = true) || f.name.endsWith(".bin", ignoreCase = true)) &&
                        f.length() > 50_000_000L
                    }
                    if (match != null) {
                        explicitlyImportedFile = match
                        _downloadState.value = ModelState.InstalledUnloaded
                        return match
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    fun setImportedFile(file: File) {
        explicitlyImportedFile = file
        _downloadState.value = ModelState.InstalledUnloaded
    }

    fun checkInstalledStatus(config: ModelConfig) {
        if (isModelInstalled(config)) {
            _downloadState.value = ModelState.InstalledUnloaded
        } else {
            _downloadState.value = ModelState.NotInstalled
        }
    }

    fun getAvailableStorageBytes(): Long {
        return context.filesDir.freeSpace
    }

    fun startDownload(config: ModelConfig) {
        if (isModelInstalled(config)) {
            _downloadState.value = ModelState.InstalledUnloaded
            return
        }

        val requiredBytes = config.fileSizeBytes
        val availableBytes = getAvailableStorageBytes()
        if (availableBytes < requiredBytes + (50 * 1024 * 1024)) { // 50MB safety margin
            _downloadState.value = ModelState.Error(
                "Insufficient storage: Needs ${config.formattedSize}, only ${availableBytes / (1024 * 1024 * 1024)} GB available. You can switch to Gemini API in Settings for instant AI without any storage requirement!",
                canRetry = false
            )
            return
        }

        currentDownloadJob?.cancel()
        isPaused = false

        // Immediately update state so UI shows progress bar and feedback right away
        _downloadState.value = ModelState.Downloading(
            progress = 0.01f,
            downloadedBytes = 0L,
            totalBytes = config.fileSizeBytes,
            speedMbPerSec = 0f
        )

        currentDownloadJob = coroutineScope.launch(Dispatchers.IO) {
            val targetFile = getModelFile(config)
            val tempFile = File(modelsDir, "${config.fileName}.part")

            try {
                var downloadedBytes = if (tempFile.exists()) tempFile.length() else 0L

                val requestBuilder = Request.Builder()
                    .url(config.downloadUrl)

                if (downloadedBytes > 0) {
                    requestBuilder.addHeader("Range", "bytes=$downloadedBytes-")
                }

                val request = requestBuilder.build()
                val response = httpClient.newCall(request).execute()

                if (!response.isSuccessful && response.code != 206) {
                    throw IllegalStateException("Server returned HTTP ${response.code}: ${response.message}")
                }

                val body = response.body ?: throw IllegalStateException("Empty response body")
                val totalBytes = if (response.code == 206) {
                    downloadedBytes + body.contentLength()
                } else {
                    body.contentLength()
                }

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile, downloadedBytes > 0)

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int
                var lastSpeedCalcTime = System.currentTimeMillis()
                var bytesSinceLastSpeedCalc = 0L
                var currentSpeed = 0f

                inputStream.use { input ->
                    outputStream.use { output ->
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (isPaused) {
                                _downloadState.value = ModelState.Downloading(
                                    progress = downloadedBytes.toFloat() / totalBytes.coerceAtLeast(1L),
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    speedMbPerSec = 0f,
                                    isPaused = true
                                )
                                return@launch
                            }

                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            bytesSinceLastSpeedCalc += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastSpeedCalcTime
                            if (elapsed >= 1000) {
                                currentSpeed = (bytesSinceLastSpeedCalc / (1024f * 1024f)) / (elapsed / 1000f)
                                bytesSinceLastSpeedCalc = 0
                                lastSpeedCalcTime = now

                                _downloadState.value = ModelState.Downloading(
                                    progress = (downloadedBytes.toFloat() / totalBytes.coerceAtLeast(1L)).coerceIn(0f, 1f),
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = totalBytes,
                                    speedMbPerSec = currentSpeed
                                )
                            }
                        }
                    }
                }

                // Download completed, rename part file to final file
                if (tempFile.exists()) {
                    if (targetFile.exists()) targetFile.delete()
                    tempFile.renameTo(targetFile)
                }

                _downloadState.value = ModelState.InstalledUnloaded
            } catch (e: CancellationException) {
                // Cancelled
            } catch (e: Exception) {
                _downloadState.value = ModelState.Error(
                    message = "Download failed: ${e.localizedMessage ?: "Network error"}",
                    canRetry = true
                )
            }
        }
    }

    fun pauseDownload(config: ModelConfig) {
        isPaused = true
    }

    fun cancelDownload(config: ModelConfig) {
        currentDownloadJob?.cancel()
        val tempFile = File(modelsDir, "${config.fileName}.part")
        if (tempFile.exists()) tempFile.delete()
        _downloadState.value = ModelState.NotInstalled
    }

    suspend fun importLocalModelFile(uri: Uri, targetConfig: ModelConfig): Result<File> = withContext(Dispatchers.IO) {
        try {
            val targetFile = getModelFile(targetConfig)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Cannot open selected file"))

            _downloadState.value = ModelState.InstalledUnloaded
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun deleteModel(config: ModelConfig): Boolean {
        val file = getModelFile(config)
        val tempFile = File(modelsDir, "${config.fileName}.part")
        if (tempFile.exists()) tempFile.delete()
        val deleted = if (file.exists()) file.delete() else true
        _downloadState.value = ModelState.NotInstalled
        return deleted
    }
}
