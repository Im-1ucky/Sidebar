
package com.example.sidebar.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.example.sidebar.RECORD_START"
        const val ACTION_PAUSE = "com.example.sidebar.RECORD_PAUSE"
        const val ACTION_RESUME = "com.example.sidebar.RECORD_RESUME"
        const val ACTION_STOP = "com.example.sidebar.RECORD_STOP"

        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TAG = "ScreenRecording"
        private const val CHANNEL_ID = "sidebar_recording"
        private const val NOTIFICATION_ID = 2002
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var isRecording = false
    private var isPaused = false
    private var isFinishing = false

    private var audioMode = RecordingAudioMode.NONE

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> finishRecording()
        }

        return START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (projection != null || isFinishing) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return
        }

        audioMode = runCatching {
            RecordingAudioMode.valueOf(
                intent.getStringExtra(EXTRA_AUDIO_MODE)
                    ?: RecordingAudioMode.NONE.name
            )
        }.getOrDefault(RecordingAudioMode.NONE)

        try {
            startForegroundCompat()

            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("Screen capture permission was not granted")

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        finishRecording()
                    }
                },
                Handler(mainLooper)
            )

            prepareRecorder()
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start recording", exception)
            finishRecording()
        }
    }

    private fun prepareRecorder() {
        val windowManager =
            getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val bounds = windowManager.currentWindowMetrics.bounds

        val width = (bounds.width() / 2) * 2
        val height = (bounds.height() / 2) * 2
        require(width > 0 && height > 0) { "Invalid screen dimensions" }

        val density = resources.configuration.densityDpi
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "Sidebar_$timestamp.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Sidebar")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        outputUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: error("Could not create output video")

        val descriptor = contentResolver.openFileDescriptor(
            outputUri!!,
            "w"
        ) ?: error("Could not open output video")

        try {
            val mediaRecorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

            recorder = mediaRecorder

            if (audioMode == RecordingAudioMode.MICROPHONE) {
                mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            }

            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mediaRecorder.setOutputFile(descriptor.fileDescriptor)
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            mediaRecorder.setVideoSize(width, height)
            mediaRecorder.setVideoFrameRate(30)
            mediaRecorder.setVideoEncodingBitRate(8_000_000)

            if (audioMode == RecordingAudioMode.MICROPHONE) {
                mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mediaRecorder.setAudioEncodingBitRate(128_000)
                mediaRecorder.setAudioSamplingRate(44_100)
            }

            mediaRecorder.prepare()

            virtualDisplay = projection?.createVirtualDisplay(
                "SidebarRecording",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.surface,
                null,
                null
            ) ?: error("Could not create virtual display")

            mediaRecorder.start()
            isRecording = true
        } finally {
            descriptor.close()
        }
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            isRecording && !isPaused
        ) {
            try {
                recorder?.pause()
                isPaused = true
            } catch (exception: Exception) {
                Log.e(TAG, "Pause failed", exception)
            }
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
            isRecording && isPaused
        ) {
            try {
                recorder?.resume()
                isPaused = false
            } catch (exception: Exception) {
                Log.e(TAG, "Resume failed", exception)
            }
        }
    }

    private fun finishRecording() {
        if (isFinishing) return
        isFinishing = true

        try {
            if (isRecording) recorder?.stop()
        } catch (exception: Exception) {
            Log.e(TAG, "Could not finalize recording", exception)
            outputUri?.let { contentResolver.delete(it, null, null) }
            outputUri = null
        } finally {
            isRecording = false
            isPaused = false

            virtualDisplay?.release()
            virtualDisplay = null

            recorder?.release()
            recorder = null

            val activeProjection = projection
            projection = null

            try {
                activeProjection?.stop()
            } catch (exception: Exception) {
                Log.w(TAG, "Projection already stopped", exception)
            }

            outputUri?.let { uri ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    contentResolver.update(uri, values, null, null)
                }
            }

            outputUri = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

            if (audioMode == RecordingAudioMode.MICROPHONE) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Sidebar screen recorder")
                .setContentText("Screen recording is active")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Sidebar screen recorder")
                .setContentText("Screen recording is active")
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sidebar screen recording",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        finishRecording()
        super.onDestroy()
    }
}
