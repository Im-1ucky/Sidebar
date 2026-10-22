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
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.os.Looper

class ScreenRecordingService : Service() {

    companion object {
        const val ACTION_START = "com.example.sidebar.RECORD_START"
        const val ACTION_PAUSE = "com.example.sidebar.RECORD_PAUSE"
        const val ACTION_RESUME = "com.example.sidebar.RECORD_RESUME"
        const val ACTION_STOP = "com.example.sidebar.RECORD_STOP"

        const val ACTION_RECORDING_READY = "com.example.sidebar.RECORDING_READY"
        const val ACTION_RECORDING_PAUSED = "com.example.sidebar.RECORDING_PAUSED"
        const val ACTION_RECORDING_RESUMED = "com.example.sidebar.RECORDING_RESUMED"
        const val ACTION_RECORDING_STOPPED = "com.example.sidebar.RECORDING_STOPPED"
        const val ACTION_RECORDING_FAILED = "com.example.sidebar.RECORDING_FAILED"

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
    private var cleanupComplete = false
    private var preparationInProgress = false
    private var audioMode = RecordingAudioMode.NONE

    private val recordingExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    private val mainHandler = Handler(Looper.getMainLooper())

    private data class PreparedRecording(
        val recorder: MediaRecorder,
        val descriptor: ParcelFileDescriptor,
        val outputUri: Uri,
        val outputIsMediaStore: Boolean,
        val width: Int,
        val height: Int,
        val density: Int
    )

    private fun broadcastRecordingState(action: String) {
        sendBroadcast(Intent(action).setPackage(packageName))
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
        if (
            projection != null ||
            preparationInProgress ||
            isFinishing ||
            cleanupComplete
        ) return

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
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

            val manager =
                getSystemService(MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager

            projection = manager.getMediaProjection(
                resultCode,
                resultData
            ) ?: error("Screen capture permission was not granted")

            projection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        finishRecording()
                    }
                },
                mainHandler
            )

            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            val bounds = windowManager.currentWindowMetrics.bounds
            val width = (bounds.width() / 2) * 2
            val height = (bounds.height() / 2) * 2

            require(width > 0 && height > 0) {
                "Invalid screen dimensions"
            }

            val density = resources.configuration.densityDpi

            preparationInProgress = true

            recordingExecutor.execute {
                val result = runCatching {
                    prepareRecorder(width, height, density)
                }

                mainHandler.post {
                    preparationInProgress = false

                    val prepared = result.getOrNull()

                    if (prepared == null) {
                        result.exceptionOrNull()?.let {
                            Log.e(TAG, "Failed to prepare recorder", it)
                        }

                        broadcastRecordingState(ACTION_RECORDING_FAILED)
                        isFinishing = true
                        completeFinishRecording()
                        return@post
                    }

                    if (isFinishing || projection == null) {
                        discardPreparedRecording(prepared)
                        completeFinishRecording()
                        return@post
                    }

                    recorder = prepared.recorder
                    outputUri = prepared.outputUri
                    outputIsMediaStore = prepared.outputIsMediaStore

                    try {
                        virtualDisplay = projection?.createVirtualDisplay(
                            "SidebarRecording",
                            prepared.width,
                            prepared.height,
                            prepared.density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            prepared.recorder.surface,
                            null,
                            null
                        ) ?: error("Could not create virtual display")

                        prepared.recorder.start()
                        isRecording = true

                        broadcastRecordingState(ACTION_RECORDING_READY)
                    } catch (exception: Exception) {
                        Log.e(
                            TAG,
                            "Failed to start recording",
                            exception
                        )

                        outputUri?.let {
                            runCatching {
                                contentResolver.delete(it, null, null)
                            }
                        }

                        outputUri = null
                        outputIsMediaStore = false

                        broadcastRecordingState(ACTION_RECORDING_FAILED)
                        finishRecording()
                    } finally {
                        runCatching {
                            prepared.descriptor.close()
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to start recording", exception)
            broadcastRecordingState(ACTION_RECORDING_FAILED)
            finishRecording()
        }
    }

    /**
     * File-provider/MediaStore operations and MediaRecorder.prepare()
     * run on the background executor.
     *
     * VirtualDisplay creation and recorder.start() are performed
     * on the main thread after preparation completes.
     */
    private fun prepareRecorder(
        width: Int,
        height: Int,
        density: Int
    ): PreparedRecording {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val fileName = "Sidebar_$timestamp.mp4"

        val customFolderUri = getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        ).getString(KEY_RECORDING_FOLDER_URI, null)

        var createdUri: Uri? = null
        var isMediaStore = false
        var descriptor: ParcelFileDescriptor? = null
        var createdRecorder: MediaRecorder? = null

        try {
            if (customFolderUri != null) {
                val treeUri = customFolderUri.toUri()

                val parentDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )

                createdUri = DocumentsContract.createDocument(
                    contentResolver,
                    parentDocumentUri,
                    "video/mp4",
                    fileName
                ) ?: error(
                    "Could not create video in the selected folder"
                )

                descriptor = contentResolver.openFileDescriptor(
                    createdUri,
                    "w"
                ) ?: error(
                    "Could not open the video in the selected folder"
                )
            } else {
                val values = ContentValues().apply {
                    put(
                        MediaStore.Video.Media.DISPLAY_NAME,
                        fileName
                    )
                    put(
                        MediaStore.Video.Media.MIME_TYPE,
                        "video/mp4"
                    )
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        "Pictures/Sidebar"
                    )
                    put(
                        MediaStore.Video.Media.IS_PENDING,
                        1
                    )
                }

                createdUri = contentResolver.insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: error("Could not create output video")

                isMediaStore = true

                descriptor = contentResolver.openFileDescriptor(
                    createdUri,
                    "w"
                ) ?: error("Could not open output video")
            }

            val outputDescriptor = descriptor

            val configuredRecorder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }

