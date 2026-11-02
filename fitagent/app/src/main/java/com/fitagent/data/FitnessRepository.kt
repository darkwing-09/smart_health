package com.fitagent.data

import android.content.Context
import android.util.Log
import com.fitagent.data.health.AppLabels
import com.fitagent.data.health.CompatibilityRow
import com.fitagent.data.health.HealthAvailability
import com.fitagent.data.health.HealthConnectSource
import com.fitagent.data.local.AppDatabase
import com.fitagent.data.local.DailyDeviceEntity
import com.fitagent.data.local.HiddenImportEntity
import com.fitagent.data.local.HourlyEntity
import com.fitagent.data.local.HrBucketEntity
import com.fitagent.data.local.PhoneStepsEntity
import com.fitagent.data.local.SleepSegmentEntity
import com.fitagent.data.local.SourceEntity
import com.fitagent.data.local.SyncLogEntity
import com.fitagent.data.local.WorkoutEntity
import com.fitagent.data.prefs.AuthStore
import com.fitagent.data.prefs.ProfileStore
import com.fitagent.data.prefs.SyncStateStore
import com.fitagent.data.sensors.PhoneStepCounter
import com.fitagent.domain.DayActivity
import com.fitagent.domain.DeviceDay
import com.fitagent.domain.HrBucket
import com.fitagent.domain.HrSummary
import com.fitagent.domain.PHONE_SENSOR_SOURCE
import com.fitagent.domain.SleepSegment
import com.fitagent.domain.SleepStage
import com.fitagent.domain.Workout
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.buildDays
import com.fitagent.domain.mergePhoneSteps
import com.fitagent.domain.workoutKcal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class HealthStatus(
    val availability: HealthAvailability = HealthAvailability.NOT_INSTALLED,
    val granted: Set<String> = emptySet(),
    val required: Set<String> = emptySet(),
    val backgroundRead: Boolean = false,
) {
    val connected: Boolean get() = availability == HealthAvailability.AVAILABLE && granted.isNotEmpty()
    val missing: Int get() = (required - granted).size
}

/** A data source as shown on the Devices & data health screen. */
data class DataSource(
    val id: String,
    val label: String,
    val device: String?,
    val lastDataMs: Long,
    val recordsReceived: Long,
    val dataTypes: List<String>,
)

data class SyncEvent(val timeMs: Long, val kind: String, val ok: Boolean, val records: Int, val message: String?)

