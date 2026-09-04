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

    suspend fun hasAllPermissions(): Boolean {
        val granted = healthConnectClient.permissionController.getGrantedPermissions()
        return granted.containsAll(requiredPermissions)
    }

    suspend fun readRecentMeasurements(hoursBack: Long = 24): List<OfflineMeasurementEntity> {
        val startTime = Instant.now().minus(hoursBack, ChronoUnit.HOURS)
        val endTime = Instant.now()
        val timeFilter = TimeRangeFilter.between(startTime, endTime)

        val results = mutableListOf<OfflineMeasurementEntity>()
        android.util.Log.i(TAG, "Querying Health Connect from $startTime to $endTime (hoursBack=$hoursBack)")

        // 1. Read Heart Rate records
        try {
            val hrResponse = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = timeFilter
                )
            )
            android.util.Log.i(TAG, "Health Connect HeartRate: found ${hrResponse.records.size} records")
            for (record in hrResponse.records) {
                for (sample in record.samples) {
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
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Health Connect HeartRate read error: ${e.message}")
        }

        // 2. Read Steps records
        try {
            val stepsResponse = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = StepsRecord::class,
                    timeRangeFilter = timeFilter
                )
            )
            android.util.Log.i(TAG, "Health Connect Steps: found ${stepsResponse.records.size} records")
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
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Health Connect Steps read error: ${e.message}")
        }

        // 3. Read Total Calories Burned records
        try {
            val caloriesResponse = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = TotalCaloriesBurnedRecord::class,
                    timeRangeFilter = timeFilter
                )
            )
            android.util.Log.i(TAG, "Health Connect Calories: found ${caloriesResponse.records.size} records")
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
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Health Connect Calories read error: ${e.message}")
        }

        // 4. Read Sleep records
        try {
            val sleepResponse = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = timeFilter
                )
            )
            android.util.Log.i(TAG, "Health Connect Sleep: found ${sleepResponse.records.size} records")
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
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Health Connect Sleep read error: ${e.message}")
        }

        android.util.Log.i(TAG, "Total fresh Health Connect measurements parsed: ${results.size}")
        return results
    }
}
