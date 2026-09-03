package com.healthos.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.healthos.data.adapter.HealthConnectManager
import com.healthos.data.local.AppDatabase
import com.healthos.data.local.SyncStatus
import com.healthos.data.remote.BatchIngestRequestDto
import com.healthos.data.remote.MeasurementItemDto
import com.healthos.data.remote.NetworkClient
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val db = AppDatabase.getInstance(appContext)
    private val healthConnectManager = HealthConnectManager(appContext)
    private val api = NetworkClient.apiService

    override suspend fun doWork(): Result {
        return try {
            // 1. Ingest latest measurements from Health Connect into offline Room DB
            if (healthConnectManager.isHealthConnectAvailable() && healthConnectManager.hasAllPermissions()) {
                val freshRecords = healthConnectManager.readRecentMeasurements(hoursBack = 6)
                if (freshRecords.isNotEmpty()) {
                    db.measurementDao().insertAll(freshRecords)
                }
            }

            // 2. Fetch pending batch from offline queue
            val pending = db.measurementDao().getPendingBatch(limit = 200)
            if (pending.isEmpty()) {
                return Result.success()
            }

            val ids = pending.map { it.id }
            db.measurementDao().updateSyncStatus(ids, SyncStatus.IN_FLIGHT)

            // 3. Prepare network payload
            val dtoList = pending.map { entity ->
                MeasurementItemDto(
                    sourceRecordId = entity.sourceRecordId,
                    metricType = entity.metricType,
                    value = entity.value,
                    unit = entity.unit,
                    recordedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(entity.recordedAt)),
                    confidence = entity.confidence,
                    dataQualityFlag = entity.dataQualityFlag
                )
            }

            val batchRequest = BatchIngestRequestDto(
                sourceId = "00000000-0000-0000-0000-000000000001", // Default Health Connect wearable source
                clientSyncTimestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                measurements = dtoList
            )

            val idempotencyKey = UUID.randomUUID().toString()
            val token = "Bearer dev_mock_token" // Sourced from encrypted SharedPreferences in production

            // 4. Dispatch batch to FastAPI backend
            val response = api.syncBatch(
                bearerToken = token,
                idempotencyKey = idempotencyKey,
                payload = batchRequest
            )

            if (response.isSuccessful) {
                db.measurementDao().updateSyncStatus(ids, SyncStatus.SYNCED)
                Result.success()
            } else {
                db.measurementDao().incrementFailedAttempts(ids)
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
