package com.fitagent.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.fitagent.domain.DeviceDay
import com.fitagent.domain.NightSignals
import com.fitagent.domain.SleepStages
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Watch/phone readings for one local calendar day (see [DeviceDay]). Kept indefinitely as history. */
@Entity(tableName = "daily_device")
data class DailyDeviceEntity(
    @PrimaryKey @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "steps") val steps: Long,
    @ColumnInfo(name = "distance_m") val distanceMeters: Double,
    @ColumnInfo(name = "active_kcal") val activeKcal: Double?,
    @ColumnInfo(name = "resting_hr") val restingHr: Int?,
    @ColumnInfo(name = "avg_hr") val avgHr: Int?,
    @ColumnInfo(name = "sleep_min") val sleepMinutes: Int?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "hr_min") val hrMin: Int? = null,
    @ColumnInfo(name = "hr_max") val hrMax: Int? = null,
    @ColumnInfo(name = "floors") val floors: Int? = null,
    @ColumnInfo(name = "vo2max") val vo2max: Double? = null,
    @ColumnInfo(name = "sleep_deep_min") val sleepDeep: Int? = null,
    @ColumnInfo(name = "sleep_rem_min") val sleepRem: Int? = null,
    @ColumnInfo(name = "sleep_light_min") val sleepLight: Int? = null,
    @ColumnInfo(name = "sleep_awake_min") val sleepAwake: Int? = null,
    @ColumnInfo(name = "bed_ms") val bedMs: Long? = null,
    @ColumnInfo(name = "wake_ms") val wakeMs: Long? = null,
    @ColumnInfo(name = "hrv_ms") val hrvMs: Double? = null,
    @ColumnInfo(name = "spo2_avg") val spo2Avg: Double? = null,
    @ColumnInfo(name = "spo2_min") val spo2Min: Double? = null,
    @ColumnInfo(name = "resp_rate") val respiratoryRate: Double? = null,
    @ColumnInfo(name = "skin_temp_delta") val skinTempDelta: Double? = null,
    @ColumnInfo(name = "steps_source") val stepsSource: String? = null,
    @ColumnInfo(name = "sleep_source") val sleepSource: String? = null,
) {
    fun toDomain() = DeviceDay(
        date = LocalDate.ofEpochDay(epochDay),
        steps = steps,
        distanceMeters = distanceMeters,
        activeKcal = activeKcal,
        restingHr = restingHr,
        avgHr = avgHr,
        sleepMinutes = sleepMinutes,
        hrMin = hrMin,
        hrMax = hrMax,
        floors = floors,
        vo2max = vo2max,
        sleepStages = if (listOf(sleepDeep, sleepRem, sleepLight, sleepAwake).all { it == null }) null
        else SleepStages(sleepDeep ?: 0, sleepRem ?: 0, sleepLight ?: 0, sleepAwake ?: 0),
        bedMs = bedMs,
        wakeMs = wakeMs,
        night = NightSignals(hrvMs, spo2Avg, spo2Min, respiratoryRate, skinTempDelta),
        stepsSource = stepsSource,
        sleepSource = sleepSource,
    )

    companion object {
        fun from(d: DeviceDay) = DailyDeviceEntity(
            epochDay = d.date.toEpochDay(),
            steps = d.steps,
            distanceMeters = d.distanceMeters,
            activeKcal = d.activeKcal,
            restingHr = d.restingHr,
            avgHr = d.avgHr,
            sleepMinutes = d.sleepMinutes,
            hrMin = d.hrMin,
            hrMax = d.hrMax,
            floors = d.floors,
            vo2max = d.vo2max,
            sleepDeep = d.sleepStages?.deep,
            sleepRem = d.sleepStages?.rem,
            sleepLight = d.sleepStages?.light,
            sleepAwake = d.sleepStages?.awake,
            bedMs = d.bedMs,
            wakeMs = d.wakeMs,
            hrvMs = d.night.hrvMs,
            spo2Avg = d.night.spo2Avg,
            spo2Min = d.night.spo2Min,
            respiratoryRate = d.night.respiratoryRate,
            skinTempDelta = d.night.skinTempDelta,
            stepsSource = d.stepsSource,
            sleepSource = d.sleepSource,
        )
    }
}

