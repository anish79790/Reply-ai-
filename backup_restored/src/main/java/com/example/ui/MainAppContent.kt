package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llm.AndroidDeviceCapabilityManager
import com.example.llm.ModelDownloadManager
import com.example.llm.QuantizedLocalLLMEngine
import com.example.prompt.ReplyPromptBuilder
import com.example.reply.LocalReplyGenerator
import com.example.settings.AppSettingsRepository
import com.example.ui.screens.AppsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.ModelSetupScreen
import com.example.ui.screens.PrivacyScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.ReplyAIAccent
import com.example.ui.theme.ReplyAIPurple
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextTertiary

sealed class NavItem(val route: String, val title: String, val icon: ImageVector) {
    data object Home : NavItem("home", "Home", Icons.Default.Home)
    data object Model : NavItem("model", "AI Model", Icons.Default.Memory)
    data object Apps : NavItem("apps", "Apps", Icons.Default.Apps)
    data object Settings : NavItem("settings", "Settings", Icons.Default.Settings)
    data object Privacy : NavItem("privacy", "Privacy", Icons.Default.Security)
}

@Composable
fun MainAppContent(repository: AppSettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val capabilityManager = remember { AndroidDeviceCapabilityManager(context) }
    val downloadManager = remember { ModelDownloadManager(context, scope) }
    val llmEngine = remember { QuantizedLocalLLMEngine(context, capabilityManager, downloadManager, scope) }
    val promptBuilder = remember { ReplyPromptBuilder() }
    val replyGenerator = remember(repository) {
        LocalReplyGenerator(
            promptBuilder = promptBuilder,
            llmEngine = llmEngine,
            capabilityManager = capabilityManager,
            appSettingsRepository = repository
        )
    }

    var currentTab by remember { mutableStateOf<NavItem>(NavItem.Home) }

    val navItems = listOf(
        NavItem.Home,
        NavItem.Model,
        NavItem.Apps,
        NavItem.Settings,
        NavItem.Privacy
    )

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                tonalElevation = 8.dp
            ) {
                navItems.forEach { item ->
                    val isSelected = currentTab.route == item.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = item },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = item.title,
                                fontSize = 11.sp
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = ReplyAIAccent,
                            unselectedIconColor = TextTertiary,
                            unselectedTextColor = TextTertiary,
                            indicatorColor = ReplyAIPurple
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground)
                .padding(paddingValues)
        ) {
            when (currentTab) {
                NavItem.Home -> HomeScreen(
                    repository = repository,
                    downloadManager = downloadManager,
                    capabilityManager = capabilityManager,
                    replyGenerator = replyGenerator,
                    onNavigateToModelSetup = { currentTab = NavItem.Model }
                )
                NavItem.Model -> ModelSetupScreen(
                    downloadManager = downloadManager,
                    capabilityManager = capabilityManager,
                    repository = repository
                )
                NavItem.Apps -> AppsScreen(repository = repository)
                NavItem.Settings -> SettingsScreen(repository = repository)
                NavItem.Privacy -> PrivacyScreen()
            }
        }
    }
}
