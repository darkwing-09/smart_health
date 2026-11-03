package com.fitagent.data.health

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.FloorsClimbedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.fitagent.data.local.WorkoutEntity
import com.fitagent.domain.DeviceDay
import com.fitagent.domain.HealthReadout
import com.fitagent.domain.HrBucket
import com.fitagent.domain.NightSignals
import com.fitagent.domain.SleepSegment
import com.fitagent.domain.SleepSession
import com.fitagent.domain.nightOf
import com.fitagent.domain.pickNights
import com.fitagent.domain.SleepStage
import com.fitagent.domain.WorkoutSource
import com.fitagent.domain.WorkoutType
import com.fitagent.domain.workoutKcal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.reflect.KClass

enum class HealthAvailability { AVAILABLE, NOT_INSTALLED, UPDATE_REQUIRED }

/** A Health Connect data type FitAgent reads, with a human label for the compatibility check. */
data class HealthType(val label: String, val type: KClass<out Record>)

/** What each app wrote to Health Connect recently for one data type (record counts; capped at one page). */
data class CompatibilityRow(val type: HealthType, val countsByApp: Map<String, Int>, val truncated: Boolean)

/** One app's contribution seen through the Changes API since the last poll. */
data class OriginActivity(val records: Int, val types: Set<String>, val device: String?)

data class ChangesPoll(val token: String?, val origins: Map<String, OriginActivity>, val expired: Boolean)

/**
 * Reads the watch's data from Health Connect. Watch apps (NoiseFit, boAt Crest, Samsung Health, Fitbit, …) write
 * there; FitAgent never talks to closed watch firmware directly.
 *
 * Steps, distance and calories use platform aggregates (not raw records) so overlapping sources — phone and
 * watch both counting steps — are merged by Health Connect instead of being double-counted.
 */
class HealthConnectSource(private val context: Context) {

    private val client by lazy { HealthConnectClient.getOrCreate(context) }

    val types: List<HealthType> = listOf(
        HealthType("Steps", StepsRecord::class),
        HealthType("Distance", DistanceRecord::class),
        HealthType("Active calories", ActiveCaloriesBurnedRecord::class),
        HealthType("Floors", FloorsClimbedRecord::class),
        HealthType("Workouts", ExerciseSessionRecord::class),
        HealthType("Heart rate", HeartRateRecord::class),
        HealthType("Resting heart rate", RestingHeartRateRecord::class),
        HealthType("Heart rate variability", HeartRateVariabilityRmssdRecord::class),
        HealthType("Blood oxygen", OxygenSaturationRecord::class),
        HealthType("Respiratory rate", RespiratoryRateRecord::class),
        HealthType("Sleep", SleepSessionRecord::class),
        HealthType("VO₂ max", Vo2MaxRecord::class),
        HealthType("Skin temperature", SkinTemperatureRecord::class),
    )

    val readPermissions: Set<String> = types.map { HealthPermission.getReadPermission(it.type) }.toSet()
    val writeExercise: String = HealthPermission.getWritePermission(ExerciseSessionRecord::class)

    /** Data-type permissions plus background/history access where this Health Connect version supports them. */
    val allPermissions: Set<String>
        get() = readPermissions + writeExercise + optionalPermissions()

    fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.UPDATE_REQUIRED
        else -> HealthAvailability.NOT_INSTALLED
    }

    /** On Android 14+ Health Connect is part of the OS; earlier versions install it from Play. */
    val isBuiltIn: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    private fun featureAvailable(feature: Int): Boolean =
        availability() == HealthAvailability.AVAILABLE && runCatching {
            client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)

    private fun optionalPermissions(): Set<String> = buildSet {
        if (featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
        if (featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY)
        }
    }

    fun canReadInBackground(granted: Set<String>) = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted
    fun canReadHistory(granted: Set<String>) = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY in granted

    suspend fun grantedPermissions(): Set<String> =
        if (availability() != HealthAvailability.AVAILABLE) emptySet()
        else runCatching { client.permissionController.getGrantedPermissions() }
            .onFailure { Log.w(TAG, "permission query failed", it) }
            .getOrDefault(emptySet())

    private fun granted(granted: Set<String>, type: KClass<out Record>) =
        HealthPermission.getReadPermission(type) in granted

    // ── Reading ──────────────────────────────────────────────────────────────────────────────────

    /** Reads everything for [from]..[to]; intraday detail (hourly steps, HR buckets) only for [detailFrom]..[to]. */
    suspend fun read(from: LocalDate, to: LocalDate, detailFrom: LocalDate, granted: Set<String>, zone: ZoneId): HealthReadout {
        val origins = mutableMapOf<String, MutableSet<String>>()
        fun saw(pkg: String, label: String) { origins.getOrPut(pkg) { mutableSetOf() } += label }

        val days = readDailyAggregates(from, to, granted, zone).toMutableMap()

        // Sleep: a night belongs to the date it ends on. Read from 18:00 the evening before the window.
        val nights = if (granted(granted, SleepSessionRecord::class)) {
            val start = from.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
            val sessions = readAll(SleepSessionRecord::class, TimeRangeFilter.after(start))
            sessions.forEach { saw(it.metadata.dataOrigin.packageName, "Sleep") }
            pickNights(sessions.map { it.toSession() }, zone).filterKeys { !it.isBefore(from) && !it.isAfter(to) }
        } else emptyMap()
        for ((date, night) in nights) {
            val day = days[date] ?: DeviceDay(date)
            days[date] = day.copy(
                sleepMinutes = night.asleepMinutes,
                sleepStages = night.stages,
                bedMs = night.bedMs,
                wakeMs = night.wakeMs,
                sleepSource = night.source,
            )
        }

        // Overnight signals, attributed to the night they were measured in.
        val nightStart = from.minusDays(1).atTime(12, 0).atZone(zone).toInstant()
        val nightEnd = to.plusDays(1).atStartOfDay(zone).toInstant()
        val window = TimeRangeFilter.between(nightStart, nightEnd)
        val nightly = mutableMapOf<LocalDate, NightAccumulator>()
        fun acc(time: Instant): NightAccumulator? {
            val date = nightOf(time, zone, nights)
            if (date.isBefore(from) || date.isAfter(to)) return null
            return nightly.getOrPut(date) { NightAccumulator() }
        }
        if (granted(granted, HeartRateVariabilityRmssdRecord::class)) {
            readAll(HeartRateVariabilityRmssdRecord::class, window).forEach {
                saw(it.metadata.dataOrigin.packageName, "Heart rate variability")
                acc(it.time)?.hrv?.add(it.heartRateVariabilityMillis)
            }
        }
        if (granted(granted, OxygenSaturationRecord::class)) {
            readAll(OxygenSaturationRecord::class, window).forEach {
                saw(it.metadata.dataOrigin.packageName, "Blood oxygen")
                acc(it.time)?.spo2?.add(it.percentage.value)
            }
        }
        if (granted(granted, RespiratoryRateRecord::class)) {
            readAll(RespiratoryRateRecord::class, window).forEach {
                saw(it.metadata.dataOrigin.packageName, "Respiratory rate")
                acc(it.time)?.resp?.add(it.rate)
            }
        }
        if (granted(granted, SkinTemperatureRecord::class)) {
            readAll(SkinTemperatureRecord::class, window).forEach { r ->
                saw(r.metadata.dataOrigin.packageName, "Skin temperature")
                r.deltas.forEach { d -> acc(d.time)?.skinTemp?.add(d.delta.inCelsius) }
            }
        }
        for ((date, a) in nightly) {
            val day = days[date] ?: DeviceDay(date)
            days[date] = day.copy(night = a.toSignals())
        }

        if (granted(granted, Vo2MaxRecord::class)) {
            val vo2Window = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), nightEnd)
            readAll(Vo2MaxRecord::class, vo2Window).forEach {
                saw(it.metadata.dataOrigin.packageName, "VO₂ max")
                val date = it.time.atZone(zone).toLocalDate()
                days[date] = (days[date] ?: DeviceDay(date)).copy(vo2max = it.vo2MillilitersPerMinuteKilogram)
            }
        }

        val hourly = mutableMapOf<LocalDate, List<Pair<Int, Long>>>()
        val hourlyKcal = mutableMapOf<LocalDate, Map<Int, Double>>()
        var hr = emptyList<HrBucket>()
        if (!detailFrom.isAfter(to)) {
            val detail = TimeRangeFilter.between(
                detailFrom.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(),
            )
            val metrics = buildSet<AggregateMetric<*>> {
                if (granted(granted, StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
                if (granted(granted, ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            }
            if (metrics.isNotEmpty()) {
                val buckets = client.aggregateGroupByDuration(AggregateGroupByDurationRequest(metrics, detail, Duration.ofHours(1)))
                val steps = mutableMapOf<LocalDate, MutableList<Pair<Int, Long>>>()
                val kcal = mutableMapOf<LocalDate, MutableMap<Int, Double>>()
                for (b in buckets) {
                    val t = b.startTime.atZone(zone)
                    b.result[StepsRecord.COUNT_TOTAL]?.let { steps.getOrPut(t.toLocalDate()) { mutableListOf() } += t.hour to it }
                    b.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.let {
                        kcal.getOrPut(t.toLocalDate()) { mutableMapOf() }[t.hour] = it.inKilocalories
                    }
                }
                hourly += steps
                hourlyKcal += kcal
            }
            if (granted(granted, HeartRateRecord::class)) {
                hr = client.aggregateGroupByDuration(
                    AggregateGroupByDurationRequest(
                        setOf(HeartRateRecord.BPM_MIN, HeartRateRecord.BPM_AVG, HeartRateRecord.BPM_MAX),
                        detail,
                        Duration.ofMinutes(HR_BUCKET_MINUTES),
                    )
                ).mapNotNull { b ->
                    val avg = b.result[HeartRateRecord.BPM_AVG] ?: return@mapNotNull null
                    HrBucket(
                        b.startTime.toEpochMilli(),
                        (b.result[HeartRateRecord.BPM_MIN] ?: avg).toInt(),
                        avg.toInt(),
                        (b.result[HeartRateRecord.BPM_MAX] ?: avg).toInt(),
                    )
                }
            }
        }

        return HealthReadout(
            days = days,
            hourly = hourly,
            hourlyKcal = hourlyKcal,
            hr = hr,
            sleepNights = nights.mapValues { it.value.segments },
            origins = origins,
        )
    }

    private suspend fun readDailyAggregates(from: LocalDate, to: LocalDate, granted: Set<String>, zone: ZoneId): Map<LocalDate, DeviceDay> {
        val metrics = buildSet<AggregateMetric<*>> {
            if (granted(granted, StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (granted(granted, DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (granted(granted, ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (granted(granted, FloorsClimbedRecord::class)) add(FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL)
            if (granted(granted, RestingHeartRateRecord::class)) add(RestingHeartRateRecord.BPM_AVG)
            if (granted(granted, HeartRateRecord::class)) {
                add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MIN); add(HeartRateRecord.BPM_MAX)
            }
        }
        val days = mutableMapOf<LocalDate, DeviceDay>()
        var date = from
        while (!date.isAfter(to)) { days[date] = DeviceDay(date); date = date.plusDays(1) }
        if (metrics.isEmpty()) return days

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
            val steps = r[StepsRecord.COUNT_TOTAL] ?: 0L
            days[d] = DeviceDay(
                date = d,
                steps = steps,
                distanceMeters = r[DistanceRecord.DISTANCE_TOTAL]?.inMeters ?: 0.0,
                activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                restingHr = r[RestingHeartRateRecord.BPM_AVG]?.toInt(),
                avgHr = r[HeartRateRecord.BPM_AVG]?.toInt(),
                hrMin = r[HeartRateRecord.BPM_MIN]?.toInt(),
                hrMax = r[HeartRateRecord.BPM_MAX]?.toInt(),
                floors = r[FloorsClimbedRecord.FLOORS_CLIMBED_TOTAL]?.roundToInt(),
                // Aggregates merge sources; name the app when exactly one supplied the day's data.
                stepsSource = if (steps > 0) r.dataOrigins.singleOrNull()?.packageName else null,
            )
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

    // ── Change tracking & compatibility ──────────────────────────────────────────────────────────

    /**
     * Polls the Changes API: which apps wrote which data since [token]. Cheap, and tells the Data health screen
     * exactly when the watch app last delivered something. A null or expired token starts a new one.
     */
    suspend fun pollChanges(token: String?, granted: Set<String>): ChangesPoll {
        val readable = types.filter { granted(granted, it.type) }
        if (readable.isEmpty()) return ChangesPoll(null, emptyMap(), expired = false)
        val request = ChangesTokenRequest(readable.map { it.type }.toSet())
        if (token == null) return ChangesPoll(client.getChangesToken(request), emptyMap(), expired = false)

        val labels = readable.associate { it.type to it.label }
        val counts = mutableMapOf<String, Int>()
        val typesByApp = mutableMapOf<String, MutableSet<String>>()
        val devices = mutableMapOf<String, String>()
        var next: String = token
        while (true) {
            val response = client.getChanges(next)
            if (response.changesTokenExpired) return ChangesPoll(client.getChangesToken(request), emptyMap(), expired = true)
            for (change in response.changes) {
                if (change is DeletionChange || change !is UpsertionChange) continue
                val r = change.record
                val pkg = r.metadata.dataOrigin.packageName
                if (pkg == context.packageName) continue
                counts[pkg] = (counts[pkg] ?: 0) + 1
                labels[r::class]?.let { typesByApp.getOrPut(pkg) { mutableSetOf() } += it }
                describe(r.metadata.device)?.let { devices.putIfAbsent(pkg, it) }
            }
            next = response.nextChangesToken
            if (!response.hasMore) break
        }
        val origins = counts.mapValues { (pkg, n) -> OriginActivity(n, typesByApp[pkg].orEmpty(), devices[pkg]) }
        return ChangesPoll(next, origins, expired = false)
    }

    /** Which apps wrote which data types in the last [days] days — answers "does my watch app share with Health Connect?". */
    suspend fun compatibility(days: Long, granted: Set<String>): List<CompatibilityRow> {
        val since = Instant.now().minus(Duration.ofDays(days))
        return types.filter { granted(granted, it.type) }.map { t ->
            val page = runCatching {
                client.readRecords(ReadRecordsRequest(t.type, TimeRangeFilter.after(since), pageSize = COMPAT_PAGE))
            }.onFailure { Log.w(TAG, "compatibility read failed for ${t.label}", it) }.getOrNull()
            val counts = page?.records.orEmpty().groupingBy { it.metadata.dataOrigin.packageName }.eachCount()
            CompatibilityRow(t, counts, truncated = page?.pageToken != null)
        }
    }

    // ── Writing ──────────────────────────────────────────────────────────────────────────────────

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
        private const val COMPAT_PAGE = 1000
        const val HR_BUCKET_MINUTES = 10L
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

        fun describe(device: Device?): String? {
            if (device == null) return null
            return listOfNotNull(device.manufacturer, device.model).joinToString(" ").trim().ifEmpty { null }
        }

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

        fun stageOf(hcStage: Int): SleepStage = when (hcStage) {
            SleepSessionRecord.STAGE_TYPE_DEEP -> SleepStage.DEEP
            SleepSessionRecord.STAGE_TYPE_REM -> SleepStage.REM
            SleepSessionRecord.STAGE_TYPE_LIGHT -> SleepStage.LIGHT
            SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> SleepStage.AWAKE
            else -> SleepStage.ASLEEP
        }
    }
}

private fun SleepSessionRecord.toSession() = SleepSession(
    source = metadata.dataOrigin.packageName,
    startMs = startTime.toEpochMilli(),
    endMs = endTime.toEpochMilli(),
    stages = stages.sortedBy { it.startTime }.map {
        SleepSegment(it.startTime.toEpochMilli(), it.endTime.toEpochMilli(), HealthConnectSource.stageOf(it.stage))
    },
)

private class NightAccumulator {
    val hrv = mutableListOf<Double>()
    val spo2 = mutableListOf<Double>()
    val resp = mutableListOf<Double>()
    val skinTemp = mutableListOf<Double>()

    fun toSignals() = NightSignals(
        hrvMs = hrv.averageOrNull()?.round1(),
        spo2Avg = spo2.averageOrNull()?.round1(),
        spo2Min = spo2.minOrNull()?.round1(),
        respiratoryRate = resp.averageOrNull()?.round1(),
        skinTempDelta = skinTemp.averageOrNull()?.let { (it * 100).roundToInt() / 100.0 },
    )

    private fun List<Double>.averageOrNull() = if (isEmpty()) null else average()
    private fun Double.round1() = (this * 10).roundToInt() / 10.0
}
