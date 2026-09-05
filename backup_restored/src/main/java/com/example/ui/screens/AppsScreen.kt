package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.settings.AppSettingsRepository
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkCard
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ReplyAIAccent
import com.example.ui.theme.ReplyAIPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

data class SupportedApp(
    val name: String,
    val packageName: String,
    val category: String,
    val icon: ImageVector,
    val iconTint: Color
)

val SUPPORTED_APPS = listOf(
    SupportedApp(
        name = "Instagram",
        packageName = "com.instagram.android",
        category = "Direct Messages & Stories",
        icon = Icons.AutoMirrored.Filled.Chat,
        iconTint = Color(0xFFE1306C)
    ),
    SupportedApp(
        name = "Instagram Lite",
        packageName = "com.instagram.lite",
        category = "Direct Messages",
        icon = Icons.AutoMirrored.Filled.Chat,
        iconTint = Color(0xFFC13584)
    ),
    SupportedApp(
        name = "WhatsApp",
        packageName = "com.whatsapp",
        category = "Personal & Group Chats",
        icon = Icons.AutoMirrored.Filled.Message,
        iconTint = Color(0xFF25D366)
    ),
    SupportedApp(
        name = "WhatsApp Business",
        packageName = "com.whatsapp.w4b",
        category = "Business Chats",
        icon = Icons.AutoMirrored.Filled.Message,
        iconTint = Color(0xFF128C7E)
    ),
    SupportedApp(
        name = "Telegram",
        packageName = "org.telegram.messenger",
        category = "Chats & Channels",
        icon = Icons.AutoMirrored.Filled.Send,
        iconTint = Color(0xFF0088CC)
    ),
    SupportedApp(
        name = "Facebook Messenger",
        packageName = "com.facebook.orca",
        category = "Messenger Chats",
        icon = Icons.Default.Forum,
        iconTint = Color(0xFF0084FF)
    ),
    SupportedApp(
        name = "Discord",
        packageName = "com.discord",
        category = "Server & DMs",
        icon = Icons.AutoMirrored.Filled.Chat,
        iconTint = Color(0xFF5865F2)
    ),
    SupportedApp(
        name = "Messages (SMS)",
        packageName = "com.google.android.apps.messaging",
        category = "RCS & SMS",
        icon = Icons.AutoMirrored.Filled.Message,
        iconTint = Color(0xFF1A73E8)
    )
)

@Composable
fun AppsScreen(repository: AppSettingsRepository) {
    val settings by repository.settings.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp)
    ) {
        Text(
            text = "Supported Apps",
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Choose which apps display the floating [ ReplyAI ✨ ] suggestion pill when you open a chat.",
            color = TextSecondary,
            fontSize = 13.5.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        SUPPORTED_APPS.forEach { app ->
            val isEnabled = settings.enabledApps.contains(app.packageName)

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(app.iconTint.copy(alpha = 0.15f)),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = app.icon,
                                contentDescription = null,
                                tint = app.iconTint,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Column {
                            Text(
                                text = app.name,
                                color = TextPrimary,
                                fontSize = 15.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = app.category,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = app.packageName,
                                color = TextTertiary,
                                fontSize = 10.5.sp
                            )
                        }
                    }

                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            repository.toggleApp(app.packageName, checked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ReplyAIPurple,
                            uncheckedThumbColor = TextTertiary,
                            uncheckedTrackColor = Color(0xFF262633)
                        )
                    )
                }
            }
        }
    }
}
