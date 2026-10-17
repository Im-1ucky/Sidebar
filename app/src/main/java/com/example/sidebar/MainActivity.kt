
package com.example.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sidebar.screenshot.ScreenshotCaptureService
import com.example.sidebar.sidebar.SidebarService
import com.example.sidebar.ui.dashboard.DashboardScreen
import com.example.sidebar.ui.dashboard.DashboardViewModel
import com.example.sidebar.ui.dashboard.ServiceOperation
import com.example.sidebar.ui.theme.SidebarTheme

class MainActivity : ComponentActivity() {

    private lateinit var dashboardViewModel: DashboardViewModel

    private var pendingOperation: ServiceOperation? = null
    private var receiverRegistered = false
    private var waitingForOverlayPermission = false

    private val preferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var screenshotFolderUri by mutableStateOf<String?>(null)
    private var captureDelaySeconds by mutableIntStateOf(0)

    private val overlayPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Settings.canDrawOverlays(this)) {
                if (waitingForOverlayPermission) {
                    waitingForOverlayPermission = false
                    SidebarService.start(this)
                }
            } else {
                waitingForOverlayPermission = false
                pendingOperation = null
                dashboardViewModel.operationCompleted(running = false)
            }
        }

    private val captureConsentLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val resultData = result.data

            if (result.resultCode == RESULT_OK && resultData != null) {
                val serviceIntent =
                    Intent(this, ScreenshotCaptureService::class.java).apply {
                        putExtra(
                            ScreenshotCaptureService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )
                        putExtra(
                            ScreenshotCaptureService.EXTRA_RESULT_DATA,
                            resultData
                        )
                        putExtra(
                            ScreenshotCaptureService.EXTRA_FOLDER_URI,
                            screenshotFolderUri
                        )
                        putExtra(
                            ScreenshotCaptureService.EXTRA_DELAY_SECONDS,
                            captureDelaySeconds
                        )
                    }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }

                    // Return to the app that was underneath the sidebar.
                    moveTaskToBack(true)
                } catch (exception: Exception) {
                    Log.e(TAG, "Unable to start screenshot service", exception)
                }
            } else {
                Log.i(TAG, "Screen capture consent declined")
                moveTaskToBack(true)
            }
        }

    private val folderPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val uri = data?.data

            if (result.resultCode == RESULT_OK && uri != null) {
                val grantedFlags = data.flags and (
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )

                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        grantedFlags
                    )

                    screenshotFolderUri = uri.toString()

                    preferences.edit()
                        .putString(KEY_FOLDER_URI, screenshotFolderUri)
                        .apply()
                } catch (exception: Exception) {
                    Log.e(TAG, "Couldn't save folder permission", exception)
                }
            }
        }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SidebarService.ACTION_STARTED -> {
                    Log.i(TAG, "Start acknowledged")

                    if (pendingOperation == ServiceOperation.START ||
                        pendingOperation == ServiceOperation.RESTART
                    ) {
                        pendingOperation = null
                        dashboardViewModel.operationCompleted(running = true)
                    }
                }

                SidebarService.ACTION_STOPPED -> {
                    Log.i(TAG, "Stop acknowledged")

                    when (pendingOperation) {
                        ServiceOperation.STOP -> {
                            pendingOperation = null
                            dashboardViewModel.operationCompleted(running = false)
                        }

                        ServiceOperation.RESTART -> {
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                SidebarService.start(this@MainActivity)
                            } else {
                                pendingOperation = null
                                dashboardViewModel.operationCompleted(running = false)
                            }
                        }

                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        screenshotFolderUri = preferences.getString(KEY_FOLDER_URI, null)
        captureDelaySeconds = preferences.getInt(KEY_CAPTURE_DELAY, 0)

        enableEdgeToEdge()

        setContent {
            SidebarTheme {
                dashboardViewModel = viewModel()
                dashboardViewModel.syncServiceState(
                    SidebarService.isRunning(this@MainActivity)
                )
                val state by dashboardViewModel.uiState.collectAsState()

                DashboardScreen(
                    state = state,
                    screenshotFolderLabel =
                        if (screenshotFolderUri == null) {
                            "Pictures/Sidebar (default)"
                        } else {
                            "Custom folder selected"
                        },
                    captureDelaySeconds = captureDelaySeconds,
                    onCaptureDelayChanged = { seconds ->
                        captureDelaySeconds = seconds
                        preferences.edit()
                            .putInt(KEY_CAPTURE_DELAY, seconds)
                            .apply()
                    },
                    onChooseScreenshotFolder = {
                        folderPickerLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        )
                    },
                    onResetScreenshotFolder = {
                        screenshotFolderUri = null
                        preferences.edit().remove(KEY_FOLDER_URI).apply()
                    },
                    onStart = {
                        if (pendingOperation == null) {
                            pendingOperation = ServiceOperation.START
                            dashboardViewModel.beginOperation(ServiceOperation.START)
                            startSidebarWithPermission()
                        }
                    },
                    onStop = {
                        if (pendingOperation == null) {
                            pendingOperation = ServiceOperation.STOP
                            dashboardViewModel.beginOperation(ServiceOperation.STOP)
                            SidebarService.stop(this@MainActivity)
                        }
                    },
                    onRestart = {
                        if (pendingOperation == null) {
                            pendingOperation = ServiceOperation.RESTART
                            dashboardViewModel.beginOperation(ServiceOperation.RESTART)
                            SidebarService.stop(this@MainActivity)
                        }
                    }
                )
            }
        }

        if (intent?.action == ACTION_REQUEST_SCREENSHOT) {
            requestScreenshot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        if (intent.action == ACTION_REQUEST_SCREENSHOT) {
            requestScreenshot()
        }
    }

    private fun requestScreenshot() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        captureConsentLauncher.launch(
            manager.createScreenCaptureIntent()
        )
    }

    private fun startSidebarWithPermission() {
        if (Settings.canDrawOverlays(this)) {
            SidebarService.start(this)
            return
        }

        waitingForOverlayPermission = true

        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

        overlayPermissionLauncher.launch(intent)
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter().apply {
            addAction(SidebarService.ACTION_STARTED)
            addAction(SidebarService.ACTION_STOPPED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                serviceReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(serviceReceiver, filter)
        }

        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(serviceReceiver)
            receiverRegistered = false
        }

        super.onStop()
    }

    companion object {
        const val ACTION_REQUEST_SCREENSHOT =
            "com.example.sidebar.REQUEST_SCREENSHOT"

        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "sidebar_preferences"
        private const val KEY_FOLDER_URI = "screenshot_folder_uri"
        private const val KEY_CAPTURE_DELAY = "screenshot_delay_seconds"
    }
}
