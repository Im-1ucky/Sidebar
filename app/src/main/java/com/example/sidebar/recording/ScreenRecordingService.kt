
package com.example.sidebar.recording

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.net.toUri

class ScreenRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.example.sidebar.RECORD_START"
        const val ACTION_PAUSE = "com.example.sidebar.RECORD_PAUSE"
        const val ACTION_RESUME = "com.example.sidebar.RECORD_RESUME"
        const val ACTION_STOP = "com.example.sidebar.RECORD_STOP"

        const val ACTION_RECORDING_READY =
            "com.example.sidebar.RECORDING_READY"
        const val ACTION_RECORDING_PAUSED =
            "com.example.sidebar.RECORDING_PAUSED"
        const val ACTION_RECORDING_RESUMED =
            "com.example.sidebar.RECORDING_RESUMED"
        const val ACTION_RECORDING_STOPPED =
            "com.example.sidebar.RECORDING_STOPPED"
        const val ACTION_RECORDING_FAILED =
            "com.example.sidebar.RECORDING_FAILED"

        const val EXTRA_AUDIO_MODE = "audio_mode"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val TAG = "ScreenRecording"
        private const val CHANNEL_ID = "sidebar_recording"
        private const val NOTIFICATION_ID = 2002
        private const val PREFS_NAME = "sidebar_preferences"
        private const val KEY_RECORDING_FOLDER_URI = "recording_folder_uri"
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputIsMediaStore = false
    private var isRecording = false
    private var isPaused = false
    private var isFinishing = false
    private var audioMode = RecordingAudioMode.NONE

    private fun broadcastRecordingState(action: String) {
        sendBroadcast(
            Intent(action).setPackage(packageName)
        )
    }

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

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            broadcastRecordingState(ACTION_RECORDING_FAILED)
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
                MEDIA_PROJECTION_SERVICE
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

            if (isRecording) {
                broadcastRecordingState(ACTION_RECORDING_READY)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start recording", exception)
            broadcastRecordingState(ACTION_RECORDING_FAILED)
            finishRecording()
        }
    }

    private fun prepareRecorder() {
        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val bounds = windowManager.currentWindowMetrics.bounds
        val width = (bounds.width() / 2) * 2
        val height = (bounds.height() / 2) * 2
        require(width > 0 && height > 0) { "Invalid screen dimensions" }

        val density = resources.configuration.densityDpi
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val fileName = "Sidebar_$timestamp.mp4"
        val customFolderUri = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).getString(KEY_RECORDING_FOLDER_URI, null)

        val descriptor: android.os.ParcelFileDescriptor

        if (customFolderUri != null) {
            val treeUri = customFolderUri.toUri()

            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri)
            )

            val createdUri = DocumentsContract.createDocument(
                contentResolver,
                parentDocumentUri,
                "video/mp4",
                fileName
            ) ?: error("Could not create video in the selected folder")

            outputUri = createdUri
            outputIsMediaStore = false

            descriptor = try {
                contentResolver.openFileDescriptor(createdUri, "w")
                    ?: error("Could not open the video in the selected folder")
            } catch (exception: Exception) {
                contentResolver.delete(createdUri, null, null)
                outputUri = null
                throw exception
            }
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Pictures/Sidebar")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val createdUri = contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: error("Could not create output video")

            outputUri = createdUri
            outputIsMediaStore = true

            descriptor = try {
                contentResolver.openFileDescriptor(createdUri, "w")
                    ?: error("Could not open output video")
            } catch (exception: Exception) {
                contentResolver.delete(createdUri, null, null)
                outputUri = null
                throw exception
            }
        }

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
        } catch (exception: Exception) {
            outputUri?.let { uri ->
                runCatching { contentResolver.delete(uri, null, null) }
            }
            outputUri = null
            throw exception
        } finally {
            descriptor.close()
        }
    }

    private fun pauseRecording() {
        if (
            isRecording && !isPaused
        ) {
            try {
                recorder?.pause()
                isPaused = true
                broadcastRecordingState(ACTION_RECORDING_PAUSED)
            } catch (exception: Exception) {
                Log.e(TAG, "Pause failed", exception)
            }
        }
    }

    private fun resumeRecording() {
        if (
            isRecording && isPaused
        ) {
            try {
                recorder?.resume()
                isPaused = false
                broadcastRecordingState(ACTION_RECORDING_RESUMED)
            } catch (exception: Exception) {
                Log.e(TAG, "Resume failed", exception)
            }
        }
    }

    private fun finishRecording() {
        if (isFinishing) return
        isFinishing = true

        try {
            if (isRecording) {
                recorder?.stop()
            }
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
                if (
                    outputIsMediaStore
                ) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }

                    runCatching {
                        contentResolver.update(uri, values, null, null)
                    }.onFailure {
                        Log.e(TAG, "Could not finalize saved video", it)
                    }
                }
            }

            outputUri = null
            outputIsMediaStore = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            broadcastRecordingState(ACTION_RECORDING_STOPPED)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        if (audioMode == RecordingAudioMode.MICROPHONE) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        startForeground(NOTIFICATION_ID, notification, type)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sidebar screen recorder")
            .setContentText("Screen recording is active")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sidebar screen recording",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        finishRecording()
        super.onDestroy()
    }
}
