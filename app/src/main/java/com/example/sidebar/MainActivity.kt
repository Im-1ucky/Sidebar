package com.example.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
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
import com.example.sidebar.recording.RecordingAudioMode
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
    private var recordingFolderUri by mutableStateOf<String?>(null)
    private var captureDelaySeconds by mutableIntStateOf(0)

    private var recordingAudioMode by mutableStateOf(
        RecordingAudioMode.MICROPHONE
    )

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

                    moveTaskToBack(true)
                } catch (exception: Exception) {
                    Log.e(TAG, "Unable to start screenshot service", exception)
                }
            } else {
                Log.i(TAG, "Screen capture consent declined")
                moveTaskToBack(true)
            }
        }

    private val screenshotFolderPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val uri = data?.data

            if (result.resultCode == RESULT_OK && uri != null && data != null) {
                persistFolderSelection(
                    uri = uri,
                    flags = data.flags,
                    preferenceKey = KEY_FOLDER_URI,
                    onSaved = { screenshotFolderUri = it }
                )
            }
        }

    private val recordingFolderPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val data = result.data
            val uri = data?.data

            if (result.resultCode == RESULT_OK && uri != null && data != null) {
                persistFolderSelection(
                    uri = uri,
                    flags = data.flags,
                    preferenceKey = KEY_RECORDING_FOLDER_URI,
                    onSaved = { recordingFolderUri = it }
                )
            }
        }

    /**
     * Recognizes the app's default Pictures/Sidebar directory when selected
     * through Android's standard external-storage document provider.
     */
    private fun isDefaultFolder(uri: Uri): Boolean {
        if (uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) {
            return false
        }

        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return false

        return documentId.equals(
            DEFAULT_FOLDER_DOCUMENT_ID,
            ignoreCase = true
        )
    }

    /**
     * Displays the saved folder path and whether it is the default or custom.
     */
    private fun getFolderLabel(savedUri: String?): String {
        if (savedUri == null) {
            return "$DEFAULT_FOLDER_PATH (default)"
        }

        val uri = Uri.parse(savedUri)

        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull()

        val folderPath = documentId
            ?.substringAfter(':', documentId)
            ?.trim('/')
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "Selected folder"

        return "$folderPath (custom)"
    }

    /**
     * Saves a folder selection, unless it is the default folder.
     * Selecting the default folder removes the custom URI preference.
     */
    private fun persistFolderSelection(
        uri: Uri,
        flags: Int,
        preferenceKey: String,
        onSaved: (String?) -> Unit
    ) {
        val grantedFlags = flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

        try {
            if (isDefaultFolder(uri)) {
                // Clear a previously saved custom folder, if any.
                val previousUri = preferences.getString(preferenceKey, null)

                if (previousUri != null && previousUri != uri.toString()) {
                    releaseFolderPermission(previousUri)
                }

                // The picker may already have granted a temporary/persistable
                // permission. Release it because the default uses MediaStore.
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        uri,
                        grantedFlags
                    )
                }

                preferences.edit()
                    .remove(preferenceKey)
                    .apply()

                onSaved(null)
                return
            }

            contentResolver.takePersistableUriPermission(uri, grantedFlags)

            val uriString = uri.toString()
            val previousUri = preferences.getString(preferenceKey, null)

            preferences.edit()
                .putString(preferenceKey, uriString)
                .apply()

            // Release the old folder permission if the selection changed.
            if (previousUri != null && previousUri != uriString) {
                releaseFolderPermission(previousUri)
            }

            onSaved(uriString)
        } catch (exception: Exception) {
            Log.e(TAG, "Couldn't save folder permission for $uri", exception)
        }
    }

    private fun releaseFolderPermission(savedUri: String?) {
        if (savedUri == null) return

        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(savedUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun resetScreenshotFolder() {
        releaseFolderPermission(screenshotFolderUri)

        screenshotFolderUri = null

        preferences.edit()
            .remove(KEY_FOLDER_URI)
            .apply()
    }

    private fun resetRecordingFolder() {
        releaseFolderPermission(recordingFolderUri)

        recordingFolderUri = null

        preferences.edit()
            .remove(KEY_RECORDING_FOLDER_URI)
            .apply()
    }

    private fun loadRecordingAudioMode(): RecordingAudioMode {
        val savedMode = preferences.getString(
            KEY_RECORDING_AUDIO_MODE,
            RecordingAudioMode.MICROPHONE.name
        )

        return runCatching {
            RecordingAudioMode.valueOf(
                savedMode ?: RecordingAudioMode.MICROPHONE.name
            )
        }.getOrDefault(RecordingAudioMode.MICROPHONE)
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SidebarService.ACTION_STARTED -> {
                    Log.i(TAG, "Start acknowledged")

                    if (
                        pendingOperation == ServiceOperation.START ||
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
        recordingFolderUri =
            preferences.getString(KEY_RECORDING_FOLDER_URI, null)
        captureDelaySeconds = preferences.getInt(KEY_CAPTURE_DELAY, 0)
        recordingAudioMode = loadRecordingAudioMode()

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
                        getFolderLabel(screenshotFolderUri),

                    recordingFolderLabel =
                        getFolderLabel(recordingFolderUri),

                    captureDelaySeconds = captureDelaySeconds,

                    onCaptureDelayChanged = { seconds ->
                        captureDelaySeconds = seconds

                        preferences.edit()
                            .putInt(KEY_CAPTURE_DELAY, seconds)
                            .apply()
                    },

                    onChooseScreenshotFolder = {
                        screenshotFolderPickerLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        )
                    },

                    onResetScreenshotFolder = {
                        resetScreenshotFolder()
                    },

                    onChooseRecordingFolder = {
                        recordingFolderPickerLauncher.launch(
                            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        )
                    },

                    onResetRecordingFolder = {
                        resetRecordingFolder()
                    },

                    recordingAudioMode = recordingAudioMode,

                    onRecordingAudioModeChanged = { mode ->
                        recordingAudioMode = mode

                        preferences.edit()
                            .putString(KEY_RECORDING_AUDIO_MODE, mode.name)
                            .apply()
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

    override fun onResume() {
        super.onResume()
        recordingAudioMode = loadRecordingAudioMode()
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

        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )

        overlayPermissionLauncher.launch(permissionIntent)
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
        private const val KEY_RECORDING_FOLDER_URI = "recording_folder_uri"
        private const val KEY_CAPTURE_DELAY = "screenshot_delay_seconds"
        private const val KEY_RECORDING_AUDIO_MODE = "recording_audio_mode"

        private const val DEFAULT_FOLDER_PATH = "Pictures/Sidebar"
        private const val DEFAULT_FOLDER_DOCUMENT_ID =
            "primary:Pictures/Sidebar"

        private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
            "com.android.externalstorage.documents"
    }
}