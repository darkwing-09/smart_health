package com.fitagent.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Watch-face complication (W3): readiness as a ranged value (0–100) or short text. */
class ReadinessComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? = data(type, 81)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType, SummaryStore.load(this)?.score)

    private fun data(type: ComplicationType, score: Int?): ComplicationData? {
        val text = PlainComplicationText.Builder(score?.toString() ?: "—").build()
        val description = PlainComplicationText.Builder("Readiness ${score ?: "unknown"}").build()
        val tap = PendingIntent.getActivity(this, 0, Intent(this, WearActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder((score ?: 0).toFloat(), 0f, 100f, description)
                .setText(text).setTapAction(tap).build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(text, description)
                .setTitle(PlainComplicationText.Builder("READY").build()).setTapAction(tap).build()
            else -> null
        }
    }
}
