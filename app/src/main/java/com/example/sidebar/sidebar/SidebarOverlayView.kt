
package com.example.sidebar.sidebar

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

class SidebarOverlayView(
    context: Context,
    private val onExpandChanged: (Boolean) -> Unit,
    private val onDragBy: (Float, Float) -> Unit,
    private val onDragFinished: (Float) -> Unit,
    private val onDragModeChanged: (Boolean) -> Unit,
    private val onSwipeInward: (Float) -> Unit,
    private val onScreenshot: () -> Unit,
    private val onRecord: (com.example.sidebar.recording.RecordingAudioMode) -> Unit
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

    private val appLoader = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "SidebarAppLoader").apply {
            isDaemon = true
        }
    }

    private var launchableAppsCache: List<LaunchableApp>? = null
    private var loadingLaunchableApps = false

    private val panelColor = Color.argb(240, 255, 255, 255)
    private val actionColor = Color.argb(210, 225, 225, 225)
    private val foregroundColor = Color.BLACK
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
    private var showingScreenshotDelayPicker = false

    private val shortcutPrefs = context.getSharedPreferences(
        "sidebar_shortcuts",
        Context.MODE_PRIVATE
    )

    private val settingsPrefs = context.getSharedPreferences(
        "sidebar_preferences",
        Context.MODE_PRIVATE
    )

    private companion object {
        const val KEY_CAPTURE_DELAY = "screenshot_delay_seconds"
        const val TAG = "SidebarOverlayView"
    }

    /**
     * Runs only on the background executor.
     * Package queries and app label/icon loading must not block the UI thread.
     */
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

    /**
     * Starts app discovery once. Results are cached and UI rendering
     * is triggered on the main thread when discovery completes.
     */
    private fun ensureLaunchableAppsLoaded() {
        if (launchableAppsCache != null || loadingLaunchableApps) {
            return
        }

        loadingLaunchableApps = true

        appLoader.execute {
            val apps = try {
                getLaunchableApps()
            } catch (exception: Exception) {
                Log.e(TAG, "Unable to load launchable apps", exception)
                emptyList()
            }

            handler.post {
                launchableAppsCache = apps
                loadingLaunchableApps = false

                if (isExpanded) {
                    render()
                }
            }
        }
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

                    if (
                        !longPressTriggered &&
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

        // Start app discovery without blocking rendering or touch events.
        ensureLaunchableAppsLoaded()

        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = roundedBackground(panelColor, 24)

        if (showingScreenshotDelayPicker) {
            renderScreenshotDelayPicker()
            return
        }

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
                iconRes = android.R.drawable.ic_menu_camera,
                description = "Screenshot",
                onLongClick = {
                    showingScreenshotDelayPicker = true
                    render()
                }
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
                iconRes = android.R.drawable.presence_video_online,
                description = "Screen recording",
                onLongClick = {
                    showRecordingAudioPicker()
                }
            ) {
                changeExpanded(false)
                onRecord(
                    com.example.sidebar.recording.RecordingAudioMode.NONE
                )
            },
            LayoutParams(dp(54), dp(54))
        )

        addView(actions, LayoutParams(LayoutParams.MATCH_PARENT, dp(58)))

        val heading = TextView(context).apply {
            text = "APP SHORTCUTS"
            setTextColor(Color.DKGRAY)
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

        val launchableApps = launchableAppsCache

        if (launchableApps == null) {
            addStatusText(shortcutList, "Loading apps…")
        } else {
            val appsByPackage = launchableApps.associateBy { it.packageName }

            getSelectedPackages().forEach { packageName ->
                val app = appsByPackage[packageName]

                if (app != null) {
                    addAppRow(shortcutList, app, selected = true)
                }
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

        val launchableApps = launchableAppsCache

        if (launchableApps == null) {
            addStatusText(list, "Loading apps…")
        } else {
            val selectedPackages = getSelectedPackages()

            launchableApps.forEach { app ->
                addAppRow(
                    list,
                    app,
                    selected = app.packageName in selectedPackages
                ) {
                    val selected = getSelectedPackages().toMutableSet()

                    if (!selected.add(app.packageName)) {
                        selected.remove(app.packageName)
                    }

                    saveSelectedPackages(selected)
                    render()
                }
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

    private fun addStatusText(
        container: LinearLayout,
        message: String
    ) {
        val status = TextView(context).apply {
            text = message
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }

        container.addView(
            status,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(52))
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
                    val launchIntent = context.packageManager
                        .getLaunchIntentForPackage(app.packageName)

                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                        )

                        context.startActivity(launchIntent)
                    }
                } catch (exception: Exception) {
                    Log.e(
                        TAG,
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

    private fun renderScreenshotDelayPicker() {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(context).apply {
            text = "‹"
            textSize = 30f
            setTextColor(foregroundColor)
            gravity = Gravity.CENTER
            contentDescription = "Back"
            setOnClickListener {
                showingScreenshotDelayPicker = false
                render()
            }
        }

        header.addView(back, LayoutParams(dp(36), dp(40)))

        val title = TextView(context).apply {
            text = "SCREENSHOT DELAY"
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(foregroundColor)
        }

        header.addView(
            title,
            LayoutParams(LayoutParams.WRAP_CONTENT, dp(40))
        )

        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(40)))

        val selectedDelay = settingsPrefs.getInt(KEY_CAPTURE_DELAY, 0)

        listOf(
            0 to "Instant",
            3 to "3 seconds",
            5 to "5 seconds"
        ).forEach { (seconds, label) ->
            val option = TextView(context).apply {
                text = if (selectedDelay == seconds) "✓  $label" else label
                textSize = 14f
                setTextColor(foregroundColor)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = roundedBackground(actionColor, 12)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    settingsPrefs.edit()
                        .putInt(KEY_CAPTURE_DELAY, seconds)
                        .apply()

                    showingScreenshotDelayPicker = false
                    render()
                }
            }

            addView(
                option,
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48)).apply {
                    topMargin = dp(6)
                }
            )
        }
    }

    private fun showRecordingAudioPicker() {
        val dialog = AlertDialog.Builder(
            context,
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        )
            .setTitle("Recording audio")
            .setItems(
                arrayOf("Microphone", "No audio")
            ) { _, which ->
                val mode =
                    if (which == 0) {
                        com.example.sidebar.recording.RecordingAudioMode.MICROPHONE
                    } else {
                        com.example.sidebar.recording.RecordingAudioMode.NONE
                    }

                changeExpanded(false)
                onRecord(mode)
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.window?.setType(
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        )

        dialog.show()
    }

    private fun createIconButton(
        iconRes: Int,
        description: String,
        onLongClick: (() -> Unit)? = null,
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
            setOnLongClickListener {
                if (onLongClick != null) {
                    onLongClick()
                    true
                } else {
                    false
                }
            }
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
