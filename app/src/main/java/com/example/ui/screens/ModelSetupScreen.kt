package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llm.DeviceCapabilityManager
import com.example.llm.ModelConfig
import com.example.llm.ModelDownloadManager
import com.example.llm.ModelState
import com.example.llm.QuantizedLocalLLMEngine
import com.example.reply.LocalReplyGenerator
import com.example.reply.ReplyGenerator
import com.example.ai.AiProviderRegistry
import com.example.ai.ProviderId
import com.example.settings.AppSettings
import com.example.settings.AppSettingsRepository
import com.example.ui.components.LocalLlmDiagnosticCard
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ReplyAIAccent
import com.example.ui.theme.ReplyAIPurple
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun ModelSetupScreen(
    downloadManager: ModelDownloadManager,
    capabilityManager: DeviceCapabilityManager,
    repository: AppSettingsRepository,
    registry: AiProviderRegistry,
    replyGenerator: ReplyGenerator? = null,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val downloadState by downloadManager.downloadState.collectAsState()

    val localEngine = (replyGenerator as? LocalReplyGenerator)?.llmEngine as? QuantizedLocalLLMEngine

    var selectedEngine by remember(settings.aiEngine) { mutableStateOf(settings.aiEngine) }
    var selectedModel by remember { mutableStateOf(capabilityManager.recommendedModel) }
    var importStatusMessage by remember { mutableStateOf<String?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                importStatusMessage = "Importing model file..."
                val result = downloadManager.importLocalModelFile(uri, selectedModel)
                if (result.isSuccess) {
                    importStatusMessage = "Successfully imported ${selectedModel.name}!"
                    localEngine?.setModelConfig(selectedModel)
                } else {
                    importStatusMessage = "Import failed: ${result.exceptionOrNull()?.localizedMessage}"
                }
            }
        }
    }

    val isInstalled = downloadManager.isModelInstalled(selectedModel)
    val freeStorageGb = remember { downloadManager.getAvailableStorageBytes() / (1024f * 1024f * 1024f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // Header
        Text(
            text = "AI Engine Configuration",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Choose between instant Gemini Cloud AI or 100% offline on-device neural model.",
            color = TextSecondary,
            fontSize = 13.5.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // Engine Selector Tabs: Gemini Cloud vs On-Device Local
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EngineTabButton(
                title = "Gemini",
                subtitle = "Fast • 0 GB Storage",
                isSelected = selectedEngine == AppSettings.ENGINE_GEMINI,
                icon = Icons.Default.AutoAwesome,
                modifier = Modifier.weight(1f),
                onClick = {
                    selectedEngine = AppSettings.ENGINE_GEMINI
                    repository.setAiEngine(AppSettings.ENGINE_GEMINI)
                }
            )

            EngineTabButton(
                title = "Smart AI",
                subtitle = "Groq + xKiro",
                isSelected = selectedEngine == AppSettings.ENGINE_SMART,
                icon = Icons.Default.Storage,
                modifier = Modifier.weight(1f),
                onClick = {
                    selectedEngine = AppSettings.ENGINE_SMART
                    repository.setAiEngine(AppSettings.ENGINE_SMART)
                }
            )

            EngineTabButton(
                title = "On-Device GGUF",
                subtitle = "100% Offline",
                isSelected = selectedEngine == AppSettings.ENGINE_LOCAL,
                icon = Icons.Default.Memory,
                modifier = Modifier.weight(1f),
                onClick = {
                    selectedEngine = AppSettings.ENGINE_LOCAL
                    repository.setAiEngine(AppSettings.ENGINE_LOCAL)
                }
            )
        }

        if (selectedEngine == AppSettings.ENGINE_GEMINI) {
            // GEMINI CLOUD AI CONFIGURATION
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = null,
                            tint = ReplyAIAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Gemini API Key",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Gemini replies are generated by Google Gemini using the key you saved in Settings. Keys are encrypted with the Android Keystore and are never stored here.",
                        color = TextSecondary,
                        fontSize = 12.5.sp,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkBackground),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = "To add, test or remove your Gemini key, open Settings → AI Providers / API Keys.",
                                color = Color(0xFFDDD6FE),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = onNavigateToSettings,
                                colors = ButtonDefaults.buttonColors(containerColor = ReplyAIPurple),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Open AI Providers", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else if (selectedEngine == AppSettings.ENGINE_SMART) {
            // SMART AI (internal Groq / xKiro router)
            val configuredProviders by registry.keys.configured.collectAsState()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Storage,
                            contentDescription = null,
                            tint = ReplyAIAccent,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Smart AI Providers",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Smart AI ranks Groq and xKiro for every request and fails over automatically. No model download is required.",
                        color = TextSecondary,
                        fontSize = 12.5.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Groq configured: ${if (configuredProviders.contains(ProviderId.GROQ)) "yes" else "no"}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "xKiro configured: ${if (configuredProviders.contains(ProviderId.XKIRO)) "yes" else "no"}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = ReplyAIPurple),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Open AI Providers", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // LOCAL ON-DEVICE MODEL CONFIGURATION
            // Error Card if download failed
            if (downloadState is ModelState.Error) {
                val errorState = downloadState as ModelState.Error
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1515)),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Error, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Download Notice", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = errorState.message, color = Color(0xFFFCA5A5), fontSize = 12.sp)

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    downloadManager.startDownload(selectedModel)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Retry Download", fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    selectedEngine = AppSettings.ENGINE_GEMINI
                                    repository.setAiEngine(AppSettings.ENGINE_GEMINI)
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = ReplyAIAccent),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Switch to Gemini Cloud AI", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // ==========================================
            // PROMINENT LOCAL LLM DIAGNOSTICS AT TOP
            // ==========================================
            LocalLlmDiagnosticCard(
                localEngine = localEngine,
                selectedModel = selectedModel,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Status Card: "AI model: Ready / Downloading / Not installed"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = DarkCard),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (statusIcon, statusColor, statusLabel) = when {
                        downloadState is ModelState.Downloading ->
                            Triple(Icons.Default.Download, ReplyAIAccent, "AI Model: Downloading")
                        isInstalled ->
                            Triple(Icons.Default.CheckCircle, SuccessGreen, "AI Model: Ready")
                        else ->
                            Triple(Icons.Default.Refresh, WarningAmber, "AI Model: Not Installed")
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = statusLabel,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        val subText = when {
                            downloadState is ModelState.Downloading -> "Downloading model shards..."
                            isInstalled -> "${selectedModel.name} loaded & ready for inference"
                            else -> "Download recommended model below to enable offline replies"
                        }
                        Text(
                            text = subText,
                            color = TextSecondary,
                            fontSize = 12.5.sp
                        )
                    }
                }
            }

            // Available Models Section
            Text(
                text = "Available On-Device Models",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                text = "Models run 100% on your device CPU/NPU using 4-bit quantization.",
                color = TextSecondary,
                fontSize = 12.5.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ModelConfig.AVAILABLE_MODELS.forEach { model ->
                val isSelected = selectedModel.id == model.id
                val isModelFileDownloaded = downloadManager.isModelInstalled(model)
                val isRecommended = model.id == capabilityManager.recommendedModel.id

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clickable {
                            selectedModel = model
                            downloadManager.checkInstalledStatus(model)
                            scope.launch {
                                localEngine?.setModelConfig(model)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = if (isSelected) {
                        androidx.compose.foundation.BorderStroke(2.dp, ReplyAIPurple)
                    } else {
                        androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF262633))
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = model.name,
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )

                            if (isRecommended) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(ReplyAIPurple.copy(alpha = 0.2f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = "★ Recommended for ${capabilityManager.totalRamGb.toInt()}GB RAM",
                                        color = ReplyAIAccent,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = model.description,
                            color = TextSecondary,
                            fontSize = 12.5.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Size: ${model.formattedSize} • Quant: ${model.quantization}",
                                color = TextTertiary,
                                fontSize = 12.sp
                            )

                            if (isModelFileDownloaded) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Downloaded",
                                        color = SuccessGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Download Action Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    // Storage Info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Free Device Storage:",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "%.1f GB available".format(freeStorageGb),
                            color = if (freeStorageGb > 2.0f) SuccessGreen else WarningAmber,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Progress Bar if Downloading
                    if (downloadState is ModelState.Downloading) {
                        val state = downloadState as ModelState.Downloading
                        Column {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = ReplyAIPurple,
                                trackColor = Color(0xFF262633)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${(state.progress * 100).toInt()}% • ${(state.downloadedBytes / (1024 * 1024))}MB / ${(state.totalBytes / (1024 * 1024))}MB",
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "%.1f MB/s".format(state.speedMbPerSec),
                                    color = ReplyAIAccent,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { downloadManager.cancelDownload(selectedModel) },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }
                    } else if (isInstalled) {
                        val activeFile = downloadManager.getModelFile(selectedModel)
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1B261F)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SuccessGreen.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("✓ Model Active & Ready", color = SuccessGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("File: ${activeFile.name} (${activeFile.length() / (1024 * 1024)} MB)", color = TextSecondary, fontSize = 11.5.sp)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    downloadManager.deleteModel(selectedModel)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFEF4444))
                            ) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Delete / Unlink File")
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                downloadManager.startDownload(selectedModel)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ReplyAIPurple),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Download ${selectedModel.name} (${selectedModel.formattedSize})",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = {
                                val found = downloadManager.scanDownloadsFolder()
                                if (found != null) {
                                    downloadManager.setImportedFile(found)
                                    importStatusMessage = "✓ Found '${found.name}' (${found.length() / (1024 * 1024)} MB) in Downloads! Model is active."
                                    scope.launch {
                                        localEngine?.setModelConfig(selectedModel)
                                    }
                                } else {
                                    importStatusMessage = "No .gguf file auto-detected in Downloads. Please tap 'Import .GGUF from Storage' to select your downloaded file."
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2B2144)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp), tint = ReplyAIAccent)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("🔍 Scan Downloads (Already Downloaded)", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Icon(imageVector = Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import .GGUF from Storage (No Data Used)")
                        }
                    }

                    importStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = ReplyAIAccent,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineTabButton(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) DarkSurfaceVariant else DarkSurface
        ),
        shape = RoundedCornerShape(16.dp),
        border = if (isSelected) {
            androidx.compose.foundation.BorderStroke(2.dp, ReplyAIPurple)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2A38))
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) ReplyAIAccent else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                color = if (isSelected) TextPrimary else TextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.5.sp
            )
            Text(
                text = subtitle,
                color = TextTertiary,
                fontSize = 10.5.sp
            )
        }
    }
}
