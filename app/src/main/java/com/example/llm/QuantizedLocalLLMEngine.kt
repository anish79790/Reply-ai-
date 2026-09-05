package com.example.llm

import android.content.Context
import android.os.Build
import android.util.Log
import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.loader.LlamaLoader
import net.ladenthin.llama.parameters.InferenceParameters
import net.ladenthin.llama.parameters.ModelParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticReport(
    val stage: String = "IDLE",
    val nativeLibraryLoaded: Boolean = false,
    val nativeLibraryMethod: String? = null,
    val nativeLibraryError: String? = null,
    val modelPath: String = "",
    val modelExists: Boolean = false,
    val modelSizeMb: Long = 0L,
    val ggufHeaderValid: Boolean = false,
    val modelLoaded: Boolean = false,
    val contextCreated: Boolean = false,
    val tokenizeSuccess: Boolean = false,
    val inputTokens: Int = 0,
    val inferenceStarted: Boolean = false,
    val inferenceSuccess: Boolean = false,
    val generatedTokens: Int = 0,
    val generationTimeMs: Long = 0L,
    val rawOutput: String = "",
    val availableMemoryBeforeLoadGb: Float = 0f,
    val totalRamGb: Float = 0f,
    val deviceAbi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    val exceptionClass: String? = null,
    val exceptionMessage: String? = null,
    val causeChain: String? = null,
    val nativeError: String? = null,
    val stackTrace: String? = null,
    val logs: List<String> = emptyList()
)

