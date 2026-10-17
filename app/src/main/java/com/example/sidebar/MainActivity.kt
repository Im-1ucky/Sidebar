
package com.example.sidebar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sidebar.ui.dashboard.DashboardScreen
import com.example.sidebar.ui.dashboard.DashboardViewModel
import com.example.sidebar.ui.theme.SidebarTheme
import com.example.sidebar.sidebar.SidebarService


class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SidebarTheme {
                val dashboardViewModel: DashboardViewModel = viewModel()
                val state by dashboardViewModel.uiState.collectAsState()

                DashboardScreen(
                    state = state,
                    onStart = { SidebarService.start(this@MainActivity) },
                    onStop = { SidebarService.stop(this@MainActivity) },
                    onRestart = {
                        SidebarService.stop(this@MainActivity)
                    },
                    onDismissMessage = dashboardViewModel::dismissMessage
                )
            }
        }
    }
}
