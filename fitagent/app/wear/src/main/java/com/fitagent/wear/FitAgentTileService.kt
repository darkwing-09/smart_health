package com.fitagent.wear

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture

/** Tile (W2): readiness score, step progress ring, one-tap Start. */
class FitAgentTileService : TileService() {
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            val s = SummaryStore.load(this)
            val device = requestParams.deviceConfiguration
            completer.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES)
                    .setFreshnessIntervalMillis(15 * 60 * 1000L)
                    .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(s, device)))
                    .build()
            )
            "tile"
        }

    private fun layout(s: PhoneSummary?, device: DeviceParametersBuilders.DeviceParameters): LayoutElementBuilders.LayoutElement {
        val progress = s?.let { (it.steps.toFloat() / it.stepGoal).coerceIn(0f, 1f) } ?: 0f
        val launch = ModifiersBuilders.Clickable.Builder().setId("open").setOnClick(
            ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(WearActivity::class.java.name).build()
            ).build()
        ).build()
        val score = Text.Builder(this, s?.score?.toString() ?: "—")
            .setTypography(Typography.TYPOGRAPHY_DISPLAY1)
            .setColor(argb(bandColor(s?.band).toArgbInt()))
            .build()
        val label = Text.Builder(this, if (s == null) "Open FitAgent" else "Readiness · ${s.steps} steps")
            .setTypography(Typography.TYPOGRAPHY_CAPTION1).setColor(argb(0xFFEDF1F5.toInt())).build()
        return EdgeContentLayout.Builder(device)
            .setEdgeContent(
                CircularProgressIndicator.Builder().setProgress(progress)
                    .setCircularProgressIndicatorColors(ProgressIndicatorColors(argb(0xFFC6F432.toInt()), argb(0x33FFFFFF)))
                    .build()
            )
            .setPrimaryLabelTextContent(score)
            .setSecondaryLabelTextContent(label)
            .setContent(CompactChip.Builder(this, "Start", launch, device).build())
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build())
            "resources"
        }

    companion object {
        private const val RESOURCES = "1"
    }
}

private fun androidx.compose.ui.graphics.Color.toArgbInt(): Int =
    ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
