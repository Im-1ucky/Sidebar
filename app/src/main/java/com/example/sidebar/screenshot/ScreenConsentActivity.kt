package com.example.sidebar.screenshot

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ScreenshotConsentActivity : ComponentActivity() {

    private val preferences by lazy {
        getSharedPreferences("sidebar_preferences", MODE_PRIVATE)
    }

    private val consentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            try {
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val captureIntent = Intent(
                        this,
                        ScreenshotCaptureService::class.java
                    ).apply {
                        putExtra(
                            ScreenshotCaptureService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )
                        putExtra(
                            ScreenshotCaptureService.EXTRA_RESULT_DATA,
                            result.data
                        )
                        putExtra(
                            ScreenshotCaptureService.EXTRA_FOLDER_URI,
                            preferences.getString("screenshot_folder_uri", null)
                        )
                        putExtra(
                            ScreenshotCaptureService.EXTRA_DELAY_SECONDS,
                            preferences.getInt("screenshot_delay_seconds", 0)
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(captureIntent)
                    } else {
                        startService(captureIntent)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenshotConsent", "Unable to start screenshot capture", e)
            } finally {
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        val captureIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig
                        .createConfigForDefaultDisplay()
                )
            } else {
                manager.createScreenCaptureIntent()
            }

        consentLauncher.launch(captureIntent)
    }
}