/** Local source of truth for activity: Room, fed by the watch (via Health Connect), the phone's step sensor and the in-app tracker. */
class FitnessRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val health: HealthConnectSource,
    private val phone: PhoneStepCounter,
    private val profiles: ProfileStore,
    private val auth: AuthStore,
    private val syncState: SyncStateStore,
) {
    private val dao = db.dao()
    private val healthDao = db.health()
    private val refreshLock = Mutex()
    private val _status = MutableStateFlow(HealthStatus(required = health.readPermissions + health.writeExercise))
    val status: StateFlow<HealthStatus> = _status.asStateFlow()
    val lastHealthSync: StateFlow<Long> = syncState.lastHealthSync

    /** Called after new data lands (e.g. to refresh the home-screen widget). Set by the app container. */
    var onDataChanged: suspend () -> Unit = {}

    /** Watch days with the phone's own step count filling days no watch app reported. */
    val deviceDays: Flow<Map<LocalDate, DeviceDay>> =
        combine(dao.observeDays(), healthDao.observePhoneDaily()) { rows, phoneRows ->
            mergePhoneSteps(
                rows.associate { LocalDate.ofEpochDay(it.epochDay) to it.toDomain() },
                phoneRows.associate { LocalDate.ofEpochDay(it.epochDay) to it.steps },
            )
        }
    val workouts: Flow<List<Workout>> = dao.observeWorkouts().map { rows -> rows.map { it.toDomain() } }

    /** Steps per hour for [date] (watch first, phone sensor otherwise). Always 24 entries. */
    fun hourlySteps(date: LocalDate): Flow<List<Long>> =
        combine(healthDao.observeHourly(date.toEpochDay()), healthDao.observePhoneHourly(date.toEpochDay())) { watch, ph ->
            val byHour = if (watch.any { it.steps > 0 }) watch.associate { it.hour to it.steps }
            else ph.associate { it.hour to it.steps }
            List(24) { byHour[it] ?: 0L }
        }

    /** One-shot read of [hourlySteps] for background work. */
    suspend fun hourlyStepsNow(date: LocalDate): List<Long> = hourlySteps(date).first()

    fun heartRate(date: LocalDate, zone: ZoneId): Flow<List<HrBucket>> {
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return healthDao.observeHr(from, to).map { rows -> rows.map { HrBucket(it.startMs, it.min, it.avg, it.max) } }
    }

    fun heartRateRange(from: LocalDate, to: LocalDate, zone: ZoneId): Flow<List<HrBucket>> {
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return healthDao.observeHr(start, end).map { rows -> rows.map { HrBucket(it.startMs, it.min, it.avg, it.max) } }
    }

    /** Hypnogram for the night ending on [date]. */
    fun sleepSegments(date: LocalDate): Flow<List<SleepSegment>> =
        healthDao.observeSleep(date.toEpochDay()).map { rows ->
            rows.map { SleepSegment(it.startMs, it.endMs, SleepStage.valueOf(it.stage)) }
        }

    val sources: Flow<List<DataSource>> = healthDao.observeSources().map { rows ->
        rows.map {
            val tags = it.dataTypes.split('|').filter(String::isNotBlank)
            DataSource(
                id = it.id,
                label = it.label,
                device = tags.firstOrNull { t -> t.startsWith(DEVICE_PREFIX) }?.removePrefix(DEVICE_PREFIX),
                lastDataMs = it.lastDataMs,
                recordsReceived = it.recordsReceived,
                dataTypes = tags.filterNot { t -> t.startsWith(DEVICE_PREFIX) },
            )
        }
    }

    fun syncLog(limit: Int = 60): Flow<List<SyncEvent>> =
        healthDao.observeLog(limit).map { rows -> rows.map { SyncEvent(it.timeMs, it.kind, it.ok, it.records, it.message) } }

    suspend fun refreshStatus(): HealthStatus {
        val granted = health.grantedPermissions()
        val s = HealthStatus(health.availability(), granted, health.allPermissions, health.canReadInBackground(granted))
        _status.value = s
        return s
    }

    /**
     * Pulls watch data from Health Connect into Room and samples the phone's step sensor. Safe to call often:
     * a quick refresh re-reads the last 3 days (late-syncing watch apps fill in yesterday), and a full refresh —
     * on demand or every 12 h — re-reads 30 days (90 on first import when history access is granted).
     */
    suspend fun refresh(full: Boolean = false): Result<Unit> = refreshLock.withLock {
        val started = System.currentTimeMillis()
        var records = 0
        val result = runCatching {
            records += samplePhoneSteps()
            val s = refreshStatus()
            if (!s.connected) return@runCatching
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)

            val poll = health.pollChanges(syncState.changesToken, s.granted)
            syncState.changesToken = poll.token
            for ((pkg, activity) in poll.origins) {
                records += activity.records
                recordSource(pkg, started, activity.records.toLong(), activity.types, activity.device)
            }

            val stale = started - syncState.lastFullReadMs > FULL_READ_INTERVAL_MS
            val daysBack = when {
                !syncState.historyImported && health.canReadHistory(s.granted) -> 90L
                full || stale || poll.expired -> 30L
                else -> 3L
            }
            val from = today.minusDays(daysBack - 1)
            val readout = health.read(from, today, today.minusDays(1), s.granted, zone)

            dao.upsertDays(readout.days.values.map(DailyDeviceEntity::from))
            healthDao.upsertHourly(readout.hourly.flatMap { (date, hours) ->
                val kcal = readout.hourlyKcal[date].orEmpty()
                hours.map { (h, steps) -> HourlyEntity(date.toEpochDay(), h, steps, kcal[h]) }
            })
            healthDao.upsertHr(readout.hr.map { HrBucketEntity(it.startMs, it.min, it.avg, it.max) })
            healthDao.pruneHr(started - Duration.ofDays(HR_RETENTION_DAYS).toMillis())
            for ((date, segments) in readout.sleepNights) {
                healthDao.replaceSleepNight(date.toEpochDay(), segments.map {
                    SleepSegmentEntity(it.startMs, it.endMs, it.stage.name, date.toEpochDay())
                })
            }
            for ((pkg, types) in readout.origins) recordSource(pkg, null, 0, types, null)

            val since = from.atStartOfDay(zone).toInstant()
            val imported = health.readWorkouts(since, s.granted, profiles.profile.value.weightKg)
            dao.replaceImported(since.toEpochMilli(), imported)

            if (daysBack >= 30) syncState.lastFullReadMs = started
            if (daysBack == 90L) syncState.historyImported = true
            syncState.markHealthSync(System.currentTimeMillis())
        }
        log(KIND_WATCH, started, result.isSuccess, records, result.exceptionOrNull()?.let(::describeError))
        if (result.isSuccess) onDataChanged()
        result.onFailure { Log.w(TAG, "refresh failed", it) }
    }

    /** Samples only the phone's step sensor (used in the background when Health Connect can't be read). */
    suspend fun samplePhoneOnly() = refreshLock.withLock {
        val started = System.currentTimeMillis()
        val result = runCatching { samplePhoneSteps() }
        if (result.getOrDefault(0) > 0 || result.isFailure) {
            log(KIND_PHONE, started, result.isSuccess, result.getOrDefault(0), result.exceptionOrNull()?.let(::describeError))
        }
    }

    /** Reads the phone's step counter and books the steps since the last reading to the current hour. */
    private suspend fun samplePhoneSteps(): Int {
        val value = phone.read() ?: return 0
        val now = System.currentTimeMillis()
        val steps = PhoneStepCounter.stepsSince(syncState.phoneCounter, value, now, PhoneStepCounter.bootTimeMs())
        syncState.phoneCounter = value to now
        if (steps <= 0) return 0
        val t = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault())
        val day = t.toLocalDate().toEpochDay()
        val existing = healthDao.phoneSteps(day, t.hour)?.steps ?: 0
        healthDao.upsertPhoneSteps(PhoneStepsEntity(day, t.hour, existing + steps))
        recordSource(PHONE_SENSOR_SOURCE, now, steps, setOf("Steps"), null)
        return 1
    }

    private suspend fun recordSource(id: String, dataAtMs: Long?, records: Long, types: Set<String>, device: String?) {
        val now = System.currentTimeMillis()
        val old = healthDao.source(id)
        val oldTags = old?.dataTypes?.split('|')?.filter(String::isNotBlank).orEmpty()
        val deviceTag = device?.let { DEVICE_PREFIX + it } ?: oldTags.firstOrNull { it.startsWith(DEVICE_PREFIX) }
        val merged = (oldTags.filterNot { it.startsWith(DEVICE_PREFIX) } + types).distinct().sorted()
        healthDao.upsertSource(
            SourceEntity(
                id = id,
                label = AppLabels.label(context, id),
                firstSeenMs = old?.firstSeenMs ?: now,
                lastDataMs = maxOf(old?.lastDataMs ?: 0, dataAtMs ?: 0),
                recordsReceived = (old?.recordsReceived ?: 0) + records,
                dataTypes = (listOfNotNull(deviceTag) + merged).joinToString("|"),
            )
        )
    }

    suspend fun log(kind: String, startedMs: Long, ok: Boolean, records: Int, message: String?) {
        healthDao.insertLog(SyncLogEntity(0, startedMs, kind, ok, records, System.currentTimeMillis() - startedMs, message))
        healthDao.trimLog()
    }

    /** Which apps wrote which data in the last week, with readable app names. */
    suspend fun compatibility(): List<Pair<CompatibilityRow, Map<String, String>>> {
        val granted = refreshStatus().granted
        return health.compatibility(7, granted).map { row ->
            row to row.countsByApp.keys.associateWith { AppLabels.label(context, it) }
        }
    }

    /** Saves a workout tracked or logged in the app and mirrors it to Health Connect. */
    suspend fun saveWorkout(type: WorkoutType, startMs: Long, endMs: Long, activeSeconds: Long, hr: HrSummary? = null, title: String? = null): Workout {
        val entity = WorkoutEntity(
            uid = "app:${UUID.randomUUID()}",
            type = type.name,
            title = title,
            startMs = startMs,
            endMs = endMs,
            activeSeconds = activeSeconds,
            kcal = workoutKcal(type, profiles.profile.value.weightKg, activeSeconds),
            source = WorkoutSource.APP.name,
            healthConnectId = null,
            avgHr = hr?.avg,
            maxHr = hr?.max,
            zoneSeconds = hr?.zoneSeconds?.joinToString(","),
        )
        val id = dao.insertWorkout(entity)
        health.writeWorkout(entity, ZoneId.systemDefault())?.let { dao.setHealthConnectId(id, it) }
        onDataChanged()
        return entity.copy(id = id).toDomain()
    }

    suspend fun deleteWorkout(id: Long) {
        val w = dao.getWorkout(id) ?: return
        if (w.source == WorkoutSource.APP.name) {
            w.healthConnectId?.let { health.deleteWorkout(it) }
            dao.deleteWorkout(id)
            db.route().delete(w.startMs)
        } else {
            // Another app owns this record and the next refresh would re-import it: hide it instead (D6).
            dao.hideImport(HiddenImportEntity(w.uid))
        }
        if (auth.account.value != null) auth.queueDeletion(w.uid)
    }

    suspend fun snapshot(): Pair<List<DayActivity>, List<Workout>> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val device = mergePhoneSteps(
            dao.getDays().associate { LocalDate.ofEpochDay(it.epochDay) to it.toDomain() },
            healthDao.phoneDaily().associate { LocalDate.ofEpochDay(it.epochDay) to it.steps },
        )
        val workouts = dao.getWorkouts().map { it.toDomain() }
        val from = listOfNotNull(device.keys.minOrNull(), today.minusDays(29)).min()
        return buildDays(from, today, device, workouts, profiles.profile.value.weightKg, zone) to workouts
    }

    companion object {
        private const val TAG = "FitnessRepository"
        const val KIND_WATCH = "watch"
        const val KIND_CLOUD = "cloud"
        const val KIND_PHONE = "phone"
        private const val DEVICE_PREFIX = "device:"
        private const val HR_RETENTION_DAYS = 90L
        private val FULL_READ_INTERVAL_MS = Duration.ofHours(12).toMillis()

        fun describeError(t: Throwable): String = when (t) {
            is SecurityException -> "Health Connect permission missing or background reading not allowed"
            is android.os.RemoteException -> "Health Connect is busy or rate-limited — will retry"
            else -> t.message?.take(160) ?: t::class.java.simpleName
        }
    }
}
