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
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.healthos.data.adapter.HealthConnectManager
import com.healthos.data.local.AppDatabase
import com.healthos.service.HealthSyncWorker
import com.healthos.ui.theme.PersonalHealthOSTheme
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var db: AppDatabase

    private var hasPermissions by mutableStateOf(false)
    private var pendingCount by mutableIntStateOf(0)

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
        checkStatus()

        setContent {
            PersonalHealthOSTheme {
                HealthDashboardScreen(
                    isHealthConnectAvailable = healthConnectManager.isHealthConnectAvailable(),
                    hasPermissions = hasPermissions,
                    pendingQueueCount = pendingCount,
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

    private fun checkStatus() {
        lifecycleScope.launch {
            hasPermissions = healthConnectManager.hasAllPermissions()
            pendingCount = db.measurementDao().getPendingCount()
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
            "HealthOS_PeriodicSync",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWorkRequest
        )
    }

    private fun triggerImmediateSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueue(oneTimeWorkRequest)
        checkStatus()
    }
}
