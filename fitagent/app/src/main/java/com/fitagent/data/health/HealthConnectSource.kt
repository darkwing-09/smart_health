package com.fitagent.data.health

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitagent.data.local.WorkoutEntity
import com.fitagent.domain.DeviceDay
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.workoutKcal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.reflect.KClass

enum class HealthAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

/**
 * Reads de-duplicated daily aggregates from Health Connect. Aggregates (not raw records) are used so
 * that overlapping sources — e.g. phone and watch both counting steps — are merged by the platform
 * instead of being double-counted.
 */
class HealthConnectSource(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )
    val writeExercise: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    val allPermissions: Set<String> = readPermissions + writeExercise

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.NOT_INSTALLED
    }

    /** On Android 14+ Health Connect is part of the OS; earlier versions install it from Play. */
    val isBuiltIn: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    suspend fun grantedPermissions(): Set<String> =
        if (availability() != HealthAvailability.AVAILABLE) emptySet()
        else runCatching { client.permissionController.getGrantedPermissions() }
            .onFailure { Log.w(TAG, "permission query failed", it) }
            .getOrDefault(emptySet())

    private fun granted(granted: Set<String>, type: KClass<out Record>) =
        HealthPermission.getReadPermission(type) in granted

    suspend fun readDays(from: LocalDate, to: LocalDate, granted: Set<String>, zone: ZoneId): Map<LocalDate, DeviceDay> {
        val metrics = buildSet<AggregateMetric<*>> {
            if (granted(granted, StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (granted(granted, DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (granted(granted, ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (granted(granted, RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_AVG)
            if (granted(granted, HeartRateRecord::class)) add(HeartRateRecord.BPM_AVG)
        }
        val days = mutableMapOf<LocalDate, DeviceDay>()
        var date = from
        while (!date.isAfter(to)) { days[date] = DeviceDay(date); date = date.plusDays(1) }

        if (metrics.isNotEmpty()) {
            val buckets = client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), to.plusDays(1).atStartOfDay()),
                    timeRangeSlicer = Period.ofDays(1),
                )
            )
            for (bucket in buckets) {
                val d = bucket.startTime.toLocalDate()
                val r = bucket.result
                days[d] = DeviceDay(
                    date = d,
                    steps = r[StepsRecord.COUNT_TOTAL] ?: 0L,
                    distanceMeters = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0,
                    activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                    restingHr = r[RestingHeartRateRecord.BPM_AVG]?.toInt(),
                    avgHr = r[HeartRateRecord.BPM_AVG]?.toInt(),
                )
            }
        }

        if (granted(granted, SleepSessionRecord::class)) {
            // A night belongs to the date it ends on. Read from 18:00 the evening before the window.
            val start = from.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
            val sleep = readAll(SleepSessionRecord::class, TimeRangeFilter.after(start))
            sleep.groupBy { it.endTime.atZone(zone).toLocalDate() }.forEach { (d, sessions) ->
                val minutes = sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt()
                days[d]?.let { days[d] = it.copy(sleepMinutes = minutes) }
            }
        }
        return days
    }

    /** Exercise sessions recorded by *other* apps (ours are already in the local database). */
    suspend fun readWorkouts(since: Instant, granted: Set<String>, weightKg: Double): List<WorkoutEntity> {
        if (!granted(granted, ExerciseSessionRecord::class)) return emptyList()
        return readAll(ExerciseSessionRecord::class, TimeRangeFilter.after(since))
            .filter { it.metadata.dataOrigin.packageName != context.packageName }
            .map { r ->
                val type = fromHealthConnect(r.exerciseType)
                val seconds = Duration.between(r.startTime, r.endTime).seconds
                WorkoutEntity(
                    uid = "hc:${r.metadata.id}",
                    type = type.name,
                    title = r.title,
                    startMs = r.startTime.toEpochMilli(),
                    endMs = r.endTime.toEpochMilli(),
                    activeSeconds = seconds,
                    kcal = workoutKcal(type, weightKg, seconds),
                    source = WorkoutSource.HEALTH_CONNECT.name,
                    healthConnectId = r.metadata.id,
                )
            }
    }

    /** Writes an in-app workout to Health Connect so Google Fit, Samsung Health etc. see it. */
    suspend fun writeWorkout(w: WorkoutEntity, zone: ZoneId): String? {
        if (writeExercise !in grantedPermissions()) return null
        val start = Instant.ofEpochMilli(w.startMs)
        val end = Instant.ofEpochMilli(w.endMs)
        val record = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            // Recorded live by the in-app workout player on this phone.
            metadata = Metadata.activelyRecorded(Device(type = Device.TYPE_PHONE)),
            exerciseType = toHealthConnect(WorkoutType.fromName(w.type)),
            title = w.title ?: WorkoutType.fromName(w.type).label,
        )
        return runCatching { client.insertRecords(listOf(record)).recordIdsList.firstOrNull() }
            .onFailure { Log.w(TAG, "write workout failed", it) }
            .getOrNull()
    }

    suspend fun deleteWorkout(hcId: String) {
        if (writeExercise !in grantedPermissions()) return
        runCatching { client.deleteRecords(ExerciseSessionRecord::class, listOf(hcId), emptyList()) }
            .onFailure { Log.w(TAG, "delete workout failed", it) }
    }

    private suspend fun <T : Record> readAll(type: KClass<T>, filter: TimeRangeFilter): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, filter, pageToken = token))
            out += response.records
            token = response.pageToken
        } while (token != null)
        return out
    }

    companion object {
        private const val TAG = "HealthConnect"
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        fun toHealthConnect(type: WorkoutType): Int = when (type) {
            WorkoutType.WALK -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
            WorkoutType.RUN -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
            WorkoutType.CYCLE -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
            WorkoutType.STRENGTH -> ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
            WorkoutType.HIIT -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
            WorkoutType.YOGA -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
            WorkoutType.SWIM -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
            WorkoutType.DANCE -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
            WorkoutType.SPORTS, WorkoutType.OTHER -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        }

        fun fromHealthConnect(type: Int): WorkoutType = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING, ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> WorkoutType.WALK
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> WorkoutType.RUN
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> WorkoutType.CYCLE
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> WorkoutType.STRENGTH
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
            ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> WorkoutType.HIIT
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> WorkoutType.YOGA
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> WorkoutType.SWIM
            ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> WorkoutType.DANCE
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
            ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON, ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
            ExerciseSessionRecord.EXERCISE_TYPE_CRICKET, ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS, ExerciseSessionRecord.EXERCISE_TYPE_SQUASH -> WorkoutType.SPORTS
            else -> WorkoutType.OTHER
        }
    }
}
