package com.example.sidebar.recording

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class RecordingConsentActivity : ComponentActivity() {

    private val consentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {
                val serviceIntent = Intent(
                    this,
                    ScreenRecordingService::class.java
                ).apply {
                    action = ScreenRecordingService.ACTION_START

                    putExtra(
                        ScreenRecordingService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )

                    putExtra(
                        ScreenRecordingService.EXTRA_RESULT_DATA,
                        result.data
                    )

                    putExtra(
                        ScreenRecordingService.EXTRA_AUDIO_MODE,
                        intent.getStringExtra(
                            ScreenRecordingService.EXTRA_AUDIO_MODE
                        ) ?: RecordingAudioMode.NONE.name
                    )
                }

                startForegroundService(serviceIntent)
            }

            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val captureIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }

        consentLauncher.launch(captureIntent)
    }
}