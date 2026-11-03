package com.fitagent.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.UUID

data class LiveWorkout(
    val type: WatchWorkout,
    val startedMs: Long,
    val activeSeconds: Long = 0,
    val bpm: Int? = null,
    val kcal: Double = 0.0,
    val distanceM: Double = 0.0,
    val paused: Boolean = false,
    val intervals: Intervals? = null,
)

/**
 * Records a workout with Health Services' ExerciseClient — the watch keeps tracking even if this app's process is
 * killed — and queues the result for the phone when it ends (W1).
 */
class WorkoutRecorder(private val context: Context, private val ageYears: Int = 20) {
    private val client by lazy { HealthServices.getClient(context).exerciseClient }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _live = MutableStateFlow<LiveWorkout?>(null)
    val live: StateFlow<LiveWorkout?> = _live.asStateFlow()
    private var hr = HrAccumulator(hrMax(ageYears))
    private var lastPhase: Phase? = null

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val w = _live.value ?: return
            val m = update.latestMetrics
            val now = System.currentTimeMillis()
            m.getData(DataType.HEART_RATE_BPM).forEach { hr.add(now, it.value.toInt()) }
            val bpm = m.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.toInt() ?: w.bpm
            val active = update.activeDurationCheckpoint?.activeDuration?.seconds ?: w.activeSeconds
            _live.value = w.copy(
                activeSeconds = active,
                bpm = bpm,
                kcal = m.getData(DataType.CALORIES_TOTAL)?.total ?: w.kcal,
                distanceM = m.getData(DataType.DISTANCE_TOTAL)?.total ?: w.distanceM,
                paused = update.exerciseStateInfo.state.isPaused,
            )
            w.intervals?.let { cue(phaseAt(it, active).phase) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
        override fun onRegistered() = Unit
        override fun onRegistrationFailed(throwable: Throwable) { Log.w(TAG, "callback registration failed", throwable) }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
    }

    /** Wrist buzz on WORK / REST / DONE so the student never has to look. */
    private fun cue(p: Phase) {
        if (p == lastPhase) return
        lastPhase = p
        val v = context.getSystemService(Vibrator::class.java) ?: return
        val pattern = when (p) {
            Phase.WORK -> longArrayOf(0, 300)
            Phase.REST -> longArrayOf(0, 120, 100, 120)
            Phase.DONE -> longArrayOf(0, 400, 150, 400)
        }
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun start(type: WatchWorkout, intervals: Intervals? = null) = scope.launch {
        hr = HrAccumulator(hrMax(ageYears))
        lastPhase = null
        val caps = runCatching { client.getCapabilitiesAsync().await() }.getOrNull()
        val exercise = exerciseType(type)
        val supported = caps?.getExerciseTypeCapabilities(exercise)?.supportedDataTypes.orEmpty()
        val wanted = setOf(DataType.HEART_RATE_BPM, DataType.CALORIES_TOTAL, DataType.DISTANCE_TOTAL)
            .filter { it in supported }.toSet()
        val config = ExerciseConfig.builder(exercise)
            .setDataTypes(wanted)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        client.setUpdateCallback(callback)
        _live.value = LiveWorkout(type, System.currentTimeMillis(), intervals = intervals)
        runCatching { client.startExerciseAsync(config).await() }.onFailure {
            Log.w(TAG, "start failed", it)
            _live.value = null
        }
    }

    fun togglePause() = scope.launch {
        val w = _live.value ?: return@launch
        runCatching { if (w.paused) client.resumeExerciseAsync().await() else client.pauseExerciseAsync().await() }
    }

    /** Ends the exercise and queues it for the phone (≥ 60 s only). */
    fun finish() = scope.launch {
        val w = _live.value ?: return@launch
        runCatching { client.endExerciseAsync().await() }
        _live.value = null
        if (w.activeSeconds < 60) return@launch
        val result = FinishedWorkout(
            uid = "wear:" + UUID.randomUUID(), type = w.type, startMs = w.startedMs, endMs = System.currentTimeMillis(),
            activeSeconds = w.activeSeconds, kcal = w.kcal, avgHr = hr.average, maxHr = hr.peak.takeIf { it > 0 },
            zoneSeconds = hr.zones.map { it.toInt() }.toIntArray(), distanceM = w.distanceM,
        )
        runCatching { DataLayer.sendWorkout(context, result) }.onFailure { Log.w(TAG, "queueing workout failed", it) }
    }

    private fun exerciseType(t: WatchWorkout): ExerciseType = when (t) {
        WatchWorkout.RUN -> ExerciseType.RUNNING
        WatchWorkout.WALK -> ExerciseType.WALKING
        WatchWorkout.CYCLE -> ExerciseType.BIKING
        WatchWorkout.HIIT -> ExerciseType.HIGH_INTENSITY_INTERVAL_TRAINING
        WatchWorkout.STRENGTH -> ExerciseType.STRENGTH_TRAINING
        WatchWorkout.YOGA -> ExerciseType.YOGA
    }

    companion object {
        private const val TAG = "WorkoutRecorder"
        @Volatile private var instance: WorkoutRecorder? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: WorkoutRecorder(context.applicationContext).also { instance = it }
        }
    }
}
