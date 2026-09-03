package com.healthos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface MeasurementDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(measurements: List<OfflineMeasurementEntity>): List<Long>

    @Query("SELECT * FROM offline_measurements WHERE sync_status = 'PENDING' ORDER BY recorded_at ASC LIMIT :limit")
    suspend fun getPendingBatch(limit: Int = 200): List<OfflineMeasurementEntity>

    @Query("UPDATE offline_measurements SET sync_status = :status WHERE id IN (:ids)")
    suspend fun updateSyncStatus(ids: List<String>, status: SyncStatus)

    @Query("UPDATE offline_measurements SET sync_status = 'FAILED', sync_attempts = sync_attempts + 1 WHERE id IN (:ids)")
    suspend fun incrementFailedAttempts(ids: List<String>)

    @Query("DELETE FROM offline_measurements WHERE sync_status = 'SYNCED' AND recorded_at < :olderThanTimestamp")
    suspend fun pruneSynced(olderThanTimestamp: Long): Int

    @Query("SELECT COUNT(*) FROM offline_measurements WHERE sync_status = 'PENDING'")
    suspend fun getPendingCount(): Int
}
