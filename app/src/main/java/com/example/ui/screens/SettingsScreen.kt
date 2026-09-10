package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedButtonDefaults
import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.example.ai.AiProviderRegistry
import com.example.ai.ConnectionState
import com.example.ai.ProviderId
import com.example.settings.AppSettings
import com.example.settings.AppSettingsRepository
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ReplyAIAccent
import com.example.ui.theme.ReplyAIPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun SettingsScreen(
    repository: AppSettingsRepository,
    registry: AiProviderRegistry
) {
    val scope = rememberCoroutineScope()
    val settings by repository.settings.collectAsState()
    var customPersonaText by remember(settings.customPersona) { mutableStateOf(settings.customPersona) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Settings",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Configure floating bubble behavior, AI tone, and memory optimization.",
            color = TextSecondary,
            fontSize = 13.5.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // 0. AI Engine Selection (exactly three user-facing engines)
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
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = ReplyAIAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "AI Engine",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Gemini calls Google Gemini directly. Smart AI automatically routes between the Groq and xKiro providers you configure below. Local AI runs fully on-device.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToneChip(
                        label = if (settings.aiEngine == AppSettings.ENGINE_GEMINI) "✓ Gemini" else "Gemini",
                        onClick = { repository.setAiEngine(AppSettings.ENGINE_GEMINI) }
                    )
                    ToneChip(
                        label = if (settings.aiEngine == AppSettings.ENGINE_SMART) "✓ Smart AI" else "Smart AI",
                        onClick = { repository.setAiEngine(AppSettings.ENGINE_SMART) }
                    )
                    ToneChip(
                        label = if (settings.aiEngine == AppSettings.ENGINE_LOCAL) "✓ Local AI" else "Local AI",
                        onClick = { repository.setAiEngine(AppSettings.ENGINE_LOCAL) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = when (settings.aiEngine) {
                        AppSettings.ENGINE_GEMINI ->
                            "Gemini uses only your Gemini key and calls Google Gemini directly. It is never routed through Smart AI, Groq or xKiro."

                        AppSettings.ENGINE_SMART ->
                            "Smart AI ranks and routes between Groq and xKiro for every request. Configure at least one of those keys below. It never silently falls back to Gemini or Local AI."

                        else ->
                            "Local AI runs entirely on-device with your GGUF model. It never calls Gemini, Groq or xKiro."
                    },
                    color = Color(0xFFC4B5FD),
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp
                )
            }
        }

        // 0.5 AI Providers / API Keys
        AiProvidersCard(registry = registry)



        // 1. Custom Persona & Tone
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
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = ReplyAIPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Custom Persona & Reply Tone",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Give the on-device AI hints about your personality, slang, or style.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                OutlinedTextField(
                    value = customPersonaText,
                    onValueChange = {
                        customPersonaText = it
                        repository.setCustomPersona(it)
                    },
                    placeholder = {
                        Text("e.g. Natural college student, humorous, uses emojis lightly, concise replies", color = TextTertiary, fontSize = 12.5.sp)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp),
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

                // Tone Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToneChip(
                        label = "Casual Banter",
                        onClick = {
                            val preset = "Casual friend banter, witty, short Hinglish replies, authentic slang"
                            customPersonaText = preset
                            repository.setCustomPersona(preset)
                        }
                    )
                    ToneChip(
                        label = "Concise & Safe",
                        onClick = {
                            val preset = "Direct, polite, concise, natural responses"
                            customPersonaText = preset
                            repository.setCustomPersona(preset)
                        }
                    )
                    ToneChip(
                        label = "Playful",
                        onClick = {
                            val preset = "Playful, light-hearted jokes, emojis, engaging"
                            customPersonaText = preset
                            repository.setCustomPersona(preset)
                        }
                    )
                }
            }
        }

        // 1.5 PROMPT VAULT & STRATEGY
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
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = ReplyAIPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Prompt Vault (System Instructions)",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Select the system prompt architecture used for generating replies.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToneChip(
                        label = if (settings.promptStrategy == "gemini") "✓ Default" else "Default",
                        onClick = { repository.setPromptStrategy("gemini") }
                    )
                    ToneChip(
                        label = if (settings.promptStrategy == "grok") "✓ Grok Style" else "Grok Style",
                        onClick = { repository.setPromptStrategy("grok") }
                    )
                    ToneChip(
                        label = if (settings.promptStrategy == "chatgpt") "✓ ChatGPT Style" else "ChatGPT Style",
                        onClick = { repository.setPromptStrategy("chatgpt") }
                    )
                    ToneChip(
                        label = if (settings.promptStrategy == "custom") "✓ Custom" else "Custom",
                        onClick = { repository.setPromptStrategy("custom") }
                    )
                }

                if (settings.promptStrategy == "custom") {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Custom Prompt Template",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "Use variables: {SELECTED_TONE}, {CHAT_HISTORY}, {LAST_MESSAGE}",
                        color = TextSecondary,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    
                    var customPromptT by remember(settings.customPrompt) { mutableStateOf(settings.customPrompt) }
                    OutlinedTextField(
                        value = customPromptT,
                        onValueChange = {
                            customPromptT = it
                            repository.setCustomPrompt(it)
                        },
                        placeholder = { Text("Enter your custom system prompt...", color = TextTertiary, fontSize = 12.5.sp) },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
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
                }
            }
        }

        // 2. OCR Fallback
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Screenshot,
                        contentDescription = null,
                        tint = ReplyAIPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "OCR Screen Reading Fallback",
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Uses on-device ML Kit text recognition if chat app blocks standard accessibility text.",
                            color = TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Switch(
                    checked = settings.enableOcrFallback,
                    onCheckedChange = { repository.setEnableOcrFallback(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ReplyAIPurple,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = Color(0xFF262633)
                    )
                )
            }
        }

        // 3. Memory & RAM Preservation
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
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = ReplyAIPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Memory Watchdog (RAM Preservation)",
                        color = TextPrimary,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "To prevent phone slowdowns, the on-device AI model automatically unloads from RAM after 3 minutes of chat inactivity.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }

        // 4. Floating Overlay Global Switch
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ChatBubbleOutline,
                        contentDescription = null,
                        tint = ReplyAIPurple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Enable Floating Assistant",
                            color = TextPrimary,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Show edge tab and inline suggestion bar inside chats.",
                            color = TextSecondary,
                            fontSize = 11.5.sp
                        )
                    }
                }

                Switch(
                    checked = settings.isOverlayEnabled,
                    onCheckedChange = { repository.setOverlayEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ReplyAIPurple,
                        uncheckedThumbColor = TextTertiary,
                        uncheckedTrackColor = Color(0xFF262633)
                    )
                )
            }
        }

        // 5. Zinro Business Context & Memory
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "💼", fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Business Context & Memory",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Switch(
                        checked = settings.isBusinessContextEnabled,
                        onCheckedChange = { repository.setBusinessContextEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ReplyAIPurple,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = Color(0xFF262633)
                        )
                    )
                }

                Text(
                    text = "Give Zinro facts about your brand, store, catalog, prices, shipping and return policies to answer customer inquiries accurately.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                var bName by remember(settings.businessName) { mutableStateOf(settings.businessName) }
                var bDesc by remember(settings.businessDescription) { mutableStateOf(settings.businessDescription) }

                OutlinedTextField(
                    value = bName,
                    onValueChange = {
                        bName = it
                        repository.setBusinessContext(it, bDesc)
                    },
                    label = { Text("Business / Store / Name") },
                    placeholder = { Text("e.g. Urban Threads Apparel") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ReplyAIPurple,
                        unfocusedBorderColor = Color(0xFF35344A),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = bDesc,
                    onValueChange = {
                        bDesc = it
                        repository.setBusinessContext(bName, it)
                    },
                    label = { Text("Knowledge Base & Policies") },
                    placeholder = { Text("e.g. Free shipping above ₹999. 7-day returns. We accept UPI and COD. Working hours 10am-7pm.") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ReplyAIPurple,
                        unfocusedBorderColor = Color(0xFF35344A),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    shape = RoundedCornerShape(12.dp),
                    minLines = 3,
                    maxLines = 6
                )
            }
        }

        // 6. Right-Edge Tab & Bar Styling
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🎨", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Right-Edge Tab & Positioning",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Customize the right-edge handle that opens the Zinro side panel.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Text(
                    text = "Tab Design",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ToneChip(
                        label = if (settings.edgeTabDesign == "solid") "✓ Solid" else "Solid",
                        onClick = { repository.setEdgeTabDesign("solid") }
                    )
                    ToneChip(
                        label = if (settings.edgeTabDesign == "wide") "✓ Wide" else "Wide",
                        onClick = { repository.setEdgeTabDesign("wide") }
                    )
                    ToneChip(
                        label = if (settings.edgeTabDesign == "outline") "✓ Outline" else "Outline",
                        onClick = { repository.setEdgeTabDesign("outline") }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Tab Height: ${settings.edgeTabHeightDp} dp",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Slider(
                    value = settings.edgeTabHeightDp.toFloat(),
                    onValueChange = { repository.setEdgeTabDimensions(settings.edgeTabWidthDp, it.toInt()) },
                    valueRange = 36f..140f,
                    colors = SliderDefaults.colors(
                        thumbColor = ReplyAIPurple,
                        activeTrackColor = ReplyAIPurple,
                        inactiveTrackColor = Color(0xFF2E2C44)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Bar Position Above Keyboard: ${settings.barPositionAboveKeyboardDp} dp",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Slider(
                    value = settings.barPositionAboveKeyboardDp.toFloat(),
                    onValueChange = { repository.setBarPositionAboveKeyboard(it.toInt()) },
                    valueRange = 20f..160f,
                    colors = SliderDefaults.colors(
                        thumbColor = ReplyAIPurple,
                        activeTrackColor = ReplyAIPurple,
                        inactiveTrackColor = Color(0xFF2E2C44)
                    )
                )
            }
        }

        // 7. In-Chat AI Command Guide
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "⚡", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "In-Chat ai: Command Engine",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = "Type ai: followed by any instruction inside your chat input to generate and paste a full message automatically after a short typing pause.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                )

                val examples = listOf(
                    "ai: write a mail saying we don't have stock",
                    "ai: politely follow up on payment",
                    "ai: apologize for delivery delay in Hinglish",
                    "ai: offer 10% discount to close deal"
                )

                examples.forEach { ex ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .background(DarkBackground, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(text = ex, color = ReplyAIAccent, fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToneChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(DarkBackground)
            .border(1.dp, Color(0xFF35344A), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text = label, color = ReplyAIAccent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------------------------
// AI Providers / API Keys
// ---------------------------------------------------------------------------------------------

private val KeyErrorRed = Color(0xFFFF5252)

/**
 * Dedicated API key section.
 *
 * Keys are written straight into the Keystore-backed [com.example.security.ApiKeyRepository]:
 * they are never held in [com.example.settings.AppSettings], never logged, and never shown back
 * in the field once saved (the input is cleared immediately).
 */
@Composable
private fun AiProvidersCard(registry: AiProviderRegistry) {
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
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI Providers / API Keys",
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = "Keys are encrypted with the Android Keystore and are never logged or exposed in diagnostics. Groq and xKiro are used by Smart AI only.",
                color = TextSecondary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp)
            )

            ProviderKeyRow(
                provider = ProviderId.GEMINI,
                registry = registry,
                helper = "Used by the Gemini engine. Get one at aistudio.google.com."
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProviderKeyRow(
                provider = ProviderId.GROQ,
                registry = registry,
                helper = "Smart AI provider. Key from console.groq.com (starts with gsk_)."
            )

            Spacer(modifier = Modifier.height(16.dp))

            ProviderKeyRow(
                provider = ProviderId.XKIRO,
                registry = registry,
                helper = "Smart AI provider. Key from your xKiro dashboard."
            )
        }
    }
}

@Composable
private fun ProviderKeyRow(
    provider: ProviderId,
    registry: AiProviderRegistry,
    helper: String
) {
    val scope = rememberCoroutineScope()
    val configured by registry.keys.configured.collectAsState()
    val isConfigured = configured.contains(provider)

    var input by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = provider.displayName,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isConfigured) "● Configured" else "○ Not configured",
                color = if (isConfigured) SuccessGreen else TextTertiary,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = input,
            onValueChange = {
                input = it
                if (state !is ConnectionState.Idle) state = ConnectionState.Idle
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    text = if (isConfigured) "Saved • paste a new key to replace it" else "Paste API key…",
                    color = TextTertiary,
                    fontSize = 12.5.sp
                )
            },
            visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed) "Hide key" else "Show key",
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
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

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    val value = input.trim()
                    if (value.isNotBlank()) {
                        registry.keys.setKey(provider, value)
                        // Clear immediately so the key is not retained in UI state.
                        input = ""
                        state = ConnectionState.Connected("Saved.")
                    }
                },
                enabled = input.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E2A4A))
            ) {
                Text(text = "Save", fontSize = 12.sp, color = TextPrimary)
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        testing = true
                        state = ConnectionState.Testing
                        // Test the key the user is about to use: persist first, then verify for real.
                        if (input.isNotBlank()) {
                            registry.keys.setKey(provider, input.trim())
                            input = ""
                        }
                        state = registry.providerFor(provider)?.testConnection()
                            ?: ConnectionState.NotConfigured
                        testing = false
                    }
                },
                enabled = !testing && (isConfigured || input.isNotBlank()),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                colors = OutlinedButtonDefaults.outlinedButtonColors(contentColor = ReplyAIAccent)
            ) {
                Text(
                    text = if (testing) "Testing…" else "⚡ Test Connection",
                    fontSize = 12.sp
                )
            }

            if (isConfigured) {
                OutlinedButton(
                    onClick = {
                        registry.keys.clearKey(provider)
                        input = ""
                        state = ConnectionState.Idle
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = OutlinedButtonDefaults.outlinedButtonColors(contentColor = KeyErrorRed)
                ) {
                    Text(text = "Remove", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(text = helper, color = TextTertiary, fontSize = 11.sp, lineHeight = 15.sp)

        if (state !is ConnectionState.Idle) {
            Spacer(modifier = Modifier.height(6.dp))
            val (message, color) = describeConnectionState(state)
            Text(
                text = message,
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Maps a provider result onto a clear, human-readable state.
 * Raw provider error bodies are never surfaced - only these categories.
 */
private fun describeConnectionState(state: ConnectionState): Pair<String, Color> = when (state) {
    ConnectionState.Idle -> "" to TextTertiary
    ConnectionState.Testing -> "Testing connection…" to TextSecondary
    ConnectionState.NotConfigured -> "No API key configured." to TextTertiary
    is ConnectionState.Connected -> "✓ ${state.detail}" to SuccessGreen
    is ConnectionState.InvalidKey -> "✕ Invalid API key — ${state.message}" to KeyErrorRed
    is ConnectionState.NoEligibleModel -> "⚠ No eligible models — ${state.message}" to WarningAmber
    is ConnectionState.RateLimited -> "⚠ Rate limited — ${state.message}" to WarningAmber
    is ConnectionState.NetworkUnavailable -> "✕ Network unavailable — ${state.message}" to KeyErrorRed
    is ConnectionState.Unavailable -> "⚠ Provider unavailable — ${state.message}" to WarningAmber
    is ConnectionState.Failed -> "✕ ${state.message}" to KeyErrorRed
}
