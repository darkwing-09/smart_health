package com.fitagent.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fitagent.domain.SleepSegment
import com.fitagent.domain.SleepStage
import com.fitagent.ui.theme.Fit
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Hypnogram: sleep stages as a step chart on four lanes (Awake on top, Deep at the bottom), each segment drawn in
 * its stage colour so the whole night reads at a glance — no tapping per stage.
 */
@Composable
fun Hypnogram(segments: List<SleepSegment>, zone: ZoneId, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val reveal = rememberReveal(segments, durationMs = 900)
    val colors = Fit.colors
    val measurer = rememberTextMeasurer()
    val muted = colors.muted
    val lanes = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    val laneLabels = listOf("Awake", "REM", "Light", "Deep")
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    val start = segments.minOfOrNull { it.startMs } ?: 0L
    val end = segments.maxOfOrNull { it.endMs } ?: 1L
    val span = (end - start).coerceAtLeast(1L).toFloat()
    val desc = if (segments.isEmpty()) "No sleep stages recorded" else
        "Sleep from ${fmt.format(Instant.ofEpochMilli(start).atZone(zone))} to ${fmt.format(Instant.ofEpochMilli(end).atZone(zone))}"
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { contentDescription = desc }) {
            val labelW = 44.dp.toPx()
            val plotW = size.width - labelW
            val laneH = size.height / lanes.size
            lanes.forEachIndexed { i, _ ->
                val layout = measurer.measure(laneLabels[i], TextStyle(fontSize = 10.sp, color = muted))
                drawText(layout, topLeft = Offset(0f, i * laneH + (laneH - layout.size.height) / 2))
                drawLine(colors.track, Offset(labelW, (i + 0.5f) * laneH), Offset(size.width, (i + 0.5f) * laneH), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)))
            }
            val limit = start + (span * reveal).toLong()
            var prev: Pair<Float, Float>? = null
            for (s in segments) {
                if (s.startMs > limit) break
                val lane = lanes.indexOf(if (s.stage == SleepStage.ASLEEP) SleepStage.LIGHT else s.stage)
                val x1 = labelW + plotW * ((s.startMs - start) / span)
                val x2 = labelW + plotW * ((minOf(s.endMs, limit) - start) / span)
                val yTop = lane * laneH + laneH * 0.2f
                val h = laneH * 0.6f
                val c = colors.stage(s.stage)
                prev?.let { (px, py) -> drawLine(c.copy(alpha = 0.5f), Offset(px, py), Offset(x1, yTop + h / 2), 1.5.dp.toPx()) }
                drawRoundRect(c, Offset(x1, yTop), Size((x2 - x1).coerceAtLeast(2f), h), CornerRadius(4.dp.toPx()))
                prev = x2 to yTop + h / 2
            }
        }
        if (segments.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(start = 44.dp, top = 4.dp)) {
                Text(fmt.format(Instant.ofEpochMilli(start).atZone(zone)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(fmt.format(Instant.ofEpochMilli(end).atZone(zone)), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

/**
 * GitHub-style calendar: one column per week (Monday on top), cells tinted by [intensity] 0..1 (null = no data).
 * Used for goal days, mood and the year in review.
 */
@Composable
fun CalendarHeatmap(
    from: LocalDate,
    to: LocalDate,
    intensity: (LocalDate) -> Float?,
    color: Color,
    modifier: Modifier = Modifier,
    cell: Dp = 14.dp,
    colorFor: ((LocalDate, Float) -> Color)? = null,
    description: String = "",
) {
    val track = Fit.colors.track
    val reveal = rememberReveal(from to to)
    val start = from.with(DayOfWeek.MONDAY)
    val weeks = (java.time.temporal.ChronoUnit.WEEKS.between(start, to) + 1).toInt()
    Canvas(modifier.height(cell * 7 + 18.dp).fillMaxWidth().semantics { if (description.isNotEmpty()) contentDescription = description }) {
        val gap = 3.dp.toPx()
        val c = ((size.height - gap * 6) / 7).coerceAtMost((size.width - gap * (weeks - 1)) / weeks)
        for (w in 0 until weeks) for (d in 0 until 7) {
            val date = start.plusDays((w * 7 + d).toLong())
            if (date.isBefore(from) || date.isAfter(to)) continue
            val x = w * (c + gap)
            val y = d * (c + gap)
            val v = intensity(date)
            val fill = when {
                v == null -> track
                colorFor != null -> colorFor(date, v)
                else -> color.copy(alpha = (0.18f + 0.82f * v.coerceIn(0f, 1f)) * reveal)
            }
            drawRoundRect(fill, Offset(x, y), Size(c, c), CornerRadius(c / 4))
        }
    }
}

/** Scatter with a least-squares trend line (e.g. hours slept vs readiness). */
@Composable
fun Scatter(
    points: List<Pair<Float, Float>>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    trend: Boolean = true,
    xLabel: String = "",
    yLabel: String = "",
    description: String = "",
) {
    val reveal = rememberReveal(points)
    val muted = Fit.colors.muted
    val track = Fit.colors.track
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(height).semantics { if (description.isNotEmpty()) contentDescription = description }) {
        if (points.isEmpty()) return@Canvas
        val left = 8.dp.toPx(); val bottomPad = 16.dp.toPx()
        val xs = points.map { it.first }; val ys = points.map { it.second }
        val xLo = xs.min(); val xHi = xs.max().let { if (it == xLo) xLo + 1 else it }
        val yLo = ys.min(); val yHi = ys.max().let { if (it == yLo) yLo + 1 else it }
        fun px(x: Float) = left + (size.width - left) * (x - xLo) / (xHi - xLo)
        fun py(y: Float) = (size.height - bottomPad) * (1 - (y - yLo) / (yHi - yLo)) * 0.9f + (size.height - bottomPad) * 0.05f
        drawLine(track, Offset(left, size.height - bottomPad), Offset(size.width, size.height - bottomPad), 1.dp.toPx())
        points.forEach { (x, y) -> drawCircle(color.copy(alpha = 0.75f), 5.dp.toPx() * reveal, Offset(px(x), py(y))) }
        if (trend && points.size >= 3) {
            linearFit(points)?.let { (slope, intercept) ->
                drawLine(color, Offset(px(xLo), py(slope * xLo + intercept)), Offset(px(xHi), py(slope * xHi + intercept)), 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
            }
        }
        if (xLabel.isNotEmpty()) {
            val l = measurer.measure(xLabel, TextStyle(fontSize = 10.sp, color = muted))
            drawText(l, topLeft = Offset(size.width - l.size.width, size.height - l.size.height))
        }
        if (yLabel.isNotEmpty()) drawText(measurer.measure(yLabel, TextStyle(fontSize = 10.sp, color = muted)), topLeft = Offset(left, 0f))
    }
}

/** Least-squares slope and intercept, or null when x has no spread. */
fun linearFit(points: List<Pair<Float, Float>>): Pair<Float, Float>? {
    val n = points.size
    if (n < 2) return null
    val mx = points.sumOf { it.first.toDouble() } / n
    val my = points.sumOf { it.second.toDouble() } / n
    val sxx = points.sumOf { (it.first - mx) * (it.first - mx) }
    if (sxx == 0.0) return null
    val sxy = points.sumOf { (it.first - mx) * (it.second - my) }
    val slope = sxy / sxx
    return slope.toFloat() to (my - slope * mx).toFloat()
}

/** A block on the week timeline (class, exam, planned session). */
data class TimelineBlock(val day: Int, val startMin: Int, val endMin: Int, val color: Color, val label: String = "")

/** Week timeline: columns are days, vertical axis is time of day between [fromHour] and [toHour]. */
@Composable
fun WeekTimeline(
    blocks: List<TimelineBlock>,
    dayLabels: List<String>,
    modifier: Modifier = Modifier,
    fromHour: Int = 7,
    toHour: Int = 22,
    height: Dp = 260.dp,
    description: String = "",
) {
    val track = Fit.colors.track
    val muted = Fit.colors.muted
    val measurer = rememberTextMeasurer()
    val reveal = rememberReveal(blocks)
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height).semantics { if (description.isNotEmpty()) contentDescription = description }) {
            val axis = 26.dp.toPx()
            val n = dayLabels.size.coerceAtLeast(1)
            val colW = (size.width - axis) / n
            val total = ((toHour - fromHour) * 60).toFloat()
            fun y(min: Int) = size.height * ((min - fromHour * 60).coerceIn(0, (toHour - fromHour) * 60) / total)
            for (h in fromHour..toHour step 3) {
                val yy = y(h * 60)
                drawLine(track, Offset(axis, yy), Offset(size.width, yy), 1.dp.toPx())
                val l = measurer.measure("%02d".format(h), TextStyle(fontSize = 10.sp, color = muted))
                drawText(l, topLeft = Offset(0f, (yy - l.size.height / 2).coerceIn(0f, size.height - l.size.height)))
            }
            for (b in blocks) {
                val x = axis + b.day * colW + 2.dp.toPx()
                val top = y(b.startMin)
                val h = (y(b.endMin) - top) * reveal
                if (h <= 0f) continue
                drawRoundRect(b.color, Offset(x, top), Size(colW - 4.dp.toPx(), h.coerceAtLeast(6.dp.toPx() * reveal)), CornerRadius(5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 26.dp, top = 4.dp)) {
            dayLabels.forEach {
                Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = muted,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
