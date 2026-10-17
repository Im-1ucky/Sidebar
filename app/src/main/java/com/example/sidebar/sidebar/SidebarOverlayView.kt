
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
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.content.Intent
import android.graphics.drawable.Drawable

class SidebarOverlayView(
    context: Context,
    private val onExpandChanged: (Boolean) -> Unit,
    private val onDragBy: (Float, Float) -> Unit,
    private val onDragFinished: (Float) -> Unit,
    private val onDragModeChanged: (Boolean) -> Unit,
    private val onSwipeInward: (Float) -> Unit,
    private val onScreenshot: () -> Unit
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
            handle.layoutParams =
                handle.layoutParams.apply { width = dp(20) }
            onDragModeChanged(true)
        }
    }

    private data class LaunchableApp(
        val packageName: String,
        val name: String,
        val icon: Drawable
    )

    private var showingAppPicker = false

    private val shortcutPrefs = context.getSharedPreferences(
        "sidebar_shortcuts",
        Context.MODE_PRIVATE
    )

    private fun getLaunchableApps(): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        return context.packageManager
            .queryIntentActivities(intent, 0)
            .mapNotNull { resolveInfo ->
                val appInfo = resolveInfo.activityInfo?.applicationInfo
                    ?: return@mapNotNull null

                val packageName = appInfo.packageName

                if (packageName == context.packageName) {
                    return@mapNotNull null
                }

                LaunchableApp(
                    packageName = packageName,
                    name = resolveInfo.loadLabel(context.packageManager).toString(),
                    icon = resolveInfo.loadIcon(context.packageManager)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

    private fun getSelectedPackages(): Set<String> =
        shortcutPrefs.getStringSet("selected_packages", emptySet())
            ?.toSet()
            ?: emptySet()

    private fun saveSelectedPackages(packages: Set<String>) {
        shortcutPrefs.edit()
            .putStringSet("selected_packages", packages.toSet())
            .apply()
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
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY

                    if (!longPressTriggered &&
                        (abs(dx) > touchSlop || abs(dy) > touchSlop)
                    ) {
                        handler.removeCallbacks(longPressRunnable)
                    }

                    if (dragMode) {
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
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
                                handle.layoutParams.apply { width = dp(12) }

                            onDragModeChanged(false)
                        }

                        abs(dx) > swipeThreshold && abs(dx) > abs(dy) -> {
                            onSwipeInward(dx)
                        }

                        abs(dx) < touchSlop && abs(dy) < touchSlop -> {
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
                            handle.layoutParams.apply { width = dp(12) }
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
        setPadding(dp(14), dp(14), dp(14), dp(14))

        if (!isExpanded) {
            setPadding(0, 0, 0, 0)
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = null
            handle.background = roundedBackground(panelColor, 12)

            addView(
                handle,
                LayoutParams(dp(if (dragMode) 20 else 12), dp(100))
            )
            return
        }

        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = roundedBackground(panelColor, 24)

        if (showingAppPicker) {
            renderAppPicker()
            return
        }

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }

        actions.addView(
            createIconButton(
                android.R.drawable.ic_menu_camera,
                "Screenshot"
            ) {
                changeExpanded(false)
                onScreenshot()
            },
            LayoutParams(dp(54), dp(54)).apply {
                marginEnd = dp(12)
            }
        )

        actions.addView(
            createIconButton(
                android.R.drawable.presence_video_online,
                "Screen recording"
            ) {
                // Recording will be implemented separately.
            },
            LayoutParams(dp(54), dp(54))
        )

        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, dp(58)))

        val heading = TextView(context).apply {
            text = "APP SHORTCUTS"
            setTextColor(Color.LTGRAY)
            textSize = 11f
            letterSpacing = 0.1f
            gravity = Gravity.CENTER_VERTICAL
        }

        addView(
            heading,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(36))
        )

        val shortcutList = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        val scrollView = ScrollView(context).apply {
            isFillViewport = false
            clipToPadding = false
            addView(
                shortcutList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val launchableApps = getLaunchableApps()
        val appsByPackage = launchableApps.associateBy { it.packageName }

        getSelectedPackages().forEach { packageName ->
            val app = appsByPackage[packageName]

            if (app != null) {
                addAppRow(shortcutList, app, selected = true)
            }
        }

        addView(
            scrollView,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val addApp = TextView(context).apply {
            text = "+  Add app"
            setTextColor(foregroundColor)
            textSize = 14f
            gravity = Gravity.CENTER
            background = roundedBackground(actionColor, 14)

            setOnClickListener {
                showingAppPicker = true
                render()
            }
        }

        addView(
            addApp,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(8)
            }
        )
    }

    private fun renderAppPicker() {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(context).apply {
            text = "‹"
            textSize = 30f
            setTextColor(foregroundColor)
            gravity = Gravity.CENTER
            setOnClickListener {
                showingAppPicker = false
                render()
            }
        }

        header.addView(back, LayoutParams(dp(36), dp(40)))

        val title = TextView(context).apply {
            text = "ADD APPS"
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(foregroundColor)
        }

        header.addView(
            title,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(40))
        )

        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        val list = LinearLayout(context).apply {
            orientation = VERTICAL
        }

        getLaunchableApps().forEach { app ->
            addAppRow(
                list,
                app,
                selected = app.packageName in getSelectedPackages()
            ) {
                val selected = getSelectedPackages().toMutableSet()

                if (!selected.add(app.packageName)) {
                    selected.remove(app.packageName)
                }

                saveSelectedPackages(selected)
                render()
            }
        }

        val scroll = ScrollView(context).apply {
            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }

    private fun addAppRow(
        container: LinearLayout,
        app: LaunchableApp,
        selected: Boolean,
        onSelect: (() -> Unit)? = null
    ) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            background = roundedBackground(actionColor, 12)
            isClickable = true
            isFocusable = true

            val icon = ImageView(context).apply {
                setImageDrawable(app.icon)
            }

            addView(icon, LayoutParams(dp(30), dp(30)))

            val name = TextView(context).apply {
                text = app.name
                textSize = 13f
                setTextColor(foregroundColor)
                setPadding(dp(10), 0, 0, 0)
                maxLines = 1
            }

            addView(
                name,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )

            if (onSelect != null) {
                val marker = TextView(context).apply {
                    text = if (selected) "✓" else "+"
                    textSize = 18f
                    setTextColor(foregroundColor)
                    gravity = Gravity.CENTER
                }

                addView(marker, LayoutParams(dp(24), dp(40)))
            }
        }

        row.setOnClickListener {
            if (onSelect != null) {
                onSelect()
            } else {
                try {
                    context.packageManager
                        .getLaunchIntentForPackage(app.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ?.let(context::startActivity)
                } catch (exception: Exception) {
                    android.util.Log.e(
                        "SidebarOverlayView",
                        "Unable to launch ${app.packageName}",
                        exception
                    )
                }
            }
        }

        if (onSelect == null) {
            row.setOnLongClickListener {
                val selected = getSelectedPackages().toMutableSet()
                selected.remove(app.packageName)
                saveSelectedPackages(selected)
                render()
                true
            }
        }

        container.addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(7)
            }
        )
    }

    private fun createIconButton(
        iconRes: Int,
        description: String,
        onClick: () -> Unit
    ): View {
        return ImageView(context).apply {
            contentDescription = description
            setImageResource(iconRes)
            setColorFilter(foregroundColor)
            scaleType = ImageView.ScaleType.CENTER
            background = roundedBackground(actionColor, 100)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }
    private fun roundedBackground(
        color: Int,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
