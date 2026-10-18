
package com.example.sidebar.recording

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class RecordingConsentActivity : ComponentActivity() {

    private val audioMode: RecordingAudioMode
        get() = runCatching {
            RecordingAudioMode.valueOf(
                intent.getStringExtra(ScreenRecordingService.EXTRA_AUDIO_MODE)
                    ?: RecordingAudioMode.MICROPHONE.name
            )
        }.getOrDefault(RecordingAudioMode.MICROPHONE)

    private val microphonePermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                launchScreenCaptureConsent()
            } else {
                // Do not silently start a microphone recording without permission.
                finish()
            }
        }

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
                        audioMode.name
                    )
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                } finally {
                    finish()
                }
            } else {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            audioMode == RecordingAudioMode.MICROPHONE &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        } else {
            launchScreenCaptureConsent()
        }
    }

    private fun launchScreenCaptureConsent() {
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
