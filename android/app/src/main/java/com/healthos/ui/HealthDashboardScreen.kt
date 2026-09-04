package com.healthos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

sealed interface SyncUiState {
    data object Idle : SyncUiState
    data object Queued : SyncUiState
    data object Syncing : SyncUiState
    data object WaitingForConstraint : SyncUiState
    data class Success(val message: String, val isOffline: Boolean = false, val timestamp: Long = System.currentTimeMillis()) : SyncUiState
    data class Error(val message: String, val timestamp: Long = System.currentTimeMillis()) : SyncUiState
}

@Composable
fun HealthDashboardScreen(
    isHealthConnectAvailable: Boolean,
    hasPermissions: Boolean,
    pendingQueueCount: Int,
    syncState: SyncUiState = SyncUiState.Idle,
    onRequestPermissions: () -> Unit,
    onTriggerSync: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Personal Health OS",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = "Longitudinal health intelligence and calm observation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Integration & Gateway Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Device Gateway Status",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Health Connect:")
                        Text(
                            text = if (isHealthConnectAvailable) "Available" else "Not Installed",
                            color = if (isHealthConnectAvailable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Biometric Permissions:")
                        Text(
                            text = if (hasPermissions) "Granted" else "Action Required",
                            color = if (hasPermissions) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Offline Queue Depth:")
                        Text("$pendingQueueCount records pending")
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sync Status:")
                        val (statusText, statusColor) = when (syncState) {
                            is SyncUiState.Idle -> "Idle" to MaterialTheme.colorScheme.onSurfaceVariant
                            is SyncUiState.Queued -> "Queued..." to MaterialTheme.colorScheme.primary
                            is SyncUiState.Syncing -> "Syncing..." to MaterialTheme.colorScheme.primary
                            is SyncUiState.WaitingForConstraint -> "Waiting for network" to MaterialTheme.colorScheme.tertiary
                            is SyncUiState.Success -> (if (syncState.isOffline) "Staged Offline" else "Up to date") to MaterialTheme.colorScheme.secondary
                            is SyncUiState.Error -> "Failed" to MaterialTheme.colorScheme.error
                        }
                        Text(text = statusText, color = statusColor)
                    }
                }
            }

            // Sync Status Activity Banner
            if (syncState !is SyncUiState.Idle) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (syncState) {
                            is SyncUiState.Syncing, is SyncUiState.Queued -> MaterialTheme.colorScheme.surfaceVariant
                            is SyncUiState.Success -> if (syncState.isOffline) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.secondaryContainer
                            is SyncUiState.WaitingForConstraint -> MaterialTheme.colorScheme.tertiaryContainer
                            is SyncUiState.Error -> MaterialTheme.colorScheme.errorContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (syncState is SyncUiState.Syncing || syncState is SyncUiState.Queued) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val bannerText = when (syncState) {
                            is SyncUiState.Queued -> "Sync queued in WorkManager..."
                            is SyncUiState.Syncing -> "Reading Health Connect & synchronizing..."
                            is SyncUiState.WaitingForConstraint -> "⏳ Waiting for network connection..."
                            is SyncUiState.Success -> "✓ ${syncState.message}"
                            is SyncUiState.Error -> "⚠ ${syncState.message}"
                            else -> ""
                        }
                        Text(
                            text = bannerText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (!hasPermissions) {
                Button(
                    onClick = onRequestPermissions,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Grant Health Connect Permissions")
                }
            }

            val isSyncActive = syncState is SyncUiState.Syncing || syncState is SyncUiState.Queued
            OutlinedButton(
                onClick = onTriggerSync,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncActive
            ) {
                if (isSyncActive) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text("Synchronizing Timeline...")
                    }
                } else {
                    Text("Sync Health Timeline Now")
                }
            }
        }
    }
}
