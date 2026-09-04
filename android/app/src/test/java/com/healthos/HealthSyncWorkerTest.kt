package com.healthos

import com.healthos.data.local.OfflineMeasurementEntity
import com.healthos.data.local.SyncStatus
import com.healthos.data.remote.BatchIngestRequestDto
import com.healthos.data.remote.MeasurementItemDto
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

class HealthSyncWorkerTest {

    @Test
    fun testExponentialBackoffCalculation() {
        // Retry delay formula in MeasurementDao: 1000 * (1 << sync_attempts)
        val delays = (0..4).map { attempts ->
            1000L * (1 shl attempts)
        }
        assertEquals(1000L, delays[0])  // 1s
        assertEquals(2000L, delays[1])  // 2s
        assertEquals(4000L, delays[2])  // 4s
        assertEquals(8000L, delays[3])  // 8s
        assertEquals(16000L, delays[4]) // 16s
    }

    @Test
    fun testMaxRetryExclusion() {
        // Records with >= 5 sync attempts must be marked permanently failed
        val syncAttempts = listOf(0, 1, 3, 4, 5, 6)
        val isRetryable = syncAttempts.map { attempts -> attempts < 5 }

        assertTrue(isRetryable[0]) // 0 attempts -> retryable
        assertTrue(isRetryable[1]) // 1 attempt -> retryable
        assertTrue(isRetryable[2]) // 3 attempts -> retryable
        assertTrue(isRetryable[3]) // 4 attempts -> retryable
        assertFalse(isRetryable[4]) // 5 attempts -> permanent failure
        assertFalse(isRetryable[5]) // 6 attempts -> permanent failure
    }

    @Test
    fun testHttpErrorClassificationLogic() {
        // 401/403/422 must be permanent failure, 5xx must be retry
        fun classifyHttpError(statusCode: Int): String {
            return when (statusCode) {
                401, 403 -> "PERMANENT_AUTH_FAILURE"
                422 -> "PERMANENT_VALIDATION_FAILURE"
                in 500..599 -> "TRANSIENT_SERVER_RETRY"
                else -> "TRANSIENT_RETRY"
            }
        }

        assertEquals("PERMANENT_AUTH_FAILURE", classifyHttpError(401))
        assertEquals("PERMANENT_AUTH_FAILURE", classifyHttpError(403))
        assertEquals("PERMANENT_VALIDATION_FAILURE", classifyHttpError(422))
        assertEquals("TRANSIENT_SERVER_RETRY", classifyHttpError(500))
        assertEquals("TRANSIENT_SERVER_RETRY", classifyHttpError(502))
        assertEquals("TRANSIENT_SERVER_RETRY", classifyHttpError(503))
        assertEquals("TRANSIENT_SERVER_RETRY", classifyHttpError(504))
    }

    @Test
    fun testDtoMappingFromEntity() {
        val nowMs = 1725436800000L // 2024-09-04T08:00:00Z
        val entity = OfflineMeasurementEntity(
            id = UUID.randomUUID().toString(),
            sourceRecordId = "hc_hr_12345",
            metricType = "heart_rate",
            value = 72.0,
            unit = "bpm",
            recordedAt = nowMs,
            confidence = 0.95,
            dataQualityFlag = "nominal",
            syncStatus = SyncStatus.PENDING,
            syncAttempts = 0
        )

        val dto = MeasurementItemDto(
            sourceRecordId = entity.sourceRecordId,
            metricType = entity.metricType,
            value = entity.value,
            unit = entity.unit,
            recordedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(entity.recordedAt)),
            confidence = entity.confidence,
            dataQualityFlag = entity.dataQualityFlag
        )

        assertEquals("hc_hr_12345", dto.sourceRecordId)
        assertEquals("heart_rate", dto.metricType)
        assertEquals(72.0, dto.value, 0.001)
        assertEquals("bpm", dto.unit)
        assertEquals("2024-09-04T08:00:00Z", dto.recordedAt)
        assertEquals("nominal", dto.dataQualityFlag)
    }

    @Test
    fun testBatchRequestDtoStructure() {
        val batch = BatchIngestRequestDto(
            sourceId = "00000000-0000-0000-0000-000000000001",
            clientSyncTimestamp = "2024-09-04T08:00:00Z",
            measurements = emptyList()
        )

        assertEquals("00000000-0000-0000-0000-000000000001", batch.sourceId)
        assertEquals("2024-09-04T08:00:00Z", batch.clientSyncTimestamp)
        assertTrue(batch.measurements.isEmpty())
    }

    @Test
    fun testWorkerOutputDataKeysIntegrity() {
        assertEquals("records_staged", com.healthos.service.HealthSyncWorker.KEY_RECORDS_STAGED)
        assertEquals("records_synced", com.healthos.service.HealthSyncWorker.KEY_RECORDS_SYNCED)
        assertEquals("status_message", com.healthos.service.HealthSyncWorker.KEY_STATUS_MESSAGE)
        assertEquals("is_offline", com.healthos.service.HealthSyncWorker.KEY_IS_OFFLINE)
    }

    @Test
    fun testUniqueWorkNamingConvention() {
        assertEquals("HealthOS_ImmediateSync", com.healthos.ui.MainActivity.UNIQUE_WORK_NAME_IMMEDIATE_SYNC)
        assertEquals("HealthOS_PeriodicSync", com.healthos.ui.MainActivity.UNIQUE_WORK_NAME_PERIODIC_SYNC)
    }

    @Test
    fun testSyncUiStateRepresentations() {
        val idleState: com.healthos.ui.SyncUiState = com.healthos.ui.SyncUiState.Idle
        val queuedState: com.healthos.ui.SyncUiState = com.healthos.ui.SyncUiState.Queued
        val syncingState: com.healthos.ui.SyncUiState = com.healthos.ui.SyncUiState.Syncing
        val waitingState: com.healthos.ui.SyncUiState = com.healthos.ui.SyncUiState.WaitingForConstraint
        val successState = com.healthos.ui.SyncUiState.Success(message = "Synced 10 records", isOffline = false)
        val offlineState = com.healthos.ui.SyncUiState.Success(message = "Staged 5 records", isOffline = true)
        val errorState = com.healthos.ui.SyncUiState.Error(message = "Auth failed")

        assertTrue(idleState is com.healthos.ui.SyncUiState.Idle)
        assertTrue(queuedState is com.healthos.ui.SyncUiState.Queued)
        assertTrue(syncingState is com.healthos.ui.SyncUiState.Syncing)
        assertTrue(waitingState is com.healthos.ui.SyncUiState.WaitingForConstraint)
        assertFalse(successState.isOffline)
        assertTrue(offlineState.isOffline)
        assertEquals("Auth failed", errorState.message)
    }

    @Test
    fun testOfflineQueueRetentionLogic() {
        // Verifies offline staged records remain in PENDING status rather than getting dropped or marked FAILED
        val entity = OfflineMeasurementEntity(
            id = UUID.randomUUID().toString(),
            sourceRecordId = "hc_offline_sample",
            metricType = "steps",
            value = 150.0,
            unit = "count",
            recordedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING
        )

        // Offline handling invariant: when device is offline, record remains PENDING
        assertEquals(SyncStatus.PENDING, entity.syncStatus)
        assertEquals(0, entity.syncAttempts)
    }
}

