package com.example.sidebar.screenshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import androidx.core.graphics.createBitmap

class ScreenshotCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)
    private val captureRequested = AtomicBoolean(false)
    private val captureRequestedAtMs = AtomicLong(0L)
    private val captureTapAtMs = AtomicLong(0L)

    // JPEG compression and storage writing happen on this separate thread.
    private val saveExecutor = Executors.newSingleThreadExecutor()

    private var folderUri: Uri? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        Log.i(TAG, "onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_SESSION -> {
                Log.i(TAG, "Stopping screenshot session")
                finishCapture()
                return START_NOT_STICKY
            }

            ACTION_CAPTURE -> {
                if (projection == null || finished.get()) {
                    Log.w(TAG, "No active screenshot session")
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

                val delaySeconds = intent.getIntExtra(
                    EXTRA_DELAY_SECONDS,
                    0
                ).coerceIn(0, 5)

                captureTapAtMs.set(SystemClock.elapsedRealtime())

                Log.i(
                    TAG,
                    "Capture tapped; configured delay=${delaySeconds}s"
                )

                requestCapture(delaySeconds)
                return START_NOT_STICKY
            }
        }

        if (projection != null) {
            Log.w(TAG, "Screenshot session already active")
            return START_NOT_STICKY
        }

        if (intent == null) {
            Log.w(TAG, "Service started with null intent")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        if (resultCode != RESULT_OK_VALUE || resultData == null) {
            Log.e(TAG, "Missing or invalid screen capture permission data")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        folderUri = intent.getStringExtra(EXTRA_FOLDER_URI)
            ?.let(Uri::parse)

        val delaySeconds = intent.getIntExtra(
            EXTRA_DELAY_SECONDS,
            0
        ).coerceIn(0, 5)

        createNotificationChannel()

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            val manager = getSystemService(
                MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            val activeProjection = manager.getMediaProjection(
                resultCode,
                resultData
            ) ?: error("Android did not provide a screen projection")

            projection = activeProjection

            val thread = HandlerThread("SidebarScreenshot").also {
                it.start()
            }
            workerThread = thread
            workerHandler = Handler(thread.looper)

            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "Screen projection stopped")
                    finishCapture()
                }
            }
            projectionCallback = callback

            activeProjection.registerCallback(
                callback,
                workerHandler
            )

            Log.i(TAG, "Initializing screenshot capture session")
            initializeCaptureSession(delaySeconds)

        } catch (exception: Exception) {
            Log.e(TAG, "Unable to initialize screen capture", exception)

            sendBroadcast(
                Intent(ACTION_SESSION_FAILED).setPackage(packageName)
            )

            showToast("Couldn't start screenshot")
            finishCapture()
        }

        return START_NOT_STICKY
    }

    private fun initializeCaptureSession(initialDelaySeconds: Int) {
        val initStart = SystemClock.elapsedRealtime()

        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        val bounds = windowManager.currentWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        val density = resources.configuration.densityDpi

        require(width > 0 && height > 0) {
            "Invalid screen dimensions"
        }

        Log.i(TAG, "Screen dimensions: ${width}x${height}, density=$density")

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader = reader

        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage()
                ?: return@setOnImageAvailableListener

            try {
                if (
                    finished.get() ||
                    !captureRequested.compareAndSet(true, false)
                ) {
                    return@setOnImageAvailableListener
                }

                val frameTime = SystemClock.elapsedRealtime()
                val tapTime = captureTapAtMs.get()
                val armedTime = captureRequestedAtMs.get()

                Log.i(
                    TAG,
                    "CAPTURE_FRAME: " +
                            "tapToFrame=${if (tapTime > 0L) frameTime - tapTime else -1L}ms, " +
                            "armedToFrame=${if (armedTime > 0L) frameTime - armedTime else -1L}ms"
                )

                val bitmapStart = SystemClock.elapsedRealtime()

                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val paddedBitmap = createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )

                paddedBitmap.copyPixelsFromBuffer(plane.buffer)

                val bitmap = Bitmap.createBitmap(
                    paddedBitmap,
                    0,
                    0,
                    width,
                    height
                )

                paddedBitmap.recycle()

                Log.i(
                    TAG,
                    "BITMAP_READY: processing=" +
                            "${SystemClock.elapsedRealtime() - bitmapStart}ms"
                )

                // Give immediate visual feedback before JPEG compression.
                showFlash()

                // The capture thread is now free to receive future frames.
                // Only the save worker compresses and writes the JPEG.
                saveExecutor.execute {
                    val saveStart = SystemClock.elapsedRealtime()

                    try {
                        Log.i(TAG, "SAVE_START: customFolder=${folderUri != null}")

                        val saved = saveBitmap(bitmap)

                        val saveDuration =
                            SystemClock.elapsedRealtime() - saveStart

                        val totalDuration =
                            if (tapTime > 0L) {
                                SystemClock.elapsedRealtime() - tapTime
                            } else {
                                -1L
                            }

                        Log.i(
                            TAG,
                            "SAVE_COMPLETE: saved=$saved, " +
                                    "saveDuration=${saveDuration}ms, " +
                                    "totalTapToSave=${totalDuration}ms"
                        )

                        if (saved) {
                            showToast("Screenshot saved")
                        } else {
                            showToast("Couldn't save screenshot")
                        }
                    } catch (exception: Exception) {
                        Log.e(TAG, "Background screenshot save failed", exception)
                        showToast("Couldn't save screenshot")
                    } finally {
                        bitmap.recycle()
                    }
                }

                Log.i(
                    TAG,
                    "SAVE_QUEUED: frameToQueue=" +
                            "${SystemClock.elapsedRealtime() - frameTime}ms"
                )

            } catch (exception: Exception) {
                Log.e(TAG, "Failed to process screenshot frame", exception)
                showToast("Couldn't capture screenshot")
            } finally {
                image.close()
            }
        }, workerHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "SidebarScreenshot",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            workerHandler
        ) ?: error("Could not create virtual display")

        Log.i(
            TAG,
            "VIRTUAL_DISPLAY_READY: initialization=" +
                    "${SystemClock.elapsedRealtime() - initStart}ms"
        )

        sendBroadcast(
            Intent(ACTION_SESSION_READY).setPackage(packageName)
        )

        Log.i(TAG, "Screenshot session ready")

        // Preserve the existing initial capture behavior.
        requestCapture(initialDelaySeconds)
    }

    private fun requestCapture(delaySeconds: Int) {
        if (finished.get()) {
            Log.w(TAG, "Ignoring capture request: session finished")
            return
        }

        Log.i(TAG, "Scheduling capture after ${delaySeconds}s + 400ms")

        mainHandler.postDelayed({
            if (finished.get()) {
                Log.w(TAG, "Capture cancelled: session finished")
                return@postDelayed
            }

            if (!captureRequested.compareAndSet(false, true)) {
                Log.w(TAG, "A screenshot is already waiting to be captured")
            } else {
                captureRequestedAtMs.set(SystemClock.elapsedRealtime())

                Log.i(
                    TAG,
                    "SCREENSHOT_ARMED: elapsedRealtime=" +
                            "${captureRequestedAtMs.get()}ms"
                )
            }
        }, delaySeconds * 1000L + 400L)
    }

    private fun saveBitmap(bitmap: Bitmap): Boolean {
        val saveStart = SystemClock.elapsedRealtime()

        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            Locale.US
        ).format(Date())

        val filename = "Screenshot_$timestamp.jpg"

        return try {
            Log.i(TAG, "Creating JPEG: $filename")

            val output = folderUri?.let { treeUri ->
                val documentId =
                    DocumentsContract.getTreeDocumentId(treeUri)

                val treeDocumentUri =
                    DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        documentId
                    )

                val newFile = DocumentsContract.createDocument(
                    contentResolver,
                    treeDocumentUri,
                    "image/jpeg",
                    filename
                )

                newFile?.let {
                    contentResolver.openOutputStream(it, "w")
                }
            } ?: openMediaStoreOutput(filename)

            if (output == null) {
                Log.e(TAG, "SAVE_FAILED: output stream is null")
                return false
            }


            val compressionStart = SystemClock.elapsedRealtime()

            val compressed = try {
                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    80,
                    output
                )
            } finally {
                Log.i(
                    TAG,
                    "JPEG_COMPRESS_ONLY: " +
                            "${SystemClock.elapsedRealtime() - compressionStart}ms"
                )

                val closeStart = SystemClock.elapsedRealtime()

                try {
                    output.close()
                } finally {
                    Log.i(
                        TAG,
                        "OUTPUT_CLOSE_AND_FINALIZE: " +
                                "${SystemClock.elapsedRealtime() - closeStart}ms"
                    )
                }
            }

            if (!compressed) {
                Log.e(TAG, "SAVE_FAILED: JPEG compression returned false")
                return false
            }

            Log.i(
                TAG,
                "SAVE_SUCCESS: total=${SystemClock.elapsedRealtime() - saveStart}ms"
            )

            true
        } catch (exception: Exception) {
            Log.e(TAG, "Screenshot storage failed", exception)
            false
        }
    }

    private fun openMediaStoreOutput(filename: String): OutputStream? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/Sidebar"
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null

        val stream = contentResolver.openOutputStream(uri, "w")

        if (stream == null) {
            contentResolver.delete(uri, null, null)
            return null
        }

        return object : java.io.FilterOutputStream(stream) {
            override fun close() {
                super.close()

                val complete = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }

                contentResolver.update(uri, complete, null, null)
            }
        }
    }

    private fun showFlash() {
        mainHandler.post {
            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            val flashView = FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                alpha = 0.75f
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            try {
                windowManager.addView(flashView, params)

                flashView.animate()
                    .alpha(0f)
                    .setDuration(160L)
                    .withEndAction {
                        try {
                            if (flashView.isAttachedToWindow) {
                                windowManager.removeView(flashView)
                            }
                        } catch (exception: Exception) {
                            Log.w(
                                TAG,
                                "Couldn't remove screenshot flash",
                                exception
                            )
                        }
                    }
                    .start()
            } catch (exception: Exception) {
                Log.w(TAG, "Couldn't display screenshot flash", exception)
            }
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun finishCapture() {
        if (!finished.compareAndSet(false, true)) return

        Log.i(TAG, "Cleaning up screenshot session")

        try {
            virtualDisplay?.release()
        } catch (exception: Exception) {
            Log.w(TAG, "Virtual display cleanup failed", exception)
        }
        virtualDisplay = null

        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (exception: Exception) {
            Log.w(TAG, "Image reader cleanup failed", exception)
        }
        imageReader = null

        val activeProjection = projection
        projection = null

        try {
            val callback = projectionCallback
            if (callback != null && activeProjection != null) {
                activeProjection.unregisterCallback(callback)
            }
        } catch (_: Exception) {
            // The projection may already have stopped.
        }

        try {
            activeProjection?.stop()
        } catch (_: Exception) {
            // It may already have stopped.
        }

        projectionCallback = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Screenshot service destroyed")

        finished.set(true)

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            imageReader?.close()
        } catch (_: Exception) {
        }
        imageReader = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        workerThread?.quitSafely()
        workerThread = null

        saveExecutor.shutdown()

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screenshot capture",
            NotificationManager.IMPORTANCE_LOW
        )

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Taking screenshot")
            .setContentText("Capturing the screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenshotCapture"
        private const val CHANNEL_ID = "sidebar_screenshot"
        private const val NOTIFICATION_ID = 1002
        private const val RESULT_OK_VALUE = -1

        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_FOLDER_URI = "screenshot_folder_uri"
        const val EXTRA_DELAY_SECONDS = "screenshot_delay_seconds"

        const val ACTION_CAPTURE =
            "com.example.sidebar.SCREENSHOT_CAPTURE"

        const val ACTION_STOP_SESSION =
            "com.example.sidebar.SCREENSHOT_STOP_SESSION"

        const val ACTION_SESSION_READY =
            "com.example.sidebar.SCREENSHOT_SESSION_READY"

        const val ACTION_SESSION_FAILED =
            "com.example.sidebar.SCREENSHOT_SESSION_FAILED"
    }
}