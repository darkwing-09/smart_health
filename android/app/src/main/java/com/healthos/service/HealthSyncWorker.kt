package com.healthos.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.healthos.data.adapter.HealthConnectManager
import com.healthos.data.local.AppDatabase
import com.healthos.data.local.SyncStatus
import com.healthos.data.remote.BatchIngestRequestDto
import com.healthos.data.remote.MeasurementItemDto
import com.healthos.data.remote.NetworkClient
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "HealthSyncWorker"
        const val ORPHAN_TIMEOUT_MS = 15 * 60 * 1000L  // 15 minutes

        const val KEY_RECORDS_STAGED = "records_staged"
        const val KEY_RECORDS_SYNCED = "records_synced"
        const val KEY_STATUS_MESSAGE = "status_message"
        const val KEY_IS_OFFLINE = "is_offline"
    }

    private val db = AppDatabase.getInstance(appContext)
    private val healthConnectManager = HealthConnectManager(appContext)
    private val api = NetworkClient.apiService

    override suspend fun doWork(): Result {
        return try {
            Log.i(TAG, "HealthSyncWorker execution started [id=$id]")

            // 0. Crash Recovery: Reset orphaned IN_FLIGHT records from previous worker crashes
            val recoveredCount = db.measurementDao().resetOrphanedInFlightRecords(
                timeoutThresholdMs = System.currentTimeMillis() - ORPHAN_TIMEOUT_MS
            )
            if (recoveredCount > 0) {
                Log.i(TAG, "Recovered $recoveredCount orphaned IN_FLIGHT records back to PENDING")
            }

            // 1. Ingest latest measurements from Health Connect into offline Room DB (Always executed locally, offline-safe)
            var stagedCount = 0
            if (healthConnectManager.isHealthConnectAvailable() && healthConnectManager.hasAllPermissions()) {
                try {
                    val freshRecords = healthConnectManager.readRecentMeasurements(hoursBack = 24)
                    if (freshRecords.isNotEmpty()) {
                        val insertedRowIds = db.measurementDao().insertAll(freshRecords)
                        stagedCount = insertedRowIds.count { it != -1L }
                        Log.i(TAG, "Health Connect read: ${freshRecords.size} records found, newly staged $stagedCount in Room")
                    } else {
                        Log.i(TAG, "Health Connect read: 0 records found in 24h window")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Health Connect read non-fatal error: ${e.message}")
                }
            } else {
                Log.w(
                    TAG,
                    "Health Connect unavailable or permissions missing. " +
                        "available=${healthConnectManager.isHealthConnectAvailable()}, " +
                        "permissions=${healthConnectManager.hasAllPermissions()}"
                )
            }

            // 2. Fetch syncable batch (PENDING + retryable FAILED with backoff)
            val pending = db.measurementDao().getSyncableBatch(limit = 200)
            if (pending.isEmpty()) {
                val completionMessage = if (stagedCount > 0) {
                    "Staged $stagedCount records in offline database"
                } else {
                    "Health Connect is up to date (0 new records)"
                }
                Log.i(TAG, "Sync complete. No pending records to transmit across network. $completionMessage")
                return Result.success(
                    workDataOf(
                        KEY_RECORDS_STAGED to stagedCount,
                        KEY_RECORDS_SYNCED to 0,
                        KEY_IS_OFFLINE to false,
                        KEY_STATUS_MESSAGE to completionMessage
                    )
                )
            }

            // 3. Check network availability before attempting HTTP dispatch
            val isConnected = isNetworkAvailable(applicationContext)
            if (!isConnected) {
                val offlineMsg = "Offline: Staged $stagedCount records. ${pending.size} records queued in local database."
                Log.i(TAG, "Device is offline. $offlineMsg")
                return Result.success(
                    workDataOf(
                        KEY_RECORDS_STAGED to stagedCount,
                        KEY_RECORDS_SYNCED to 0,
                        KEY_IS_OFFLINE to true,
                        KEY_STATUS_MESSAGE to offlineMsg
                    )
                )
            }

            val ids = pending.map { it.id }
            db.measurementDao().updateSyncStatus(ids, SyncStatus.IN_FLIGHT)

            // 4. Prepare network payload
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
            val token = resolveAuthToken()

            // 5. Dispatch batch to FastAPI backend
            val response = try {
                api.syncBatch(
                    bearerToken = token,
                    idempotencyKey = idempotencyKey,
                    payload = batchRequest
                )
            } catch (e: IOException) {
                // Network unreachable, DNS failure, or connection refused: revert batch to PENDING so records are preserved
                Log.w(TAG, "Backend unreachable (${e.javaClass.simpleName}: ${e.message}). Reverting ${ids.size} records to PENDING.")
                db.measurementDao().updateSyncStatus(ids, SyncStatus.PENDING)
                return Result.success(
                    workDataOf(
                        KEY_RECORDS_STAGED to stagedCount,
                        KEY_RECORDS_SYNCED to 0,
                        KEY_IS_OFFLINE to true,
                        KEY_STATUS_MESSAGE to "Server unreachable. ${pending.size} records preserved locally in queue."
                    )
                )
            }

            if (response.isSuccessful) {
                db.measurementDao().updateSyncStatus(ids, SyncStatus.SYNCED)
                val successMsg = "Successfully synced ${ids.size} records to timeline"
                Log.i(TAG, successMsg)
                Result.success(
                    workDataOf(
                        KEY_RECORDS_STAGED to stagedCount,
                        KEY_RECORDS_SYNCED to ids.size,
                        KEY_IS_OFFLINE to false,
                        KEY_STATUS_MESSAGE to successMsg
                    )
                )
            } else {
                handleServerError(response.code(), ids, stagedCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected sync worker error: ${e.message}", e)
            Result.failure(
                workDataOf(
                    KEY_RECORDS_STAGED to 0,
                    KEY_RECORDS_SYNCED to 0,
                    KEY_STATUS_MESSAGE to "Sync failed: ${e.message}"
                )
            )
        }
    }

    /**
     * Classifies HTTP error codes into permanent (don't retry) vs transient (retry).
     * - 401/403: Authentication failure — do not busy-spin retry
     * - 422: Validation failure — permanent, do not retry (data is bad)
     * - 5xx: Server error — retry with backoff
     */
    private suspend fun handleServerError(statusCode: Int, ids: List<String>, stagedCount: Int): Result {
        return when (statusCode) {
            401, 403 -> {
                Log.w(TAG, "Auth failure ($statusCode) — marking batch as failed")
                db.measurementDao().incrementFailedAttempts(ids)
                Result.failure(
                    workDataOf(
                        KEY_RECORDS_STAGED to stagedCount,
                        KEY_RECORDS_SYNCED to 0,
                        KEY_STATUS_MESSAGE to "Authentication failed ($statusCode). Check credentials."
                    )
                )
            }
            422 -> {
                Log.w(TAG, "Validation error (422) — marking batch as permanently failed")
                db.measurementDao().incrementFailedAttempts(ids)
                Result.failure(
                    workDataOf(
                        KEY_RECORDS_STAGED to stagedCount,
                        KEY_RECORDS_SYNCED to 0,
                        KEY_STATUS_MESSAGE to "Server rejected invalid measurement payload (422)."
                    )
                )
            }
            in 500..599 -> {
                Log.w(TAG, "Server error ($statusCode) — reverting to PENDING for retry")
                db.measurementDao().updateSyncStatus(ids, SyncStatus.PENDING)
                db.measurementDao().incrementFailedAttempts(ids)
                Result.retry()
            }
            else -> {
                Log.w(TAG, "Unexpected HTTP $statusCode — scheduling retry")
                db.measurementDao().updateSyncStatus(ids, SyncStatus.PENDING)
                db.measurementDao().incrementFailedAttempts(ids)
                Result.retry()
            }
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }


    /**
     * Resolves the authentication bearer token.
     * In production, this reads from EncryptedSharedPreferences.
     * Falls back to a development mock token if not configured.
     */
    private fun resolveAuthToken(): String {
        // Production: read from EncryptedSharedPreferences
        val prefs = applicationContext.getSharedPreferences("healthos_auth", Context.MODE_PRIVATE)
        val token = prefs.getString("access_token", null)
        return if (!token.isNullOrBlank()) {
            "Bearer $token"
        } else {
            // Development fallback
            "Bearer dev_mock_token"
        }
    }
}