            createdRecorder = configuredRecorder

            if (audioMode == RecordingAudioMode.MICROPHONE) {
                configuredRecorder.setAudioSource(
                    MediaRecorder.AudioSource.MIC
                )
            }

            configuredRecorder.setVideoSource(
                MediaRecorder.VideoSource.SURFACE
            )

            configuredRecorder.setOutputFormat(
                MediaRecorder.OutputFormat.MPEG_4
            )

            configuredRecorder.setOutputFile(
                outputDescriptor.fileDescriptor
            )

            configuredRecorder.setVideoEncoder(
                MediaRecorder.VideoEncoder.H264
            )

            configuredRecorder.setVideoSize(width, height)
            configuredRecorder.setVideoFrameRate(30)
            configuredRecorder.setVideoEncodingBitRate(8_000_000)

            if (audioMode == RecordingAudioMode.MICROPHONE) {
                configuredRecorder.setAudioEncoder(
                    MediaRecorder.AudioEncoder.AAC
                )

                configuredRecorder.setAudioEncodingBitRate(128_000)
                configuredRecorder.setAudioSamplingRate(44_100)
            }

            configuredRecorder.prepare()

            val finalOutputUri = createdUri

            return PreparedRecording(
                recorder = configuredRecorder,
                descriptor = outputDescriptor,
                outputUri = finalOutputUri,
                outputIsMediaStore = isMediaStore,
                width = width,
                height = height,
                density = density
            )
        } catch (exception: Exception) {
            createdRecorder?.let {
                runCatching { it.reset() }
                runCatching { it.release() }
            }

            descriptor?.let {
                runCatching { it.close() }
            }

            createdUri?.let {
                runCatching {
                    contentResolver.delete(it, null, null)
                }
            }

            throw exception
        }
    }

    private fun discardPreparedRecording(
        prepared: PreparedRecording
    ) {
        runCatching { prepared.recorder.reset() }
        runCatching { prepared.recorder.release() }
        runCatching { prepared.descriptor.close() }

        runCatching {
            contentResolver.delete(
                prepared.outputUri,
                null,
                null
            )
        }
    }

    private fun pauseRecording() {
        if (isRecording && !isPaused && !isFinishing) {
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
        if (isRecording && isPaused && !isFinishing) {
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
        if (isFinishing || cleanupComplete) return

        isFinishing = true

        if (preparationInProgress) {
            // The worker retains ownership of its local resources
            // until preparation returns.
            runCatching {
                projection?.stop()
            }
            return
        }

        completeFinishRecording()
    }

    private fun completeFinishRecording() {
        if (cleanupComplete) return
        cleanupComplete = true

        try {
            if (isRecording) {
                recorder?.stop()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Could not finalize recording", exception)

            outputUri?.let {
                runCatching {
                    contentResolver.delete(it, null, null)
                }
            }

            outputUri = null
        } finally {
            isRecording = false
            isPaused = false

            runCatching {
                virtualDisplay?.release()
            }
            virtualDisplay = null

            runCatching {
                recorder?.release()
            }
            recorder = null

            val activeProjection = projection
            projection = null

            runCatching {
                activeProjection?.stop()
            }

            outputUri?.let { uri ->
                if (outputIsMediaStore) {
                    val values = ContentValues().apply {
                        put(
                            MediaStore.Video.Media.IS_PENDING,
                            0
                        )
                    }

                    runCatching {
                        contentResolver.update(
                            uri,
                            values,
                            null,
                            null
                        )
                    }.onFailure {
                        Log.e(
                            TAG,
                            "Could not finalize saved video",
                            it
                        )
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

        var type =
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

        if (audioMode == RecordingAudioMode.MICROPHONE) {
            type = type or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        startForeground(
            NOTIFICATION_ID,
            notification,
            type
        )
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Sidebar screen recorder")
            .setContentText("Screen recording is active")
            .setSmallIcon(
                android.R.drawable.presence_video_online
            )
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
        recordingExecutor.shutdown()
        super.onDestroy()
    }
}