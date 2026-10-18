
package com.example.sidebar.sidebar

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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt
import com.example.sidebar.recording.RecordingAudioMode
import android.widget.FrameLayout
import com.example.sidebar.R

class SidebarOverlayView(
    context: Context,
    private val onExpandChanged: (Boolean) -> Unit,
    private val onDragBy: (Float, Float) -> Unit,
    private val onDragFinished: (Float) -> Unit,
    private val onDragModeChanged: (Boolean) -> Unit,
    private val onSwipeInward: (Float) -> Unit,
    private val onScreenshot: () -> Unit,
    private val onRecord: (RecordingAudioMode) -> Unit
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
    private var showingRecordingAudioPicker = false

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
        const val KEY_RECORDING_AUDIO_MODE = "recording_audio_mode"
        const val TAG = "SidebarOverlayView"
    }

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
                    name = resolveInfo.loadLabel(
                        context.packageManager
                    ).toString(),
                    icon = resolveInfo.loadIcon(
                        context.packageManager
                    )
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

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

    private fun getSelectedRecordingAudioMode(): RecordingAudioMode {
        val savedMode = settingsPrefs.getString(
            KEY_RECORDING_AUDIO_MODE,
            RecordingAudioMode.MICROPHONE.name
        )

        return runCatching {
            RecordingAudioMode.valueOf(
                savedMode ?: RecordingAudioMode.MICROPHONE.name
            )
        }.getOrDefault(RecordingAudioMode.MICROPHONE)
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
        setPadding(dp(14), dp(14), dp(14), dp(14))

        if (!isExpanded) {
            setPadding(0, 0, 0, 0)
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

        ensureLaunchableAppsLoaded()

        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = roundedBackground(panelColor, 24)

        when {
            showingScreenshotDelayPicker -> {
                renderScreenshotDelayPicker()
                return
            }

            showingRecordingAudioPicker -> {
                renderRecordingAudioPicker()
                return
            }

            showingAppPicker -> {
                renderAppPicker()
                return
            }
        }

        val actions = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
        }

        actions.addView(
            createIconButton(
                iconRes = R.drawable.ic_screenshot,
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
                iconRes = R.drawable.ic_screen_record,
                description = "Screen recording",
                onLongClick = {
                    showRecordingAudioPicker()
                }

            ) {
                val mode = getSelectedRecordingAudioMode()
                changeExpanded(false)
                onRecord(mode)
            },
            LayoutParams(dp(54), dp(54))
        )

        addView(
            actions,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(58))
        )

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
            val appsByPackage =
                launchableApps.associateBy { it.packageName }

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
                showingScreenshotDelayPicker = false
                showingRecordingAudioPicker = false
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
        // Keep the header and scrolling list in a dedicated vertical container.
        val content = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.TOP
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = true
            clipToPadding = true
        }

        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
            contentDescription = "Back from app picker"
            setOnClickListener {
                showingAppPicker = false
                render()
            }
        }

        val back = TextView(context).apply {
            text = "‹"
            textSize = 28f
            setTextColor(foregroundColor)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }

        header.addView(
            back,
            LayoutParams(dp(32), dp(44))
        )

        val title = TextView(context).apply {
            text = "ADD APPS"
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(foregroundColor)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false

            // Optical alignment with the back arrow.
            translationY = dp(2).toFloat()
        }

        header.addView(
            title,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                dp(44)
            )
        )

        content.addView(
            header,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                dp(44)
            )
        )

        // App rows are clipped to the scrollable region below the header.
        val list = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = true
            clipToPadding = true
        }

        val launchableApps = launchableAppsCache

        if (launchableApps == null) {
            addStatusText(list, "Loading apps…")
        } else {
            val selectedPackages = getSelectedPackages()

            launchableApps.forEach { app ->
                addAppRow(
                    container = list,
                    app = app,
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
            isFillViewport = false
            clipChildren = true
            clipToPadding = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setBackgroundColor(Color.TRANSPARENT)

            addView(
                list,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        content.addView(
            scroll,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // The dedicated container occupies the available sidebar content area.
        addView(
            content,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
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
                scaleType = ImageView.ScaleType.FIT_CENTER
            }

            addView(icon, LayoutParams(dp(30), dp(30)))

            val name = TextView(context).apply {
                text = app.name
                textSize = 13f
                setTextColor(foregroundColor)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                setPadding(dp(10), 0, 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            addView(
                name,
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
            )
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
            includeFontPadding = false
            contentDescription = "Back"

            setOnClickListener {
                showingScreenshotDelayPicker = false
                showingRecordingAudioPicker = false
                render()
            }
        }

        header.addView(
            back,
            LayoutParams(dp(36), dp(44))
        )

        val title = TextView(context).apply {
            text = "SCREENSHOT DELAY"
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(foregroundColor)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            translationY = dp(2).toFloat()
        }

        header.addView(
            title,
            LayoutParams(0, dp(44), 1f)
        )

        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
        )

        val selectedDelay = settingsPrefs.getInt(KEY_CAPTURE_DELAY, 0)

        listOf(
            0 to "Instant",
            3 to "3 seconds",
            5 to "5 seconds"
        ).forEach { (seconds, label) ->
            val option = TextView(context).apply {
                text = if (selectedDelay == seconds) {
                    "✓  $label"
                } else {
                    label
                }

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

    private fun renderRecordingAudioPicker() {
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = TextView(context).apply {
            text = "‹"
            textSize = 30f
            setTextColor(foregroundColor)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = "Back"

            setOnClickListener {
                showingScreenshotDelayPicker = false
                showingRecordingAudioPicker = false
                render()
            }
        }

        header.addView(
            back,
            LayoutParams(dp(36), dp(44))
        )

        val title = TextView(context).apply {
            text = "RECORDING AUDIO"
            textSize = 12f
            letterSpacing = 0.1f
            setTextColor(foregroundColor)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            translationY = dp(2).toFloat()
        }

        header.addView(
            title,
            LayoutParams(0, dp(44), 1f)
        )

        addView(
            header,
            LayoutParams(LayoutParams.MATCH_PARENT, dp(44))
        )

        val selectedMode = getSelectedRecordingAudioMode()

        listOf(
            RecordingAudioMode.MICROPHONE to "Microphone",
            RecordingAudioMode.NONE to "No audio"
        ).forEach { (mode, label) ->
            val option = TextView(context).apply {
                text = if (selectedMode == mode) {
                    "✓  $label"
                } else {
                    label
                }

                textSize = 14f
                setTextColor(foregroundColor)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(10), 0)
                background = roundedBackground(actionColor, 12)
                isClickable = true
                isFocusable = true

                setOnClickListener {
                    settingsPrefs.edit()
                        .putString(KEY_RECORDING_AUDIO_MODE, mode.name)
                        .apply()

                    showingRecordingAudioPicker = false
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
        showingRecordingAudioPicker = true
        render()
    }

    private fun createIconButton(
        iconRes: Int,
        description: String,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): View {
        val iconSize = dp(24)

        return FrameLayout(context).apply {
            contentDescription = description
            background = roundedBackground(actionColor, 100)
            isClickable = true
            isFocusable = true

            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                setColorFilter(Color.rgb(85, 85, 85))
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(
                    iconSize,
                    iconSize,
                    Gravity.CENTER
                )
                isClickable = false
            }

            addView(icon)

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
