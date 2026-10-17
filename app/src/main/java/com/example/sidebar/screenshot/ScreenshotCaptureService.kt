
package com.example.sidebar.screenshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

class ScreenshotCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val finished = AtomicBoolean(false)

    private var folderUri: Uri? = null
    private var projectionCallback: MediaProjection.Callback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (projection != null) {
            Log.w(TAG, "Capture already in progress")
            return START_NOT_STICKY
        }

        if (intent == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)

        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                EXTRA_RESULT_DATA,
                Intent::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != RESULT_OK_VALUE || resultData == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        folderUri = intent.getStringExtra(EXTRA_FOLDER_URI)?.let(Uri::parse)
        val delaySeconds =
            intent.getIntExtra(EXTRA_DELAY_SECONDS, 0).coerceIn(0, 5)

        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, resultData)
                ?: error("Android did not provide a screen projection")

            workerThread = HandlerThread("SidebarScreenshot").also {
                it.start()
            }
            workerHandler = Handler(workerThread!!.looper)

            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    finishCapture()
                }
            }

            projection!!.registerCallback(
                projectionCallback!!,
                workerHandler
            )

            mainHandler.postDelayed(
                { captureOneFrame() },
                delaySeconds * 1000L + 400L
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to initialize screen capture", exception)
            showToast("Couldn't start screenshot")
            finishCapture()
        }

        return START_NOT_STICKY
    }

    private fun captureOneFrame() {
        if (finished.get()) return

        try {
            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = windowManager.currentWindowMetrics
            val bounds = metrics.bounds
            val width = bounds.width()
            val height = bounds.height()
            val density = resources.configuration.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            val reader = imageReader!!

            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage()
                    ?: return@setOnImageAvailableListener

                try {
                    if (finished.get()) return@setOnImageAvailableListener

                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding =
                        rowStride - pixelStride * width

                    val paddedBitmap = Bitmap.createBitmap(
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

                    val saved = saveBitmap(bitmap)
                    bitmap.recycle()

                    if (saved) {
                        showToast("Screenshot saved")
                        showFlash()
                    } else {
                        showToast("Couldn't save screenshot")
                    }

                    finishCapture()
                } catch (exception: Exception) {
                    Log.e(TAG, "Failed to save screenshot", exception)
                    showToast("Couldn't save screenshot")
                    finishCapture()
                } finally {
                    image.close()
                }
            }, workerHandler)

            // This projection token is used for exactly one virtual display.
            virtualDisplay = projection!!.createVirtualDisplay(
                "SidebarScreenshot",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                workerHandler
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Failed to capture screen", exception)
            showToast("Couldn't capture screen")
            finishCapture()
        }
    }

    private fun saveBitmap(bitmap: Bitmap): Boolean {
        val timestamp = SimpleDateFormat(
            "yyyyMMdd_HHmmss_SSS",
            Locale.US
        ).format(Date())

        val filename = "Screenshot_$timestamp.png"

        return try {
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
                    "image/png",
                    filename
                )

                newFile?.let {
                    contentResolver.openOutputStream(it, "w")
                }
            } ?: openMediaStoreOutput(filename)

            if (output == null) return false

            output.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    return false
                }
            }

            true
        } catch (exception: Exception) {
            Log.e(TAG, "Screenshot storage failed", exception)
            false
        }
    }

    private fun openMediaStoreOutput(filename: String): OutputStream? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
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
                            Log.w(TAG, "Couldn't remove screenshot flash", exception)
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

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screenshot capture",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
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
        const val ACTION_SCREENSHOT_SAVED =
            "com.example.sidebar.SCREENSHOT_SAVED"
    }
}
