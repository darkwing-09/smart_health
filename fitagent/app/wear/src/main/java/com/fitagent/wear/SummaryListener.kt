package com.fitagent.wear

import android.content.ComponentName
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/** Receives the phone's readiness summary and refreshes the tile and complication (W2, W3). */
class SummaryListener : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == Protocol.SUMMARY_PATH }.forEach { e ->
            val m = DataMapItem.fromDataItem(e.dataItem).dataMap
            SummaryStore.save(this, PhoneSummary(m.getInt(Protocol.K_SCORE), m.getString(Protocol.K_BAND) ?: "STEADY",
                m.getLong(Protocol.K_STEPS), m.getInt(Protocol.K_STEP_GOAL, 8000), m.getInt(Protocol.K_STREAK), m.getLong(Protocol.K_UPDATED)))
            TileService.getUpdater(this).requestUpdate(FitAgentTileService::class.java)
            ComplicationDataSourceUpdateRequester.create(this, ComponentName(this, ReadinessComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
