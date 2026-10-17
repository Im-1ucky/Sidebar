
package com.example.sidebar.recording

import android.content.Context
import android.content.Intent

class RecordingController(
    private val context: Context
) {

    fun start(audioMode: RecordingAudioMode) {
        val intent = Intent(
            context,
            RecordingConsentActivity::class.java
        ).apply {
            putExtra(
                ScreenRecordingService.EXTRA_AUDIO_MODE,
                audioMode.name
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
    }


    fun pause() {
        sendAction(ScreenRecordingService.ACTION_PAUSE)
    }

    fun resume() {
        sendAction(ScreenRecordingService.ACTION_RESUME)
    }

    fun stop() {
        sendAction(ScreenRecordingService.ACTION_STOP)
    }

    private fun sendAction(action: String) {
        val intent = Intent(
            context,
            ScreenRecordingService::class.java
        ).apply {
            this.action = action
        }

        context.startService(intent)
    }
}