/** Steps and active kcal per local hour (for the hourly bar chart). */
@Entity(tableName = "hourly", primaryKeys = ["epoch_day", "hour"])
data class HourlyEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "hour") val hour: Int,
    @ColumnInfo(name = "steps") val steps: Long,
    @ColumnInfo(name = "active_kcal") val activeKcal: Double?,
)

/** Heart rate summarised per 10-minute window. Intraday detail stays on the phone. */
@Entity(tableName = "hr_buckets")
data class HrBucketEntity(
    @PrimaryKey @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "bpm_min") val min: Int,
    @ColumnInfo(name = "bpm_avg") val avg: Int,
    @ColumnInfo(name = "bpm_max") val max: Int,
)

/** One stage segment of a night's sleep (hypnogram). [stage] is a [SleepStage] name. */
@Entity(tableName = "sleep_segments", indices = [Index(value = ["night_epoch_day"])])
data class SleepSegmentEntity(
    @PrimaryKey @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "stage") val stage: String,
    @ColumnInfo(name = "night_epoch_day") val nightEpochDay: Long,
)

/** Steps counted by the phone's own sensor, per local hour (fallback when no watch app shares steps). */
@Entity(tableName = "phone_steps", primaryKeys = ["epoch_day", "hour"])
data class PhoneStepsEntity(
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "hour") val hour: Int,
    @ColumnInfo(name = "steps") val steps: Long,
)

/** An app (or the phone sensor) that has supplied health data, for the Devices & data health screen. */
@Entity(tableName = "sources")
data class SourceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "label") val label: String,
    @ColumnInfo(name = "first_seen_ms") val firstSeenMs: Long,
    @ColumnInfo(name = "last_data_ms") val lastDataMs: Long,
    @ColumnInfo(name = "records_received") val recordsReceived: Long,
    @ColumnInfo(name = "data_types") val dataTypes: String,
)

/** One run of a sync job (watch→phone or phone→cloud). Powers the sync timeline; trimmed to the latest 300. */
@Entity(tableName = "sync_log", indices = [Index(value = ["time_ms"])])
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "ok") val ok: Boolean,
    @ColumnInfo(name = "records") val records: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "message") val message: String?,
)


/**
 * Workouts tracked in-app (APP) or imported from other apps via Health Connect (HEALTH_CONNECT).
 * [uid] is stable across devices and is the id used for cloud sync.
 * [healthConnectId] is the Health Connect record id (for imports, and for app workouts written there).
 */
@Entity(
    tableName = "workouts",
    indices = [Index(value = ["uid"], unique = true), Index(value = ["start_ms"])],
)
data class WorkoutEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "active_seconds") val activeSeconds: Long,
    @ColumnInfo(name = "kcal") val kcal: Double,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "hc_id") val healthConnectId: String?,
    @ColumnInfo(name = "avg_hr") val avgHr: Int? = null,
    @ColumnInfo(name = "max_hr") val maxHr: Int? = null,
    /** Comma-separated seconds in zones 1–5. */
    @ColumnInfo(name = "zone_seconds") val zoneSeconds: String? = null,
) {
    fun toDomain() = Workout(
        id, uid, WorkoutType.fromName(type), title, startMs, endMs, activeSeconds, kcal, WorkoutSource.valueOf(source),
        avgHr, maxHr, zoneSeconds?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 5 },
    )
}

@Dao
interface FitnessDao {
    @Query("SELECT * FROM daily_device ORDER BY epoch_day ASC")
    fun observeDays(): Flow<List<DailyDeviceEntity>>

    @Query("SELECT * FROM daily_device ORDER BY epoch_day ASC")
    suspend fun getDays(): List<DailyDeviceEntity>

    @Upsert
    suspend fun upsertDays(days: List<DailyDeviceEntity>)

