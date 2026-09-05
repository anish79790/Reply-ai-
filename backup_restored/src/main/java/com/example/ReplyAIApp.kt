package com.example

import android.app.Application
import com.example.settings.AppSettingsRepository

class ReplyAIApp : Application() {

    lateinit var repository: AppSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        repository = AppSettingsRepository.getInstance(this)
    }

    companion object {
        lateinit var instance: ReplyAIApp
            private set
    }
}
