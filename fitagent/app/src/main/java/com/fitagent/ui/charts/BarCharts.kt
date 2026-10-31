package com.fitagent.ui.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fitagent.ui.theme.Fit

/**
 * Pill bars with an optional dashed goal, per-bar colours, scrubbing and a dimmed look for bars under the goal.
 * [highlight] bars are always drawn in full colour (e.g. today).
 */
@Composable
fun Bars(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    goal: Float? = null,
    colors: List<Color>? = null,
    highlight: Set<Int> = emptySet(),
    labels: List<String> = emptyList(),
    scrubLabel: ((Int) -> String)? = null,
    maxBarWidth: Dp = 28.dp,
    description: String = "",
) {
    val reveal = rememberReveal(values)
    val scrub = rememberScrub()
    val measurer = rememberTextMeasurer()
    val muted = Fit.colors.muted
    val track = Fit.colors.track
    val bubble = MaterialTheme.colorScheme.inverseSurface
    val onBubble = MaterialTheme.colorScheme.inverseOnSurface
    val maxV = maxOf(values.maxOrNull() ?: 0f, goal ?: 0f, 1f) * 1.1f
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).scrub(values.size, scrub, enabled = scrubLabel != null)
                .semantics { if (description.isNotEmpty()) contentDescription = description }
        ) {
            val top = if (scrubLabel != null) 26.dp.toPx() else 0f
            val plot = size.height - top
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.56f).coerceAtMost(maxBarWidth.toPx()).coerceAtLeast(2f)
            values.forEachIndexed { i, v ->
                val h = plot * (v / maxV) * reveal
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, top), Size(barW, plot), CornerRadius(barW / 2))
                val c = colors?.getOrNull(i) ?: color
                val full = goal == null || v >= goal || i in highlight || scrub.value == i
                if (h > 0) drawRoundRect(if (full) c else c.copy(alpha = 0.45f), Offset(x, size.height - h), Size(barW, h), CornerRadius(barW / 2))
            }
            goal?.let {
                val y = size.height - plot * (it / maxV)
                drawLine(muted, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }
            val sel = scrub.value
            if (sel != null && scrubLabel != null) drawScrubMarker(slotCenter(sel, n, size.width), scrubLabel(sel), measurer, muted, bubble, onBubble)
        }
        AxisLabels(labels)
    }
}

/** Vertical stacked bars, e.g. sleep stages per night or zone minutes per week. [stacks][i] lists segment values bottom-up. */
@Composable
fun StackedBars(
    stacks: List<List<Float>>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    goal: Float? = null,
    labels: List<String> = emptyList(),
    scrubLabel: ((Int) -> String)? = null,
    description: String = "",
) {
    val reveal = rememberReveal(stacks)
    val scrub = rememberScrub()
    val measurer = rememberTextMeasurer()
    val muted = Fit.colors.muted
    val track = Fit.colors.track
    val bubble = MaterialTheme.colorScheme.inverseSurface
    val onBubble = MaterialTheme.colorScheme.inverseOnSurface
    val maxV = maxOf(stacks.maxOfOrNull { it.sum() } ?: 0f, goal ?: 0f, 1f) * 1.1f
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).scrub(stacks.size, scrub, enabled = scrubLabel != null)
                .semantics { if (description.isNotEmpty()) contentDescription = description }
        ) {
            val top = if (scrubLabel != null) 26.dp.toPx() else 0f
            val plot = size.height - top
            val n = stacks.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.56f).coerceAtMost(28.dp.toPx())
            stacks.forEachIndexed { i, parts ->
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(track, Offset(x, top), Size(barW, plot), CornerRadius(barW / 2))
                var y = size.height
                val dim = scrub.value != null && scrub.value != i
                parts.forEachIndexed { pi, v ->
                    val h = plot * (v / maxV) * reveal
                    if (h <= 0f) return@forEachIndexed
                    y -= h
                    val c = colors.getOrElse(pi) { muted }
                    drawRoundRect(if (dim) c.copy(alpha = 0.5f) else c, Offset(x, y), Size(barW, h), CornerRadius(barW / 4))
                }
            }
            goal?.let {
                val gy = size.height - plot * (it / maxV)
                drawLine(muted, Offset(0f, gy), Offset(size.width, gy), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
            }
            val sel = scrub.value
            if (sel != null && scrubLabel != null) drawScrubMarker(slotCenter(sel, n, size.width), scrubLabel(sel), measurer, muted, bubble, onBubble)
        }
        AxisLabels(labels)
    }
}

/** One horizontal bar split into coloured parts (zone split of a workout, stage split of a night). */
@Composable
fun HorizontalStackBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val total = parts.sumOf { it.first.toDouble() }.toFloat().takeIf { it > 0f } ?: 1f
    val reveal = rememberReveal(parts)
    val track = Fit.colors.track
    Canvas(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))) {
        drawRect(track)
        var x = 0f
        parts.forEach { (v, c) ->
            val w = size.width * (v / total) * reveal
            drawRect(c, Offset(x, 0f), Size(w, size.height))
            x += w
        }
    }
}

/**
 * Floating min–max bars per slot (bedtime→wake, nightly SpO2 range, daily HR range). [target] is shaded behind.
 * Values grow downwards when [downwards] is true (time of night reads top-to-bottom).
 */
