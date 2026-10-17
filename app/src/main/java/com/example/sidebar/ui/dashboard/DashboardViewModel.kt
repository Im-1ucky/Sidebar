
package com.example.sidebar.ui.dashboard

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DashboardUiState(
    val serviceRunning: Boolean = false,
    val serviceImplemented: Boolean = true,
    val message: String? = null
)

class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState = _uiState.asStateFlow()

    fun startSidebar() {
        _uiState.update { it.copy(message = "Starting sidebar service…") }
    }

    fun stopSidebar() {
        _uiState.update { it.copy(message = "Stopping sidebar service…") }
    }

    fun restartSidebar() {
        _uiState.update { it.copy(message = "Restarting sidebar service…") }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }
}
