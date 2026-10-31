package com.fitagent.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitagent.ui.theme.Fit
import kotlin.math.cos
import kotlin.math.sin

/** Segmented ring with content in the middle (sleep stages, workout mix, macros). */
@Composable
fun Donut(
    slices: List<Pair<Float, Color>>,
    modifier: Modifier = Modifier,
    thickness: Dp = 18.dp,
    gapDegrees: Float = 3f,
    description: String = "",
    content: @Composable BoxScope.() -> Unit = {},
) {
    val reveal = rememberReveal(slices, durationMs = 900)
    val track = Fit.colors.track
    val total = slices.sumOf { it.first.toDouble() }.toFloat()
    Box(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = thickness.toPx()
            val s = Size(size.minDimension - w, size.minDimension - w)
            val tl = Offset((size.width - s.width) / 2, (size.height - s.height) / 2)
            drawArc(track, 0f, 360f, false, tl, s, style = Stroke(w))
            if (total <= 0f) return@Canvas
            var start = -90f
            val visible = slices.count { it.first > 0f }
            slices.forEach { (v, c) ->
                if (v <= 0f) return@forEach
                val sweep = 360f * (v / total) * reveal
                val gap = if (visible > 1) gapDegrees else 0f
                drawArc(c, start + gap / 2, (sweep - gap).coerceAtLeast(0.5f), false, tl, s, style = Stroke(w, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        content()
    }
}

/** Generic 270° arc gauge (sleep score, live HR zone, readiness). */
@Composable
fun ArcGauge(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    thickness: Dp = 14.dp,
    description: String = "",
    content: @Composable BoxScope.() -> Unit = {},
) {
    val reveal = rememberReveal(progress, durationMs = 1000)
    val track = Fit.colors.track
    Box(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = thickness.toPx()
            val s = Size(size.minDimension - w, size.minDimension - w)
            val tl = Offset((size.width - s.width) / 2, (size.height - s.height) / 2)
            drawArc(track, 135f, 270f, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(color, 135f, 270f * progress.coerceIn(0f, 1f) * reveal, false, tl, s, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/**
 * 24-hour radial bar chart ("when in the day am I active?"). [values] has 24 entries, midnight at the top,
 * hours running clockwise like a clock face.
 */
@Composable
fun PolarClock(values: List<Float>, color: Color, modifier: Modifier = Modifier, description: String = "") {
    val reveal = rememberReveal(values, durationMs = 900)
    val track = Fit.colors.track
    val muted = Fit.colors.muted
    val measurer = rememberTextMeasurer()
    val maxV = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
    Canvas(modifier.semantics { if (description.isNotEmpty()) contentDescription = description }) {
        val c = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2 - 14.dp.toPx()
        val inner = outer * 0.32f
        drawCircle(track, outer, c, style = Stroke(1.dp.toPx()))
        drawCircle(track, inner, c, style = Stroke(1.dp.toPx()))
        val n = values.size.coerceAtLeast(1)
        values.forEachIndexed { i, v ->
            val angle = Math.toRadians((i + 0.5) * 360.0 / n - 90.0)
            val r = inner + (outer - inner) * (v / maxV) * reveal
            val from = Offset(c.x + inner * cos(angle).toFloat(), c.y + inner * sin(angle).toFloat())
            val to = Offset(c.x + r * cos(angle).toFloat(), c.y + r * sin(angle).toFloat())
            if (v > 0f) drawLine(color.copy(alpha = 0.35f + 0.65f * (v / maxV)), from, to, (outer * 0.09f).coerceAtLeast(3f), StrokeCap.Round)
        }
        listOf(0 to "0", 6 to "6", 12 to "12", 18 to "18").forEach { (h, label) ->
            val angle = Math.toRadians(h * 360.0 / 24 - 90.0)
            val layout = measurer.measure(label, TextStyle(fontSize = 10.sp, color = muted))
            val r = outer + 9.dp.toPx()
            drawText(layout, topLeft = Offset(c.x + r * cos(angle).toFloat() - layout.size.width / 2, c.y + r * sin(angle).toFloat() - layout.size.height / 2))
        }
    }
}
