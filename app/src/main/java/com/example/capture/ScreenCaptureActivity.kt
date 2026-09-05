package com.example.capture

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ScreenCaptureActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            _captureResults.tryEmit(CaptureResultData(result.resultCode, result.data!!))
        } else {
            _captureResults.tryEmit(null)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = ScreenCaptureManager(this)
        captureLauncher.launch(manager.createCaptureIntent())
    }

    companion object {
        data class CaptureResultData(val resultCode: Int, val data: Intent)

        private val _captureResults = MutableSharedFlow<CaptureResultData?>(extraBufferCapacity = 1)
        val captureResults = _captureResults.asSharedFlow()
    }
}
