package com.healthos.data.adapter

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.healthos.data.local.OfflineMeasurementEntity
import com.healthos.data.local.SyncStatus
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class HealthConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnectManager"
    }

    private val healthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    val requiredPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    )

    fun isHealthConnectAvailable(): Boolean {
        return HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE
    }

    suspend fun getGrantedPermissions(): Set<String> {
        return try {
            healthConnectClient.permissionController.getGrantedPermissions()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ [PERMISSIONS] Failed to query granted permissions: ${e.message}")
            emptySet()
        }
    }

    suspend fun hasAllPermissions(): Boolean {
        val granted = getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    suspend fun hasAnyPermissions(): Boolean {
        val granted = getGrantedPermissions()
        return granted.any { it in requiredPermissions }
    }

    suspend fun readRecentMeasurements(hoursBack: Long = 30 * 24): List<OfflineMeasurementEntity> {
        val startTime = Instant.now().minus(hoursBack, ChronoUnit.HOURS)
        // TimeRangeFilter.after covers all records from startTime onwards, immune to minor clock skew
        val timeFilter = TimeRangeFilter.after(startTime)

        val results = mutableListOf<OfflineMeasurementEntity>()
        val grantedPermissions = getGrantedPermissions()
        android.util.Log.i(TAG, "🔍 [HEALTH_CONNECT_READ] Querying from $startTime onwards (${hoursBack}h / ${hoursBack / 24} days window)")
        android.util.Log.i(TAG, "📋 [PERMISSIONS] Granted permissions: $grantedPermissions")

        // 1. Read Heart Rate records
        val hrPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
        if (grantedPermissions.contains(hrPermission)) {
            try {
                val hrResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = HeartRateRecord::class,
                        timeRangeFilter = timeFilter
                    )
                )
                var sampleCount = 0
                for (record in hrResponse.records) {
                    for (sample in record.samples) {
                        sampleCount++
                        results.add(
                            OfflineMeasurementEntity(
                                id = UUID.randomUUID().toString(),
                                sourceRecordId = "hc_hr_${record.metadata.id}_${sample.time.toEpochMilli()}",
                                metricType = "heart_rate",
                                value = sample.beatsPerMinute.toDouble(),
                                unit = "bpm",
                                recordedAt = sample.time.toEpochMilli(),
                                confidence = 1.0,
                                dataQualityFlag = "nominal",
                                syncStatus = SyncStatus.PENDING
                            )
                        )
                    }
                }
                android.util.Log.i(TAG, "📊 [RECORDS_FOUND] HeartRate: ${hrResponse.records.size} records found ($sampleCount samples)")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ [HEALTH_CONNECT_ERROR] HeartRate read error: ${e.message}", e)
            }
        } else {
            android.util.Log.w(TAG, "⚠️ [PERMISSIONS] HeartRate permission ($hrPermission) not granted. Skipping.")
        }

        // 2. Read Steps records
        val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
        if (grantedPermissions.contains(stepsPermission)) {
            try {
                val stepsResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = StepsRecord::class,
                        timeRangeFilter = timeFilter
                    )
                )
                for (record in stepsResponse.records) {
                    results.add(
                        OfflineMeasurementEntity(
                            id = UUID.randomUUID().toString(),
                            sourceRecordId = "hc_steps_${record.metadata.id}",
                            metricType = "steps",
                            value = record.count.toDouble(),
                            unit = "count",
                            recordedAt = record.endTime.toEpochMilli(),
                            confidence = 1.0,
                            dataQualityFlag = "nominal",
                            syncStatus = SyncStatus.PENDING
                        )
                    )
                }
                android.util.Log.i(TAG, "📊 [RECORDS_FOUND] Steps: ${stepsResponse.records.size} records found")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ [HEALTH_CONNECT_ERROR] Steps read error: ${e.message}", e)
            }
        } else {
            android.util.Log.w(TAG, "⚠️ [PERMISSIONS] Steps permission ($stepsPermission) not granted. Skipping.")
        }

        // 3. Read Total Calories Burned records
        val caloriesPermission = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
        if (grantedPermissions.contains(caloriesPermission)) {
            try {
                val caloriesResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = TotalCaloriesBurnedRecord::class,
                        timeRangeFilter = timeFilter
                    )
                )
                for (record in caloriesResponse.records) {
                    results.add(
                        OfflineMeasurementEntity(
                            id = UUID.randomUUID().toString(),
                            sourceRecordId = "hc_cal_${record.metadata.id}",
                            metricType = "active_calories",
                            value = record.energy.inKilocalories,
                            unit = "kcal",
                            recordedAt = record.endTime.toEpochMilli(),
                            confidence = 1.0,
                            dataQualityFlag = "nominal",
                            syncStatus = SyncStatus.PENDING
                        )
                    )
                }
                android.util.Log.i(TAG, "📊 [RECORDS_FOUND] Calories: ${caloriesResponse.records.size} records found")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ [HEALTH_CONNECT_ERROR] Calories read error: ${e.message}", e)
            }
        } else {
            android.util.Log.w(TAG, "⚠️ [PERMISSIONS] Calories permission ($caloriesPermission) not granted. Skipping.")
        }

        // 4. Read Sleep records
        val sleepPermission = HealthPermission.getReadPermission(SleepSessionRecord::class)
        if (grantedPermissions.contains(sleepPermission)) {
            try {
                val sleepResponse = healthConnectClient.readRecords(
                    ReadRecordsRequest(
                        recordType = SleepSessionRecord::class,
                        timeRangeFilter = timeFilter
                    )
                )
                for (record in sleepResponse.records) {
                    val durationMinutes = ChronoUnit.MINUTES.between(record.startTime, record.endTime).toDouble()
                    results.add(
                        OfflineMeasurementEntity(
                            id = UUID.randomUUID().toString(),
                            sourceRecordId = "hc_sleep_${record.metadata.id}",
                            metricType = "sleep_stage",
                            value = durationMinutes,
                            unit = "minutes",
                            recordedAt = record.endTime.toEpochMilli(),
                            confidence = 1.0,
                            dataQualityFlag = "nominal",
                            syncStatus = SyncStatus.PENDING
                        )
                    )
                }
                android.util.Log.i(TAG, "📊 [RECORDS_FOUND] Sleep: ${sleepResponse.records.size} records found")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ [HEALTH_CONNECT_ERROR] Sleep read error: ${e.message}", e)
            }
        } else {
            android.util.Log.w(TAG, "⚠️ [PERMISSIONS] Sleep permission ($sleepPermission) not granted. Skipping.")
        }

        android.util.Log.i(TAG, "📊 [HEALTH_CONNECT_SUMMARY] Total parsed measurements: ${results.size}")
        return results
    }
}
