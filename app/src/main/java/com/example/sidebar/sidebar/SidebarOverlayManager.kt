
package com.example.sidebar.sidebar

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.sidebar.recording.RecordingController
import com.example.sidebar.recording.ScreenRecordingService
import com.example.sidebar.screenshot.ScreenshotCaptureService
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import com.example.sidebar.R

class SidebarOverlayManager(
    context: Context,
    private val onScreenshot: () -> Unit
) {
    private val appContext = context.applicationContext

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlay: SidebarOverlayView? = null
    private var onAttachmentChanged: ((Boolean) -> Unit)? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var outsideTouchView: View? = null
    private var outsideTouchParams: WindowManager.LayoutParams? = null

    private var anchoredLeft = false
    private var callbacksRegistered = false

    private val recordingController = RecordingController(appContext)

    private var screenshotPill: View? = null
    private var screenshotReceiverRegistered = false

    private var recordingPill: View? = null
    private var recordingTimer: TextView? = null
    private var recordingPauseButton: TextView? = null
    private var recordingReceiverRegistered = false
    private var recordingPaused = false
    private var recordingStartedAt = 0L
    private var pausedDuration = 0L

    private val recordingHandler = Handler(Looper.getMainLooper())

    private val recordingTimerRunnable = object : Runnable {
        @Suppress("DEPRECATION")
        override fun run() {
            if (recordingPill == null) return

            val elapsed = if (recordingPaused) {
                pausedDuration
            } else {
                pausedDuration +
                        (SystemClock.elapsedRealtime() - recordingStartedAt)
            }

            val totalSeconds = elapsed / 1000
            recordingTimer?.text = String.format(
                java.util.Locale.US,
                "%02d:%02d",
                totalSeconds / 60,
                totalSeconds % 60
            )

            recordingHandler.postDelayed(this, 1000)
        }
    }

    private val screenshotPreferences = appContext.getSharedPreferences(
        "sidebar_preferences",
        Context.MODE_PRIVATE
    )

    private val screenshotSessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenshotCaptureService.ACTION_SESSION_READY -> {
                    showScreenshotPill()
                }

                ScreenshotCaptureService.ACTION_SESSION_FAILED -> {
                    removeScreenshotPill()
                }
            }
        }
    }

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenRecordingService.ACTION_RECORDING_READY -> {
                    showRecordingPill()
                }

                ScreenRecordingService.ACTION_RECORDING_PAUSED -> {
                    if (!recordingPaused) {
                        pausedDuration +=
                            SystemClock.elapsedRealtime() - recordingStartedAt
                        recordingPaused = true
                        updateRecordingPauseButton()
                    }
                }

                ScreenRecordingService.ACTION_RECORDING_RESUMED -> {
                    if (recordingPaused) {
                        recordingStartedAt = SystemClock.elapsedRealtime()
                        recordingPaused = false
                        updateRecordingPauseButton()
                    }
                }

                ScreenRecordingService.ACTION_RECORDING_STOPPED,
                ScreenRecordingService.ACTION_RECORDING_FAILED -> {
                    removeRecordingPill()
                }
            }
        }
    }

    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            repositionForCurrentScreen()
        }

        @Deprecated("Deprecated in the implemented interface")
        override fun onLowMemory() = Unit
    }

    private fun registerScreenshotReceiver() {
        if (screenshotReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ScreenshotCaptureService.ACTION_SESSION_READY)
            addAction(ScreenshotCaptureService.ACTION_SESSION_FAILED)
        }

        ContextCompat.registerReceiver(
            appContext,
            screenshotSessionReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        screenshotReceiverRegistered = true
    }

    private fun unregisterScreenshotReceiver() {
        if (!screenshotReceiverRegistered) return

        try {
            appContext.unregisterReceiver(screenshotSessionReceiver)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to unregister screenshot receiver", exception)
        } finally {
            screenshotReceiverRegistered = false
        }
    }

    private fun registerRecordingReceiver() {
        if (recordingReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ScreenRecordingService.ACTION_RECORDING_READY)
            addAction(ScreenRecordingService.ACTION_RECORDING_PAUSED)
            addAction(ScreenRecordingService.ACTION_RECORDING_RESUMED)
            addAction(ScreenRecordingService.ACTION_RECORDING_STOPPED)
            addAction(ScreenRecordingService.ACTION_RECORDING_FAILED)
        }

        ContextCompat.registerReceiver(
            appContext,
            recordingReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        recordingReceiverRegistered = true
    }

    private fun unregisterRecordingReceiver() {
        if (!recordingReceiverRegistered) return

        try {
            appContext.unregisterReceiver(recordingReceiver)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to unregister recording receiver", exception)
        } finally {
            recordingReceiverRegistered = false
        }
    }

    fun isOverlayAttached(): Boolean {
        return overlay?.isAttachedToWindow == true
    }

    fun clearLaunchableAppsCache() {
        overlay?.clearLaunchableAppsCache()
    }

    fun setOnOverlayAttachmentChanged(
        callback: (Boolean) -> Unit
    ) {
        onAttachmentChanged = callback
        callback(isOverlayAttached())
    }

    fun show() {
        if (overlay?.isAttachedToWindow == true) return

        if (overlay != null) {
            Log.w(TAG, "Sidebar view exists but is detached; recreating it")
            hide()
        }

        check(Settings.canDrawOverlays(appContext)) {
            "Display over other apps permission is required."
        }

        registerScreenshotReceiver()
        registerRecordingReceiver()

        val outsideView = View(appContext).apply {
            background = Color.TRANSPARENT.toDrawable()
            visibility = View.GONE

            @Suppress("ClickableViewAccessibility")
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    this@SidebarOverlayManager.overlay?.collapse()
                }
                true
            }
        }

        val outsideParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val params = WindowManager.LayoutParams(
            dp(12),
            dp(100),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth() - dp(12)
            y = (screenHeight() - dp(100)) / 2
        }

        val sidebarView = SidebarOverlayView(
            context = appContext,

            onExpandChanged = { expanded ->
                params.width = dp(if (expanded) 260 else 12)
                params.height = dp(if (expanded) 440 else 100)

                params.x = if (anchoredLeft) {
                    0
                } else {
                    screenWidth() - params.width
                }

                outsideView.visibility =
                    if (expanded) View.VISIBLE else View.GONE

                clampPosition(params, expanded)
                updateOverlayLayout()
            },

            onDragBy = { deltaX, deltaY ->
                params.x = (params.x + deltaX.roundToInt()).coerceIn(
                    0,
                    (screenWidth() - params.width).coerceAtLeast(0)
                )

                params.y = (params.y + deltaY.roundToInt()).coerceIn(
                    0,
                    (screenHeight() - params.height).coerceAtLeast(0)
                )

                updateOverlayLayout()
            },

            onDragFinished = { rawX ->
                anchoredLeft = rawX < screenWidth() / 2f
                params.width = dp(12)

                params.x = if (anchoredLeft) {
                    0
                } else {
                    screenWidth() - params.width
                }

                clampPosition(params, false)
                updateOverlayLayout()
            },

            onDragModeChanged = { active ->
                if (overlay?.isExpanded != true) {
                    params.width = dp(if (active) 20 else 12)

                    params.x = if (anchoredLeft) {
                        0
                    } else {
                        screenWidth() - params.width
                    }

                    updateOverlayLayout()
                }
            },

            onSwipeInward = { dx ->
                val inward = if (anchoredLeft) dx > 0 else dx < 0
                if (inward) overlay?.changeExpanded(true)
            },

            onScreenshot = {
                onScreenshot()
            },

            onRecord = { audioMode ->
                recordingController.start(audioMode)
            }
        )

        overlay = sidebarView
        layoutParams = params
        outsideTouchView = outsideView
        outsideTouchParams = outsideParams

        sidebarView.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    onAttachmentChanged?.invoke(true)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    onAttachmentChanged?.invoke(false)
                }
            }
        )

        try {
            windowManager.addView(outsideView, outsideParams)
            windowManager.addView(sidebarView, params)

            Log.i(
                TAG,
                "Sidebar added; attached=${sidebarView.isAttachedToWindow}"
            )

            appContext.registerComponentCallbacks(configurationCallbacks)
            callbacksRegistered = true
        } catch (exception: Exception) {
            try {
                if (sidebarView.isAttachedToWindow) {
                    windowManager.removeView(sidebarView)
                }

                if (outsideView.isAttachedToWindow) {
                    windowManager.removeView(outsideView)
                }
            } finally {
                overlay = null
                layoutParams = null
                outsideTouchView = null
                outsideTouchParams = null
                unregisterScreenshotReceiver()
                unregisterRecordingReceiver()
            }

            throw exception
        }
    }

    private fun repositionForCurrentScreen() {
        val sidebarView = overlay ?: return
        val params = layoutParams ?: return
        val outsideView = outsideTouchView ?: return
        val outsideParams = outsideTouchParams ?: return

        if (outsideView.isAttachedToWindow) {
            windowManager.updateViewLayout(outsideView, outsideParams)
        }

        params.width = dp(if (sidebarView.isExpanded) 260 else 12)
        params.height = dp(if (sidebarView.isExpanded) 440 else 100)

        params.x = if (anchoredLeft) {
            0
        } else {
            screenWidth() - params.width
        }

        clampPosition(params, sidebarView.isExpanded)
        updateOverlayLayout()
    }

    private fun clampPosition(
        params: WindowManager.LayoutParams,
        expanded: Boolean
    ) {
        params.x = params.x.coerceIn(
            0,
            (screenWidth() - params.width).coerceAtLeast(0)
        )

        val fallbackHeight = dp(if (expanded) 440 else 100)
        val viewHeight = overlay?.height?.takeIf { it > 0 }
            ?: fallbackHeight

        params.y = params.y.coerceIn(
            0,
            (screenHeight() - viewHeight).coerceAtLeast(0)
        )
    }

    private fun updateOverlayLayout() {
        val sidebarView = overlay ?: return
        val params = layoutParams ?: return

        if (sidebarView.isAttachedToWindow) {
            windowManager.updateViewLayout(sidebarView, params)
        }
    }

    private class DragHandleView(context: Context) : LinearLayout(context) {
        override fun performClick(): Boolean {
            super.performClick()
            return true
        }
    }

    private fun createDragHandle(
        pill: View,
        params: WindowManager.LayoutParams
    ): View {
        val handle = DragHandleView(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            contentDescription = "Drag to move pill"

            repeat(3) {
                val row = LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }

                repeat(2) {
                    val dot = View(appContext).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.LTGRAY)
                        }
                    }

                    row.addView(
                        dot,
                        LinearLayout.LayoutParams(dp(3), dp(3)).apply {
                            setMargins(dp(2), dp(2), dp(2), dp(2))
                        }
                    )
                }

                addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (
                        kotlin.math.abs(event.rawX - downX) > dp(6) ||
                        kotlin.math.abs(event.rawY - downY) > dp(6)
                    ) {
                        moved = true
                    }

                    params.x = (
                            startX + (event.rawX - downX).roundToInt()
                            ).coerceIn(
                            0,
                            (screenWidth() - params.width).coerceAtLeast(0)
                        )

                    params.y = (
                            startY + (event.rawY - downY).roundToInt()
                            ).coerceIn(
                            0,
                            (screenHeight() - params.height).coerceAtLeast(0)
                        )

                    try {
                        if (pill.isAttachedToWindow) {
                            windowManager.updateViewLayout(pill, params)
                        }
                    } catch (exception: Exception) {
                        Log.e(TAG, "Unable to move pill", exception)
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        handle.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }

        return handle
    }

    private fun showScreenshotPill() {
        if (screenshotPill != null) return
        if (!Settings.canDrawOverlays(appContext)) return

        val pill = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))

            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 30, 30))
                cornerRadius = dp(28).toFloat()
            }
        }

        val cameraButton = ImageView(appContext).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(Color.WHITE)
            contentDescription = "Take screenshot"
            isClickable = true
            isFocusable = true
            setPadding(dp(10), dp(10), dp(10), dp(10))

            setOnClickListener {
                val delay = screenshotPreferences.getInt(
                    "screenshot_delay_seconds",
                    0
                )

                val intent = Intent(
                    appContext,
                    ScreenshotCaptureService::class.java
                ).apply {
                    action = ScreenshotCaptureService.ACTION_CAPTURE
                    putExtra(
                        ScreenshotCaptureService.EXTRA_DELAY_SECONDS,
                        delay
                    )
                }

                try {
                    appContext.startService(intent)
                } catch (exception: Exception) {
                    Log.e(TAG, "Unable to request screenshot", exception)
                }
            }
        }

        pill.addView(
            cameraButton,
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        val closeButton = TextView(appContext).apply {
            text = "×"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Close screenshot mode"
            isClickable = true
            isFocusable = true

            setOnClickListener {
                closeScreenshotPill()
            }
        }

        pill.addView(
            closeButton,
            LinearLayout.LayoutParams(dp(40), dp(48))
        )

        val params = WindowManager.LayoutParams(
            dp(148),
            dp(60),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth() - width) / 2).coerceAtLeast(0)
            y = dp(100)
        }

        pill.addView(
            createDragHandle(pill, params),
            LinearLayout.LayoutParams(dp(32), dp(48))
        )

        try {
            windowManager.addView(pill, params)
            screenshotPill = pill
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to show screenshot pill", exception)
        }
    }

    private fun removeScreenshotPill() {
        screenshotPill?.let { pill ->
            try {
                if (pill.isAttachedToWindow) {
                    windowManager.removeView(pill)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to remove screenshot pill", exception)
            }
        }

        screenshotPill = null
    }

    private fun closeScreenshotPill() {
        removeScreenshotPill()

        try {
            appContext.startService(
                Intent(
                    appContext,
                    ScreenshotCaptureService::class.java
                ).apply {
                    action = ScreenshotCaptureService.ACTION_STOP_SESSION
                }
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Unable to stop screenshot session", exception)
        }
    }

    private fun showRecordingPill() {
        if (recordingPill != null) return
        if (!Settings.canDrawOverlays(appContext)) return

        recordingPaused = false
        pausedDuration = 0L
        recordingStartedAt = SystemClock.elapsedRealtime()

        val pill = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))

            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 30, 30))
                cornerRadius = dp(28).toFloat()
            }
        }

        val timer = TextView(appContext).apply {
            text = appContext.getString(R.string.recording_timer_initial)
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        recordingTimer = timer

        pill.addView(
            timer,
            LinearLayout.LayoutParams(dp(64), dp(48))
        )

        val pauseButton = TextView(appContext).apply {
            text = "Ⅱ"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Pause recording"
            isClickable = true
            isFocusable = true

            setOnClickListener {
                if (recordingPaused) {
                    recordingController.resume()
                } else {
                    recordingController.pause()
                }
            }
        }

        recordingPauseButton = pauseButton

        pill.addView(
            pauseButton,
            LinearLayout.LayoutParams(dp(44), dp(48))
        )

        val stopButton = TextView(appContext).apply {
            text = "■"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Stop recording"
            isClickable = true
            isFocusable = true

            setOnClickListener {
                recordingController.stop()
            }
        }

        pill.addView(
            stopButton,
            LinearLayout.LayoutParams(dp(44), dp(48))
        )

        val params = WindowManager.LayoutParams(
            dp(208),
            dp(60),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ((screenWidth() - width) / 2).coerceAtLeast(0)
            y = dp(100)
        }

        pill.addView(
            createDragHandle(pill, params),
            LinearLayout.LayoutParams(dp(32), dp(48))
        )

        try {
            windowManager.addView(pill, params)
            recordingPill = pill

            recordingHandler.removeCallbacks(recordingTimerRunnable)
            recordingHandler.post(recordingTimerRunnable)
        } catch (exception: Exception) {
            recordingPill = null
            recordingTimer = null
            recordingPauseButton = null
            Log.e(TAG, "Unable to show recording pill", exception)
        }
    }

    private fun updateRecordingPauseButton() {
        recordingPauseButton?.apply {
            text = if (recordingPaused) "▶" else "Ⅱ"
            contentDescription = if (recordingPaused) {
                "Resume recording"
            } else {
                "Pause recording"
            }
        }
    }

    private fun removeRecordingPill() {
        recordingHandler.removeCallbacks(recordingTimerRunnable)

        recordingPill?.let { pill ->
            try {
                if (pill.isAttachedToWindow) {
                    windowManager.removeView(pill)
                }
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to remove recording pill", exception)
            }
        }

        recordingPill = null
        recordingTimer = null
        recordingPauseButton = null
        recordingPaused = false
        recordingStartedAt = 0L
        pausedDuration = 0L
    }

    fun hide() {
        closeScreenshotPill()
        unregisterScreenshotReceiver()

        // Stop the recording pill timer, but do not stop an active recording.
        // Keep the pill visible for an active recording session.
        unregisterRecordingReceiver()

        try {
            if (callbacksRegistered) {
                appContext.unregisterComponentCallbacks(configurationCallbacks)
                callbacksRegistered = false
            }

            outsideTouchView?.let {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            }

            overlay?.let {
                if (it.isAttachedToWindow) {
                    windowManager.removeView(it)
                }
            }
        } finally {
            overlay = null
            layoutParams = null
            outsideTouchView = null
            outsideTouchParams = null
        }
    }

    private fun screenWidth(): Int =
        windowManager.currentWindowMetrics.bounds.width()

    private fun screenHeight(): Int =
        windowManager.currentWindowMetrics.bounds.height()

    private fun dp(value: Int): Int =
        (value * appContext.resources.displayMetrics.density).roundToInt()

    private companion object {
        const val TAG = "SidebarOverlayManager"
    }
}
