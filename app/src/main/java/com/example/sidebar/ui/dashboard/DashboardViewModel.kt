
package com.example.sidebar.ui.dashboard

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ServiceOperation {
    START,
    STOP,
    RESTART
}

data class DashboardUiState(
    val serviceRunning: Boolean = false,
    val serviceImplemented: Boolean = true,
    val operation: ServiceOperation? = null,
    val message: String? = null
)

class DashboardViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState = _uiState.asStateFlow()

    fun syncServiceState(running: Boolean) {
        _uiState.update {
            it.copy(serviceRunning = running)
        }
    }

    fun beginOperation(operation: ServiceOperation) {
        _uiState.update {
            it.copy(
                operation = operation,
                message = when (operation) {
                    ServiceOperation.START -> "Starting sidebar service…"
                    ServiceOperation.STOP -> "Stopping sidebar service…"
                    ServiceOperation.RESTART -> "Restarting sidebar service…"
                }
            )
        }
    }

    fun operationCompleted(running: Boolean) {
        _uiState.update {
            it.copy(
                serviceRunning = running,
                operation = null,
                message = if (running) {
                    "Sidebar service is running."
                } else {
                    "Sidebar service stopped."
                }
            )
        }
    }

}
