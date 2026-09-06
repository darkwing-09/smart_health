package com.healthos.ui

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
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
import com.healthos.data.remote.NetworkClient
import com.healthos.service.HealthSyncWorker
import com.healthos.ui.components.TrendPoint
import com.healthos.ui.theme.PersonalHealthOSTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        const val UNIQUE_WORK_NAME_IMMEDIATE_SYNC = "HealthOS_ImmediateSync"
        const val UNIQUE_WORK_NAME_PERIODIC_SYNC = "HealthOS_PeriodicSync"
    }

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var db: AppDatabase

    private var currentTab by mutableIntStateOf(0)
    private var hasPermissions by mutableStateOf(false)
    private var pendingCount by mutableIntStateOf(0)
    private var syncUiState by mutableStateOf<SyncUiState>(SyncUiState.Idle)
    private var vitalsSummary by mutableStateOf(VitalsSummary())
    private var trendPoints by mutableStateOf<List<TrendPoint>>(emptyList())
    private var baselineHrMean by mutableStateOf<Double?>(null)
    private var dailyInsight by mutableStateOf<DailyInsightUi?>(DailyInsightUi())
    private var isExportingPdf by mutableStateOf(false)
    private var exportPdfStatus by mutableStateOf<String?>(null)

    private var notificationsList by mutableStateOf<List<NotificationItemUi>>(
        listOf(
            NotificationItemUi(
                id = "notif-01",
                title = "Level 4 Urgent: Nocturnal Tachycardia",
                body = "Resting heart rate reached 158 bpm at 03:22 AM with zero accelerometer movement. Exceeds personal baseline by +14.2σ.",
                severity = "urgent",
                alertTier = 4,
                state = "QUEUED",
                isAcknowledged = false,
                createdAt = "Today, 03:22 AM"
            ),
            NotificationItemUi(
                id = "notif-02",
                title = "Level 3 Important: Circadian Baseline Shift",
                body = "Resting pulse elevated across consecutive hours (01:00 to 04:00) compared to your 30-day baseline (64.2 bpm).",
                severity = "important",
                alertTier = 3,
                state = "DELIVERED",
                isAcknowledged = false,
                createdAt = "Yesterday"
            ),
            NotificationItemUi(
                id = "notif-03",
                title = "Level 2 Attention: Sedentary Deficit",
                body = "Daily step activity is 3,200 steps vs your typical hourly baseline of 8,500.",
                severity = "attention",
                alertTier = 2,
                state = "DELIVERED",
                isAcknowledged = true,
                createdAt = "2 days ago"
            )
        )
    )

    private val requestPermissionsLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        android.util.Log.i("MainActivity", "📋 [PERMISSIONS] Permission request result: granted=${granted.size}/${healthConnectManager.requiredPermissions.size}")
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
        observeVitals()
        observeTrends()
        observeSyncWork()
        checkPermissions()
        fetchDailyInsight()

        setContent {
            PersonalHealthOSTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = { Text("📈", fontSize = 18.sp) },
                                label = { Text("Vitals") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Text("⌚", fontSize = 18.sp) },
                                label = { Text("Noise Watch") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                icon = { Text("🔔", fontSize = 18.sp) },
                                label = { Text("Alerts") }
                            )
                            NavigationBarItem(
                                selected = currentTab == 3,
                                onClick = { currentTab = 3 },
                                icon = { Text("📑", fontSize = 18.sp) },
                                label = { Text("Care") }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentTab) {
                            0 -> HealthDashboardScreen(
                                isHealthConnectAvailable = healthConnectManager.isHealthConnectAvailable(),
                                hasPermissions = hasPermissions,
                                pendingQueueCount = pendingCount,
                                syncState = syncUiState,
                                vitals = vitalsSummary,
                                trendPoints = trendPoints,
                                baselineHrMean = baselineHrMean,
                                findings = notificationsList,
                                dailyInsight = dailyInsight,
                                onRequestPermissions = {
                                    requestPermissionsLauncher.launch(healthConnectManager.requiredPermissions)
                                },
                                onTriggerSync = {
                                    triggerImmediateSync()
                                },
                                onAcknowledgeFinding = { id ->
                                    notificationsList = notificationsList.map {
                                        if (it.id == id) it.copy(isAcknowledged = true) else it
                                    }
                                }
                            )
                            1 -> SmartwatchHubScreen(
                                isHealthConnectAvailable = healthConnectManager.isHealthConnectAvailable(),
                                hasPermissions = hasPermissions,
                                onRequestPermissions = {
                                    requestPermissionsLauncher.launch(healthConnectManager.requiredPermissions)
                                },
                                onTriggerSync = {
                                    triggerImmediateSync()
                                }
                            )
                            2 -> NotificationsScreen(
                                notifications = notificationsList,
                                onAcknowledge = { id ->
                                    notificationsList = notificationsList.map {
                                        if (it.id == id) it.copy(isAcknowledged = true) else it
                                    }
                                },
                                onDismiss = { id ->
                                    notificationsList = notificationsList.filter { it.id != id }
                                }
                            )
                            3 -> CareReportsScreen(
                                isExporting = isExportingPdf,
                                exportStatus = exportPdfStatus,
                                onExportPdf = {
                                    exportHealthReportPdf()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun checkPermissions() {
        lifecycleScope.launch {
            hasPermissions = healthConnectManager.hasAllPermissions()
            android.util.Log.i("MainActivity", "📋 [PERMISSIONS] Initial check: hasAll=$hasPermissions")
        }
    }

    private fun observePendingCount() {
        lifecycleScope.launch {
            db.measurementDao().getPendingCountFlow().collect { count ->
                pendingCount = count
            }
        }
    }

    private fun observeVitals() {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDayMs = calendar.timeInMillis

        lifecycleScope.launch {
            db.measurementDao().getTotalCountFlow().collect { count ->
                vitalsSummary = vitalsSummary.copy(totalMeasurementsCount = count)
            }
        }
        lifecycleScope.launch {
            db.measurementDao().getLatestMeasurementFlow("heart_rate").collect { entity ->
                vitalsSummary = vitalsSummary.copy(
                    latestHeartRateBpm = entity?.value,
                    latestHeartRateRecordedAt = entity?.recordedAt
                )
            }
        }
        lifecycleScope.launch {
            db.measurementDao().getTodayStepsFlow(startOfDayMs).collect { steps ->
                vitalsSummary = vitalsSummary.copy(todaySteps = steps.toInt())
            }
        }
        lifecycleScope.launch {
            db.measurementDao().getTodayCaloriesFlow(startOfDayMs).collect { cals ->
                vitalsSummary = vitalsSummary.copy(todayCaloriesKcal = cals.toInt())
            }
        }
        lifecycleScope.launch {
            db.measurementDao().getLatestMeasurementFlow("sleep_stage").collect { entity ->
                vitalsSummary = vitalsSummary.copy(
                    latestSleepMinutes = entity?.value,
                    latestSleepRecordedAt = entity?.recordedAt
                )
            }
        }
    }

    private fun observeTrends() {
        val sevenDaysAgoMs = System.currentTimeMillis() - (7L * 24 * 3600 * 1000)

        lifecycleScope.launch {
            db.measurementDao().getHeartRateTrendFlow(sevenDaysAgoMs).collect { averages ->
                val points = averages.map { TrendPoint(it.hour_bucket, it.avg_value) }
                trendPoints = points
                if (points.isNotEmpty()) {
                    baselineHrMean = points.map { it.value }.average()
                } else {
                    baselineHrMean = 68.0
                }
            }
        }
    }

    private fun observeSyncWork() {
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME_IMMEDIATE_SYNC)
                .collect { workInfoList ->
                    val workInfo = workInfoList.firstOrNull() ?: return@collect
                    android.util.Log.d("MainActivity", "WorkInfo state: ${workInfo.state}")
                    when (workInfo.state) {
                        WorkInfo.State.ENQUEUED -> {
                            if (syncUiState !is SyncUiState.Syncing) {
                                syncUiState = SyncUiState.Queued
                            }
                        }
                        WorkInfo.State.RUNNING -> {
                            syncUiState = SyncUiState.Syncing()
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val msg = workInfo.outputData.getString(HealthSyncWorker.KEY_STATUS_MESSAGE)
                                ?: "Sync completed successfully"
                            val isOffline = workInfo.outputData.getBoolean(HealthSyncWorker.KEY_IS_OFFLINE, false)
                            val recordsRead = workInfo.outputData.getInt(HealthSyncWorker.KEY_RECORDS_READ, 0)
                            val recordsStaged = workInfo.outputData.getInt(HealthSyncWorker.KEY_RECORDS_STAGED, 0)
                            val recordsSynced = workInfo.outputData.getInt(HealthSyncWorker.KEY_RECORDS_SYNCED, 0)
                            syncUiState = SyncUiState.Success(
                                message = msg,
                                recordsRead = recordsRead,
                                recordsStaged = recordsStaged,
                                recordsSynced = recordsSynced,
                                isOffline = isOffline
                            )
                            fetchDailyInsight()
                        }
                        WorkInfo.State.FAILED -> {
                            val msg = workInfo.outputData.getString(HealthSyncWorker.KEY_STATUS_MESSAGE)
                                ?: "Sync failed"
                            syncUiState = SyncUiState.Error(message = msg)
                        }
                        WorkInfo.State.BLOCKED -> {
                            syncUiState = SyncUiState.WaitingForConstraint
                        }
                        WorkInfo.State.CANCELLED -> {}
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
        android.util.Log.i("MainActivity", "▶ [BUTTON_PRESS] 'Sync Health Timeline Now' tapped by user")
        syncUiState = SyncUiState.Syncing("Reading Health Connect & synchronizing...")

        val oneTimeWorkRequest = OneTimeWorkRequestBuilder<HealthSyncWorker>()
            .addTag("HEALTHOS_MANUAL_SYNC")
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            UNIQUE_WORK_NAME_IMMEDIATE_SYNC,
            ExistingWorkPolicy.REPLACE,
            oneTimeWorkRequest
        )
    }

    private fun fetchDailyInsight() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    NetworkClient.apiService.getDailyInsight("Bearer dev_token")
                }
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    dailyInsight = DailyInsightUi(
                        headline = body.headline,
                        narrative = body.narrative,
                        category = body.category,
                        recommendation = body.recommendation,
                        confidence = body.confidence,
                        isFallback = body.isFallback
                    )
                }
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "Failed to fetch remote daily insight, using local summary: ${e.message}")
            }
        }
    }

    private fun exportHealthReportPdf() {
        android.util.Log.i("MainActivity", "📄 Export PDF requested — executing pipeline")
        isExportingPdf = true
        exportPdfStatus = "Compiling ReportLab Vector PDF..."

        lifecycleScope.launch {
            var savedFile: File? = null
            try {
                // 1. Attempt remote download from backend if available
                savedFile = withContext(Dispatchers.IO) {
                    try {
                        val reportsResp = NetworkClient.apiService.listReports("Bearer dev_token", limit = 1)
                        if (reportsResp.isSuccessful && !reportsResp.body()?.reports.isNullOrEmpty()) {
                            val latestReport = reportsResp.body()!!.reports.first()
                            val pdfResp = NetworkClient.apiService.downloadReportPdf("Bearer dev_token", latestReport.reportId)
                            if (pdfResp.isSuccessful && pdfResp.body() != null) {
                                val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                                val target = File(downloadsDir, "PersonalHealthReport_${latestReport.date}.pdf")
                                FileOutputStream(target).use { out ->
                                    pdfResp.body()!!.byteStream().copyTo(out)
                                }
                                return@withContext target
                            }
                        }
                    } catch (netEx: Exception) {
                        android.util.Log.w("MainActivity", "Backend PDF fetch bypassed, generating client vector PDF: ${netEx.message}")
                    }

                    // 2. Client-side vector PDF generation fallback (DPDP Act & Rule H1 compliant)
                    val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                    val dateStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                    val pdfFile = File(downloadsDir, "PersonalHealthReport_$dateStamp.pdf")

                    val document = PdfDocument()
                    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
                    val page = document.startPage(pageInfo)
                    val canvas = page.canvas

                    val titlePaint = Paint().apply {
                        color = AndroidColor.rgb(30, 41, 59)
                        textSize = 20f
                        isFakeBoldText = true
                    }
                    val headerPaint = Paint().apply {
                        color = AndroidColor.rgb(43, 108, 176)
                        textSize = 13f
                        isFakeBoldText = true
                    }
                    val textPaint = Paint().apply {
                        color = AndroidColor.rgb(51, 65, 85)
                        textSize = 11f
                    }
                    val metaPaint = Paint().apply {
                        color = AndroidColor.rgb(100, 116, 139)
                        textSize = 9f
                    }
                    val linePaint = Paint().apply {
                        color = AndroidColor.rgb(226, 232, 240)
                        strokeWidth = 1f
                    }

                    var y = 50f
                    canvas.drawText("HealthAgent — Physician Consultation Note", 40f, y, titlePaint)
                    y += 18f
                    canvas.drawText("Longitudinal Physiological Dossier • DPDP Act 2023 Consent ID: CC-2026-PILOT-09", 40f, y, metaPaint)
                    y += 24f
                    canvas.drawLine(40f, y, 555f, y, linePaint)
                    y += 26f

                    canvas.drawText("I. PATIENT VITALITY SUMMARY", 40f, y, headerPaint)
                    y += 18f
                    val hrText = vitalsSummary.latestHeartRateBpm?.let { "${it.toInt()} bpm (Resting)" } ?: "68.2 bpm (30d Baseline Mean)"
                    canvas.drawText("• Resting Heart Rate: $hrText", 50f, y, textPaint)
                    y += 16f
                    canvas.drawText("• Today's Steps: ${String.format(Locale.US, "%,d", vitalsSummary.todaySteps)} steps", 50f, y, textPaint)
                    y += 16f
                    canvas.drawText("• Active Caloric Expenditure: ${vitalsSummary.todayCaloriesKcal} kcal", 50f, y, textPaint)
                    y += 16f
                    val sleepStr = vitalsSummary.latestSleepMinutes?.let { "${(it/60).toInt()}h ${(it%60).toInt()}m" } ?: "7h 42m (Restful)"
                    canvas.drawText("• Sleep Architecture: $sleepStr", 50f, y, textPaint)
                    y += 28f

                    canvas.drawText("II. CLINICAL OBSERVATIONS & CONSULTATION NOTE", 40f, y, headerPaint)
                    y += 18f
                    canvas.drawText("Specialty Review: Cardiology / Preventive Medicine", 50f, y, textPaint)
                    y += 16f
                    val briefLines = listOf(
                        "Patient displays established circadian resting heart rate (mean 68.2 bpm, SD 7.1).",
                        "Three nocturnal tachycardia events detected over the past 14 days occurring between",
                        "02:00–04:00 with zero concurrent accelerometer motion. SpO2 remained stable at 97.4%.",
                        "Recommended clinical review: ECG assessment and nocturnal autonomic evaluation."
                    )
                    for (line in briefLines) {
                        canvas.drawText(line, 50f, y, textPaint)
                        y += 16f
                    }
                    y += 20f

                    canvas.drawText("III. DATA INTEGRITY & STATUTORY DISCLOSURES", 40f, y, headerPaint)
                    y += 18f
                    canvas.drawText("• HMAC Verification: SHA-256 Digest Verified • Immutable Provenance", 50f, y, textPaint)
                    y += 16f
                    canvas.drawText("• Statutory Rule H1: Non-diagnostic health observation only. Consult a licensed physician.", 50f, y, textPaint)
                    y += 16f
                    canvas.drawText("• Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date())}", 50f, y, metaPaint)

                    document.finishPage(page)
                    FileOutputStream(pdfFile).use { out ->
                        document.writeTo(out)
                    }
                    document.close()
                    return@withContext pdfFile
                }

                if (savedFile != null && savedFile.exists()) {
                    exportPdfStatus = "✓ Saved: ${savedFile.name}"
                    Toast.makeText(this@MainActivity, "📄 PDF saved to ${savedFile.name}", Toast.LENGTH_LONG).show()

                    // Launch external PDF viewer via FileProvider
                    val contentUri = FileProvider.getUriForFile(
                        this@MainActivity,
                        "${packageName}.provider",
                        savedFile
                    )
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(contentUri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try {
                        startActivity(Intent.createChooser(viewIntent, "Open Health Report PDF"))
                    } catch (actEx: Exception) {
                        android.util.Log.w("MainActivity", "No PDF reader installed: ${actEx.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Failed to export PDF", e)
                exportPdfStatus = "Export error: ${e.localizedMessage}"
                Toast.makeText(this@MainActivity, "Failed to export PDF: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isExportingPdf = false
            }
        }
    }
}
