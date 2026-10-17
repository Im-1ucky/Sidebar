
package com.example.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.lifecycle.viewmodel.compose.viewModel
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

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {
            when (intent?.action) {
                SidebarService.ACTION_STARTED -> {
                    Log.i(TAG, "Start acknowledged")

                    if (
                        pendingOperation == ServiceOperation.START ||
                        pendingOperation == ServiceOperation.RESTART
                    ) {
                        pendingOperation = null
                        dashboardViewModel.operationCompleted(
                            running = true
                        )
                    }
                }

                SidebarService.ACTION_STOPPED -> {
                    Log.i(TAG, "Stop acknowledged")

                    when (pendingOperation) {
                        ServiceOperation.STOP -> {
                            pendingOperation = null
                            dashboardViewModel.operationCompleted(
                                running = false
                            )
                        }

                        ServiceOperation.RESTART -> {
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                SidebarService.start(this@MainActivity)
                            } else {
                                pendingOperation = null
                                dashboardViewModel.operationCompleted(
                                    running = false
                                )
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
        enableEdgeToEdge()

        setContent {
            SidebarTheme {
                dashboardViewModel = viewModel()

                val state by dashboardViewModel.uiState.collectAsState()

                DashboardScreen(
                    state = state,
                    onStart = {
                        if (pendingOperation == null) {
                            pendingOperation = ServiceOperation.START
                            dashboardViewModel.beginOperation(
                                ServiceOperation.START
                            )
                            startSidebarWithPermission()
                        }
                    },
                    onStop = {
                        if (pendingOperation == null) {
                            pendingOperation = ServiceOperation.STOP
                            dashboardViewModel.beginOperation(
                                ServiceOperation.STOP
                            )
                            SidebarService.stop(this@MainActivity)
                        }
                    },
                    onRestart = {
                        if (pendingOperation == null) {
                            pendingOperation = ServiceOperation.RESTART
                            dashboardViewModel.beginOperation(
                                ServiceOperation.RESTART
                            )
                            SidebarService.stop(this@MainActivity)
                        }
                    }
                )
            }
        }
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
        private const val TAG = "MainActivity"
    }
}
