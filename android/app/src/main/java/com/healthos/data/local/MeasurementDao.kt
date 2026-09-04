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

    @Query("SELECT COUNT(*) FROM offline_measurements WHERE sync_status = 'PENDING'")
    fun getPendingCountFlow(): kotlinx.coroutines.flow.Flow<Int>


    /**
     * Recovers orphaned IN_FLIGHT records that were stuck due to worker/app crash.
     * Records older than [timeoutThresholdMs] (epoch millis) that are still IN_FLIGHT
     * are reset to PENDING so they are retried on the next sync cycle.
     */
    @Query(
        """UPDATE offline_measurements 
           SET sync_status = 'PENDING' 
           WHERE sync_status = 'IN_FLIGHT' 
           AND recorded_at < :timeoutThresholdMs"""
    )
    suspend fun resetOrphanedInFlightRecords(timeoutThresholdMs: Long): Int

    /**
     * Fetches a syncable batch of both PENDING records and FAILED records
     * whose exponential backoff has elapsed (retry delay = 2^sync_attempts seconds * 1000ms).
     * Records with >= 5 attempts are excluded (permanent failure).
     */
    @Query(
        """SELECT * FROM offline_measurements 
           WHERE (sync_status = 'PENDING')
              OR (sync_status = 'FAILED' 
                  AND sync_attempts < 5 
                  AND recorded_at < :nowMs - (1000 * (1 << sync_attempts)))
           ORDER BY recorded_at ASC 
           LIMIT :limit"""
    )
    suspend fun getSyncableBatch(limit: Int = 200, nowMs: Long = System.currentTimeMillis()): List<OfflineMeasurementEntity>
}
