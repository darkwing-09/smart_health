package com.fitagent.wearsync

import android.content.Context
import android.util.Log
import com.fitagent.container
import com.fitagent.domain.Band
import com.fitagent.domain.HrSummary
import com.fitagent.domain.WorkoutType
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

/** Mirror of the watch's Protocol.kt (wear module) — keep in sync. */
object WearProtocol {
    const val WORKOUT_PREFIX = "/fitagent/workout/"
    const val SUMMARY_PATH = "/fitagent/summary"
    const val VERSION = 1
}

/** A workout recorded on the watch, as parsed from its DataMap. Null when the payload is unusable. */
data class WatchWorkoutPayload(
    val uid: String,
    val type: WorkoutType,
    val startMs: Long,
    val endMs: Long,
    val activeSeconds: Long,
    val kcal: Double,
    val hr: HrSummary?,
)

fun parseWatchWorkout(m: DataMap): WatchWorkoutPayload? {
    if (m.getInt("v", 0) != WearProtocol.VERSION) return null
    val uid = m.getString("uid")?.takeIf { it.startsWith("wear:") } ?: return null
    val start = m.getLong("start_ms"); val end = m.getLong("end_ms"); val active = m.getLong("active_s")
    if (end <= start || active !in 60..86_400) return null
    val zones = m.getIntegerArrayList("zone_s")?.takeIf { it.size == 5 }
    val avg = m.getInt("avg_hr", 0).takeIf { it > 0 }
    val hr = if (avg != null && zones != null) HrSummary(avg, m.getInt("max_hr", avg), zones) else null
    return WatchWorkoutPayload(uid, WorkoutType.fromName(m.getString("type")), start, end, active,
        m.getDouble("kcal").coerceIn(0.0, 5000.0), hr)
}

/**
 * Receives workouts recorded on the watch (W1/W4). Saving is idempotent by uid; deleting the DataItem afterwards
 * is the acknowledgement — until then Play services keeps re-delivering it, so nothing is lost if the phone app
 * is killed mid-way.
 */
class WearSyncService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val items = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(WearProtocol.WORKOUT_PREFIX) == true }
            .map { it.dataItem.uri to DataMapItem.fromDataItem(it.dataItem).dataMap }
        if (items.isEmpty()) return
        val c = applicationContext.container
        runBlocking {
            for ((uri, map) in items) {
                val w = parseWatchWorkout(map)
                if (w != null) c.fitness.saveWatchWorkout(w.uid, w.type, w.startMs, w.endMs, w.activeSeconds, w.kcal, w.hr)
                else Log.w(TAG, "ignored malformed watch workout at $uri")
                runCatching { Wearable.getDataClient(applicationContext).deleteDataItems(uri).await() }
            }
        }
    }

    companion object {
        private const val TAG = "WearSync"

        /** Pushes readiness + today's steps for the watch app, tile and complication. Cheap no-op without a watch. */
        suspend fun publishSummary(context: Context, score: Int, band: Band, steps: Long, stepGoal: Int, streak: Int) {
            val req = PutDataMapRequest.create(WearProtocol.SUMMARY_PATH).apply {
                dataMap.putInt("score", score)
                dataMap.putString("band", band.name)
                dataMap.putLong("steps", steps)
                dataMap.putInt("step_goal", stepGoal)
                dataMap.putInt("streak", streak)
                dataMap.putLong("updated_ms", System.currentTimeMillis())
            }.asPutDataRequest()
            runCatching { Wearable.getDataClient(context).putDataItem(req).await() }
        }
    }
}
