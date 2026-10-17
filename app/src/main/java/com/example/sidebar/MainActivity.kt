
package com.example.sidebar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                            // Start again only after the stop acknowledgement.
                            SidebarService.start(this@MainActivity)
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
                            SidebarService.start(this@MainActivity)
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
                    },
                )
            }
        }
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
