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

    suspend fun readRecentMeasurements(hoursBack: Long = 6): List<OfflineMeasurementEntity> {
        val startTime = Instant.now().minus(hoursBack, ChronoUnit.HOURS)
        val endTime = Instant.now()
        val timeFilter = TimeRangeFilter.between(startTime, endTime)

        val results = mutableListOf<OfflineMeasurementEntity>()

        // 1. Read Heart Rate records
        try {
            val hrResponse = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = timeFilter
                )
            )
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
            // Log read error; continue reading other records
        }

        // 2. Read Steps records
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
        } catch (e: Exception) {
            // Log read error
        }

        return results
    }
}
