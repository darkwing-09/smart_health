package com.fitagent.data.local

import android.content.Context
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
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Health Connect aggregates for one local calendar day. Kept indefinitely as history. */
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
) {
    fun toDomain() = DeviceDay(
        LocalDate.ofEpochDay(epochDay), steps, distanceMeters, activeKcal, restingHr, avgHr, sleepMinutes,
    )

    companion object {
        fun from(d: DeviceDay) = DailyDeviceEntity(
            d.date.toEpochDay(), d.steps, d.distanceMeters, d.activeKcal, d.restingHr, d.avgHr, d.sleepMinutes,
        )
    }
}

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
) {
    fun toDomain() = Workout(
        id, uid, WorkoutType.fromName(type), title, startMs, endMs, activeSeconds, kcal, WorkoutSource.valueOf(source),
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

    @Query("SELECT * FROM workouts ORDER BY start_ms DESC")
    fun observeWorkouts(): Flow<List<WorkoutEntity>>

    @Query("SELECT * FROM workouts ORDER BY start_ms DESC")
    suspend fun getWorkouts(): List<WorkoutEntity>

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

@Database(entities = [DailyDeviceEntity::class, WorkoutEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): FitnessDao

    companion object {
        // Future schema changes must ship Room migrations: logged workouts are user data.
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "fitagent.db").build()
    }
}