    // Imported workouts the user removed stay hidden even though every refresh re-imports them.
    @Query("SELECT * FROM workouts WHERE uid NOT IN (SELECT uid FROM hidden_imports) ORDER BY start_ms DESC")
    fun observeWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts WHERE uid NOT IN (SELECT uid FROM hidden_imports) ORDER BY start_ms DESC")
    suspend fun getWorkouts(): List<WorkoutEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun hideImport(row: HiddenImportEntity)

    @Query("SELECT * FROM workouts WHERE id = :id")
    suspend fun getWorkout(id: Long): WorkoutEntity?

    @Insert
    suspend fun insertWorkout(workout: WorkoutEntity): Long

    @Query("UPDATE workouts SET hc_id = :hcId WHERE id = :id")
    suspend fun setHealthConnectId(id: Long, hcId: String)

    @Query("DELETE FROM workouts WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

    @Query("DELETE FROM workouts WHERE source = 'HEALTH_CONNECT' AND start_ms >= :sinceMs")
    suspend fun deleteImportedSince(sinceMs: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertImported(workouts: List<WorkoutEntity>)

    /** Imports mirror Health Connect, so edits and deletions made in other apps propagate here. */
    @Transaction
    suspend fun replaceImported(sinceMs: Long, workouts: List<WorkoutEntity>) {
        deleteImportedSince(sinceMs)
        insertImported(workouts)
    }
}

@Dao
interface HealthDao {
    @Upsert suspend fun upsertHourly(rows: List<HourlyEntity>)

    @Query("SELECT * FROM hourly WHERE epoch_day = :epochDay ORDER BY hour")
    fun observeHourly(epochDay: Long): Flow<List<HourlyEntity>>

    @Upsert suspend fun upsertHr(rows: List<HrBucketEntity>)

    @Query("SELECT * FROM hr_buckets WHERE start_ms >= :fromMs AND start_ms < :toMs ORDER BY start_ms")
    fun observeHr(fromMs: Long, toMs: Long): Flow<List<HrBucketEntity>>

    @Query("DELETE FROM hr_buckets WHERE start_ms < :beforeMs")
    suspend fun pruneHr(beforeMs: Long)

    @Query("DELETE FROM sleep_segments WHERE night_epoch_day = :nightEpochDay")
    suspend fun deleteSleepNight(nightEpochDay: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleep(rows: List<SleepSegmentEntity>)

    @Transaction
    suspend fun replaceSleepNight(nightEpochDay: Long, rows: List<SleepSegmentEntity>) {
        deleteSleepNight(nightEpochDay)
        insertSleep(rows)
    }

    @Query("SELECT * FROM sleep_segments WHERE night_epoch_day = :nightEpochDay ORDER BY start_ms")
    fun observeSleep(nightEpochDay: Long): Flow<List<SleepSegmentEntity>>

    @Query("SELECT * FROM phone_steps WHERE epoch_day = :epochDay AND hour = :hour")
    suspend fun phoneSteps(epochDay: Long, hour: Int): PhoneStepsEntity?

    @Upsert suspend fun upsertPhoneSteps(row: PhoneStepsEntity)

    @Query("SELECT epoch_day AS epochDay, SUM(steps) AS steps FROM phone_steps GROUP BY epoch_day")
    fun observePhoneDaily(): Flow<List<DaySteps>>

    @Query("SELECT epoch_day AS epochDay, SUM(steps) AS steps FROM phone_steps GROUP BY epoch_day")
    suspend fun phoneDaily(): List<DaySteps>

    @Query("SELECT * FROM phone_steps WHERE epoch_day = :epochDay ORDER BY hour")
    fun observePhoneHourly(epochDay: Long): Flow<List<PhoneStepsEntity>>

    @Query("SELECT * FROM sources ORDER BY last_data_ms DESC")
    fun observeSources(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun source(id: String): SourceEntity?

    @Upsert suspend fun upsertSource(row: SourceEntity)

    @Insert suspend fun insertLog(row: SyncLogEntity)

    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY time_ms DESC LIMIT 300)")
    suspend fun trimLog()

    @Query("SELECT * FROM sync_log ORDER BY time_ms DESC LIMIT :limit")
    fun observeLog(limit: Int): Flow<List<SyncLogEntity>>
}

data class DaySteps(val epochDay: Long, val steps: Long)

/** One mood check-in per day. Stays on the phone. [tags] is a comma-separated list. */
@Entity(tableName = "mood")
data class MoodEntity(
    @PrimaryKey @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "score") val score: Int,
    @ColumnInfo(name = "tags") val tags: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/** A food-log entry. Stays on the phone. */
@Entity(tableName = "food", indices = [Index(value = ["epoch_day"])])
data class FoodEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "epoch_day") val epochDay: Long,
    @ColumnInfo(name = "meal") val meal: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "portion") val portion: String,
    @ColumnInfo(name = "kcal") val kcal: Int,
    @ColumnInfo(name = "protein_g") val proteinG: Double,
    @ColumnInfo(name = "carbs_g") val carbsG: Double,
    @ColumnInfo(name = "fat_g") val fatG: Double,
    @ColumnInfo(name = "from_photo") val fromPhoto: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Dao
interface FoodDao {
    @Insert suspend fun insert(rows: List<FoodEntity>)

