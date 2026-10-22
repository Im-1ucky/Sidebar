
package com.example.sidebar.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sidebar.recording.RecordingAudioMode

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
    onResetRecordingFolder: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    recordingAudioMode: RecordingAudioMode,
    onRecordingAudioModeChanged: (RecordingAudioMode) -> Unit,
    overlayPermissionGranted: Boolean,
    notificationsPermissionGranted: Boolean,
    onOpenOverlaySettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit
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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
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
                                    !state.serviceRunning -> "Stopped"
                                    state.sidebarVisible -> "Running"
                                    else -> "Running · Hidden"
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
                                text = when {
                                    !state.serviceRunning -> "INACTIVE"
                                    state.sidebarVisible -> "VISIBLE"
                                    else -> "HIDDEN"
                                },
                                modifier = Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 8.dp
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Text(
                        text = when {
                            !state.serviceRunning ->
                                "The sidebar service has stopped."
                            state.sidebarVisible ->
                                "The sidebar service is running and the sidebar is visible."
                            else ->
                                "The service is running, but the sidebar is hidden."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    state.message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

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
                text = "Permissions & Access",
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
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    PermissionRow(
                        title = "Display over other apps",
                        category = "Necessary",
                        description = "Required for the floating sidebar.",
                        enabled = overlayPermissionGranted,
                        onClick = onOpenOverlaySettings
                    )

                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    PermissionRow(
                        title = "Notifications",
                        category = "Optional",
                        description = "Used for service notifications.",
                        enabled = notificationsPermissionGranted,
                        onClick = onOpenNotificationSettings
                    )
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

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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

                    if (screenshotFolderLabel.endsWith("(custom)")) {
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
                        text = "Recording audio",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RecordingAudioMode.entries.forEach { mode ->
                            val label = when (mode) {
                                RecordingAudioMode.MICROPHONE -> "Microphone"
                                RecordingAudioMode.NONE -> "No audio"
                            }

                            if (recordingAudioMode == mode) {
                                Button(
                                    onClick = { onRecordingAudioModeChanged(mode) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(label)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onRecordingAudioModeChanged(mode) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }

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

                    if (recordingFolderLabel.endsWith("(custom)")) {
                        OutlinedButton(
                            onClick = onResetRecordingFolder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Use default folder")
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    category: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClick = onClick
            )
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = "$category · $description",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (enabled) "Enabled" else "Not enabled",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )

            Text(
                text = "Manage ›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
