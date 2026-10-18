
package com.example.sidebar.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DashboardScreen(
    state: DashboardUiState,
    screenshotFolderLabel: String,
    recordingFolderLabel: String,
    captureDelaySeconds: Int,
    onCaptureDelayChanged: (Int) -> Unit,
    onChooseScreenshotFolder: () -> Unit,
    onResetScreenshotFolder: () -> Unit,
    onChooseRecordingFolder: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    val busy = state.operation != null

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Spacer(Modifier.height(2.dp))

            Text(
                text = "Dashboard",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(
                                text = "SIDEBAR SERVICE",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Text(
                                text = when {
                                    !state.serviceImplemented -> "Not configured"
                                    state.operation == ServiceOperation.START -> "Starting…"
                                    state.operation == ServiceOperation.STOP -> "Stopping…"
                                    state.operation == ServiceOperation.RESTART -> "Restarting…"
                                    state.serviceRunning -> "Running"
                                    else -> "Stopped"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (state.serviceRunning) "ACTIVE" else "INACTIVE",
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 8.dp
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Text(
                        text = "Control the sidebar background service.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy
                    ) {
                        if (state.operation == ServiceOperation.START) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Starting…")
                        } else {
                            Text("Start sidebar")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onStop,
                            modifier = Modifier.weight(1f),
                            enabled = !busy
                        ) {
                            if (state.operation == ServiceOperation.STOP) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Stopping…")
                            } else {
                                Text("Stop")
                            }
                        }

                        OutlinedButton(
                            onClick = onRestart,
                            modifier = Modifier.weight(1f),
                            enabled = !busy
                        ) {
                            if (state.operation == ServiceOperation.RESTART) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Restarting…")
                            } else {
                                Text("Restart")
                            }
                        }
                    }
                }
            }

            Text(
                text = "Screenshot settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Capture delay",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = "The countdown begins after Android's consent prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 3, 5).forEach { seconds ->
                            val label = if (seconds == 0) "Instant" else "${seconds}s"

                            if (captureDelaySeconds == seconds) {
                                Button(
                                    onClick = { onCaptureDelayChanged(seconds) }
                                ) {
                                    Text(label)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onCaptureDelayChanged(seconds) }
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }

                    Text(
                        text = "Save screenshots to",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = screenshotFolderLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onChooseScreenshotFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose folder")
                    }

                    if (screenshotFolderLabel != "Pictures/Sidebar (default)") {
                        OutlinedButton(
                            onClick = onResetScreenshotFolder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use default folder")
                        }
                    }
                }
            }

            Text(
                text = "Screen recording settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Save recordings to",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Text(
                        text = recordingFolderLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onChooseRecordingFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Choose folder")
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
