package com.fitagent.data

import android.util.Log
import com.fitagent.data.health.HealthAvailability
import com.fitagent.data.health.HealthConnectSource
import com.fitagent.data.local.AppDatabase
import com.fitagent.data.local.DailyDeviceEntity
import com.fitagent.data.local.WorkoutEntity
import com.fitagent.data.prefs.AuthStore
import com.fitagent.data.prefs.ProfileStore
import com.fitagent.domain.DayActivity
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.buildDays
import com.fitagent.domain.workoutKcal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class HealthStatus(
    val availability: HealthAvailability = HealthAvailability.NOT_INSTALLED,
    val granted: Set<String> = emptySet(),
    val required: Set<String> = emptySet(),
) {
    val connected: Boolean get() = availability == HealthAvailability.AVAILABLE && granted.isNotEmpty()
    val missing: Int get() = (required - granted).size
}

/** Local source of truth for activity: Room, fed by Health Connect and the in-app workout tracker. */
class FitnessRepository(
    private val db: AppDatabase,
    private val health: HealthConnectSource,
    private val profiles: ProfileStore,
    private val auth: AuthStore,
) {
    private val dao = db.dao()
    private val refreshLock = Mutex()
    private val _status = MutableStateFlow(HealthStatus(required = health.allPermissions))
    val status: StateFlow<HealthStatus> = _status.asStateFlow()

    val deviceDays: Flow<Map<LocalDate, com.fitagent.domain.DeviceDay>> =
        dao.observeDays().map { rows -> rows.associate { LocalDate.ofEpochDay(it.epochDay) to it.toDomain() } }
    val workouts: Flow<List<Workout>> = dao.observeWorkouts().map { rows -> rows.map { it.toDomain() } }

    suspend fun refreshStatus(): HealthStatus {
        val s = HealthStatus(health.availability(), health.grantedPermissions(), health.allPermissions)
        _status.value = s
        return s
    }

    /** Pulls the last [daysBack] days from Health Connect into Room. Safe to call often. */
    suspend fun refresh(daysBack: Long = 30): Result<Unit> = refreshLock.withLock {
        runCatching {
            val s = refreshStatus()
            if (!s.connected) return@runCatching
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val from = today.minusDays(daysBack - 1)
            val days = health.readDays(from, today, s.granted, zone)
            dao.upsertDays(days.values.map(DailyDeviceEntity::from))
            val since = from.atStartOfDay(zone).toInstant()
            val imported = health.readWorkouts(since, s.granted, profiles.profile.value.weightKg)
            dao.replaceImported(since.toEpochMilli(), imported)
        }.onFailure { Log.w(TAG, "refresh failed", it) }
    }

    /** Saves a workout tracked or logged in the app and mirrors it to Health Connect. */
    suspend fun saveWorkout(type: WorkoutType, startMs: Long, endMs: Long, activeSeconds: Long): Workout {
        val entity = WorkoutEntity(
            uid = "app:${UUID.randomUUID()}",
            type = type.name,
            title = null,
            startMs = startMs,
            endMs = endMs,
            activeSeconds = activeSeconds,
            kcal = workoutKcal(type, profiles.profile.value.weightKg, activeSeconds),
            source = WorkoutSource.APP.name,
            healthConnectId = null,
        )
        val id = dao.insertWorkout(entity)
        health.writeWorkout(entity, ZoneId.systemDefault())?.let { dao.setHealthConnectId(id, it) }
        return entity.copy(id = id).toDomain()
    }

    suspend fun deleteWorkout(id: Long) {
        val w = dao.getWorkout(id) ?: return
        if (w.source == WorkoutSource.APP.name) w.healthConnectId?.let { health.deleteWorkout(it) }
        dao.deleteWorkout(id)
        if (auth.account.value != null) auth.queueDeletion(w.uid)
    }

    suspend fun snapshot(): Pair<List<DayActivity>, List<Workout>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val device = dao.getDays().associate { LocalDate.ofEpochDay(it.epochDay) to it.toDomain() }
        val workouts = dao.getWorkouts().map { it.toDomain() }
        val from = listOfNotNull(device.keys.minOrNull(), today.minusDays(29)).min()
        return buildDays(from, today, device, workouts, profiles.profile.value.weightKg, zone) to workouts
    }

    companion object {
        private const val TAG = "FitnessRepository"
    }
}
