package com.fitagent.wear

import android.content.Context
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

data class FinishedWorkout(
    val uid: String,
    val type: WatchWorkout,
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Long,
    val kcal: Double,
    val avgHr: Int?,
    val maxHr: Int?,
    val zoneSeconds: IntArray,
    val distanceM: Double,
)

/** Readiness + today's progress pushed by the phone (shown in the app, tile and complication). */
data class PhoneSummary(val score: Int, val band: String, val steps: Long, val stepGoal: Int, val streak: Int, val updatedMs: Long)

object DataLayer {
    /** Queues a finished workout for the phone. Durable: delivered whenever the phone is reachable. */
    suspend fun sendWorkout(context: Context, w: FinishedWorkout) {
        val req = PutDataMapRequest.create(Protocol.WORKOUT_PREFIX + w.uid).apply {
            dataMap.putInt(Protocol.K_VERSION, Protocol.VERSION)
            dataMap.putString(Protocol.K_UID, w.uid)
            dataMap.putString(Protocol.K_TYPE, w.type.phoneType)
            dataMap.putLong(Protocol.K_START, w.startMs)
            dataMap.putLong(Protocol.K_END, w.endMs)
            dataMap.putLong(Protocol.K_ACTIVE, w.activeSeconds)
            dataMap.putDouble(Protocol.K_KCAL, w.kcal)
            w.avgHr?.let { dataMap.putInt(Protocol.K_AVG_HR, it) }
            w.maxHr?.let { dataMap.putInt(Protocol.K_MAX_HR, it) }
            dataMap.putIntegerArrayList(Protocol.K_ZONES, ArrayList(w.zoneSeconds.toList()))
            dataMap.putDouble(Protocol.K_DISTANCE, w.distanceM)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(req).await()
    }
}

/** Last summary from the phone, cached so the tile/complication work offline. */
object SummaryStore {
    private val _summary = MutableStateFlow<PhoneSummary?>(null)
    val summary: StateFlow<PhoneSummary?> = _summary.asStateFlow()

    fun load(context: Context): PhoneSummary? {
        val p = context.getSharedPreferences("summary", Context.MODE_PRIVATE)
        if (!p.contains(Protocol.K_SCORE)) return null
        return PhoneSummary(p.getInt(Protocol.K_SCORE, 0), p.getString(Protocol.K_BAND, "STEADY") ?: "STEADY",
            p.getLong(Protocol.K_STEPS, 0), p.getInt(Protocol.K_STEP_GOAL, 8000), p.getInt(Protocol.K_STREAK, 0),
            p.getLong(Protocol.K_UPDATED, 0)).also { _summary.value = it }
    }

    fun save(context: Context, s: PhoneSummary) {
        context.getSharedPreferences("summary", Context.MODE_PRIVATE).edit()
            .putInt(Protocol.K_SCORE, s.score).putString(Protocol.K_BAND, s.band).putLong(Protocol.K_STEPS, s.steps)
            .putInt(Protocol.K_STEP_GOAL, s.stepGoal).putInt(Protocol.K_STREAK, s.streak).putLong(Protocol.K_UPDATED, s.updatedMs)
            .apply()
        _summary.value = s
    }
}
