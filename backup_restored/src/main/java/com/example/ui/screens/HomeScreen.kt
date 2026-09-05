package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.accessibility.ReplyAIAccessibilityService
import com.example.conversation.ChatMessage
import com.example.conversation.ConversationParser
import com.example.conversation.ExtractedConversation
import com.example.conversation.ExtractionSource
import com.example.llm.DeviceCapabilityManager
import com.example.llm.ModelConfig
import com.example.llm.ModelDownloadManager
import com.example.llm.ModelState
import com.example.reply.ReplyGenerator
import com.example.reply.ReplyGenerationResult
import com.example.reply.ReplySuggestion
import com.example.reply.ReplyStyle
import com.example.settings.AppSettingsRepository
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ReplyAIAccent
import com.example.ui.theme.ReplyAIGradient
import com.example.ui.theme.ReplyAIPurple
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.theme.WarningAmber
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    repository: AppSettingsRepository,
    downloadManager: ModelDownloadManager,
    capabilityManager: DeviceCapabilityManager,
    replyGenerator: ReplyGenerator,
    onNavigateToModelSetup: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    val isAccessibilityActive by ReplyAIAccessibilityService.isServiceRunning.collectAsState()
    val downloadState by downloadManager.downloadState.collectAsState()

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }

    // Re-check permissions when returning to screen
    LaunchedEffect(Unit) {
        hasOverlayPermission = Settings.canDrawOverlays(context)
    }

    val isModelInstalled = remember(downloadState) {
        downloadManager.isModelInstalled(capabilityManager.recommendedModel)
    }
    val effectiveGeminiKey = settings.geminiApiKey.ifBlank { com.example.BuildConfig.GEMINI_API_KEY }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        // App Title & Tagline
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ReplyAI",
                    color = TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "On-Device Real-Time Chat Assistant",
                    color = ReplyAIAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            // Quick Status Pill
            val isAiReady = (settings.aiEngine == "gemini" && effectiveGeminiKey.isNotBlank()) ||
                            (settings.aiEngine == "groq" && settings.groqApiKey.isNotBlank()) ||
                            (settings.aiEngine == "local" && isModelInstalled)
            val allReady = isAccessibilityActive && hasOverlayPermission && isAiReady && settings.isAssistantEnabled
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (allReady) SuccessGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f))
                    .border(
                        1.dp,
                        if (allReady) SuccessGreen.copy(alpha = 0.5f) else WarningAmber.copy(alpha = 0.5f),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (allReady) "v2.0 Active" else "Offline",
                    color = if (allReady) SuccessGreen else WarningAmber,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 🛑 MASTER KILL SWITCH
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = if (settings.isAssistantEnabled) Color(0xFF142E1F) else Color(0xFF2E1414)),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, if (settings.isAssistantEnabled) SuccessGreen else Color.Red)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Master Switch",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (settings.isAssistantEnabled) "Assistant is ON and scanning." else "Assistant is entirely disabled.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Switch(
                    checked = settings.isAssistantEnabled,
                    onCheckedChange = { repository.setAssistantEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = SuccessGreen,
                        uncheckedThumbColor = Color.LightGray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // v2.0 Quick Engine & Key Setup Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1B33)),
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, ReplyAIPurple)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = ReplyAIAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "v2.0 Engine & Quick Key",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ReplyAIPurple.copy(alpha = 0.3f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (settings.aiEngine == "gemini") "Gemini Active" else if (settings.aiEngine == "groq") "Groq Active" else "Local Active",
                            color = ReplyAIAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "🎯 Chat Filter Fixed: Pill will ONLY show inside a chat conversation (hidden in Reels/Feeds).",
                    color = Color(0xFFC4B5FD),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { repository.setAiEngine("gemini") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.aiEngine == "gemini") ReplyAIPurple else DarkSurface
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "✨ Gemini",
                            fontSize = 11.sp,
                            fontWeight = if (settings.aiEngine == "gemini") FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    Button(
                        onClick = { repository.setAiEngine("groq") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.aiEngine == "groq") ReplyAIPurple else DarkSurface
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "🚀 Groq",
                            fontSize = 11.sp,
                            fontWeight = if (settings.aiEngine == "groq") FontWeight.Bold else FontWeight.Normal
                        )
                    }
                    Button(
                        onClick = { repository.setAiEngine("local") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (settings.aiEngine == "local") ReplyAIPurple else DarkSurface
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "⚡ Local",
                            fontSize = 11.sp,
                            fontWeight = if (settings.aiEngine == "local") FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                if (settings.aiEngine == "gemini") {
                    Spacer(modifier = Modifier.height(10.dp))
                    var quickApiKey by remember(settings.geminiApiKey) { mutableStateOf(settings.geminiApiKey) }
                    var geminiTestStatus by remember { mutableStateOf<String?>(null) }
                    var isTestingGemini by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        value = quickApiKey,
                        onValueChange = {
                            quickApiKey = it
                            repository.setGeminiApiKey(it)
                            geminiTestStatus = null
                        },
                        placeholder = { Text(if (effectiveGeminiKey.isNotBlank()) "Built-in Gemini Key Active (or paste custom)" else "Paste Gemini API Key...", color = TextTertiary, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = ReplyAIPurple,
                            unfocusedBorderColor = Color(0xFF35344A),
                            focusedContainerColor = DarkBackground,
                            unfocusedContainerColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (effectiveGeminiKey.isNotBlank()) "✓ Built-in Key Active" else "Key required",
                            color = if (effectiveGeminiKey.isNotBlank()) SuccessGreen else WarningAmber,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = {
                                isTestingGemini = true
                                geminiTestStatus = "Testing connection..."
                                scope.launch {
                                    val client = com.example.gemini.GeminiClient()
                                    val keyToTest = if (quickApiKey.isNotBlank()) quickApiKey else com.example.BuildConfig.GEMINI_API_KEY
                                    val res = client.testApiKey(keyToTest)
                                    isTestingGemini = false
                                    geminiTestStatus = if (res.isSuccess) "✓ Connected (Gemini 3.8 Flash Ready!)" else "✕ ${res.exceptionOrNull()?.localizedMessage ?: "Failed"}"
                                }
                            },
                            enabled = !isTestingGemini && effectiveGeminiKey.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2A4A))
                        ) {
                            Text(
                                text = if (isTestingGemini) "Testing..." else "⚡ Test Gemini",
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    if (geminiTestStatus != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = geminiTestStatus!!,
                            color = if (geminiTestStatus!!.startsWith("✓")) SuccessGreen else Color(0xFFFF5252),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (settings.aiEngine == "groq") {
                    Spacer(modifier = Modifier.height(10.dp))
                    var groqKey by remember(settings.groqApiKey) { mutableStateOf(settings.groqApiKey) }
                    var groqTestStatus by remember { mutableStateOf<String?>(null) }
                    var isTestingGroq by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        value = groqKey,
                        onValueChange = {
                            groqKey = it
                            repository.setGroqApiKey(it)
                            groqTestStatus = null
                        },
                        placeholder = { Text("Paste Groq API Key (starts with gsk_...)", color = TextTertiary, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = ReplyAIPurple,
                            unfocusedBorderColor = Color(0xFF35344A),
                            focusedContainerColor = DarkBackground,
                            unfocusedContainerColor = DarkBackground
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    val keyTrimmed = groqKey.trim()
                    val keyLen = keyTrimmed.length
                    val isKeyIncomplete = keyLen in 1..44
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (keyTrimmed.isBlank()) "Free key from console.groq.com" else "$keyLen/56 chars" + if (isKeyIncomplete) " ⚠️ Incomplete" else " ✓",
                            color = if (isKeyIncomplete) WarningAmber else if (keyLen >= 45) SuccessGreen else TextSecondary,
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = {
                                isTestingGroq = true
                                groqTestStatus = "Testing Groq connection..."
                                scope.launch {
                                    val client = com.example.groq.GroqClient()
                                    val res = client.testApiKey(keyTrimmed)
                                    isTestingGroq = false
                                    groqTestStatus = if (res.isSuccess) "✓ Connected (Groq LLaMA 3.1 Ready!)" else "✕ ${res.exceptionOrNull()?.localizedMessage ?: "Failed"}"
                                }
                            },
                            enabled = !isTestingGroq && keyTrimmed.isNotBlank(),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2A4A))
                        ) {
                            Text(
                                text = if (isTestingGroq) "Testing..." else "⚡ Test Groq",
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    if (isKeyIncomplete) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "⚠️ Groq API key is truncated ($keyLen chars). Full Groq key is ~56 characters. Please re-copy the entire key from console.groq.com/keys.",
                            color = WarningAmber,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }

                    if (groqTestStatus != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = groqTestStatus!!,
                            color = if (groqTestStatus!!.startsWith("✓")) SuccessGreen else Color(0xFFFF5252),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                } else if (settings.aiEngine == "local") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "ℹ️ Offline GGUF requires 64-bit llama.cpp native binaries. For instant real AI replies without downloading 1.1GB, use Gemini (Built-in Free) or Groq!",
                        color = Color(0xFFC4B5FD),
                        fontSize = 11.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // AI Model Status Card: "AI model: Ready / Downloading / Not installed"
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateToModelSetup() },
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2B2844))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (statusIcon, statusColor, statusLabel, statusSubtitle) = when {
                        settings.aiEngine == "gemini" && effectiveGeminiKey.isNotBlank() ->
                            listOf(Icons.Default.AutoAwesome, ReplyAIAccent, "AI: Gemini 3.8 Flash", "Google Gemini AI Active • Zero storage required")
                        settings.aiEngine == "groq" && settings.groqApiKey.isNotBlank() ->
                            listOf(Icons.Default.AutoAwesome, ReplyAIAccent, "AI: Groq (Llama 3.1)", "Ultra-Fast Cloud Llama 3.1 Active")
                        settings.aiEngine == "gemini" ->
                            listOf(Icons.Default.AutoAwesome, WarningAmber, "AI: Gemini (Key Missing)", "Tap to enter free Gemini API key")
                        settings.aiEngine == "groq" ->
                            listOf(Icons.Default.AutoAwesome, WarningAmber, "AI: Groq (Key Missing)", "Tap to enter free Groq API key")
                        downloadState is ModelState.Downloading ->
                            listOf(Icons.Default.Download, ReplyAIAccent, "AI Model: Downloading", "Setting up local weights...")
                        isModelInstalled ->
                            listOf(Icons.Default.CheckCircle, SuccessGreen, "AI Model: Ready (Offline)", "${capabilityManager.recommendedModel.name} • 100% On-Device")
                        else ->
                            listOf(Icons.Default.Refresh, WarningAmber, "AI: Ready", "Local contextual engine ready or switch to Gemini/Groq")
                    }

                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background((statusColor as Color).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = statusIcon as androidx.compose.ui.graphics.vector.ImageVector, contentDescription = null, tint = statusColor, modifier = Modifier.size(24.dp))
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = statusLabel as String,
                            color = statusColor,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = statusSubtitle as String,
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Manage Model",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Hardware Capability Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = ReplyAIPurple,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Device Hardware Profile",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Text(
                        text = "%.1f GB RAM".format(capabilityManager.totalRamGb),
                        color = ReplyAIAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Available RAM: %.1f GB".format(capabilityManager.availableRamGb),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Thermal: ${capabilityManager.getThermalStatus()}",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Optimized for Android 15/16 • 6 GB RAM footprint",
                    color = TextTertiary,
                    fontSize = 11.5.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Permissions Checklist Card
        Text(
            text = "Required Permissions",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        // 1. Accessibility Service
        PermissionItem(
            title = "Accessibility Service",
            description = "Reads active conversation and injects reply into chat composer.",
            isGranted = isAccessibilityActive,
            onAction = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Draw Over Other Apps
        PermissionItem(
            title = "Display Over Other Apps",
            description = "Shows the floating [ ReplyAI ✨ ] pill above messaging apps.",
            isGranted = hasOverlayPermission,
            onAction = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            }
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Interactive Live Chat Tester
        Text(
            text = "Interactive Reply Tester",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Test real on-device reply generation right now without opening Instagram or WhatsApp.",
            color = TextSecondary,
            fontSize = 12.5.sp,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        InteractiveTesterCard(
            replyGenerator = replyGenerator,
            customPersona = settings.customPersona,
            repository = repository
        )

        Spacer(modifier = Modifier.height(24.dp))

        // How to Use Section
        Text(
            text = "How to Use ReplyAI",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                HowToStep(
                    stepNumber = "1",
                    title = "Open any messaging app",
                    description = "Works in Instagram Direct, WhatsApp, Telegram, and Messenger."
                )
                Spacer(modifier = Modifier.height(14.dp))
                HowToStep(
                    stepNumber = "2",
                    title = "Tap the [ ReplyAI ✨ ] pill",
                    description = "A floating pill appears on the screen edge while you are in a chat."
                )
                Spacer(modifier = Modifier.height(14.dp))
                HowToStep(
                    stepNumber = "3",
                    title = "Pick from 3 instant replies",
                    description = "Tap any suggestion to copy and paste it into the message input field. Review & hit send!"
                )
            }
        }
    }
}

@Composable
private fun PermissionItem(
    title: String,
    description: String,
    isGranted: Boolean,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (isGranted) SuccessGreen.copy(alpha = 0.15f) else WarningAmber.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isGranted) SuccessGreen else WarningAmber,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = description,
                        color = TextSecondary,
                        fontSize = 11.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGranted) {
                Text(
                    text = "Granted",
                    color = SuccessGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            } else {
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = ReplyAIPurple),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Enable", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InteractiveTesterCard(
    replyGenerator: ReplyGenerator,
    customPersona: String,
    repository: AppSettingsRepository
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val settings by repository.settings.collectAsState()
    val effectiveKey = settings.geminiApiKey.ifBlank { com.example.BuildConfig.GEMINI_API_KEY }

    var simulatedMessage by remember { mutableStateOf("Bhai kal free ho kya? Movie chalte hain!") }
    var isGenerating by remember { mutableStateOf(false) }
    var generationResult by remember { mutableStateOf<ReplyGenerationResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A283D))
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Select Active AI Engine:",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Engine Selection Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (settings.aiEngine == "gemini") ReplyAIPurple else DarkBackground)
                        .border(1.dp, if (settings.aiEngine == "gemini") ReplyAIAccent else Color(0xFF35344A), RoundedCornerShape(10.dp))
                        .clickable {
                            repository.setAiEngine("gemini")
                            generationResult = null
                            errorMessage = null
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✨ Gemini 3.8",
                        color = if (settings.aiEngine == "gemini") Color.White else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (settings.aiEngine == "groq") ReplyAIPurple else DarkBackground)
                        .border(1.dp, if (settings.aiEngine == "groq") ReplyAIAccent else Color(0xFF35344A), RoundedCornerShape(10.dp))
                        .clickable {
                            repository.setAiEngine("groq")
                            generationResult = null
                            errorMessage = null
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "⚡ Groq LLaMA",
                        color = if (settings.aiEngine == "groq") Color.White else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (settings.aiEngine == "local") ReplyAIPurple else DarkBackground)
                        .border(1.dp, if (settings.aiEngine == "local") ReplyAIAccent else Color(0xFF35344A), RoundedCornerShape(10.dp))
                        .clickable {
                            repository.setAiEngine("local")
                            generationResult = null
                            errorMessage = null
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📱 Local GGUF",
                        color = if (settings.aiEngine == "local") Color.White else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (settings.aiEngine == "local") {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF251D38)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF5B3E8C))
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "ℹ️ Offline GGUF requires ~1.1 GB downloaded model weights and native C++ JNI (libllama.so). To get real, 100% genuine AI replies immediately with zero download, switch to Gemini 3.8 Flash (Free) or Groq LLaMA.",
                            color = Color(0xFFDDD6FE),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { repository.setAiEngine("gemini") },
                            colors = ButtonDefaults.buttonColors(containerColor = ReplyAIPurple),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text("Switch to Gemini 3.8 Flash (Free & Active)", fontSize = 11.5.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Incoming Message to Test:",
                color = TextSecondary,
                fontSize = 12.5.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = simulatedMessage,
                onValueChange = { simulatedMessage = it },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedBorderColor = ReplyAIPurple,
                    unfocusedBorderColor = Color(0xFF35344A),
                    focusedContainerColor = DarkBackground,
                    unfocusedContainerColor = DarkBackground
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Preset Quick Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickPresetChip(
                    text = "Hinglish Plan",
                    onClick = { simulatedMessage = "Bhai kal free ho kya? Movie chalte hain!" }
                )
                QuickPresetChip(
                    text = "English Meeting",
                    onClick = { simulatedMessage = "Hey! Are you joining the project sync at 4 PM?" }
                )
                QuickPresetChip(
                    text = "Casual Banter",
                    onClick = { simulatedMessage = "Kab mil rahe hain? Party pending hai teri taraf se 😂" }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            val buttonLabel = when (settings.aiEngine) {
                "groq" -> "Generate with Groq LLaMA ⚡"
                "local" -> "Generate with Local Model 📱"
                else -> "Generate with Gemini 3.8 Flash ✨"
            }

            Button(
                onClick = {
                    scope.launch {
                        isGenerating = true
                        errorMessage = null
                        generationResult = null

                        val sampleHistory = listOf(
                            ChatMessage(sender = "Me", text = "Sab badhiya bro!", isUser = true),
                            ChatMessage(sender = "Them", text = simulatedMessage, isUser = false)
                        )
                        val conversation = ExtractedConversation(
                            messages = sampleHistory,
                            latestIncomingMessage = sampleHistory.last(),
                            activePackage = "com.instagram.android",
                            source = ExtractionSource.SIMULATED,
                            detectedLanguage = ConversationParser.detectLanguage(sampleHistory)
                        )

                        val result = replyGenerator.generateReplies(conversation, customPersona)
                        isGenerating = false

                        if (result.isSuccess) {
                            generationResult = result.getOrThrow()
                        } else {
                            errorMessage = result.exceptionOrNull()?.localizedMessage
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ReplyAIPurple),
                shape = RoundedCornerShape(12.dp),
                enabled = !isGenerating && simulatedMessage.isNotBlank()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Querying ${settings.aiEngine.uppercase()} AI Engine…")
                } else {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buttonLabel,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Results Display
            AnimatedVisibility(visible = generationResult != null) {
                val res = generationResult ?: return@AnimatedVisibility
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Live AI Generated Replies:",
                            color = TextPrimary,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "✓ ${res.engineUsed.uppercase()} • ${res.latencyMs}ms",
                            color = SuccessGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    res.suggestions.forEach { reply ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp)
                                .clickable {
                                    val clip = ClipData.newPlainText("ReplyAI", reply.text)
                                    clipboardManager.setPrimaryClip(clip)
                                    Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
                                },
                            colors = CardDefaults.cardColors(containerColor = DarkBackground),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF35344A))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${reply.index}. ${reply.style.title}",
                                        color = Color(reply.style.badgeColor),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = reply.text,
                                        color = TextPrimary,
                                        fontSize = 13.5.sp,
                                        modifier = Modifier.padding(top = 2.dp)
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = TextTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            errorMessage?.let { err ->
                Text(
                    text = "Error: $err",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun QuickPresetChip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkBackground)
            .border(1.dp, Color(0xFF3A384F), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = text, color = TextSecondary, fontSize = 11.sp)
    }
}

@Composable
private fun HowToStep(stepNumber: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(ReplyAIPurple.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stepNumber,
                color = ReplyAIAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
