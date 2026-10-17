
package com.example.sidebar.sidebar

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt
import com.example.sidebar.recording.RecordingController

class SidebarOverlayManager(
    context: Context,
    private val onScreenshot: () -> Unit
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlay: SidebarOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var outsideTouchView: View? = null
    private var outsideTouchParams: WindowManager.LayoutParams? = null

    private var anchoredLeft = false
    private var callbacksRegistered = false

    private val recordingController = RecordingController(appContext)

    private val configurationCallbacks = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            repositionForCurrentScreen()
        }

        override fun onLowMemory() = Unit
    }

    fun show() {
        if (overlay != null) return

        check(Settings.canDrawOverlays(appContext)) {
            "Display over other apps permission is required."
        }

        val outsideView = View(appContext).apply {
            background = ColorDrawable(Color.TRANSPARENT)
            visibility = View.GONE

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
            gravity = Gravity.TOP or Gravity.LEFT
        }

        val params = WindowManager.LayoutParams(
            dp(12),
            dp(100),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
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
            onScreenshot = onScreenshot,
            onRecord = { audioMode ->
                recordingController.start(audioMode)
            }
        )

        overlay = sidebarView
        layoutParams = params
        outsideTouchView = outsideView
        outsideTouchParams = outsideParams

        try {
            windowManager.addView(outsideView, outsideParams)
            windowManager.addView(sidebarView, params)

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
        params.height = dp(if (sidebarView.isExpanded) 320 else 100)

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

    fun hide() {
        try {
            if (callbacksRegistered) {
                appContext.unregisterComponentCallbacks(configurationCallbacks)
                callbacksRegistered = false
            }

            outsideTouchView?.let {
                if (it.isAttachedToWindow) windowManager.removeView(it)
            }

            overlay?.let {
                if (it.isAttachedToWindow) windowManager.removeView(it)
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
}
