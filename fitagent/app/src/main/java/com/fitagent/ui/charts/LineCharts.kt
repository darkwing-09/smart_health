package com.fitagent.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitagent.ui.theme.Fit

/** One line. Null values leave a gap. */
data class LineSeries(
    val values: List<Float?>,
    val color: Color,
    val dashed: Boolean = false,
    val fill: Boolean = false,
    val width: Dp = 2.5.dp,
    val dots: Boolean = false,
)

/** A horizontal coloured band, e.g. a heart-rate zone or the "your normal range" shading. */
data class HBand(val from: Float, val to: Float, val color: Color)

/**
 * Line chart for trends. Supports several series (e.g. "this period" + a dashed ghost of last period),
 * horizontal bands (zones, normal range), an optional dashed goal line and scrubbing.
 * Points of the first series outside [highlightOutside] are drawn as dots so out-of-range days stand out.
 */
@Composable
fun LineChart(
    series: List<LineSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    bands: List<HBand> = emptyList(),
    yMin: Float? = null,
    yMax: Float? = null,
    goal: Float? = null,
    invertY: Boolean = false,
    highlightOutside: ClosedFloatingPointRange<Float>? = null,
    xLabels: List<String> = emptyList(),
    scrubLabel: ((Int) -> String)? = null,
    description: String = "",
) {
    val reveal = rememberReveal(series.firstOrNull()?.values)
    val scrub = rememberScrub()
    val measurer = rememberTextMeasurer()
    val muted = Fit.colors.muted
    val bubble = MaterialTheme.colorScheme.inverseSurface
    val onBubble = MaterialTheme.colorScheme.inverseOnSurface
    val n = series.maxOfOrNull { it.values.size } ?: 0
    val all = series.flatMap { it.values.filterNotNull() } + bands.flatMap { listOf(it.from, it.to) } + listOfNotNull(goal)
    val lo = yMin ?: ((all.minOrNull() ?: 0f) - pad(all))
    val hi = yMax ?: ((all.maxOrNull() ?: 1f) + pad(all))
    val span = (hi - lo).takeIf { it > 0f } ?: 1f

    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height)
                .scrub(n, scrub, enabled = scrubLabel != null)
                .semantics { if (description.isNotEmpty()) contentDescription = description }
        ) {
            val top = if (scrubLabel != null) 26.dp.toPx() else 6.dp.toPx()
            val bottom = size.height - 6.dp.toPx()
            fun y(v: Float): Float {
                val f = ((v - lo) / span).coerceIn(-0.05f, 1.05f)
                return if (invertY) top + f * (bottom - top) else bottom - f * (bottom - top)
            }
            fun x(i: Int): Float = if (n <= 1) size.width / 2 else size.width * i / (n - 1)

            for (b in bands) {
                val y1 = y(b.to); val y2 = y(b.from)
                drawRect(b.color, Offset(0f, minOf(y1, y2)), Size(size.width, kotlin.math.abs(y2 - y1)))
            }
            goal?.let {
                drawLine(muted, Offset(0f, y(it)), Offset(size.width, y(it)), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }
            val visible = (n * reveal).toInt().coerceIn(0, n)
            series.forEachIndexed { si, s ->
                val path = Path()
                val fillPath = Path()
                var started = false
                var firstX = 0f
                var lastX = 0f
                for (i in 0 until minOf(visible, s.values.size)) {
                    val v = s.values[i]
                    if (v == null) {
                        if (started && s.fill) { fillPath.lineTo(lastX, bottom); fillPath.lineTo(firstX, bottom); fillPath.close() }
                        started = false
                        continue
                    }
                    val px = x(i); val py = y(v)
                    if (!started) { path.moveTo(px, py); fillPath.moveTo(px, bottom); fillPath.lineTo(px, py); firstX = px; started = true }
                    else { path.lineTo(px, py); fillPath.lineTo(px, py) }
                    lastX = px
                }
                if (started && s.fill) { fillPath.lineTo(lastX, bottom); fillPath.lineTo(firstX, bottom); fillPath.close() }
                if (s.fill) drawPath(fillPath, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.35f), s.color.copy(alpha = 0f)), top, bottom))
                drawPath(path, s.color, style = Stroke(s.width.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round,
                    pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) else null))
                val r = 3.5.dp.toPx()
                for (i in 0 until minOf(visible, s.values.size)) {
                    val v = s.values[i] ?: continue
                    val out = si == 0 && highlightOutside != null && v !in highlightOutside
                    if (s.dots || out || (i == s.values.lastIndex && !s.dashed)) {
                        drawCircle(s.color, if (out) r * 1.3f else r, Offset(x(i), y(v)))
                    }
                }
            }
            val sel = scrub.value
            if (sel != null && scrubLabel != null) {
                series.firstOrNull()?.values?.getOrNull(sel)?.let { v ->
                    drawCircle(bubble, 6.dp.toPx(), Offset(x(sel), y(v)))
                    drawCircle(series.first().color, 4.dp.toPx(), Offset(x(sel), y(v)))
                }
                drawScrubMarker(x(sel), scrubLabel(sel), measurer, muted, bubble, onBubble)
            }
        }
        AxisLabels(xLabels)
    }
}

private fun pad(values: List<Float>): Float {
    val lo = values.minOrNull() ?: return 1f
    val hi = values.maxOrNull() ?: return 1f
    return ((hi - lo) * 0.15f).coerceAtLeast(1f)
}

/** Tiny axis-less line for stat tiles and recap cards. */
@Composable
fun Sparkline(values: List<Float?>, color: Color, modifier: Modifier = Modifier, fill: Boolean = true) {
    val points = values.filterNotNull()
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lo = points.min(); val hi = points.max()
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val n = values.size
        val path = Path(); val area = Path()
        var started = false; var lastX = 0f; var firstX = 0f
        values.forEachIndexed { i, v ->
            v ?: return@forEachIndexed
            val px = size.width * i / (n - 1).coerceAtLeast(1)
            val py = size.height - (v - lo) / span * size.height * 0.85f - size.height * 0.075f
            if (!started) { path.moveTo(px, py); area.moveTo(px, size.height); area.lineTo(px, py); firstX = px; started = true }
            else { path.lineTo(px, py); area.lineTo(px, py) }
            lastX = px
        }
        area.lineTo(lastX, size.height); area.lineTo(firstX, size.height); area.close()
        if (fill) drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), Color.Transparent)))
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
