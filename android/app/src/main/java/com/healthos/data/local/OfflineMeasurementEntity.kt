package com.healthos.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class SyncStatus {
    PENDING,
    IN_FLIGHT,
    SYNCED,
    FAILED
}

@Entity(
    tableName = "offline_measurements",
    indices = [
        Index(value = ["source_record_id"], unique = true),
        Index(value = ["sync_status", "recorded_at"])
    ]
)
data class OfflineMeasurementEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "source_record_id")
    val sourceRecordId: String,

    @ColumnInfo(name = "metric_type")
    val metricType: String,

    @ColumnInfo(name = "value")
    val value: Double,

    @ColumnInfo(name = "unit")
    val unit: String,

    @ColumnInfo(name = "recorded_at")
    val recordedAt: Long, // Epoch millis in UTC

    @ColumnInfo(name = "confidence")
    val confidence: Double = 1.0,

    @ColumnInfo(name = "data_quality_flag")
    val dataQualityFlag: String = "nominal",

    @ColumnInfo(name = "sync_status")
    val syncStatus: SyncStatus = SyncStatus.PENDING,

    @ColumnInfo(name = "sync_attempts")
    val syncAttempts: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
