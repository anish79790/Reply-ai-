package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llm.DiagnosticReport
import com.example.llm.ModelConfig
import com.example.llm.QuantizedLocalLLMEngine
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.ReplyAIAccent
import com.example.ui.theme.ReplyAIPurple
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import kotlinx.coroutines.launch

@Composable
fun LocalLlmDiagnosticCard(
    localEngine: QuantizedLocalLLMEngine?,
    selectedModel: ModelConfig,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnosticReport by localEngine?.lastDiagnostic?.collectAsState() ?: remember { mutableStateOf(DiagnosticReport()) }
    var isRunningDiagnosticTest by remember { mutableStateOf(false) }
    var showRawLogs by remember { mutableStateOf(false) }

    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    val hasError = diagnosticReport.exceptionClass != null || diagnosticReport.stage.startsWith("FAILED")

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14141F)),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            if (hasError) Color(0xFFEF4444) else if (diagnosticReport.inferenceSuccess) SuccessGreen else ReplyAIPurple
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasError) Icons.Default.Error else Icons.Default.BugReport,
                        contentDescription = null,
                        tint = if (hasError) Color(0xFFEF4444) else ReplyAIAccent,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Local LLM Diagnostics",
                        color = TextPrimary,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Stage Pill
                val stageColor = when {
                    diagnosticReport.stage == "COMPLETED" -> SuccessGreen
                    hasError -> Color(0xFFEF4444)
                    isRunningDiagnosticTest -> ReplyAIAccent
                    else -> TextSecondary
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(stageColor.copy(alpha = 0.2f))
                        .border(1.dp, stageColor.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isRunningDiagnosticTest) "RUNNING..." else diagnosticReport.stage,
                        color = stageColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Text(
                text = "Real-time verification harness executing the full production inference path (LocalReplyGenerator → QuantizedLocalLLMEngine → LlamaModel → native llama.cpp JNI).",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            // Primary TEST LOCAL MODEL Button
            Button(
                onClick = {
                    if (localEngine != null) {
                        isRunningDiagnosticTest = true
                        scope.launch {
                            try {
                                localEngine.setModelConfig(selectedModel)
                                val testPrompt = "Say hello in one short sentence."
                                val res = localEngine.generate(testPrompt)
                                if (res.isSuccess) {
                                    Toast.makeText(context, "Test Success! Generated tokens.", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Inference failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Throwable) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            } finally {
                                isRunningDiagnosticTest = false
                            }
                        }
                    } else {
                        Toast.makeText(context, "Local LLM Engine not initialized", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ReplyAIPurple),
                shape = RoundedCornerShape(12.dp),
                enabled = !isRunningDiagnosticTest
            ) {
                if (isRunningDiagnosticTest) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Running Native Inference Test...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } else {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("TEST LOCAL MODEL", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // MILESTONE CHECKLIST
            Text(
                text = "EXECUTION MILESTONES",
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkBackground)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MilestoneItem("MODEL_FILE_FOUND", diagnosticReport.modelExists, "Size: ${diagnosticReport.modelSizeMb} MB")
                MilestoneItem("GGUF_HEADER_VALID", diagnosticReport.ggufHeaderValid, "Header: 'GGUF'")
                MilestoneItem("NATIVE_RUNTIME_LOAD_SUCCESS", diagnosticReport.nativeLibraryLoaded, diagnosticReport.nativeLibraryMethod ?: diagnosticReport.nativeLibraryError ?: "")
                MilestoneItem("LLAMA_MODEL_LOAD_SUCCESS", diagnosticReport.modelLoaded, if (diagnosticReport.modelLoaded) "LlamaModel instance created" else "")
                MilestoneItem("TOKENIZE_SUCCESS", diagnosticReport.tokenizeSuccess, "Input tokens: ${diagnosticReport.inputTokens}")
                MilestoneItem("INFERENCE_START", diagnosticReport.inferenceStarted, "")
                MilestoneItem("TOKEN_GENERATION_COUNT", diagnosticReport.generatedTokens > 0, "Tokens: ${diagnosticReport.generatedTokens}")
                MilestoneItem("INFERENCE_SUCCESS", diagnosticReport.inferenceSuccess, "Time: ${diagnosticReport.generationTimeMs}ms")
            }

            // RAW OUTPUT (if tokens generated)
            if (diagnosticReport.rawOutput.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "RAW MODEL OUTPUT (${diagnosticReport.generatedTokens} tokens generated):",
                    color = SuccessGreen,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF1B2E23))
                        .border(1.dp, SuccessGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = diagnosticReport.rawOutput,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ENVIRONMENT & MEMORY DIAGNOSTICS TABLE
            Text(
                text = "ENVIRONMENT & RUNTIME SPECS",
                color = TextTertiary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(DarkBackground)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SpecItem("Model File Path", diagnosticReport.modelPath.ifBlank { "None" })
                SpecItem("File Exists / Size", "${diagnosticReport.modelExists} / ${diagnosticReport.modelSizeMb} MB")
                SpecItem("Device Supported ABIs", diagnosticReport.deviceAbi)
                SpecItem("Native Runtime Loaded", "${diagnosticReport.nativeLibraryLoaded} (${diagnosticReport.nativeLibraryMethod ?: "Failed"})")
                SpecItem("Available RAM / Total RAM", "%.2f GB / %.2f GB".format(diagnosticReport.availableMemoryBeforeLoadGb, diagnosticReport.totalRamGb))
                SpecItem("Failing Stage", if (diagnosticReport.exceptionClass != null) diagnosticReport.stage else "None")
            }

            // REAL EXCEPTION DETAILS (ROOT CAUSE)
            if (diagnosticReport.exceptionClass != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "REAL EXCEPTION DETAILS (ROOT CAUSE)",
                    color = Color(0xFFEF4444),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF2A1515))
                        .border(1.dp, Color(0xFFEF4444), RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = "Exception Class: ${diagnosticReport.exceptionClass}",
                        color = Color(0xFFFCA5A5),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Message: ${diagnosticReport.exceptionMessage}",
                        color = Color(0xFFFEE2E2),
                        fontSize = 12.sp
                    )
                    if (!diagnosticReport.causeChain.isNullOrBlank() && diagnosticReport.causeChain != "None") {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Cause Chain:\n${diagnosticReport.causeChain}",
                            color = Color(0xFFFCA5A5),
                            fontSize = 11.5.sp
                        )
                    }
                    if (!diagnosticReport.nativeError.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Native Error: ${diagnosticReport.nativeError}",
                            color = Color(0xFFF87171),
                            fontSize = 11.5.sp
                        )
                    }

                    if (!diagnosticReport.stackTrace.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Scrollable Stack Trace:",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF150A0A))
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Text(
                                text = diagnosticReport.stackTrace ?: "",
                                color = Color(0xFFFCA5A5),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // COPY DIAGNOSTIC REPORT BUTTON
            Button(
                onClick = {
                    val reportText = buildString {
                        appendLine("=== REPLYAI LOCAL LLM DIAGNOSTIC REPORT ===")
                        appendLine("Stage: ${diagnosticReport.stage}")
                        appendLine("Device ABI: ${diagnosticReport.deviceAbi}")
                        appendLine("Total RAM: ${diagnosticReport.totalRamGb} GB")
                        appendLine("Available RAM: ${diagnosticReport.availableMemoryBeforeLoadGb} GB")
                        appendLine("Model Path: ${diagnosticReport.modelPath}")
                        appendLine("Model Exists: ${diagnosticReport.modelExists} (Size: ${diagnosticReport.modelSizeMb} MB)")
                        appendLine("GGUF Header Valid: ${diagnosticReport.ggufHeaderValid}")
                        appendLine("Native Lib Loaded: ${diagnosticReport.nativeLibraryLoaded} (${diagnosticReport.nativeLibraryMethod})")
                        appendLine("Native Lib Error: ${diagnosticReport.nativeLibraryError ?: "None"}")
                        appendLine("LlamaModel Loaded: ${diagnosticReport.modelLoaded}")
                        appendLine("Tokenize Success: ${diagnosticReport.tokenizeSuccess} (Input tokens: ${diagnosticReport.inputTokens})")
                        appendLine("Inference Started: ${diagnosticReport.inferenceStarted}")
                        appendLine("Tokens Generated: ${diagnosticReport.generatedTokens}")
                        appendLine("Inference Success: ${diagnosticReport.inferenceSuccess} (${diagnosticReport.generationTimeMs} ms)")
                        appendLine("Raw Output: ${diagnosticReport.rawOutput}")
                        appendLine("Exception Class: ${diagnosticReport.exceptionClass ?: "None"}")
                        appendLine("Exception Message: ${diagnosticReport.exceptionMessage ?: "None"}")
                        appendLine("Cause Chain: ${diagnosticReport.causeChain ?: "None"}")
                        appendLine("Native Error: ${diagnosticReport.nativeError ?: "None"}")
                        appendLine("--- LOGS ---")
                        diagnosticReport.logs.forEach { appendLine(it) }
                        appendLine("--- STACK TRACE ---")
                        appendLine(diagnosticReport.stackTrace ?: "None")
                    }
                    val clip = ClipData.newPlainText("LocalLLM_Diagnostic_Report", reportText)
                    clipboardManager.setPrimaryClip(clip)
                    Toast.makeText(context, "Full diagnostic report copied to clipboard!", Toast.LENGTH_LONG).show()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF262638)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp), tint = ReplyAIAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Copy Full Diagnostic Report to Clipboard", color = TextPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Expandable Execution Logs
            OutlinedButton(
                onClick = { showRawLogs = !showRawLogs },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text(if (showRawLogs) "Hide Execution Logs" else "Show Execution Logs (${diagnosticReport.logs.size} lines)", fontSize = 11.5.sp)
            }

            AnimatedVisibility(visible = showRawLogs) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0F0F17))
                        .padding(10.dp)
                ) {
                    if (diagnosticReport.logs.isEmpty()) {
                        Text("No logs recorded yet. Tap 'TEST LOCAL MODEL' above to begin.", color = TextTertiary, fontSize = 11.sp)
                    } else {
                        diagnosticReport.logs.forEach { log ->
                            Text(log, color = Color(0xFFC4B5FD), fontSize = 10.5.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MilestoneItem(title: String, isSuccess: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = if (isSuccess) "✓" else "○",
                color = if (isSuccess) SuccessGreen else TextTertiary,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = if (isSuccess) TextPrimary else TextSecondary,
                fontSize = 12.sp,
                fontWeight = if (isSuccess) FontWeight.Bold else FontWeight.Normal
            )
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                color = if (isSuccess) SuccessGreen else TextTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun SpecItem(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextSecondary, fontSize = 11.5.sp)
        Text(text = value, color = TextPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
    }
}