    @Query("DELETE FROM food WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM food WHERE epoch_day >= :fromDay ORDER BY epoch_day, created_at")
    fun observeSince(fromDay: Long): Flow<List<FoodEntity>>
}

/** An imported (Health Connect) workout the user removed — e.g. a wrongly auto-detected walk. */
@Entity(tableName = "hidden_imports")
data class HiddenImportEntity(
    @PrimaryKey @ColumnInfo(name = "uid") val uid: String,
    @ColumnInfo(name = "hidden_at") val hiddenAt: Long = System.currentTimeMillis(),
)

/** One GPS fix of a workout route, keyed by the session's start time (= the saved workout's start). */
@Entity(tableName = "route_points", primaryKeys = ["session_start", "time_ms"])
data class RoutePointEntity(
    @ColumnInfo(name = "session_start") val sessionStart: Long,
    @ColumnInfo(name = "time_ms") val timeMs: Long,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "accuracy") val accuracy: Float,
)

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: RoutePointEntity)

    @Query("SELECT * FROM route_points WHERE session_start = :sessionStart ORDER BY time_ms")
    suspend fun points(sessionStart: Long): List<RoutePointEntity>

    @Query("SELECT DISTINCT session_start FROM route_points")
    fun observeSessions(): Flow<List<Long>>

    @Query("DELETE FROM route_points WHERE session_start = :sessionStart")
    suspend fun delete(sessionStart: Long)
}

@Dao
interface MoodDao {
    @Upsert suspend fun upsert(row: MoodEntity)

    @Query("SELECT * FROM mood ORDER BY epoch_day")
    fun observe(): Flow<List<MoodEntity>>

    @Query("DELETE FROM mood")
    suspend fun clear()
}

@Database(
    entities = [
        DailyDeviceEntity::class, WorkoutEntity::class, HourlyEntity::class, HrBucketEntity::class,
        SleepSegmentEntity::class, PhoneStepsEntity::class, SourceEntity::class, SyncLogEntity::class, MoodEntity::class, FoodEntity::class, RoutePointEntity::class, HiddenImportEntity::class,
    ],
    version = 7,
    exportSchema = true,
    // v2 watch metrics · v3 mood · v4 food log · v5 workout HR · v6 GPS routes · v7 hidden imports. Additive → auto.
    autoMigrations = [
        AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FitnessDao
    abstract fun health(): HealthDao
    abstract fun mood(): MoodDao
    abstract fun food(): FoodDao
    abstract fun route(): RouteDao

    companion object {
        // Future schema changes must ship Room migrations: logged workouts are user data.
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fitagent.db").build()
    }
}
