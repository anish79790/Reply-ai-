package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.settings.AppSettingsRepository
import com.example.ui.MainAppContent
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private lateinit var repository: AppSettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        repository = AppSettingsRepository.getInstance(this)

        setContent {
            MyApplicationTheme(darkTheme = true) {
                MainAppContent(repository = repository)
            }
        }
    }
}

