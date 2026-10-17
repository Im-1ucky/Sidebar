
package com.example.sidebar.sidebar

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

class SidebarOverlayView(
    context: Context,
    private val onExpandChanged: (Boolean) -> Unit,
    private val onDragBy: (Float, Float) -> Unit,
    private val onDragFinished: (Float) -> Unit,
    private val onDragModeChanged: (Boolean) -> Unit,
    private val onSwipeInward: (Float) -> Unit
) : LinearLayout(context) {

    var isExpanded = false
        private set

    private var dragMode = false
    private var longPressTriggered = false
    private var dragging = false

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val handle = View(context)
    private val handler = Handler(Looper.getMainLooper())

    private val panelColor = Color.argb(185, 24, 24, 27)
    private val actionColor = Color.argb(150, 55, 55, 60)
    private val foregroundColor = Color.WHITE

    private val touchSlop = dp(6)
    private val swipeThreshold = dp(40)
    private val longPressTimeout = 250L

    private val longPressRunnable = Runnable {
        if (!isExpanded) {
            longPressTriggered = true
            dragMode = true

            handle.layoutParams = handle.layoutParams.apply {
                width = dp(20)
            }

            onDragModeChanged(true)
        }
    }

    init {
        clipChildren = false
        clipToPadding = false

        handle.background = roundedBackground(panelColor, 12)

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    lastX = event.rawX
                    lastY = event.rawY

                    dragging = false
                    dragMode = false
                    longPressTriggered = false

                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(
                        longPressRunnable,
                        longPressTimeout
                    )
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (
                        !longPressTriggered &&
                        (abs(dx) > touchSlop || abs(dy) > touchSlop)
                    ) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (dragMode) {
                        if (
                            abs(dx) > touchSlop ||
                            abs(dy) > touchSlop
                        ) {
                            dragging = true
                        }

                        if (dragging) {
                            onDragBy(
                                event.rawX - lastX,
                                event.rawY - lastY
                            )
                        }
                    }

                    lastX = event.rawX
                    lastY = event.rawY
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)

                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    when {
                        longPressTriggered -> {
                            if (dragging) {
                                onDragFinished(event.rawX)
                            }

                            dragMode = false
                            longPressTriggered = false
                            dragging = false

                            handle.layoutParams =
                                handle.layoutParams.apply {
                                    width = dp(12)
                                }

                            onDragModeChanged(false)
                        }

                        abs(dx) > swipeThreshold &&
                                abs(dx) > abs(dy) -> {
                            onSwipeInward(dx)
                        }

                        abs(dx) < touchSlop &&
                                abs(dy) < touchSlop -> {
                            changeExpanded(true)
                        }
                    }

                    performClick()
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)

                    if (dragMode && dragging) {
                        onDragFinished(lastX)
                    }

                    dragMode = false
                    longPressTriggered = false
                    dragging = false

                    if (!isExpanded) {
                        handle.layoutParams =
                            handle.layoutParams.apply {
                                width = dp(12)
                            }

                        onDragModeChanged(false)
                    }

                    true
                }

                else -> true
            }
        }

        render()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun changeExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return

        isExpanded = expanded
        render()
        onExpandChanged(expanded)
    }

    fun collapse() {
        changeExpanded(false)
    }

    private fun render() {
        removeAllViews()
        setPadding(0, 0, 0, 0)

        if (!isExpanded) {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = null

            handle.background = roundedBackground(panelColor, 12)

            addView(
                handle,
                LayoutParams(
                    dp(if (dragMode) 20 else 12),
                    dp(100)
                )
            )
            return
        }

        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(18), dp(16), dp(18))
        background = roundedBackground(panelColor, 20)

        val header = TextView(context).apply {
            text = "SIDEBAR"
            setTextColor(foregroundColor)
            textSize = 13f
            letterSpacing = 0.12f
            gravity = Gravity.CENTER_VERTICAL
        }

        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(36))
        )

        addAction("Screenshot")
        addAction("Screen recording")
        addAction("App shortcuts")

        val close = TextView(context).apply {
            text = "Close"
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setOnClickListener {
                changeExpanded(false)
            }
        }

        addView(
            close,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
        )
    }

    private fun addAction(label: String) {
        val action = TextView(context).apply {
            text = label
            setTextColor(foregroundColor)
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            background = roundedBackground(actionColor, 12)
        }

        addView(
            action,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(48)
            ).apply {
                topMargin = dp(8)
            }
        )
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int
    ): GradientDrawable {
        val radius = dp(radiusDp).toFloat()

        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