@Composable
fun RangeBars(
    ranges: List<ClosedFloatingPointRange<Float>?>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    yMin: Float? = null,
    yMax: Float? = null,
    target: ClosedFloatingPointRange<Float>? = null,
    downwards: Boolean = false,
    labels: List<String> = emptyList(),
    scrubLabel: ((Int) -> String)? = null,
    description: String = "",
) {
    val reveal = rememberReveal(ranges)
    val scrub = rememberScrub()
    val measurer = rememberTextMeasurer()
    val muted = Fit.colors.muted
    val band = Fit.colors.baselineBand
    val bubble = MaterialTheme.colorScheme.inverseSurface
    val onBubble = MaterialTheme.colorScheme.inverseOnSurface
    val present = ranges.filterNotNull()
    val lo = yMin ?: (present.minOfOrNull { it.start } ?: 0f)
    val hi = yMax ?: (present.maxOfOrNull { it.endInclusive } ?: 1f)
    val span = (hi - lo).takeIf { it > 0f } ?: 1f
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).scrub(ranges.size, scrub, enabled = scrubLabel != null)
                .semantics { if (description.isNotEmpty()) contentDescription = description }
        ) {
            val top = if (scrubLabel != null) 26.dp.toPx() else 4.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            fun y(v: Float): Float {
                val f = ((v - lo) / span).coerceIn(0f, 1f)
                return if (downwards) top + f * (bottom - top) else bottom - f * (bottom - top)
            }
            target?.let {
                val a = y(it.start); val b = y(it.endInclusive)
                drawRect(band, Offset(0f, minOf(a, b)), Size(size.width, kotlin.math.abs(a - b)))
            }
            val n = ranges.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.5f).coerceAtMost(22.dp.toPx())
            ranges.forEachIndexed { i, r ->
                r ?: return@forEachIndexed
                val a = y(r.start); val b = y(r.endInclusive)
                val mid = (a + b) / 2
                val half = kotlin.math.abs(a - b) / 2 * reveal
                val x = i * slot + (slot - barW) / 2
                drawRoundRect(color, Offset(x, mid - half), Size(barW, (half * 2).coerceAtLeast(barW)), CornerRadius(barW / 2))
            }
            val sel = scrub.value
            if (sel != null && scrubLabel != null) drawScrubMarker(slotCenter(sel, n, size.width), scrubLabel(sel), measurer, muted, bubble, onBubble)
        }
        AxisLabels(labels)
    }
}

/** Bars above / below zero (skin-temperature deviation, readiness vs average). */
@Composable
fun DivergingBars(
    values: List<Float?>,
    positive: Color,
    negative: Color,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    labels: List<String> = emptyList(),
    scrubLabel: ((Int) -> String)? = null,
    description: String = "",
) {
    val reveal = rememberReveal(values)
    val scrub = rememberScrub()
    val measurer = rememberTextMeasurer()
    val muted = Fit.colors.muted
    val bubble = MaterialTheme.colorScheme.inverseSurface
    val onBubble = MaterialTheme.colorScheme.inverseOnSurface
    val maxAbs = (values.filterNotNull().maxOfOrNull { kotlin.math.abs(it) } ?: 1f).coerceAtLeast(0.1f) * 1.1f
    Column(modifier) {
        Canvas(
            Modifier.fillMaxWidth().height(height).scrub(values.size, scrub, enabled = scrubLabel != null)
                .semantics { if (description.isNotEmpty()) contentDescription = description }
        ) {
            val top = if (scrubLabel != null) 26.dp.toPx() else 0f
            val mid = top + (size.height - top) / 2
            val half = (size.height - top) / 2
            drawLine(muted, Offset(0f, mid), Offset(size.width, mid), 1.dp.toPx())
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot * 0.5f).coerceAtMost(20.dp.toPx())
            values.forEachIndexed { i, v ->
                v ?: return@forEachIndexed
                val h = half * (kotlin.math.abs(v) / maxAbs) * reveal
                val x = i * slot + (slot - barW) / 2
                val c = if (v >= 0) positive else negative
                drawRoundRect(c, Offset(x, if (v >= 0) mid - h else mid), Size(barW, h), CornerRadius(barW / 3))
            }
            val sel = scrub.value
            if (sel != null && scrubLabel != null) drawScrubMarker(slotCenter(sel, n, size.width), scrubLabel(sel), measurer, muted, bubble, onBubble)
        }
        AxisLabels(labels)
    }
}

/** Progress bar with a target tick (WHO 150 min/week, protein goal, challenge target). */
@Composable
fun BulletBar(value: Float, target: Float, color: Color, modifier: Modifier = Modifier, max: Float = target * 1.5f, height: Dp = 12.dp) {
    val reveal = rememberReveal(value)
    val track = Fit.colors.track
    val marker = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(height + 8.dp)) {
        val y = 4.dp.toPx()
        val h = height.toPx()
        drawRoundRect(track, Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
        val w = size.width * (value / max).coerceIn(0f, 1f) * reveal
        if (w > 0f) drawRoundRect(color, Offset(0f, y), Size(w.coerceAtLeast(h), h), CornerRadius(h / 2))
        val tx = size.width * (target / max).coerceIn(0f, 1f)
        drawLine(marker, Offset(tx, 0f), Offset(tx, size.height), 2.dp.toPx())
    }
}

data class RaceEntry(val label: String, val value: Float, val display: String, val highlight: Boolean = false)

/** Animated horizontal ranking bars for leaderboards. */
@Composable
fun RaceBars(entries: List<RaceEntry>, color: Color, modifier: Modifier = Modifier) {
    val maxV = entries.maxOfOrNull { it.value }?.takeIf { it > 0f } ?: 1f
    val reveal = rememberReveal(entries, durationMs = 900)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEachIndexed { i, e ->
            val f = e.value / maxV * reveal
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", Modifier.width(22.dp), style = MaterialTheme.typography.labelLarge, color = Fit.colors.muted)
                Box(Modifier.weight(1f).height(30.dp)) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(f.coerceIn(0.04f, 1f)).clip(RoundedCornerShape(8.dp))
                            .background(if (e.highlight) color else color.copy(alpha = 0.35f))
                    )
                    Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (e.highlight) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.display, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