class QuantizedLocalLLMEngine(
    private val context: Context,
    private val capabilityManager: DeviceCapabilityManager,
    private val downloadManager: ModelDownloadManager,
    private val scope: CoroutineScope
) : LocalLLMEngine {

    private val TAG = "LocalLLM"
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val _currentConfig = MutableStateFlow(capabilityManager.recommendedModel)
    override val currentConfig: StateFlow<ModelConfig> = _currentConfig.asStateFlow()

    private val _modelState = MutableStateFlow<ModelState>(ModelState.NotInstalled)
    override val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val _lastDiagnostic = MutableStateFlow(
        DiagnosticReport(
            deviceAbi = Build.SUPPORTED_ABIS.joinToString(),
            totalRamGb = capabilityManager.totalRamGb,
            availableMemoryBeforeLoadGb = capabilityManager.availableRamGb
        )
    )
    override val lastDiagnostic: StateFlow<DiagnosticReport> = _lastDiagnostic.asStateFlow()

    private var activeLlamaModel: LlamaModel? = null
    private var loadedModelPath: String? = null
    private val inferenceMutex = Mutex()
    private var idleUnloadJob: Job? = null
    private var isNativeLibLoaded = false

    init {
        initNativeLibrary()
        scope.launch {
            checkCurrentModelStatus()
        }
    }

    private fun addLog(msg: String) {
        val entry = "[${timeFormat.format(Date())}] $msg"
        Log.i(TAG, msg)
        val currentLogs = _lastDiagnostic.value.logs.takeLast(50).toMutableList()
        currentLogs.add(entry)
        _lastDiagnostic.value = _lastDiagnostic.value.copy(logs = currentLogs)
    }

    private fun initNativeLibrary(): Boolean {
        if (isNativeLibLoaded) return true
        addLog("NATIVE_RUNTIME_LOAD_START (ABI: ${Build.SUPPORTED_ABIS.joinToString()})")
        
        var loadSuccess = false
        var loadMethod: String? = null
        var loadErr: String? = null

        // Attempt 1: Standard Android System.loadLibrary for bundled .so
        try {
            System.loadLibrary("jllama")
            loadSuccess = true
            loadMethod = "System.loadLibrary('jllama')"
            addLog("NATIVE_RUNTIME_LOAD_SUCCESS via System.loadLibrary('jllama')")
        } catch (u: UnsatisfiedLinkError) {
            loadErr = "System.loadLibrary('jllama') UnsatisfiedLinkError: ${u.message}"
            Log.w(TAG, loadErr)
        } catch (e: Throwable) {
            loadErr = "System.loadLibrary error: ${e.javaClass.simpleName}: ${e.message}"
            Log.w(TAG, loadErr)
        }

        // Attempt 2: LlamaLoader.initialize fallback
        if (!loadSuccess) {
            try {
                LlamaLoader.initialize()
                loadSuccess = true
                loadMethod = "LlamaLoader.initialize()"
                addLog("NATIVE_RUNTIME_LOAD_SUCCESS via LlamaLoader.initialize()")
            } catch (u: UnsatisfiedLinkError) {
                loadErr = (loadErr ?: "") + "\nLlamaLoader UnsatisfiedLinkError: ${u.message}"
                Log.e(TAG, "LlamaLoader UnsatisfiedLinkError: ${u.message}")
            } catch (e: Throwable) {
                loadErr = (loadErr ?: "") + "\nLlamaLoader error: ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, "LlamaLoader error: ${e.message}")
            }
        }

        isNativeLibLoaded = loadSuccess
        _lastDiagnostic.value = _lastDiagnostic.value.copy(
            nativeLibraryLoaded = loadSuccess,
            nativeLibraryMethod = loadMethod,
            nativeLibraryError = if (loadSuccess) null else loadErr
        )
        return loadSuccess
    }

    suspend fun checkCurrentModelStatus() {
        val config = _currentConfig.value
        if (downloadManager.isModelInstalled(config)) {
            if (_modelState.value !is ModelState.Ready && _modelState.value !is ModelState.Loading) {
                _modelState.value = ModelState.InstalledUnloaded
            }
        } else {
            _modelState.value = ModelState.NotInstalled
        }
    }

    override suspend fun setModelConfig(config: ModelConfig) {
        if (_currentConfig.value.id != config.id) {
            unloadModel()
            _currentConfig.value = config
            checkCurrentModelStatus()
        }
    }

    override fun isModelReady(): Boolean {
        return _modelState.value is ModelState.Ready || _modelState.value is ModelState.InstalledUnloaded
    }

    override suspend fun loadModel(): Result<Unit> = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            val config = _currentConfig.value
            val file = downloadManager.getModelFile(config)
            val modelPath = file.absolutePath
            val availRam = capabilityManager.availableRamGb
            val totalRam = capabilityManager.totalRamGb
            val deviceAbi = Build.SUPPORTED_ABIS.joinToString()

            addLog("LOCAL_TEST_START")
            addLog("Target Model: ${config.name} ($modelPath)")
            addLog("Device ABI: $deviceAbi | Avail RAM: ${availRam}GB | Total RAM: ${totalRam}GB")

            _lastDiagnostic.value = _lastDiagnostic.value.copy(
                stage = "FILE_CHECK",
                modelPath = modelPath,
                modelExists = file.exists(),
                modelSizeMb = if (file.exists()) file.length() / (1024 * 1024) else 0L,
                availableMemoryBeforeLoadGb = availRam,
                totalRamGb = totalRam,
                deviceAbi = deviceAbi,
                exceptionClass = null,
                exceptionMessage = null,
                causeChain = null,
                stackTrace = null,
                nativeError = null
            )

            if (!file.exists() || file.length() < 1000000L) {
                val err = "LOCAL_LLM_ERROR [STAGE: FILE_CHECK]: Model file not found on device ($modelPath, exists=${file.exists()}, length=${file.length()} bytes)."
                val ex = IllegalStateException(err)
                logFailure("FILE_CHECK", ex)
                _modelState.value = ModelState.NotInstalled
                return@withLock Result.failure(ex)
            }

            addLog("MODEL_FILE_FOUND (${file.length() / (1024 * 1024)} MB)")

            // Validate GGUF Magic Header
            var isValidGguf = false
            var magicDetected = ""
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val magic = ByteArray(4)
                    raf.read(magic)
                    magicDetected = String(magic, Charsets.US_ASCII)
                    if (magicDetected == "GGUF") {
                        isValidGguf = true
                    }
                }
            } catch (e: Exception) {
                val err = "LOCAL_LLM_ERROR [STAGE: GGUF_HEADER]: Failed reading header for $modelPath: ${e.message}"
                logFailure("GGUF_HEADER", IllegalStateException(err, e))
                return@withLock Result.failure(IllegalStateException(err, e))
            }

            if (!isValidGguf) {
                val err = "LOCAL_LLM_ERROR [STAGE: GGUF_HEADER]: Corrupt GGUF file. Expected 'GGUF' header magic, detected '$magicDetected'."
                val ex = IllegalStateException(err)
                logFailure("GGUF_HEADER", ex)
                return@withLock Result.failure(ex)
            }

            addLog("GGUF_HEADER_VALID (magic: GGUF)")
            _lastDiagnostic.value = _lastDiagnostic.value.copy(ggufHeaderValid = true)

            if (activeLlamaModel != null && loadedModelPath == modelPath) {
                _modelState.value = ModelState.Ready
                return@withLock Result.success(Unit)
            }

            // Ensure native runtime is loaded
            val nativeReady = initNativeLibrary()
            if (!nativeReady) {
                val err = "LOCAL_LLM_ERROR [STAGE: NATIVE_LOAD]: Native library failed to link for ABI $deviceAbi. Error: ${_lastDiagnostic.value.nativeLibraryError}"
                val ex = UnsatisfiedLinkError(err)
                logFailure("NATIVE_LOAD", ex)
                return@withLock Result.failure(ex)
            }

            // Load model into native LlamaModel
            addLog("LLAMA_MODEL_LOAD_START: Initializing LlamaModel with $modelPath")
            _lastDiagnostic.value = _lastDiagnostic.value.copy(stage = "MODEL_LOAD")
            _modelState.value = ModelState.Loading("Loading GGUF into native LlamaModel...")

            try {
                activeLlamaModel?.close()
                activeLlamaModel = null

                val modelParams = ModelParameters()
                    .setModel(modelPath)
                    .setGpuLayers(0)

                val llamaModel = LlamaModel(modelParams)
                activeLlamaModel = llamaModel
                loadedModelPath = modelPath

                addLog("LLAMA_MODEL_LOAD_SUCCESS")
                _lastDiagnostic.value = _lastDiagnostic.value.copy(
                    modelLoaded = true,
                    contextCreated = true,
                    stage = "READY"
                )
                _modelState.value = ModelState.Ready
                scheduleIdleUnload()
                Result.success(Unit)
            } catch (u: UnsatisfiedLinkError) {
                val err = "LOCAL_LLM_ERROR [STAGE: MODEL_LOAD_JNI]: UnsatisfiedLinkError creating LlamaModel: ${u.message}"
                logFailure("MODEL_LOAD_JNI", u)
                Result.failure(IllegalStateException(err, u))
            } catch (oom: OutOfMemoryError) {
                val err = "LOCAL_LLM_ERROR [STAGE: MODEL_LOAD_OOM]: OutOfMemory allocating native model context (Avail RAM: ${availRam}GB)"
                logFailure("MODEL_LOAD_OOM", oom)
                Result.failure(IllegalStateException(err, oom))
            } catch (e: Throwable) {
                val err = "LOCAL_LLM_ERROR [STAGE: MODEL_LOAD]: ${e.javaClass.name}: ${e.message}"
                logFailure("MODEL_LOAD", e)
                Result.failure(IllegalStateException(err, e))
            }
        }
    }

    override suspend fun unloadModel(): Unit = withContext(Dispatchers.Default) {
        inferenceMutex.withLock {
            idleUnloadJob?.cancel()
            idleUnloadJob = null

            try {
                activeLlamaModel?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing native LlamaModel: ${e.message}")
            }
            activeLlamaModel = null
            loadedModelPath = null

            if (downloadManager.isModelInstalled(_currentConfig.value)) {
                _modelState.value = ModelState.InstalledUnloaded
            } else {
                _modelState.value = ModelState.NotInstalled
            }
            addLog("LOCAL_LLM: Native model context closed and RAM released.")
            Unit
        }
    }

    override suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.Default) {
        val loadResult = loadModel()
        if (loadResult.isFailure) {
            val ex = loadResult.exceptionOrNull()
            val msg = ex?.message ?: "LOCAL_LLM_ERROR: Failed to load model"
            return@withContext Result.failure(IllegalStateException(msg, ex))
        }

        inferenceMutex.withLock {
            val model = activeLlamaModel
            if (model == null) {
                val err = "LOCAL_LLM_ERROR [STAGE: INFERENCE]: Active model context is null."
                val ex = IllegalStateException(err)
                logFailure("INFERENCE", ex)
                return@withLock Result.failure(ex)
            }

            try {
                // Milestone: Tokenization
                addLog("TOKENIZE_START")
                _lastDiagnostic.value = _lastDiagnostic.value.copy(stage = "TOKENIZE")
                val tokenArray = model.encode(prompt)
                val tokenCount = tokenArray.size
                addLog("TOKENIZE_SUCCESS (tokenCount=$tokenCount)")
                _lastDiagnostic.value = _lastDiagnostic.value.copy(
                    tokenizeSuccess = true,
                    inputTokens = tokenCount
                )

                // Milestone: Inference
                addLog("INFERENCE_START (Prompt length: ${prompt.length} chars)")
                _lastDiagnostic.value = _lastDiagnostic.value.copy(
                    stage = "INFERENCE",
                    inferenceStarted = true
                )
                val startTimeMs = System.currentTimeMillis()

                val inferenceParams = InferenceParameters(prompt)
                    .withTemperature(0.7f)
                    .withNPredict(128)

                val outputBuilder = StringBuilder()
                var generatedCount = 0

                val iterable = model.generate(inferenceParams)
                for (out in iterable) {
                    outputBuilder.append(out.text)
                    generatedCount++
                }

                val durationMs = System.currentTimeMillis() - startTimeMs
                val rawOutput = outputBuilder.toString().trim()

                addLog("TOKEN_GENERATION_COUNT: $generatedCount")
                addLog("INFERENCE_SUCCESS in ${durationMs}ms | Output: '$rawOutput'")

                _lastDiagnostic.value = _lastDiagnostic.value.copy(
                    stage = "COMPLETED",
                    inferenceSuccess = true,
                    generatedTokens = generatedCount,
                    generationTimeMs = durationMs,
                    rawOutput = rawOutput,
                    exceptionClass = null,
                    exceptionMessage = null,
                    causeChain = null,
                    stackTrace = null,
                    nativeError = null
                )

                if (rawOutput.isEmpty() || generatedCount == 0) {
                    val err = "LOCAL_LLM_ERROR [STAGE: EMPTY_OUTPUT]: 0 tokens generated by native runtime."
                    val ex = IllegalStateException(err)
                    logFailure("EMPTY_OUTPUT", ex)
                    return@withLock Result.failure(ex)
                }

                scheduleIdleUnload()
                Result.success(rawOutput)
            } catch (u: UnsatisfiedLinkError) {
                val err = "LOCAL_LLM_ERROR [STAGE: INFERENCE_JNI]: UnsatisfiedLinkError during inference: ${u.message}"
                logFailure("INFERENCE_JNI", u)
                Result.failure(IllegalStateException(err, u))
            } catch (oom: OutOfMemoryError) {
                val err = "LOCAL_LLM_ERROR [STAGE: INFERENCE_OOM]: OutOfMemory during token generation"
                logFailure("INFERENCE_OOM", oom)
                Result.failure(IllegalStateException(err, oom))
            } catch (e: Throwable) {
                val err = "LOCAL_LLM_ERROR [STAGE: INFERENCE]: ${e.javaClass.name}: ${e.message}"
                logFailure("INFERENCE", e)
                Result.failure(IllegalStateException(err, e))
            }
        }
    }

    private fun logFailure(stage: String, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val causeChain = buildString {
            var current: Throwable? = throwable.cause
            var level = 1
            while (current != null) {
                append("[$level] ${current.javaClass.name}: ${current.message}\n")
                current = current.cause
                level++
            }
        }.ifBlank { "None" }

        addLog("LOCAL_TEST_FAILURE at $stage: ${throwable.javaClass.name} - ${throwable.message}")

        Log.e(TAG, """
            LOCAL_TEST_FAILURE:
            ExceptionClass: ${throwable.javaClass.name}
            ExceptionMessage: ${throwable.message}
            Cause: $causeChain
            Stage: $stage
            StackTrace:
            $stackTrace
        """.trimIndent())

        _lastDiagnostic.value = _lastDiagnostic.value.copy(
            stage = "FAILED_$stage",
            exceptionClass = throwable.javaClass.name,
            exceptionMessage = throwable.message ?: "Unknown error",
            causeChain = causeChain,
            nativeError = if (throwable is UnsatisfiedLinkError) throwable.message else null,
            stackTrace = stackTrace
        )
        _modelState.value = ModelState.Error("LOCAL_LLM_ERROR [$stage]: ${throwable.message}")
    }

    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = scope.launch(Dispatchers.Default) {
            delay(3 * 60 * 1000L)
            unloadModel()
        }
    }
}
