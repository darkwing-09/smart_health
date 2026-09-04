package com.healthos.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.healthos.data.adapter.HealthConnectManager
import com.healthos.data.local.AppDatabase
import com.healthos.service.HealthSyncWorker
import com.healthos.ui.theme.PersonalHealthOSTheme
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        const val UNIQUE_WORK_NAME_IMMEDIATE_SYNC = "HealthOS_ImmediateSync"
        const val UNIQUE_WORK_NAME_PERIODIC_SYNC = "HealthOS_PeriodicSync"
    }

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var db: AppDatabase

    private var hasPermissions by mutableStateOf(false)
    private var pendingCount by mutableIntStateOf(0)
    private var syncUiState by mutableStateOf<SyncUiState>(SyncUiState.Idle)

    private val requestPermissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthConnectManager.requiredPermissions)) {
            hasPermissions = true
            triggerImmediateSync()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        healthConnectManager = HealthConnectManager(this)
        db = AppDatabase.getInstance(this)

        schedulePeriodicSync()
        observePendingCount()
        observeSyncWork()
        checkPermissions()

        setContent {
            PersonalHealthOSTheme {
                HealthDashboardScreen(
                    isHealthConnectAvailable = healthConnectManager.isHealthConnectAvailable(),
                    hasPermissions = hasPermissions,
                    pendingQueueCount = pendingCount,
                    syncState = syncUiState,
                    onRequestPermissions = {
                        requestPermissionsLauncher.launch(healthConnectManager.requiredPermissions)
                    },
                    onTriggerSync = {
                        triggerImmediateSync()
                    }
                )
            }
        }
    }

    private fun checkPermissions() {
        lifecycleScope.launch {
            hasPermissions = healthConnectManager.hasAllPermissions()
        }
    }

    private fun observePendingCount() {
        lifecycleScope.launch {
            db.measurementDao().getPendingCountFlow().collect { count ->
                pendingCount = count
            }
        }
    }

    private fun observeSyncWork() {
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME_IMMEDIATE_SYNC)
                .collect { workInfoList ->
                    val workInfo = workInfoList.firstOrNull() ?: return@collect
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> {
                            syncUiState = SyncUiState.Queued
                        }
                        WorkInfo.State.RUNNING -> {
                            syncUiState = SyncUiState.Syncing
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val msg = workInfo.outputData.getString(HealthSyncWorker.KEY_STATUS_MESSAGE)
                                ?: "Sync completed successfully"
                            val isOffline = workInfo.outputData.getBoolean(HealthSyncWorker.KEY_IS_OFFLINE, false)
                            syncUiState = SyncUiState.Success(message = msg, isOffline = isOffline)
                        }
                        WorkInfo.State.FAILED -> {
                            val msg = workInfo.outputData.getString(HealthSyncWorker.KEY_STATUS_MESSAGE)
                                ?: "Sync failed"
                            syncUiState = SyncUiState.Error(message = msg)
                        }
                        WorkInfo.State.BLOCKED -> {
                            syncUiState = SyncUiState.WaitingForConstraint
                        }
                        WorkInfo.State.CANCELLED -> {
                            syncUiState = SyncUiState.Idle
                        }
                    }
                }
        }
    }

    private fun schedulePeriodicSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWorkRequest = PeriodicWorkRequestBuilder<HealthSyncWorker>(
            15, TimeUnit.MINUTES
        ).setConstraints(constraints).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    private fun triggerImmediateSync() {
        // Immediate sync runs without network constraint so Health Connect data is ALWAYS read and staged into Room immediately.
        // If network is available, it immediately dispatches the batch; if offline, data is retained in Room as PENDING.
        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .addTag("HEALTHOS_MANUAL_SYNC")
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            UNIQUE_WORK_NAME_IMMEDIATE_SYNC,
            ExistingWorkPolicy.REPLACE,
            oneTimeWorkRequest
        )
    }
}

